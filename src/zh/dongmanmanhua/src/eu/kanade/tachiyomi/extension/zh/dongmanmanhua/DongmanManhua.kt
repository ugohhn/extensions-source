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
import eu.kanade.tachiyomi.source.model.Filter
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

class DongmanManhua : HttpSource(), ConfigurableSource {

    init {
        Handler(Looper.getMainLooper()).post {
            CookieManager.getInstance().setAcceptCookie(true)
        }
    }

    override val name = "Dongman Manhua"
    override val lang get() = "zh-Hans"
    override val id get() = 7275979680702931948
    override val baseUrl = "https://m.dongmanmanhua.cn"
    override val supportsLatest = true

    override fun getFilterList(): FilterList = buildDongmanFilterList()

    internal val cdnBase = "https://cdn.dongmanmanhua.cn"
    internal val preferences by getPreferencesLazy()
    internal val appContext by lazy { Injekt.get<android.app.Application>() }
    internal var dialogContext: Context? = null
    internal var isLoginDialogShowing = false
    internal var loginSuccessHandled = false
    @Volatile
    private var passwordLoginInProgress = false

    private fun tryStartPasswordLogin(): Boolean = synchronized(this) {
        if (passwordLoginInProgress) {
            false
        } else {
            passwordLoginInProgress = true
            true
        }
    }

    private fun finishPasswordLogin() = synchronized(this) {
        passwordLoginInProgress = false
    }

    private fun isPasswordLoginInProgress(): Boolean = synchronized(this) {
        passwordLoginInProgress
    }

    private val autoUnlockLocks = mutableMapOf<String, ReentrantLock>()
    private val autoUnlockLocksGuard = Any()
    private val autoUnlockRecentSuccess = mutableMapOf<String, Long>()
    private val autoUnlockRecentSuccessGuard = Any()
    private val autoUnlockRecentSuccessWindowMs = 30_000L

    private fun getAutoUnlockLock(key: String): ReentrantLock = synchronized(autoUnlockLocksGuard) {
        autoUnlockLocks.getOrPut(key) { ReentrantLock() }
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
        }

