package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import org.jsoup.nodes.Element
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

class DongmanManhua : HttpSource(), ConfigurableSource {

    // 10b: based on 10a. Fixes settings UI sync after clearing manual backup; storage semantics unchanged.

    init {
        Handler(Looper.getMainLooper()).post {
            CookieManager.getInstance().setAcceptCookie(true)
        }
        Log.d("DongmanCookie", "扩展初始化完成")
    }

    override val name = "Dongman Manhua"
    override val lang get() = "zh-Hans"
    override val id get() = 7275979680702931948
    override val baseUrl = "https://m.dongmanmanhua.cn"
    override val supportsLatest = true

    internal val cdnBase = "https://cdn.dongmanmanhua.cn"
    internal val preferences by getPreferencesLazy()
    internal val appContext by lazy { Injekt.get<android.app.Application>() }
    internal var dialogContext: Context? = null
    internal var isLoginDialogShowing = false
    internal var loginSuccessHandled = false
    @Volatile private var passwordLoginRunning = false

    private val autoUnlockLocks = mutableMapOf<String, ReentrantLock>()
    private val autoUnlockLocksGuard = Any()
    private val autoUnlockRecentSuccess = mutableMapOf<String, Long>()
    private val autoUnlockRecentSuccessGuard = Any()
    private val autoUnlockRecentSuccessWindowMs = 30_000L

    private fun getAutoUnlockLock(key: String): ReentrantLock = synchronized(autoUnlockLocksGuard) {
        autoUnlockLocks.getOrPut(key) { ReentrantLock() }
    }

    private fun autoUnlockLog(message: String) {
        Log.d("DongmanAutoUnlock", message)
    }

    private fun recentAutoUnlockSuccessAge(key: String): Long? {
        val now = System.currentTimeMillis()
        return synchronized(autoUnlockRecentSuccessGuard) {
            autoUnlockRecentSuccess.entries.removeAll { now - it.value > autoUnlockRecentSuccessWindowMs }
            val lastSuccess = autoUnlockRecentSuccess[key] ?: return@synchronized null
            val age = now - lastSuccess
            if (age in 0 until autoUnlockRecentSuccessWindowMs) age else null
        }
    }

    private fun markAutoUnlockSuccess(key: String) {
        synchronized(autoUnlockRecentSuccessGuard) {
            autoUnlockRecentSuccess[key] = System.currentTimeMillis()
        }
        autoUnlockLog("AUTO_RECENT_SUCCESS_MARK key=$key windowMs=$autoUnlockRecentSuccessWindowMs")
    }

    // ══════════════════════════════════════════════════════════════════════
    // 独立存储（Cookie 文件读写）
    // ══════════════════════════════════════════════════════════════════════

    internal fun getCookieDir(): File = File(appContext.filesDir, "dongmanmanhua").apply { mkdirs() }
    internal fun getCookieFile(): File = File(getCookieDir(), "cookie.dat")

    internal var cachedCookie: String? = null
    internal var lastIndependentState: Boolean? = null
    internal var lastManualCookieState: Boolean? = null
    internal var cachedCookieSource: String = "none"
    private var lastCookieDebugToastTime: Long = 0L

    internal fun shortCookieForDebug(cookie: String?): String {
        if (cookie.isNullOrBlank()) return "none"
        val ses = extractCookieValue(cookie, "NEO_SES")
        val chk = extractCookieValue(cookie, "NEO_CHK")
        return "ses=${ses.length},chk=${chk.length}"
    }