        refreshCookieCache()
    }

    internal fun saveCookieToFile(neoSes: String, neoChk: String) {
        try {
            getCookieFile().writeText("$neoSes|$neoChk")
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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

    internal fun buildManualSwitchSummary(hasLocalOverride: Boolean? = null): String {
        val hasLocal = hasLocalOverride ?: hasManualBackup()
        return "开启：请求头使用本地保存的NEO鉴权Cookie；关闭：使用原有策略\n本地缓存：${if (hasLocal) "存在" else "无"}"
    }

    // ══════════════════════════════════════════════════════════════════════
    // 探针校验（登录状态探测）
    // ══════════════════════════════════════════════════════════════════════

    private var lastProbeTime: Long = 0L
    private var cacheLoginValid: Boolean? = null

    private fun probeIsLoginValid(): Boolean {
        if (!getManualCookieEnable()) return true
        val now = System.currentTimeMillis()
        if (now - lastProbeTime < 60_000 && cacheLoginValid != null) {
            return cacheLoginValid!!
        }
        return try {
            val req = Request.Builder()
                .url("${baseUrl}/member/isLogin")
                .headers(headersBuilder().build())
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body.string().trim()
            val code = resp.code
            resp.close()

            val valid = code == 200 && body.equals("true", ignoreCase = true)
            val invalid = code == 200 && body.equals("false", ignoreCase = true)
            lastProbeTime = now
            cacheLoginValid = if (valid || invalid) valid else true

            if (invalid) {
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
        } else {
            // 独立存储 / SP 在手动备用关闭时只作为备份，不参与请求头。
            val cmCookie = CookieManager.getInstance().getCookie(baseUrl).orEmpty()
            if (cmCookie.contains("NEO_SES") || cmCookie.contains("NEO_CHK")) {
                source = "cm"
                cmCookie
            } else {
                source = "cm-empty"
                null
            }
        }
        cachedCookie = cookie?.ifEmpty { null }
        cachedCookieSource = source
        lastIndependentState = independent
        lastManualCookieState = manual
    }

    internal fun isLoginKnown(): Boolean {
        val cmCookie = CookieManager.getInstance().getCookie(baseUrl).orEmpty()
        return extractCookieValue(cmCookie, "NEO_SES").isNotEmpty()
    }

    internal fun syncLoginIndicator() {
        refreshCookieCache()
        val shouldChecked = isLoginKnown()
        if (::loginIndicator.isInitialized) {
            loginIndicator.isChecked = shouldChecked
            loginIndicator.summary = buildLoginSummary()
        }
        if (::manualCookieSwitch.isInitialized) {
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
            summary = "开启后保存私有文件备份；只有开启手动备用Cookie模式时才会使用该备份请求"
            setDefaultValue(false)
            setOnPreferenceChangeListener { preference, newValue ->
                val enabled = newValue as Boolean
                preferences.edit().putBoolean(PREF_INDEPENDENT_STORAGE, enabled).apply()
                (preference as SwitchPreferenceCompat).isChecked = enabled
                refreshCookieCache()
                syncLoginIndicator()
                if (::loginIndicator.isInitialized) {
                    loginIndicator.summary = buildLoginSummary(independentOverride = enabled)
                }
                false
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(ctx).apply {
            key = PREF_MANUAL_COOKIE_SWITCH
            title = "启用手动备用Cookie模式"
            summary = buildManualSwitchSummary()
            setDefaultValue(false)
            setOnPreferenceChangeListener { preference, newValue ->
                val enabled = newValue as Boolean
                preferences.edit().putBoolean(PREF_MANUAL_COOKIE_SWITCH, enabled).apply()
                (preference as SwitchPreferenceCompat).isChecked = enabled
                refreshCookieCache()
                syncLoginIndicator()
                summary = buildManualSwitchSummary()
                if (::loginIndicator.isInitialized) {
                    loginIndicator.summary = buildLoginSummary(manualOverride = enabled)
                }
                false
            }
            manualCookieSwitch = this
        }.also(screen::addPreference)

        refreshCookieCache()

        SwitchPreferenceCompat(ctx).apply {
            key = "login_indicator"
            title = "登录状态"
            summary = buildLoginSummary()
            setDefaultValue(false)
            isChecked = isLoginKnown()
            setEnabled(false)
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
            refreshCookieCache()
            if (isLoginKnown()) {
                syncLoginIndicator()
                Toast.makeText(ctx, "已登录，无需重复登录", Toast.LENGTH_SHORT).show()
                return@addDualInputPreference
            }
            if (isPasswordLoginInProgress()) {
                Toast.makeText(ctx, "正在登录，请稍候", Toast.LENGTH_SHORT).show()
                return@addDualInputPreference
            }
            when {
                username.isBlank() -> Toast.makeText(ctx, "请填写账号", Toast.LENGTH_SHORT).show()
                password.isBlank() -> Toast.makeText(ctx, "请填写密码", Toast.LENGTH_SHORT).show()
                else -> {
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

        syncLoginIndicator()
        Handler(Looper.getMainLooper()).postDelayed({
            syncLoginIndicator()
        }, 300L)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 密码登录
    // ══════════════════════════════════════════════════════════════════════

    internal fun loginWithPassword(username: String, password: String) {
        refreshCookieCache()
        if (isLoginKnown()) {
            Handler(Looper.getMainLooper()).post {
                syncLoginIndicator()
                Toast.makeText(appContext, "已登录，无需重复登录", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (!tryStartPasswordLogin()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(appContext, "正在登录，请稍候", Toast.LENGTH_SHORT).show()
            }
            return
        }
        Thread {
            val loginStartMs = System.currentTimeMillis()
            try {
                val rsaResp = client.newCall(GET("$baseUrl/member/login/rsa/getKeys", headersBuilder().build())).execute()
                val rsaBody = rsaResp.body.string()
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
                val loginResp = client.newCall(POST("$baseUrl/member/login/doLoginById", headersBuilder().build(), body)).execute()
                val loginBody = loginResp.body.string()
                val loginJson = JSONObject(loginBody)

                if (loginJson.optInt("loginStatus", -1) == 0) {
                    val allSetCookies = loginResp.headers.values("Set-Cookie").joinToString("; ")
                    val neoSes = extractCookieValue(allSetCookies, "NEO_SES")
                    val neoChk = extractCookieValue(allSetCookies, "NEO_CHK")
                    if (neoSes.isNotEmpty()) {
                        saveLoginCookie(neoSes, neoChk)
                        CookieManager.getInstance().setCookie(baseUrl, "NEO_SES=$neoSes; path=/")
                        if (neoChk.isNotEmpty()) {
                            CookieManager.getInstance().setCookie(baseUrl, "NEO_CHK=$neoChk; path=/")
                        } else {
                        }
                        CookieManager.getInstance().flush()
                        refreshCookieCache()
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
                            Toast.makeText(appContext, "登录失败：未获取到登录凭证", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    val msg = loginJson.optString("loginMessage", "登录失败")
                    throw Exception(msg)
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, "登录失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                finishPasswordLogin()
            }
        }.start()
    }

    internal fun saveLoginCookie(neoSes: String, neoChk: String) {
        preferences.edit()
            .putString(KEY_NEO_SES, neoSes)
            .putString(KEY_NEO_CHK, neoChk)
            .apply()

        // 始终写入本地文件：独立存储和手动备用模式都依赖这份备份。
        saveCookieToFile(neoSes, neoChk)
        refreshCookieCache()
    }

    internal fun fullLogout() {
        CookieManager.getInstance().removeAllCookies { success ->
        }
        preferences.edit().remove(KEY_NEO_SES).remove(KEY_NEO_CHK).apply()
        if (useIndependentStorage()) {
            deleteCookieFile()
        }
        clearManualBackup()
        refreshCookieCache()
        syncLoginIndicator()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, "已彻底退出登录", Toast.LENGTH_SHORT).show()
        }
    }

    internal fun clearBackupOnly() {
        preferences.edit().remove(KEY_NEO_SES).remove(KEY_NEO_CHK).apply()
        if (useIndependentStorage()) {
            deleteCookieFile()
        }
        clearManualBackup()
        refreshCookieCache()
        syncLoginIndicator()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, "已清除独立存储备份", Toast.LENGTH_SHORT).show()
        }
    }

    internal fun buildLoginSummary(
        independentOverride: Boolean? = null,
        manualOverride: Boolean? = null,
    ): String {
        val isLoggedIn = isLoginKnown()
        val status = if (isLoggedIn) "已登录" else "未登录"
        val storageMode = "CookieManager/SP"
        val independent = independentOverride ?: useIndependentStorage()
        val manual = manualOverride ?: getManualCookieEnable()
        val backupMode = if (independent) "私有文件备份开启" else "私有文件备份关闭"
        val manualMode = if (manual) "手动备用开启" else "手动备用关闭"
        return buildString {
            append("存储方式：$storageMode\n")
            append("备份状态：$backupMode\n")
            append("手动模式：$manualMode\n")
            append("登录状态：$status")
        }
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

    private fun ajaxHeaders(referer: String = "$baseUrl/"): Headers {
        return headersBuilder()
            .set("Referer", referer)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()
    }

    // 禁用 OkHttp 自动 CookieJar，避免它用 CookieManager 里的旧 Cookie 覆盖我们手动注入的 Cookie。
    // 同时手动捕获服务器返回的 NEO_CHK，并合并进独立存储。
    override val client = network.client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath == LOCAL_GENRE_CACHE_PATH) {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .headers(Headers.Builder().set("Content-Type", "text/plain; charset=UTF-8").build())
                    .body("".toResponseBody("text/plain; charset=UTF-8".toMediaType()))
                    .build()
            } else {
                chain.proceed(request)
            }
        }
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
        return GET("$baseUrl/?pageName=home", headersBuilder().build())
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
        val weekday = preferences.getString(PREF_FILTER_WEEKDAY, "").orEmpty()
        val sort = preferences.getString(PREF_FILTER_SORT, "READ_COUNT").orEmpty()
        val todayCode = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "MONDAY"; Calendar.TUESDAY -> "TUESDAY"
            Calendar.WEDNESDAY -> "WEDNESDAY"; Calendar.THURSDAY -> "THURSDAY"
            Calendar.FRIDAY -> "FRIDAY"; Calendar.SATURDAY -> "SATURDAY"
            Calendar.SUNDAY -> "SUNDAY"; else -> "MONDAY"
        }
        val url = when (weekday) {
            "NEW" -> "$baseUrl/new"
            "COMPLETE" -> "$baseUrl/dailySchedule?weekday=COMPLETE&sortOrder=$sort"
            "" -> "$baseUrl/dailySchedule?weekday=$todayCode&sortOrder=$sort"
            else -> "$baseUrl/dailySchedule?weekday=$weekday&sortOrder=$sort"
        }
        return GET(url, headersBuilder().build())
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val weekday = preferences.getString(PREF_FILTER_WEEKDAY, "").orEmpty()

        if (weekday == "NEW" || response.request.url.encodedPath == "/new") {
            val document = response.asJsoup()
            val entries = document.select(".new_works_items").mapNotNull { item ->
                val rawHref = item.attr("href")
                val href = item.absUrl("href").ifEmpty { rawHref }
                val cleanPath = cleanMangaDetailPath(href)
                val titleNo = titleNoFromUrl(cleanPath) ?: titleNoFromUrl(href)
                // /new 是“新作”入口，但不能把入口/来源参数直接当成作品 newTitle。
                // 只认当前卡片自身的 NEW 图标；JSON 接口另走 newTitle 字段。
                val isNew = hasNewBadge(item)
                cacheNewTitle(titleNo, isNew)
                if (VERBOSE_LIST_LOG || entriesNewProbeShouldLog()) {
                    dlog(
                        "latestUpdatesParse NEW probe titleNo=$titleNo isNewBadge=$isNew " +
                            "hasIcoNewCn=${item.selectFirst(".ico_new_cn") != null} " +
                            "hasClassIcoNew=${item.selectFirst("[class*=ico_new]") != null} " +
                            "hasClassIconNew=${item.selectFirst("[class*=icon_new]") != null} " +
                            "hasImgIconNew=${item.selectFirst("img[src*=icon_new], img[src*=ico_new]") != null} " +
                            "class=${item.className()} href=$href cleanPath=$cleanPath"
                    )
                }
                SManga.create().apply {
                    url = cleanPath
                    title = item.selectFirst(".works_tit")?.text() ?: return@mapNotNull null
                    thumbnail_url = item.selectFirst(".works_img_area img")?.absUrl("src") ?: ""
                }
            }.filter { it.title.isNotEmpty() }
            dlog("latestUpdatesParse NEW url=${response.request.url} count=${entries.size}")
            return MangasPage(entries, false)
        }

        return parseDailyScheduleHtml(response)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 搜索
    // ══════════════════════════════════════════════════════════════════════

    private val nextStartMap = mutableMapOf<String, Int>()
    private val newTitleCache = mutableMapOf<String, Boolean>()
    private val dailyScheduleNewCache = mutableMapOf<String, Boolean>()
    private var dailyScheduleDocCache: org.jsoup.nodes.Document? = null
    private var dailyScheduleDocCacheTime: Long = 0L
    private val dailyScheduleDocCacheTtlMs = 30 * 60 * 1000L

    private data class CachedMangaItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String,
        val titleNo: String?,
        val hasNew: Boolean,
    )

    private data class GenrePageCache(
        val genre: String,
        val createdAt: Long,
        val items: List<CachedMangaItem>,
    )

    private val genrePageCache = mutableMapOf<String, GenrePageCache>()

    private data class FilterSnapshot(
        val weekdayState: Int,
        val sortState: Int,
        val themeState: Int,
        val myMangaState: Int,
        val activeGroup: String,
    )

    private var cachedLastFilterSnapshot: FilterSnapshot? = null

    private fun loadOrGetLastSnapshot(): FilterSnapshot? {
        cachedLastFilterSnapshot?.let { return it }
        val activeGroup = preferences.getString(PREF_FILTER_ACTIVE_GROUP, null) ?: return null
        return FilterSnapshot(
            weekdayState = preferences.getInt(PREF_FILTER_WEEKDAY_STATE, 0),
            sortState = preferences.getInt(PREF_FILTER_SORT_STATE, 0),
            themeState = preferences.getInt(PREF_FILTER_THEME_STATE, 0),
            myMangaState = preferences.getInt(PREF_FILTER_MY_MANGA_STATE, 0),
            activeGroup = activeGroup,
        ).also { cachedLastFilterSnapshot = it }
    }

    private fun dlog(message: String) = Log.d(TAG, message)
    private fun wlog(message: String, throwable: Throwable? = null) = Log.w(TAG, message, throwable)

    private fun isMixedMode() = preferences.getBoolean(PREF_SEARCH_MODE, false)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val weekdayFilter = filters.firstOrNull { it is WeekdayFilter } as? WeekdayFilter
        val weekdayState = weekdayFilter?.state ?: 0
        val weekdayValue = weekdayFilter?.getSelectedValue().orEmpty()

        val sortFilter = filters.firstOrNull { it is SortFilter } as? SortFilter
        val sortState = sortFilter?.state ?: 0
        val sortValue = getSortFilter().getOrNull(sortState)?.value ?: "READ_COUNT"

        val themeFilter = filters.firstOrNull { it is ThemeFilter } as? ThemeFilter
        val themeState = themeFilter?.state ?: 0
        val themeTag = getThemeFilter().getOrNull(themeState)
        val themeValue = themeTag?.value.orEmpty()

        val myMangaFilter = filters.firstOrNull { it is MyMangaFilter } as? MyMangaFilter
        val myMangaState = myMangaFilter?.state ?: 0
        val myMangaValue = myMangaFilter?.getSelectedValue().orEmpty()
        val myMangaName = when (myMangaValue) {
            "recent" -> "最近观看"
            "purchased" -> "我的已购"
            else -> "不使用我的漫画"
        }

        if (query.isBlank()) {
            val previous = loadOrGetLastSnapshot()
            val prevActiveGroup = previous?.activeGroup ?: "update"

            val updateChanged = previous != null &&
                (weekdayState != previous.weekdayState || sortState != previous.sortState)
            val themeChanged = previous != null && themeState != previous.themeState
            val myMangaChanged = previous != null && myMangaState != previous.myMangaState

            val activeGroup = when {
                // 从未保存过筛选快照时，按当前可见的非默认筛选决定入口。
                previous == null -> when {
                    myMangaValue.isNotEmpty() -> "migrate"
                    themeState != 0 -> "theme"
                    else -> "update"
                }
                // 三组筛选彻底按“本次谁变了谁生效”分开，避免残留 state 互相抢入口。
                myMangaChanged -> if (myMangaValue.isNotEmpty()) "migrate" else "update"
                themeChanged -> "theme"
                updateChanged -> "update"
                // 没有任何 changed 时，再沿用上次成功入口。
                // 仅保留一个保护：旧版本可能把 activeGroup 错存成 update，但界面显示非默认题材且更新项为默认，
                // 这种情况下按当前可见题材恢复。不能压过上面的 updateChanged。
                prevActiveGroup == "update" &&
                    themeState != 0 &&
                    weekdayValue.isEmpty() &&
                    myMangaValue.isEmpty() -> "theme"
                else -> prevActiveGroup
            }

            val todayCode = currentWeekdayCode()
            val updateUrl = when (weekdayValue) {
                "NEW" -> "$baseUrl/new"
                "COMPLETE" -> "$baseUrl/dailySchedule?weekday=COMPLETE&sortOrder=$sortValue"
                "" -> "$baseUrl/dailySchedule?weekday=$todayCode&sortOrder=$sortValue"
                else -> "$baseUrl/dailySchedule?weekday=$weekdayValue&sortOrder=$sortValue"
            }

            val branch: String
            val request: Request
            when (activeGroup) {
                "migrate" -> {
                    when (myMangaValue) {
                        "purchased" -> {
                            branch = "purchased"
                            val url = "$baseUrl/episode/unlock/titleList?platform=MWEB"
                            request = GET(url, ajaxHeaders("$baseUrl/purchased/list"))
                        }
                        else -> {
                            branch = "recent"
                            // 最近观看尝试请求真实页面；如果网页 localStorage 记录拿不到，解析阶段返回空，不回落到题材/更新。
                            val url = "$baseUrl/recent/more"
                            request = GET(url, headersBuilder().build())
                        }
                    }
                }
                "theme" -> {
                    if (themeValue.isEmpty() || themeValue == "ALL") {
                        branch = "theme-all-as-update"
                        // 网站没有确认过稳定的“题材全部”统一接口。
                        // 选“全部”只表示不限制题材，不再用 /LOVE/ 拼首页/推荐链接冒充全部。
                        request = GET(updateUrl, headersBuilder().build())
                    } else {
                        val cachedGenrePage = getValidGenrePageCache(themeValue)
                        val useCachedGenrePage = cachedGenrePage != null
                        branch = if (useCachedGenrePage) "theme-cache" else "theme"
                        // 题材不读取“排序”控件，不拼 sortOrder。排序只属于更新。
                        // 有缓存时走本地拦截地址，不再每页重新下载 /TERMINATION/ 这类大 HTML。
                        val url = if (useCachedGenrePage) {
                            "$baseUrl$LOCAL_GENRE_CACHE_PATH?genre=$themeValue&mihonPage=$page"
                        } else if (page > 1) {
                            "$baseUrl/$themeValue/?mihonPage=$page"
                        } else {
                            "$baseUrl/$themeValue/"
                        }
                        request = GET(url, headersBuilder().build())
                    }
                }
                else -> {
                    branch = if (weekdayValue.isNotEmpty()) "update-explicit" else "update-default"
                    request = GET(updateUrl, headersBuilder().build())
                }
            }

            cachedLastFilterSnapshot = FilterSnapshot(
                weekdayState = weekdayState,
                sortState = sortState,
                themeState = themeState,
                myMangaState = myMangaState,
                activeGroup = activeGroup,
            )

            preferences.edit()
                .putString(PREF_FILTER_WEEKDAY, weekdayValue)
                .putString(PREF_FILTER_SORT, sortValue)
                .putString(PREF_FILTER_THEME, if (activeGroup == "theme") themeValue else "")
                .putString(PREF_FILTER_ACTIVE_GROUP, activeGroup)
                .putInt(PREF_FILTER_WEEKDAY_STATE, weekdayState)
                .putInt(PREF_FILTER_SORT_STATE, sortState)
                .putInt(PREF_FILTER_THEME_STATE, themeState)
                .putInt(PREF_FILTER_MY_MANGA_STATE, myMangaState)
                .apply()

            dlog(
                "searchMangaRequest filter branch=$branch activeGroup=$activeGroup page=$page " +
                    "prevActiveGroup=$prevActiveGroup changed(update=$updateChanged, theme=$themeChanged, myManga=$myMangaChanged) " +
                    "weekdayState=$weekdayState/${previous?.weekdayState ?: -1} weekday=$weekdayValue sort=$sortValue " +
                    "themeState=$themeState/${previous?.themeState ?: -1} theme=${themeTag?.name.orEmpty()}/$themeValue " +
                    "myMangaState=$myMangaState/${previous?.myMangaState ?: -1} myManga=$myMangaName/$myMangaValue " +
                    "url=${request.url}"
            )
            return request
        }

        preferences.edit()
            .putString(PREF_FILTER_WEEKDAY, weekdayValue)
            .putString(PREF_FILTER_SORT, sortValue)
            .putString(PREF_FILTER_THEME, themeValue)
            .apply()

        // 有 query 时走关键词搜索（原有逻辑）
        if (isMixedMode() && page == 1) {
            nextStartMap.remove(query)
            val body = FormBody.Builder().add("searchType", "WEBTOON").add("keyword", query).build()
            val headers = headersBuilder()
                .set("Origin", baseUrl)
                .set("Referer", "$baseUrl/search")
                .set("Content-Type", "application/x-www-form-urlencoded")
                .build()
            return POST("$baseUrl/search", headers, body)
        }
        val start = if (isMixedMode()) nextStartMap[query] ?: (1 + (page - 1) * 20) else 1 + (page - 1) * 20
        val body = FormBody.Builder().add("keyword", query).add("searchType", "WEBTOON").add("start", start.toString()).build()
        val headers = headersBuilder()
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/search")
            .set("Content-Type", "application/x-www-form-urlencoded")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        return POST("$baseUrl/searchResult", headers, body)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val url = response.request.url.toString()
        dlog("searchMangaParse url=$url contentType=${response.header("Content-Type").orEmpty()}")
        return when {
            response.request.url.encodedPath == LOCAL_GENRE_CACHE_PATH -> parseCachedGenrePage(response)
            url.contains("/recent/more") -> parseRecentMangaPage(response)
            url.contains("/episode/unlock/titleList") -> parsePurchasedTitles(response)
            url.contains("/searchResult") -> parseSearchResultJson(response)
            url.contains("/dailySchedule") || url.endsWith("/new") || url.contains("/new?") -> latestUpdatesParse(response)
            url == baseUrl || url == "$baseUrl/" || url.startsWith("$baseUrl/?") -> parseMangaListHtml(response)
            isGenrePageUrl(url) || url.contains("/list") -> parseMangaListHtml(response)
            else -> parseSearchHtml(response)
        }
    }

    private fun parseRecentMangaPage(response: Response): MangasPage {
        val document = response.asJsoup()
        val candidates = document.select(
            ".viewed_list_container .viewed_list_items a.items_content_c[href], " +
                ".recently_viewed_list_container a.items_content_c[href]"
        )
        val realItems = candidates.filter { a ->
            val href = a.attr("href").trim()
            href.isNotEmpty() && !href.equals("javascript:void(0)", ignoreCase = true) && !href.startsWith("javascript:", ignoreCase = true)
        }
        val entries = realItems
            .map { a ->
                SManga.create().apply {
                    setUrlWithoutDomain(a.absUrl("href").ifEmpty { a.attr("href") })
                    title = a.selectFirst(".tit, .items_content_right .tit")?.text()
                        ?: a.selectFirst("img")?.attr("alt")
                        ?: ""
                    thumbnail_url = buildThumbnailUrl(a.selectFirst("img")?.attr("src").orEmpty())
                }
            }
            .filter { it.title.isNotEmpty() && it.url.isNotEmpty() }
            .distinctBy { it.url }
        dlog(
            "parseRecentMangaPage url=${response.request.url} " +
                "candidates=${candidates.size} realItems=${realItems.size} entries=${entries.size}"
        )
        return MangasPage(entries, false)
    }


    private fun parsePurchasedTitles(response: Response): MangasPage {
        val body = response.body.string()
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return MangasPage(emptyList(), false)
        if (json.optInt("code") != 200) return MangasPage(emptyList(), false)
        val data = json.optJSONObject("data") ?: return MangasPage(emptyList(), false)
        val titles = data.optJSONArray("titles") ?: data.optJSONArray("list") ?: return MangasPage(emptyList(), false)
        val entries = mutableListOf<SManga>()
        for (i in 0 until titles.length()) {
            val item = titles.getJSONObject(i)
            val manga = mangaFromJsonTitle(item)
            if (manga.title.isNotEmpty()) entries.add(manga)
        }
        return MangasPage(entries.distinctBy { it.url }, false)
    }

    private fun parseDailyScheduleHtml(response: Response): MangasPage {
        val document = response.asJsoup()
        val weekday = preferences.getString(PREF_FILTER_WEEKDAY, "").orEmpty().ifEmpty { currentWeekdayCode() }
        val selector = "a.updatePage_lst_item[data-week=$weekday]"
        val rawElements = document.select(selector)
        val entries = rawElements
            .map(::mangaFromElement)
            .distinctBy { it.url }
            .filter { it.title.isNotEmpty() }
        dlog(
            "parseDailyScheduleHtml url=${response.request.url} weekday=$weekday " +
                "selector=$selector raw=${rawElements.size} entries=${entries.size}"
        )
        return MangasPage(entries, false)
    }

    private fun mangaFromJsonTitle(item: org.json.JSONObject): SManga {
        val titleNo = item.optInt("titleNo", 0)
        val titleNoText = if (titleNo > 0) titleNo.toString() else item.optString("titleNo", "")
        val newTitle = item.optBoolean("newTitle", false)
        cacheNewTitle(titleNoText, newTitle)
        return SManga.create().apply {
            val genreSeo = item.optString("representGenreSeoCode", "")
            val groupName = item.optString("groupName", "")
            url = when {
                // 只有 SEO 题材码明确存在时才拼网页详情地址。
                // representGenre 是 ROMANCE/ACTION 这类内部枚举，不等于 URL 里的 LOVE/BOY，不能拿来拼。
                titleNoText.isNotEmpty() && genreSeo.isNotEmpty() && groupName.isNotEmpty() ->
                    "/$genreSeo/$groupName/list?title_no=$titleNoText"
                titleNoText.isNotEmpty() -> "/episodeList?titleNo=$titleNoText"
                else -> ""
            }
            dlog(
                "mangaFromJsonTitle titleNo=$titleNoText title=${item.optString("title", "")} " +
                    "representGenre=${item.optString("representGenre", "")} genreSeo=$genreSeo " +
                    "groupName=$groupName newTitle=$newTitle url=$url"
            )
            title = item.optString("title", "")
            thumbnail_url = buildThumbnailUrl(
                item.optString("thumbnail", "")
                    .ifEmpty { item.optString("thumbnailMobile", "") }
                    .ifEmpty { item.optString("bgNewMobile", "") }
                    .ifEmpty { item.optString("bgNewIpad", "") }
                    .ifEmpty { item.optString("image", "") },
                cdnBase,
            )
        }
    }

    private fun parseMangaListHtml(response: Response): MangasPage {
        val document = response.asJsoup()
        val requestUrl = response.request.url.toString()
        val requestedGenre = preferences.getString(PREF_FILTER_THEME, "").orEmpty()
            .ifEmpty { genreCodeFromUrl(requestUrl).orEmpty() }
            .let { if (it == "ALL") "" else it }
        val genreItems = document.select("a.genrePageContentItem")
        val genreIndex = if (requestedGenre.isNotEmpty()) {
            document.select("#genreList li").firstOrNull { li ->
                li.attr("data-genre") == requestedGenre || li.attr("data-genre-seo") == requestedGenre
            }?.selectFirst("a")?.attr("data-index")?.toIntOrNull()
        } else {
            null
        }
        val elements = if (genreItems.isNotEmpty()) {
            val sectionItems = genreIndex
                ?.let { document.select("div._genreFlick div.flick-ct").getOrNull(it) }
                ?.select("a.genrePageContentItem")
                .orEmpty()

            when {
                sectionItems.isNotEmpty() -> sectionItems
                requestedGenre.isEmpty() -> genreItems
                else -> genreItems.filter { item ->
                    normalizeAbsoluteUrl(item.attr("href")).contains("/$requestedGenre/")
                }
            }
        } else {
            val fallback = document.select(
                "li[id^=title_li_] > a, ul.weekly_lst li a, ul.lst_type2 li a, " +
                    "a[href*=list?title_no], a[href*=episodeList?titleNo], .daily_card li a"
            )
            if (requestedGenre.isEmpty()) {
                fallback
            } else {
                fallback.filter { item ->
                    normalizeAbsoluteUrl(item.attr("href")).contains("/$requestedGenre/")
                }
            }
        }
        val page = response.request.url.queryParameter("mihonPage")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val dedupedElements = elements.distinctBy { item ->
            val rawHref = item.attr("href")
            val href = item.absUrl("href").ifEmpty { rawHref }
            cleanMangaDetailPath(href)
        }
        val shouldClientPaginate = requestedGenre.isNotEmpty() && isGenrePageUrl(requestUrl)

        dlog("parseMangaListHtml selectorDebug url=${response.request.url} requestedGenre=$requestedGenre page=$page")
        if (VERBOSE_LIST_LOG) {
            dedupedElements.take(5).forEachIndexed { index, item ->
                val rawHref = item.attr("href")
                val absHref = item.absUrl("href")
                val hrefForPath = absHref.ifEmpty { rawHref }
                val normalized = normalizeAbsoluteUrl(hrefForPath)
                val path = normalizeMangaPath(hrefForPath)
                val titleNo = titleNoFromUrl(absHref) ?: titleNoFromUrl(rawHref) ?: item.attr("data-title-no")
                val titleText = mangaTitleFromElement(item)
                dlog(
                    "parseMangaListHtml item#$index class=${item.className()} " +
                        "rawHref=$rawHref absHref=$absHref normalized=$normalized path=$path " +
                        "titleNo=$titleNo title=$titleText"
                )
            }
        }

        val entries: List<SManga>
        val totalSize: Int
        val hasNextPage: Boolean
        if (shouldClientPaginate) {
            val cachedItems = dedupedElements
                .map(::cachedMangaItemFromElement)
                .filter { it.title.isNotEmpty() && it.url.isNotEmpty() }
            putGenrePageCache(requestedGenre, cachedItems)
            totalSize = cachedItems.size
            val pageItems = cachedItems.drop((page - 1) * GENRE_PAGE_SIZE).take(GENRE_PAGE_SIZE)
            entries = pageItems.map(::mangaFromCachedItem)
            hasNextPage = cachedItems.size > page * GENRE_PAGE_SIZE
        } else {
            entries = dedupedElements
                .map(::mangaFromElement)
                .distinctBy { it.url }
                .filter { it.title.isNotEmpty() }
            totalSize = dedupedElements.size
            hasNextPage = false
        }

        dlog(
            "parseMangaListHtml url=${response.request.url} requestedGenre=$requestedGenre " +
                "genreIndex=$genreIndex rawGenreItems=${genreItems.size} " +
                "selectedElements=${elements.size} deduped=${dedupedElements.size} " +
                "cacheWrite=$shouldClientPaginate total=$totalSize " +
                "page=$page pageSize=$GENRE_PAGE_SIZE entries=${entries.size} hasNextPage=$hasNextPage"
        )
        return MangasPage(entries, hasNextPage)
    }

    private fun parseCachedGenrePage(response: Response): MangasPage {
        val genre = response.request.url.queryParameter("genre").orEmpty()
        val page = response.request.url.queryParameter("mihonPage")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val cache = getValidGenrePageCache(genre)
        if (cache == null) {
            dlog("parseCachedGenrePage genre=$genre page=$page cacheHit=false")
            return MangasPage(emptyList(), false)
        }
        val pageItems = cache.items.drop((page - 1) * GENRE_PAGE_SIZE).take(GENRE_PAGE_SIZE)
        val entries = pageItems.map(::mangaFromCachedItem)
        val hasNextPage = cache.items.size > page * GENRE_PAGE_SIZE
        dlog(
            "parseCachedGenrePage genre=$genre page=$page cacheHit=true " +
                "total=${cache.items.size} entries=${entries.size} hasNextPage=$hasNextPage"
        )
        return MangasPage(entries, hasNextPage)
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
        val cleanPath = cleanMangaDetailPath(manga.url)
        val finalUrl = baseUrl + cleanPath
        dlog(
            "mangaDetailsRequest title=${manga.title} manga.url=${manga.url} " +
                "cleanPath=$cleanPath finalUrl=$finalUrl"
        )
        return GET(finalUrl, reqHeaders)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val parseStartedAt = System.currentTimeMillis()
        val networkMs = response.receivedResponseAtMillis - response.sentRequestAtMillis
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
            val html = document.html()
            val updateTag = extractUpdateTag(html)
            val serialStatus = extractSerialStatus(html)
            val newTag = if (
                serialStatus != "TERMINATION" &&
                isNewTitleDetail(response.request.url.toString(), updateTag)
            ) "新" else ""
            genre = joinNonBlank(genreBase, updateTag, newTag)
            description = detailDiv?.selectFirst("p.summary span.ellipsis")?.text()
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            status = when (serialStatus) {
                "SERIES" -> SManga.ONGOING
                "TERMINATION" -> SManga.COMPLETED
                "REST" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            dlog(
                "mangaDetailsParse url=${response.request.url} title=$title " +
                    "genreBase=$genreBase updateTag=$updateTag newTag=$newTag genre=$genre status=$status " +
                    "networkMs=$networkMs parseMs=${System.currentTimeMillis() - parseStartedAt}"
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 章节列表
    // ══════════════════════════════════════════════════════════════════════

    private val dateFormat = SimpleDateFormat("yyyy-M-d", Locale.ENGLISH)

    override fun chapterListRequest(manga: SManga): Request {
        val reqHeaders = headersBuilder().build()
        val cleanPath = cleanMangaDetailPath(manga.url)
        val finalUrl = baseUrl + cleanPath
        dlog(
            "chapterListRequest title=${manga.title} manga.url=${manga.url} " +
                "cleanPath=$cleanPath finalUrl=$finalUrl"
        )
        return GET(finalUrl, reqHeaders)
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
            val nextRawHref = nextPage.attr("href")
            val nextText = nextPage.text()
            dlog("chapterListParse nextPage rawHref=$nextRawHref absUrl=$nextUrl text=$nextText")
            if (nextUrl.isEmpty()) break

            val reqHeaders = headersBuilder().build()
            document = client.newCall(GET(nextUrl, reqHeaders)).execute().asJsoup()
        }

        val result = chapters.reversed()
        dlog("chapterListParse url=${response.request.url} chapters=${result.size}")
        return result
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
        val finalUrl = baseUrl + chapter.url
        dlog("pageListRequest chapter.name=${chapter.name} chapter.url=${chapter.url} finalUrl=$finalUrl")
        return GET(finalUrl, reqHeaders)
    }

    private fun autoUnlockEpisode(titleNo: String, episodeNo: String) {
        val key = "${titleNo}_${episodeNo}"
        val threadName = Thread.currentThread().name
        val lock = getAutoUnlockLock(key)

        recentAutoUnlockSuccessAge(key)?.let { age ->
            return
        }

        val acquiredImmediately = lock.tryLock()
        if (!acquiredImmediately) {
            return
        }

        try {
            recentAutoUnlockSuccessAge(key)?.let { age ->
                return
            }
            val params = "title_no=$titleNo&episode_no=$episodeNo&platform=MWEB&client=APP_ANDROID"
            val reqHeaders = headersBuilder()
                .set("Referer", "$baseUrl/FANTASY/list?title_no=$titleNo")
                .set("X-Requested-With", "XMLHttpRequest")
                .build()

            val priceResp = client.newCall(GET("$baseUrl/episode/unlock/getEpisodePrice?$params", reqHeaders)).execute()
            val priceBody = priceResp.body.string()
            val priceJson = org.json.JSONObject(priceBody)
            val data = priceJson.optJSONObject("data")
            if (data == null) {
                return
            }

            val isFree = data.optBoolean("free", true)
            val isLimit = data.optBoolean("isLimit", false)
            val price = data.optInt("price", 0)
            val coinCount = data.optInt("coinCount", 0)
            val beanCount = data.optInt("beanCount", 0)
            val episodeName = data.optString("episodeName", "本话")

            if (isFree) {
                return
            }
            if (isLimit) {
                return
            }
            if (coinCount < price) {
                throw Exception("余额不足：$episodeName 需要 $price 币，当前余额 $coinCount 币，请前往咚漫充值")
            }

            val payResp = client.newCall(GET("$baseUrl/episode/unlock/pay?$params", reqHeaders)).execute()
            val payBody = payResp.body.string()
            val payJson = org.json.JSONObject(payBody)
            val payCode = payJson.optInt("code")
            val payMessage = payJson.optString("message")
            if (payCode != 200) {
                return
            }
            markAutoUnlockSuccess(key)
        } catch (e: Exception) {
            throw e
        } finally {
            lock.unlock()
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

    private fun getValidGenrePageCache(genre: String): GenrePageCache? {
        if (genre.isBlank()) return null
        val now = System.currentTimeMillis()
        return synchronized(genrePageCache) {
            val cache = genrePageCache[genre] ?: return@synchronized null
            if (now - cache.createdAt <= GENRE_PAGE_CACHE_TTL_MS) {
                cache
            } else {
                genrePageCache.remove(genre)
                null
            }
        }
    }

    private fun putGenrePageCache(genre: String, items: List<CachedMangaItem>) {
        if (genre.isBlank() || items.isEmpty()) return
        synchronized(genrePageCache) {
            genrePageCache[genre] = GenrePageCache(genre, System.currentTimeMillis(), items)
        }
        dlog("putGenrePageCache genre=$genre total=${items.size}")
    }

    private fun cachedMangaItemFromElement(element: Element): CachedMangaItem {
        val rawHref = element.attr("href")
        val href = element.absUrl("href").ifEmpty { rawHref }
        val cleanPath = cleanMangaDetailPath(href)
        val titleNo = titleNoFromUrl(cleanPath) ?: titleNoFromUrl(href) ?: titleNoFromUrl(rawHref) ?: element.attr("data-title-no")
        val hasNew = hasNewBadge(element)
        cacheNewTitle(titleNo, hasNew)
        return CachedMangaItem(
            url = cleanPath,
            title = mangaTitleFromElement(element),
            thumbnailUrl = extractThumbnailUrl(element),
            titleNo = titleNo,
            hasNew = hasNew,
        )
    }

    private fun mangaFromCachedItem(item: CachedMangaItem): SManga = SManga.create().apply {
        url = item.url
        title = item.title
        thumbnail_url = item.thumbnailUrl
    }

    private fun mangaTitleFromElement(element: Element): String {
        return element.selectFirst(
            "p.subj, .subj .ellipsis, ._items_name_t, .home_genre_t, .works_tit, " +
                "p.chapter-title-02, .chapter-title-01, .tit_content"
        )?.text() ?: element.attr("title").ifEmpty { element.selectFirst("img")?.attr("alt") ?: "" }
    }

    private var newProbeLogCount = 0

    private fun entriesNewProbeShouldLog(): Boolean {
        if (newProbeLogCount >= NEW_PROBE_LOG_LIMIT) return false
        newProbeLogCount += 1
        return true
    }

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        val rawHref = element.attr("href")
        val href = element.absUrl("href").ifEmpty { rawHref }
        val cleanPath = cleanMangaDetailPath(href)
        val titleNo = titleNoFromUrl(cleanPath) ?: titleNoFromUrl(href) ?: titleNoFromUrl(rawHref) ?: element.attr("data-title-no")
        val hasNew = hasNewBadge(element)
        cacheNewTitle(titleNo, hasNew)
        url = cleanPath
        title = mangaTitleFromElement(element)
        thumbnail_url = extractThumbnailUrl(element)
        if (VERBOSE_LIST_LOG) {
            dlog(
                "mangaFromElement rawHref=$rawHref absHref=$href cleanPath=$cleanPath storedUrl=$url " +
                    "titleNo=$titleNo hasNew=$hasNew title=$title"
            )
        }
    }

    private fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        val rawHref = element.attr("href")
        val href = element.absUrl("href").ifEmpty { rawHref }
        val cleanPath = cleanMangaDetailPath(href)
        url = cleanPath
        title = element.selectFirst(".info .subj .ellipsis, p.subj .ellipsis")?.text() ?: ""
        thumbnail_url = extractThumbnailUrl(element)
        if (VERBOSE_LIST_LOG) {
            dlog("searchMangaFromElement rawHref=$rawHref absHref=$href cleanPath=$cleanPath storedUrl=$url title=$title")
        }
    }


    private fun normalizeAbsoluteUrl(rawUrl: String): String {
        val url = rawUrl.trim()
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$baseUrl$url"
            url.isNotEmpty() -> "$baseUrl/$url"
            else -> ""
        }
    }

    private fun normalizeMangaPath(rawUrl: String): String {
        val absoluteUrl = normalizeAbsoluteUrl(rawUrl)
        return absoluteUrl
            .removePrefix(baseUrl)
            .removePrefix("https://m.dongmanmanhua.cn")
            .removePrefix("http://m.dongmanmanhua.cn")
            .ifEmpty { rawUrl }
    }

    private fun cleanMangaDetailPath(rawUrl: String): String {
        val path = normalizeMangaPath(rawUrl).trim()
        val titleNo = titleNoFromUrl(path) ?: return path
            .substringBefore("&source")
            .substringBefore("&pageModel")
            .substringBefore("?source")
            .substringBefore("?pageModel")

        val basePath = path.substringBefore("?")
        return if (basePath.endsWith("/episodeList") || basePath == "/episodeList") {
            "/episodeList?titleNo=$titleNo"
        } else {
            "$basePath?title_no=$titleNo"
        }
    }

    private fun buildThumbnailUrl(rawUrl: String, base: String = cdnBase): String {
        val url = rawUrl.trim()
        return when {
            url.isBlank() -> ""
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$base$url"
            else -> "$base/$url"
        }
    }

    private fun extractThumbnailUrl(element: Element): String {
        val img = element.selectFirst("img")
        val rawUrl = img?.attr("data-original")
            ?.ifEmpty { img.attr("data-src") }
            ?.ifEmpty { img.attr("src") }
            ?: ""
        if (rawUrl.isNotBlank()) {
            return buildThumbnailUrl(rawUrl, cdnBase)
        }

        val style = element.selectFirst("[style*=background]")?.attr("style").orEmpty()
        val match = Regex("""url\(['"]?([^)'"]+)['"]?\)""").find(style)
        return buildThumbnailUrl(match?.groupValues?.getOrNull(1).orEmpty(), cdnBase)
    }


    private fun hasNewBadge(element: Element): Boolean {
        // 只认卡片自己的 class / 图标，不因为页面文字包含 NEW 就误判。
        return element.selectFirst(
            ".ico_new_cn, .icon_new, [class*=ico_new], [class*=icon_new], img[src*=icon_new], img[src*=ico_new]"
        ) != null
    }

    private fun genreCodeFromUrl(url: String): String? {
        return getThemeFilter()
            .map { it.value }
            .firstOrNull { it.isNotEmpty() && it != "ALL" && (url.contains("/$it/") || url.endsWith("/$it")) }
    }


    private fun currentWeekdayCode(): String {
        return when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "MONDAY"
            Calendar.TUESDAY -> "TUESDAY"
            Calendar.WEDNESDAY -> "WEDNESDAY"
            Calendar.THURSDAY -> "THURSDAY"
            Calendar.FRIDAY -> "FRIDAY"
            Calendar.SATURDAY -> "SATURDAY"
            Calendar.SUNDAY -> "SUNDAY"
            else -> "MONDAY"
        }
    }

    private fun isGenrePageUrl(url: String): Boolean {
        return getThemeFilter().any { tag ->
            tag.value.isNotEmpty() && tag.value != "ALL" &&
                (url.contains("/${tag.value}/") || url.endsWith("/${tag.value}"))
        }
    }

    private fun titleNoFromUrl(url: String): String? {
        return Regex("""(?:title_no|titleNo)=([0-9]+)""").find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""/title/([0-9]+)""").find(url)?.groupValues?.getOrNull(1)
    }

    private fun cacheNewTitle(titleNo: String?, isNew: Boolean) {
        if (titleNo.isNullOrBlank()) return
        if (isNew) {
            newTitleCache[titleNo] = true
            dlog("cacheNewTitle titleNo=$titleNo source=list-or-json isNew=true")
        } else if (VERBOSE_LIST_LOG) {
            dlog("cacheNewTitle titleNo=$titleNo source=list-or-json isNew=false ignored")
        }
    }

    private fun isNewTitleDetail(url: String, updateTag: String): Boolean {
        val titleNo = titleNoFromUrl(url) ?: run {
            dlog("isNewTitleDetail noTitleNo url=$url")
            return false
        }

        if (newTitleCache[titleNo] == true) {
            dlog("isNewTitleDetail titleNo=$titleNo source=cache value=true")
            return true
        }

        dailyScheduleNewCache[titleNo]?.let { cached ->
            dlog("isNewTitleDetail titleNo=$titleNo source=dailySchedule-cache value=$cached")
            return cached
        }

        dlog("isNewTitleDetail titleNo=$titleNo source=dailySchedule-skip-fresh value=false updateTag=$updateTag")
        return false
    }

    private fun fetchNewTitleFromDailySchedule(titleNo: String): Boolean? {
        return runCatching {
            val now = System.currentTimeMillis()
            val document = if (
                dailyScheduleDocCache != null &&
                now - dailyScheduleDocCacheTime < dailyScheduleDocCacheTtlMs
            ) {
                dlog("fetchNewTitleFromDailySchedule titleNo=$titleNo using cached dailySchedule")
                dailyScheduleDocCache!!
            } else {
                val url = "$baseUrl/dailySchedule"
                val startedAt = now
                val doc = client.newCall(GET(url, headersBuilder().build())).execute().asJsoup()
                dailyScheduleDocCache = doc
                dailyScheduleDocCacheTime = System.currentTimeMillis()
                dlog("fetchNewTitleFromDailySchedule fetched fresh elapsed=${System.currentTimeMillis() - startedAt}ms")
                doc
            }

            val element = document.selectFirst("li#title_li_$titleNo, li[data-title-no=$titleNo]")
                ?: run {
                    dailyScheduleNewCache[titleNo] = false
                    dlog("fetchNewTitleFromDailySchedule titleNo=$titleNo found=false completed-or-not-in-schedule")
                    return@runCatching null
                }

            val isNew = hasNewBadge(element)
            dailyScheduleNewCache[titleNo] = isNew
            if (isNew) cacheNewTitle(titleNo, true)
            dlog("fetchNewTitleFromDailySchedule titleNo=$titleNo found=true isNew=$isNew")
            isNew
        }.getOrElse { e ->
            wlog("fetchNewTitleFromDailySchedule failed titleNo=$titleNo", e)
            null
        }
    }

    private fun joinNonBlank(vararg parts: String): String {
        return parts.map { it.trim().trim(',') }.filter { it.isNotEmpty() }.joinToString(", ")
    }

    // ══════════════════════════════════════════════════════════════════════
    // 常量
    // ══════════════════════════════════════════════════════════════════════

    companion object {
        private const val TAG = "DongmanManhua"
        private const val GENRE_PAGE_SIZE = 50
        private const val GENRE_PAGE_CACHE_TTL_MS = 10 * 60 * 1000L
        private const val LOCAL_GENRE_CACHE_PATH = "/__dongman_cache__/genre"
        private const val NEW_PROBE_LOG_LIMIT = 5
        private const val VERBOSE_LIST_LOG = false

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
        internal const val PREF_FILTER_WEEKDAY = "pref_filter_weekday"
        internal const val PREF_FILTER_SORT = "pref_filter_sort"
        internal const val PREF_FILTER_THEME = "pref_filter_theme"
        internal const val PREF_FILTER_ACTIVE_GROUP = "pref_filter_active_group"
        internal const val PREF_FILTER_WEEKDAY_STATE = "pref_filter_weekday_state"
        internal const val PREF_FILTER_SORT_STATE = "pref_filter_sort_state"
        internal const val PREF_FILTER_THEME_STATE = "pref_filter_theme_state"
        internal const val PREF_FILTER_MY_MANGA_STATE = "pref_filter_my_manga_state"
        internal const val KEY_NEO_SES = "neo_ses"
        internal const val KEY_NEO_CHK = "neo_chk"

        internal const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
        internal const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/114.0"
    }
}