    internal fun showCookieDebug(label: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCookieDebugToastTime < 1500L) return
        lastCookieDebugToastTime = now
        val (fileSes, fileChk) = readCookieFromFile()
        val spSes = preferences.getString(KEY_NEO_SES, "").orEmpty()
        val spChk = preferences.getString(KEY_NEO_CHK, "").orEmpty()
        val msg = buildString {
            append("$label ")
            append("manual=${getManualCookieEnable()} ind=${useIndependentStorage()} src=$cachedCookieSource ")
            append("cache=${shortCookieForDebug(cachedCookie)} ")
            append("file=ses=${fileSes.length},chk=${fileChk.length} sp=ses=${spSes.length},chk=${spChk.length}")
        }
        Log.d("DongmanCookieDebug", msg)
    }

    internal fun debugRequest(label: String, request: Request): Request {
        val expectedCookie = cachedCookie.orEmpty()
        val headerCookie = request.header("Cookie").orEmpty()
        val status = when {
            expectedCookie.isNotEmpty() && headerCookie.isEmpty() -> "MISS_HEADER"
            expectedCookie.isEmpty() && headerCookie.isEmpty() -> "NO_COOKIE"
            expectedCookie.isNotEmpty() && headerCookie.isNotEmpty() -> "OK_HEADER"
            else -> "HEADER_ONLY"
        }
        val msg = buildString {
            append("$label $status\n")
            append("src=$cachedCookieSource\n")
            append("cache=${shortCookieForDebug(expectedCookie)}\n")
            append("header=${shortCookieForDebug(headerCookie)} len=${headerCookie.length}\n")
            append(request.url.encodedPath.take(80))
        }
        Log.d("DongmanRequest", msg.replace("\n", " | "))
        return request
    }

    internal fun mergeSetCookieFromResponse(response: Response) {
        val setCookies = response.headers.values("Set-Cookie").joinToString("; ")
        if (setCookies.isBlank()) return

        val newSes = extractCookieValue(setCookies, "NEO_SES")
        val newChk = extractCookieValue(setCookies, "NEO_CHK")
        if (newSes.isBlank() && newChk.isBlank()) return

        val (fileSes, fileChk) = readCookieFromFile()
        val spSes = preferences.getString(KEY_NEO_SES, "").orEmpty()
        val spChk = preferences.getString(KEY_NEO_CHK, "").orEmpty()
        val cacheSes = extractCookieValue(cachedCookie.orEmpty(), "NEO_SES")
        val cacheChk = extractCookieValue(cachedCookie.orEmpty(), "NEO_CHK")

        val mergedSes = newSes.ifBlank { cacheSes.ifBlank { fileSes.ifBlank { spSes } } }
        val mergedChk = newChk.ifBlank { cacheChk.ifBlank { fileChk.ifBlank { spChk } } }

        if (mergedSes.isBlank()) {
            Log.d(
                "DongmanCookie",
                "MERGE_SET_COOKIE skipped: no NEO_SES, new=${shortCookieForDebug(setCookies)}",
            )
            return
        }

        val oldCookie = buildCookieString(
            cacheSes.ifBlank { fileSes.ifBlank { spSes } },
            cacheChk.ifBlank { fileChk.ifBlank { spChk } },
        )
        val mergedCookie = buildCookieString(mergedSes, mergedChk)
        if (mergedCookie == oldCookie && cachedCookie == mergedCookie) return

        preferences.edit()
            .putString(KEY_NEO_SES, mergedSes)
            .putString(KEY_NEO_CHK, mergedChk)
            .apply()

        try {
            getCookieFile().writeText("$mergedSes|$mergedChk")
        } catch (e: Exception) {
            Log.e("DongmanCookie", "MERGE_SET_COOKIE 写入文件失败", e)
        }

        cachedCookie = mergedCookie
        lastIndependentState = useIndependentStorage()
        lastManualCookieState = getManualCookieEnable()
        cachedCookieSource = "merge-set-cookie"

        Log.d(
            "DongmanCookie",
            "MERGE_SET_COOKIE new=${shortCookieForDebug(setCookies)} merged=${shortCookieForDebug(mergedCookie)}",
        )
        showCookieDebug("MERGE_SET_COOKIE", force = true)
    }

    internal fun saveCookieToFile(neoSes: String, neoChk: String) {
        try {
            getCookieFile().writeText("$neoSes|$neoChk")
            Log.d("DongmanCookie", "Cookie 已写入文件: ses=${neoSes.length},chk=${neoChk.length}")
            cachedCookie = buildCookieString(neoSes, neoChk)
            lastIndependentState = useIndependentStorage()
            cachedCookieSource = "save-file"
            showCookieDebug("SAVE_FILE", force = true)
        } catch (e: Exception) {
            Log.e("DongmanCookie", "写入文件失败", e)
        }
    }

    internal fun readCookieFromFile(): Pair<String, String> {
        return try {
            val content = getCookieFile().readText()
            val parts = content.split("|")
            if (parts.size == 2) parts[0] to parts[1] else "" to ""
        } catch (e: Exception) {
            "" to ""
        }
    }

    internal fun deleteCookieFile() {
        try {
            getCookieFile().delete()
            Log.d("DongmanCookie", "Cookie 文件已删除")
        } catch (e: Exception) {
            Log.e("DongmanCookie", "删除文件失败", e)
        }
    }

    internal fun buildCookieString(neoSes: String, neoChk: String): String = buildString {
        if (neoSes.isNotEmpty()) append("NEO_SES=$neoSes; ")
        if (neoChk.isNotEmpty()) append("NEO_CHK=$neoChk")
    }.trimEnd(';', ' ')

    internal fun useIndependentStorage(): Boolean = preferences.getBoolean(PREF_INDEPENDENT_STORAGE, false)

    // ══════════════════════════════════════════════════════════════════════
    // 手动备用 Cookie 开关
    // ══════════════════════════════════════════════════════════════════════

    internal fun getManualCookieEnable(): Boolean = preferences.getBoolean(PREF_MANUAL_COOKIE_SWITCH, false)

    internal fun clearManualBackup() {
        deleteCookieFile()
        preferences.edit().putBoolean(PREF_MANUAL_COOKIE_SWITCH, false).apply()
    }

    internal fun hasManualBackup(): Boolean {
        val (ses, _) = readCookieFromFile()
        return ses.isNotBlank()
    }

    internal fun buildManualSwitchSummary(): String {
        val hasLocal = hasManualBackup()
        return "开启：请求头使用本地保存的NEO鉴权Cookie；关闭：使用原有策略\n本地缓存：${if (hasLocal) "存在" else "无"}"
    }

    // ══════════════════════════════════════════════════════════════════════
    // 探针校验（登录状态探测）
    // ══════════════════════════════════════════════════════════════════════

    private var lastProbeTime: Long = 0L
    private var cacheLoginValid: Boolean? = null

    private fun probeIsLoginValid(): Boolean {
        if (!getManualCookieEnable() && !useIndependentStorage()) return true
        val now = System.currentTimeMillis()
        if (now - lastProbeTime < 60_000 && cacheLoginValid != null) {
            return cacheLoginValid!!
        }
        return try {
            val req = Request.Builder()
                .url("${baseUrl}/member/isLogin")
                .headers(headersBuilder().build())
                .build()
            Log.d("DongmanLoginProbe", "PROBE_START cookie=${shortCookieForDebug(req.header("Cookie"))}")
            val resp = client.newCall(req).execute()
            val body = resp.body.string().trim()
            val code = resp.code
            resp.close()

            val valid = code == 200 && body.equals("true", ignoreCase = true)
            val invalid = code == 200 && body.equals("false", ignoreCase = true)
            lastProbeTime = now
            cacheLoginValid = if (valid || invalid) valid else true
            Log.d("DongmanLoginProbe", "PROBE_RESULT code=$code valid=$valid invalid=$invalid body=${body.take(80)}")

            if (invalid) {
                Log.d("DongmanLoginProbe", "PROBE_INVALID_CLEAR_LOCAL_COOKIE")
                preferences.edit()
                    .remove(KEY_NEO_SES)
                    .remove(KEY_NEO_CHK)
                    .putBoolean(PREF_MANUAL_COOKIE_SWITCH, false)
                    .apply()
                deleteCookieFile()
                refreshCookieCache()
                syncLoginIndicator()
                return false
            }
            true
        } catch (e: Exception) {
            Log.d("DongmanLoginProbe", "PROBE_ERROR keep_cookie ${e.javaClass.simpleName}:${e.message}")
            true
        }
    }

    internal fun refreshCookieCache() {
        val independent = useIndependentStorage()
        val manual = getManualCookieEnable()
        var source = "none"
        val cookie = if (manual) {
            val (neoSes, neoChk) = readCookieFromFile()
            if (neoSes.isNotEmpty()) {
                source = "manual-file"
                buildCookieString(neoSes, neoChk)
            } else {
                source = "manual-file-empty"
                null
            }
        } else if (independent) {
            val (fileNeoSes, fileNeoChk) = readCookieFromFile()
            if (fileNeoSes.isNotEmpty()) {
                source = "ind-file"
                buildCookieString(fileNeoSes, fileNeoChk)
            } else {
                val spNeoSes = preferences.getString(KEY_NEO_SES, "").orEmpty()
                val spNeoChk = preferences.getString(KEY_NEO_CHK, "").orEmpty()
                if (spNeoSes.isNotEmpty()) {
                    source = "ind-sp"
                    buildCookieString(spNeoSes, spNeoChk)
                } else {
                    val cmCookie = CookieManager.getInstance().getCookie(baseUrl).orEmpty()
                    if (cmCookie.contains("NEO_SES") || cmCookie.contains("NEO_CHK")) {
                        source = "ind-cm"
                        cmCookie
                    } else {
                        source = "ind-empty"
                        null
                    }
                }
            }
        } else {
            val cmCookie = CookieManager.getInstance().getCookie(baseUrl).orEmpty()
            if (cmCookie.contains("NEO_SES") || cmCookie.contains("NEO_CHK")) {
                source = "cm"
                cmCookie
            } else {
                val neoSes = preferences.getString(KEY_NEO_SES, "").orEmpty()
                val neoChk = preferences.getString(KEY_NEO_CHK, "").orEmpty()
                if (neoSes.isNotEmpty()) {
                    source = "sp"
                    buildCookieString(neoSes, neoChk)
                } else {
                    source = "empty"
                    null
                }
            }
        }
        cachedCookie = cookie?.ifEmpty { null }
        cachedCookieSource = source
        lastIndependentState = independent
        lastManualCookieState = manual
        Log.d(
            "DongmanCookie",
            "Cookie 缓存已刷新: manual=$manual independent=$independent source=$source cookie=${shortCookieForDebug(cachedCookie)}",
        )
        showCookieDebug("REFRESH", force = false)
    }

    internal fun isLoginKnown(): Boolean = cachedCookie?.isNotEmpty() == true

    internal fun syncLoginIndicator() {
        if (::loginIndicator.isInitialized) {
            loginIndicator.isPersistent = false
            loginIndicator.isChecked = isLoginKnown()
            loginIndicator.summary = buildLoginSummary()
        }
        if (::manualCookieSwitch.isInitialized) {
            manualCookieSwitch.isChecked = getManualCookieEnable()
            manualCookieSwitch.summary = buildManualSwitchSummary()
        }
    }

    internal lateinit var loginIndicator: SwitchPreferenceCompat
    internal lateinit var manualCookieSwitch: SwitchPreferenceCompat

    // ══════════════════════════════════════════════════════════════════════
    // 设置页
    // ══════════════════════════════════════════════════════════════════════

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val ctx = screen.context
        dialogContext = ctx

        val editor = preferences.edit()
        preferences.all.forEach { (key, value) ->
            if (value is String && (value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true))) {
                editor.putBoolean(key, value.toBoolean())
            }
        }
        editor.apply()

        SwitchPreferenceCompat(ctx).apply {
            key = PREF_INDEPENDENT_STORAGE
            title = "独立存储 Cookie"
            summary = "开启后使用私有文件保存登录态，不受清除全局Cookie影响"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                refreshCookieCache()
                syncLoginIndicator()
                true
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(ctx).apply {
            key = PREF_MANUAL_COOKIE_SWITCH
            title = "启用手动备用Cookie模式"
            summary = buildManualSwitchSummary()
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                refreshCookieCache()
                syncLoginIndicator()
                summary = buildManualSwitchSummary()
                true
            }
            manualCookieSwitch = this
        }.also(screen::addPreference)

        refreshCookieCache()

        SwitchPreferenceCompat(ctx).apply {
            key = "login_indicator"
            isPersistent = false
            title = "登录状态"
            summary = buildLoginSummary()
            setDefaultValue(false)
            isChecked = isLoginKnown()
            isSelectable = false
            loginIndicator = this
        }.also(screen::addPreference)

        SwitchPreferenceCompat(ctx).apply {
            key = "webview_login_trigger"
            title = "WebView 登录"
            summary = "拨动此开关弹出咚漫登录页面，登录后自动保存状态"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    showWebViewLoginDialog()
                    false
                } else {
                    true
                }
            }
        }.also(screen::addPreference)

        addDualInputPreference(screen, this) { username, password ->
            when {
                username.isBlank() -> Toast.makeText(ctx, "请填写账号", Toast.LENGTH_SHORT).show()
                password.isBlank() -> Toast.makeText(ctx, "请填写密码", Toast.LENGTH_SHORT).show()
                else -> {
                    Log.d("DongmanPasswordLogin", "LOGIN_CONFIRM usernameLen=${username.length} passwordLen=${password.length}")
                    loginWithPassword(username, password)
                }
            }
        }

        SwitchPreferenceCompat(ctx).apply {
            key = PREF_LOGOUT_TRIGGER
            title = "彻底退出登录"
            summary = "清除所有登录信息（包括WebView），并删除独立存储备份"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                fullLogout()
                false
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(ctx).apply {
            key = PREF_CLEAR_BACKUP
            title = "清除独立存储备份"
            summary = "仅删除本地保存的Cookie备份，不影响WebView登录状态"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                clearBackupOnly()
                false
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(ctx).apply {
            key = PREF_SEARCH_MODE
            title = "搜索显示小说"
            summary = "开启后搜索结果包含小说（首页HTML接口）\n关闭则只显示漫画（JSON接口，速度更快）"
            setDefaultValue(false)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(ctx).apply {
            key = PREF_AUTO_PAY
            title = "自动购买付费章节"
            summary = "开启后，打开未购付费章节时将自动扣费解锁\n余额不足时会提示错误，需要先开启登录状态"
            setDefaultValue(false)
        }.also(screen::addPreference)

        ListPreference(ctx).apply {
            key = PREF_UA
            title = "User-Agent 预设"
            summary = "%s"
            entries = arrayOf("移动版（默认）", "Windows Firefox", "禁用 User-Agent", "自定义（见下方输入框）")
            entryValues = arrayOf(UA_MOBILE, UA_DESKTOP, "", PREF_UA_CUSTOM_FLAG)
            setDefaultValue(UA_MOBILE)
        }.also(screen::addPreference)

        EditTextPreference(ctx).apply {
            key = PREF_UA_CUSTOM
            title = "User-Agent 自定义值"
            summary = "选择「自定义」时生效\n当前值：${preferences.getString(PREF_UA_CUSTOM, "").orEmpty().ifEmpty { "（未填写）" }}"
            dialogTitle = "输入自定义 User-Agent"
            setDefaultValue("")
        }.also(screen::addPreference)

        refreshCookieCache()
    }

    // ══════════════════════════════════════════════════════════════════════
    // 密码登录
    // ══════════════════════════════════════════════════════════════════════

    internal fun loginWithPassword(username: String, password: String) {
        if (passwordLoginRunning) {
            Log.d("DongmanPasswordLogin", "LOGIN_SKIP_RUNNING")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appContext, "正在登录，请稍候", Toast.LENGTH_SHORT).show()
            }
            return
        }
        passwordLoginRunning = true

        Thread {
            try {
                Log.d("DongmanPasswordLogin", "LOGIN_START")
                val rsaStart = System.currentTimeMillis()
                val rsaResp = client.newCall(GET("$baseUrl/member/login/rsa/getKeys", headersBuilder().build())).execute()
                val rsaBody = rsaResp.body.string()
                val rsaCode = rsaResp.code
                rsaResp.close()
                Log.d("DongmanPasswordLogin", "LOGIN_RSA_RESULT code=$rsaCode costMs=${System.currentTimeMillis() - rsaStart}")
                val rsaJson = JSONObject(rsaBody)
                val keyName = rsaJson.getString("keyName")
                val nvalue = rsaJson.getString("nvalue")
                val evalue = rsaJson.getString("evalue")
                val sessionKey = rsaJson.getString("sessionKey")

                fun lenChar(s: String) = s.length.toChar()
                val plain = "${lenChar(sessionKey)}$sessionKey${lenChar(username)}$username${lenChar(password)}$password"
                val encrypted = rsaEncryptToHex(plain, evalue, nvalue)

                val body = FormBody.Builder()
                    .add("encnm", keyName)
                    .add("encpw", encrypted)
                    .add("returnUrl", "$baseUrl/")
                    .add("loginType", "PHONE_NUMBER")
                    .build()
                val loginStart = System.currentTimeMillis()
                val loginResp = client.newCall(POST("$baseUrl/member/login/doLoginById", headersBuilder().build(), body)).execute()
                val loginBody = loginResp.body.string()
                val loginCode = loginResp.code
                val allSetCookies = loginResp.headers.values("Set-Cookie").joinToString("; ")
                loginResp.close()
                Log.d("DongmanPasswordLogin", "LOGIN_RESPONSE httpCode=$loginCode costMs=${System.currentTimeMillis() - loginStart} body=${loginBody.take(120)}")
                val loginJson = JSONObject(loginBody)

                if (loginJson.optInt("loginStatus", -1) == 0) {
                    val neoSes = extractCookieValue(allSetCookies, "NEO_SES")
                    val neoChk = extractCookieValue(allSetCookies, "NEO_CHK")
                    Log.d("DongmanPasswordLogin", "LOGIN_COOKIE ${shortCookieForDebug(buildCookieString(neoSes, neoChk))}")
                    if (neoSes.isNotEmpty()) {
                        saveLoginCookie(neoSes, neoChk)
                        CookieManager.getInstance().setCookie(baseUrl, "NEO_SES=$neoSes; path=/")
                        if (neoChk.isNotEmpty()) {
                            CookieManager.getInstance().setCookie(baseUrl, "NEO_CHK=$neoChk; path=/")
                        }
                        CookieManager.getInstance().flush()
                        // 登录成功，保存明文账号密码供下次填入输入框
                        preferences.edit()
                            .putString(PREF_LOGIN_USERNAME, username)
                            .putString(PREF_LOGIN_PASSWORD, password)
                            .apply()
                        Handler(Looper.getMainLooper()).post {
                            syncLoginIndicator()
                            Toast.makeText(appContext, "登录成功", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Handler(Looper.getMainLooper()).post {
                            syncLoginIndicator()
                            Toast.makeText(appContext, "登录失败：未获取到登录凭证", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    val status = loginJson.optInt("loginStatus", -1)
                    val msg = loginJson.optString("loginMessage", "登录失败")
                    Log.d("DongmanPasswordLogin", "LOGIN_FAILED_STATUS status=$status msg=$msg")
                    throw Exception(msg)
                }
            } catch (e: Exception) {
                Log.d("DongmanPasswordLogin", "LOGIN_ERROR ${e.javaClass.simpleName}:${e.message}")
                Handler(Looper.getMainLooper()).post {
                    syncLoginIndicator()
                    Toast.makeText(appContext, "登录失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                passwordLoginRunning = false
                Log.d("DongmanPasswordLogin", "LOGIN_FINISH")
            }
        }.start()
    }

    internal fun saveLoginCookie(neoSes: String, neoChk: String) {
        Log.d("DongmanCookie", "saveLoginCookie: neoSes=$neoSes, neoChk=$neoChk")
        preferences.edit()
            .putString(KEY_NEO_SES, neoSes)
            .putString(KEY_NEO_CHK, neoChk)
            .apply()

        // 始终写入本地文件：独立存储和手动备用模式都依赖这份备份。
        saveCookieToFile(neoSes, neoChk)
        refreshCookieCache()
        showCookieDebug("SAVE_LOGIN", force = true)
    }

    internal fun fullLogout() {
        Log.d("DongmanCookie", "fullLogout: 彻底退出登录")
        CookieManager.getInstance().removeAllCookies(null)
        preferences.edit().remove(KEY_NEO_SES).remove(KEY_NEO_CHK).apply()
        if (useIndependentStorage()) deleteCookieFile()
        clearManualBackup()
        refreshCookieCache()
        syncLoginIndicator()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, "已彻底退出登录", Toast.LENGTH_SHORT).show()
            showCookieDebug("FULL_LOGOUT", force = true)
        }
    }

    internal fun clearBackupOnly() {
        Log.d("DongmanCookie", "clearBackupOnly")
        preferences.edit().remove(KEY_NEO_SES).remove(KEY_NEO_CHK).apply()
        if (useIndependentStorage()) deleteCookieFile()
        clearManualBackup()
        refreshCookieCache()
        syncLoginIndicator()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, "已清除独立存储备份", Toast.LENGTH_SHORT).show()
            showCookieDebug("CLEAR_BACKUP", force = true)
        }
    }

    internal fun buildLoginSummary(): String {
        val isLoggedIn = cachedCookie?.isNotEmpty() == true
        val status = if (isLoggedIn) "已登录" else "未登录"
        val storageMode = if (useIndependentStorage()) "私有文件（独立）" else "CookieManager/SP"
        val manualMode = if (getManualCookieEnable()) "手动备用开启" else "手动备用关闭"
        return "存储方式：$storageMode\n手动模式：$manualMode\n登录状态：$status"
    }

    // ══════════════════════════════════════════════════════════════════════
    // 请求头
    // ══════════════════════════════════════════════════════════════════════

    internal fun currentUserAgent(): String {
        return when (val pref = preferences.getString(PREF_UA, UA_MOBILE) ?: UA_MOBILE) {
            PREF_UA_CUSTOM_FLAG -> preferences.getString(PREF_UA_CUSTOM, "").orEmpty()
            else -> pref
        }
    }

    private fun cookieHeader(): String {
        val independent = useIndependentStorage()
        val manual = getManualCookieEnable()
        if (cachedCookie == null || lastIndependentState != independent || lastManualCookieState != manual) {
            refreshCookieCache()
        }
        return cachedCookie.orEmpty()
    }

    override fun headersBuilder(): Headers.Builder {
        val builder = super.headersBuilder().set("Referer", "$baseUrl/")
        val ua = currentUserAgent()
        if (ua.isNotEmpty()) builder.set("User-Agent", ua)

        val cookie = cookieHeader()
        if (cookie.isNotEmpty()) {
            builder.set("Cookie", cookie)
        }


        return builder
    }

    // 禁用 OkHttp 自动 CookieJar，避免它用 CookieManager 里的旧 Cookie 覆盖我们手动注入的 Cookie。
    // 同时手动捕获服务器返回的 NEO_CHK，并合并进独立存储。
    override val client = network.client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            mergeSetCookieFromResponse(response)
            response
        }
        .build()

    // ══════════════════════════════════════════════════════════════════════
    // 首页（触发探针）
    // ══════════════════════════════════════════════════════════════════════

    override fun popularMangaRequest(page: Int): Request {
        probeIsLoginValid()
        showCookieDebug("POPULAR_REQUEST", force = true)
        return debugRequest("POPULAR", GET("$baseUrl/?pageName=home", headersBuilder().build()))
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val entries = document
            .select("a[href*=list?title_no], a[href*=episodeList?titleNo]")
            .distinctBy { it.absUrl("href") }
            .filter { it.attr("href").isNotEmpty() }
            .map(::mangaFromElement)
            .filter { it.title.isNotEmpty() }
        return MangasPage(entries, false)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 最新更新
    // ══════════════════════════════════════════════════════════════════════

    override fun latestUpdatesRequest(page: Int): Request {
        showCookieDebug("LATEST_REQUEST", force = true)
        return debugRequest("LATEST", GET("$baseUrl/dailySchedule?sortOrder=UPDATE&webtoonCompleteType=ONGOING", headersBuilder().build()))
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val day = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "div._list_SUNDAY"
            Calendar.MONDAY -> "div._list_MONDAY"
            Calendar.TUESDAY -> "div._list_TUESDAY"
            Calendar.WEDNESDAY -> "div._list_WEDNESDAY"
            Calendar.THURSDAY -> "div._list_THURSDAY"
            Calendar.FRIDAY -> "div._list_FRIDAY"
            Calendar.SATURDAY -> "div._list_SATURDAY"
            else -> "div"
        }
        val entries = document
            .select("div#dailyList > $day li > a, ul.daily_card li a")
            .map(::mangaFromElement)
            .distinctBy { it.url }
            .filter { it.title.isNotEmpty() }
        return MangasPage(entries, false)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 搜索
    // ══════════════════════════════════════════════════════════════════════

    private val nextStartMap = mutableMapOf<String, Int>()
    private fun isMixedMode() = preferences.getBoolean(PREF_SEARCH_MODE, false)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (isMixedMode() && page == 1) {
            nextStartMap.remove(query)
            val body = FormBody.Builder().add("searchType", "WEBTOON").add("keyword", query).build()
            val headers = headersBuilder()
                .set("Origin", baseUrl)
                .set("Referer", "$baseUrl/search")
                .set("Content-Type", "application/x-www-form-urlencoded")
                .build()
            return debugRequest("SEARCH_HTML", POST("$baseUrl/search", headers, body))
        }
        val start = if (isMixedMode()) nextStartMap[query] ?: (1 + (page - 1) * 20) else 1 + (page - 1) * 20
        val body = FormBody.Builder().add("keyword", query).add("searchType", "WEBTOON").add("start", start.toString()).build()
        val headers = headersBuilder()
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/search")
            .set("Content-Type", "application/x-www-form-urlencoded")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        return debugRequest("SEARCH_JSON", POST("$baseUrl/searchResult", headers, body))
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return if (response.request.url.toString().contains("/searchResult")) {
            parseSearchResultJson(response)
        } else {
            parseSearchHtml(response)
        }
    }

    private fun parseSearchHtml(response: Response): MangasPage {
        val document = response.asJsoup()
        val allItems = document.select("ul._searchResultList > li")
        val totalEntries = allItems.size
        val entries = allItems
            .mapNotNull { li -> li.selectFirst("a.cleFix")?.let { searchMangaFromElement(it) } }
            .filter { it.title.isNotEmpty() }
        val total = document.select("._totalCount").attr("data-total").toIntOrNull() ?: 0
        val hasNextPage = total > totalEntries
        if (hasNextPage) {
            val keyword = extractKeywordFromBody(response.request.body)
            if (keyword.isNotEmpty()) nextStartMap[keyword] = totalEntries + 1
        }
        return MangasPage(entries, hasNextPage)
    }

    private fun parseSearchResultJson(response: Response): MangasPage {
        val json = JSONObject(response.body.string())
        val total = json.optInt("total", 0)
        val start = json.optInt("start", 0)
        val titleList = json.optJSONArray("titleList")
        val rawCount = titleList?.length() ?: 0
        val entries = mutableListOf<SManga>()
        if (titleList != null) {
            for (i in 0 until titleList.length()) {
                val item = titleList.getJSONObject(i)
                val platform = item.optString("displayPlatform", "ALL")
                if (platform != "ALL" && platform != "WEB") continue
                val manga = SManga.create().apply {
                    val titleNo = item.optString("titleNo", "")
                    url = "/episodeList?titleNo=$titleNo"
                    title = item.optString("title", "")
                    thumbnail_url = buildThumbnailUrl(
                        item.optString("thumbnailMobile", "")
                            .ifEmpty { item.optString("thumbnail", "") }
                            .ifEmpty { item.optString("representGenreBackgroundImageUrl", "") },
                        cdnBase
                    )
                }
                if (manga.title.isNotEmpty()) entries.add(manga)
            }
        }
        val hasNextPage = rawCount > 0 && (start - 1 + rawCount) < total
        if (hasNextPage && isMixedMode()) {
            val keyword = extractKeywordFromBody(response.request.body)
            if (keyword.isNotEmpty()) nextStartMap[keyword] = start + rawCount
        }
        return MangasPage(entries, hasNextPage)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 漫画详情
    // ══════════════════════════════════════════════════════════════════════

    override fun mangaDetailsRequest(manga: SManga): Request {
        val reqHeaders = headersBuilder().build()
        return debugRequest("DETAIL", GET(baseUrl + manga.url, reqHeaders))
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val detailDiv = document.selectFirst("div.detail_info")
        return SManga.create().apply {
            title = detailDiv?.selectFirst("p.subj")?.text()
                ?: document.selectFirst("h1.subj, h3.subj")?.text()
                ?: document.title().substringBefore("_")
            author = detailDiv?.selectFirst("p.author")?.text()
                ?: document.selectFirst("meta[property=com-dongman:webtoon:author]")?.attr("content")
            artist = author
            val genreBase = detailDiv?.selectFirst("p.genre")?.text() ?: ""
            val updateTag = extractUpdateTag(document.html())
            genre = if (updateTag.isNotEmpty()) "$genreBase, $updateTag" else genreBase
            description = detailDiv?.selectFirst("p.summary span.ellipsis")?.text()
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            status = when (extractSerialStatus(document.html())) {
                "SERIES" -> SManga.ONGOING
                "TERMINATION" -> SManga.COMPLETED
                "REST" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 章节列表
    // ══════════════════════════════════════════════════════════════════════

    private val dateFormat = SimpleDateFormat("yyyy-M-d", Locale.ENGLISH)

    override fun chapterListRequest(manga: SManga): Request {
        val reqHeaders = headersBuilder().build()
        return debugRequest("CHAPTER_LIST", GET(baseUrl + manga.url, reqHeaders))
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        var document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()
        while (true) {
            document.select("div#_episodeList ul li").forEach { li ->
                val a = li.selectFirst("a.workEpisodeListItem") ?: return@forEach
                val dataHref = a.attr("data-href").ifEmpty { a.absUrl("href") }
                if (dataHref.isEmpty()) return@forEach
                chapters.add(SChapter.create().apply {
                    val cleanUrl = dataHref.substringBefore("&source")
                    url = if (cleanUrl.startsWith("http")) {
                        cleanUrl.removePrefix("https://m.dongmanmanhua.cn").removePrefix("//m.dongmanmanhua.cn")
                    } else {
                        cleanUrl
                    }
                    val isFree = a.attr("data-free") == "true"
                    val rawName = a.selectFirst("p.sub_title span.ellipsis")?.text()
                        ?: a.selectFirst("p.sub_title")?.text()
                        ?: "第${li.attr("data-episode-no")}话"
                    name = if (isFree) rawName else "🔒 $rawName"
                    date_upload = dateFormat.tryParse(a.selectFirst("p.date")?.text()?.trim().orEmpty()) ?: 0L
                    chapter_number = li.attr("data-episode-no").toFloatOrNull() ?: -1f
                })
            }
            val nextPage = document.select("div.paginate a[onclick] + a").firstOrNull() ?: break
            val nextUrl = nextPage.absUrl("href")
            if (nextUrl.isEmpty()) break
            val reqHeaders = headersBuilder().build()
            document = client.newCall(debugRequest("CHAPTER_NEXT", GET(nextUrl, reqHeaders))).execute().asJsoup()
        }
        return chapters.reversed()
    }

    // ══════════════════════════════════════════════════════════════════════
    // 阅读页面 & 自动解锁
    // ══════════════════════════════════════════════════════════════════════

    override fun pageListRequest(chapter: SChapter): Request {
        val reqHeaders = headersBuilder().build()
        val autoPay = preferences.getBoolean(PREF_AUTO_PAY, false)
        if (autoPay) {
            val titleNo = extractUrlParam(chapter.url, "title_no")
            val episodeNo = extractUrlParam(chapter.url, "episode_no")
            if (titleNo.isNotEmpty() && episodeNo.isNotEmpty()) {
                autoUnlockEpisode(titleNo, episodeNo)
            }
        }
        return debugRequest("PAGE_LIST", GET(baseUrl + chapter.url, reqHeaders))
    }

    private fun autoUnlockEpisode(titleNo: String, episodeNo: String) {
        val key = "${titleNo}_${episodeNo}"
        val threadName = Thread.currentThread().name
        val lock = getAutoUnlockLock(key)

        autoUnlockLog("AUTO_UNLOCK_ATTEMPT key=$key thread=$threadName")
        recentAutoUnlockSuccessAge(key)?.let { age ->
            autoUnlockLog("AUTO_RECENT_SUCCESS_SKIP key=$key ageMs=$age thread=$threadName")
            return
        }

        val acquiredImmediately = lock.tryLock()
        if (!acquiredImmediately) {
            autoUnlockLog("AUTO_LOCK_BLOCKED_SKIP key=$key thread=$threadName")
            return
        }
        autoUnlockLog("AUTO_LOCK_ACQUIRED key=$key thread=$threadName")

        try {
            recentAutoUnlockSuccessAge(key)?.let { age ->
                autoUnlockLog("AUTO_RECENT_SUCCESS_SKIP_AFTER_LOCK key=$key ageMs=$age thread=$threadName")
                return
            }
            autoUnlockLog("AUTO_UNLOCK_START key=$key thread=$threadName")
            val params = "title_no=$titleNo&episode_no=$episodeNo&platform=MWEB&client=APP_ANDROID"
            val reqHeaders = headersBuilder()
                .set("Referer", "$baseUrl/FANTASY/list?title_no=$titleNo")
                .set("X-Requested-With", "XMLHttpRequest")
                .build()
            autoUnlockLog("AUTO_HEADER key=$key cookie=${shortCookieForDebug(reqHeaders.get("Cookie"))}")

            autoUnlockLog("AUTO_PRICE_START key=$key")
            val priceResp = client.newCall(debugRequest("AUTO_PRICE", GET("$baseUrl/episode/unlock/getEpisodePrice?$params", reqHeaders))).execute()
            val priceBody = priceResp.body.string()
            val priceJson = org.json.JSONObject(priceBody)
            val data = priceJson.optJSONObject("data")
            if (data == null) {
                autoUnlockLog("AUTO_PRICE_NO_DATA key=$key code=${priceJson.optInt("code")} message=${priceJson.optString("message")}")
                return
            }

            val isFree = data.optBoolean("free", true)
            val isLimit = data.optBoolean("isLimit", false)
            val price = data.optInt("price", 0)
            val coinCount = data.optInt("coinCount", 0)
            val beanCount = data.optInt("beanCount", 0)
            val episodeName = data.optString("episodeName", "本话")
            autoUnlockLog("AUTO_PRICE_RESULT key=$key free=$isFree limit=$isLimit price=$price coin=$coinCount bean=$beanCount episode=$episodeName")

            if (isFree) {
                autoUnlockLog("AUTO_PAY_SKIP_FREE key=$key")
                return
            }
            if (isLimit) {
                autoUnlockLog("AUTO_PAY_SKIP_LIMIT key=$key")
                return
            }
            if (coinCount < price) {
                autoUnlockLog("AUTO_PAY_SKIP_BALANCE_LOW key=$key price=$price coin=$coinCount episode=$episodeName")
                throw Exception("余额不足：$episodeName 需要 $price 币，当前余额 $coinCount 币，请前往咚漫充值")
            }

            autoUnlockLog("AUTO_PAY_START key=$key price=$price coinBefore=$coinCount")
            val payResp = client.newCall(debugRequest("AUTO_PAY", GET("$baseUrl/episode/unlock/pay?$params", reqHeaders))).execute()
            val payBody = payResp.body.string()
            val payJson = org.json.JSONObject(payBody)
            val payCode = payJson.optInt("code")
            val payMessage = payJson.optString("message")
            autoUnlockLog("AUTO_PAY_RESULT key=$key code=$payCode message=$payMessage")
            if (payCode != 200) {
                autoUnlockLog("AUTO_PAY_FAIL_CODE key=$key code=$payCode message=$payMessage")
                return
            }
            markAutoUnlockSuccess(key)
            autoUnlockLog("AUTO_UNLOCK_DONE key=$key")
        } catch (e: Exception) {
            autoUnlockLog("AUTO_UNLOCK_ERROR key=$key error=${e.javaClass.simpleName}:${e.message}")
            throw e
        } finally {
            lock.unlock()
            autoUnlockLog("AUTO_LOCK_RELEASE key=$key thread=$threadName")
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        val imageRegex = Regex("""url\s*:\s*"(https://cdn\.dongmanmanhua\.cn/[^"]+)"""")
        return imageRegex.findAll(html)
            .mapIndexed { i, match -> Page(i, imageUrl = match.groupValues[1]) }
            .toList()
    }

    override fun imageRequest(page: Page): Request {
        return GET(
            page.imageUrl!!,
            headersBuilder()
                .set("Referer", "$baseUrl/")
                .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .build()
        )
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ══════════════════════════════════════════════════════════════════════
    // 元素解析（依赖 HttpSource 的 setUrlWithoutDomain）
    // ══════════════════════════════════════════════════════════════════════

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.selectFirst(
            "p.subj, .subj .ellipsis, ._items_name_t, .home_genre_t, p.chapter-title-02, .chapter-title-01"
        )?.text() ?: element.attr("title").ifEmpty { element.selectFirst("img")?.attr("alt") ?: "" }
        thumbnail_url = extractThumbnailUrl(element)
    }

    private fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.selectFirst(".info .subj .ellipsis, p.subj .ellipsis")?.text() ?: ""
        thumbnail_url = extractThumbnailUrl(element)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 常量
    // ══════════════════════════════════════════════════════════════════════

    companion object {
        internal const val PREF_UA = "pref_user_agent"
        internal const val PREF_UA_CUSTOM = "pref_user_agent_custom"
        internal const val PREF_UA_CUSTOM_FLAG = "__custom__"
        internal const val PREF_INDEPENDENT_STORAGE = "pref_independent_storage"
        internal const val PREF_MANUAL_COOKIE_SWITCH = "pref_manual_cookie_switch"
        internal const val PREF_LOGIN_DUAL = "pref_login_dual"
        internal const val PREF_LOGIN_USERNAME = "pref_login_username"
        internal const val PREF_LOGIN_PASSWORD = "pref_login_password"
        internal const val PREF_LOGOUT_TRIGGER = "pref_logout_trigger"
        internal const val PREF_CLEAR_BACKUP = "pref_clear_backup"
        internal const val PREF_SEARCH_MODE = "pref_search_mode"
        internal const val PREF_AUTO_PAY = "pref_auto_pay"
        internal const val KEY_NEO_SES = "neo_ses"
        internal const val KEY_NEO_CHK = "neo_chk"

        internal const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
        internal const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/114.0"
    }
}
