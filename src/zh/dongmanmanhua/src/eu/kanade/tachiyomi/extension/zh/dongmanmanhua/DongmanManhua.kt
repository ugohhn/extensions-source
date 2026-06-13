package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import android.content.Context
import android.os.Handler
import android.os.Looper
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
        val document = response.asJsoup()

        if (weekday == "NEW") {
            val entries = document.select(".new_works_items").mapNotNull { item ->
                SManga.create().apply {
                    setUrlWithoutDomain(item.absUrl("href"))
                    title = item.selectFirst(".works_tit")?.text() ?: return@mapNotNull null
                    thumbnail_url = item.selectFirst(".works_img_area img")?.absUrl("src") ?: ""
                }
            }.filter { it.title.isNotEmpty() }
            return MangasPage(entries, false)
        }

        // 优先：服务端已按 weekday 参数过滤，直接取全部 li
        // 兜底：旧版按 div._list_WEEKDAY 分区（某些页面结构）
        val todayDiv = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> "div._list_SUNDAY"
            Calendar.MONDAY -> "div._list_MONDAY"
            Calendar.TUESDAY -> "div._list_TUESDAY"
            Calendar.WEDNESDAY -> "div._list_WEDNESDAY"
            Calendar.THURSDAY -> "div._list_THURSDAY"
            Calendar.FRIDAY -> "div._list_FRIDAY"
            Calendar.SATURDAY -> "div._list_SATURDAY"
            else -> "div._list_MONDAY"
        }
        val targetDiv = when (weekday) {
            "" -> todayDiv
            "COMPLETE" -> "div._list_COMPLETE"
            else -> "div._list_$weekday"
        }
        val entries = (
            document.select("$targetDiv li > a") +
                document.select("li[id^=title_li_] > a") +
                document.select("ul.daily_card li a")
            )
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
        val weekdayFilter = filters.firstOrNull { it is WeekdayFilter } as? WeekdayFilter
        val weekdayValue = weekdayFilter?.getSelectedValue().orEmpty()

        val sortFilter = filters.firstOrNull { it is SortFilter } as? SortFilter
        val sortSelection = sortFilter?.state as? Filter.Sort.Selection
        val sortValue = if (sortSelection != null) {
            getSortFilter().getOrNull(sortSelection.index)?.value ?: "READ_COUNT"
        } else {
            "READ_COUNT"
        }

        // latestUpdatesParse() 需要知道当前更新分区。
        // 这里保存的是“更新”自己的状态；题材/我的漫画不会用它组合请求。
        preferences.edit()
            .putString(PREF_FILTER_WEEKDAY, weekdayValue)
            .putString(PREF_FILTER_SORT, sortValue)
            .apply()

        // query 为空时走筛选浏览。这里照 ColaManga 的思路：
        // 不额外加“入口”下拉框，而是看哪个筛选项有真实值。
        // 优先级：我的漫画 > 题材 > 更新。
        // 这样不会出现“题材 + 周日 + 我的漫画 + 排序”的组合请求。
        if (query.isBlank()) {
            val migrateFilter = filters.firstOrNull { it is MigrateFilter } as? MigrateFilter
            val migrateValue = migrateFilter?.let { getMigrateFilter().getOrNull(it.state)?.value }.orEmpty()
            when (migrateValue) {
                "recent" -> return GET("$baseUrl/home/recentSeeing?size=50", headersBuilder().build())
                "purchased" -> return GET("$baseUrl/episode/unlock/titleList?platform=MWEB", headersBuilder().build())
            }

            val themeFilter = filters.firstOrNull { it is ThemeFilter } as? ThemeFilter
            val themeValue = themeFilter?.let { getThemeFilter().getOrNull(it.state)?.value }.orEmpty()
            if (themeValue.isNotEmpty()) {
                // 题材接口不吃排序，不拼 sortOrder。
                return GET("$baseUrl/$themeValue/list", headersBuilder().build())
            }

            val todayCode = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> "MONDAY"
                Calendar.TUESDAY -> "TUESDAY"
                Calendar.WEDNESDAY -> "WEDNESDAY"
                Calendar.THURSDAY -> "THURSDAY"
                Calendar.FRIDAY -> "FRIDAY"
                Calendar.SATURDAY -> "SATURDAY"
                Calendar.SUNDAY -> "SUNDAY"
                else -> "MONDAY"
            }
            val url = when (weekdayValue) {
                "NEW" -> "$baseUrl/new"
                "COMPLETE" -> "$baseUrl/dailySchedule?weekday=COMPLETE&sortOrder=$sortValue"
                "" -> "$baseUrl/dailySchedule?weekday=$todayCode&sortOrder=$sortValue"
                else -> "$baseUrl/dailySchedule?weekday=$weekdayValue&sortOrder=$sortValue"
            }
            return GET(url, headersBuilder().build())
        }

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
        return when {
            url.contains("/home/recentSeeing") -> parseRecentSeeing(response)
            url.contains("/episode/unlock/titleList") -> parsePurchasedTitles(response)
            url.contains("/searchResult") -> parseSearchResultJson(response)
            url.contains("/dailySchedule") || url.endsWith("/new") || url.contains("/new?") -> latestUpdatesParse(response)
            url.contains("/list") -> parseMangaListHtml(response)
            else -> parseSearchHtml(response)
        }
    }

    private fun parseRecentSeeing(response: Response): MangasPage {
        val body = response.body.string()
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return MangasPage(emptyList(), false)
        if (json.optInt("code") != 200) return MangasPage(emptyList(), false)
        val data = json.optJSONObject("data") ?: return MangasPage(emptyList(), false)
        val items = data.optJSONArray("recentlyViewed") ?: data.optJSONArray("list") ?: data.optJSONArray("titles") ?: return MangasPage(emptyList(), false)
        val entries = mutableListOf<SManga>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val titleNo = item.optInt("titleNo", 0)
            if (titleNo == 0) continue
            entries.add(SManga.create().apply {
                url = "/episodeList?titleNo=$titleNo"
                title = item.optString("title", "")
                val thumb = item.optString("thumbnail", "")
                thumbnail_url = if (thumb.startsWith("//")) "https:$thumb" else thumb
            })
        }
        return MangasPage(entries.filter { it.title.isNotEmpty() }, false)
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
            val titleNo = item.optInt("titleNo", 0)
            if (titleNo == 0) continue
            entries.add(SManga.create().apply {
                url = "/episodeList?titleNo=$titleNo"
                title = item.optString("title", "")
                val thumb = item.optString("thumbnail", "")
                    .ifEmpty { item.optString("thumbnailMobile", "") }
                    .ifEmpty { item.optString("image", "") }
                thumbnail_url = if (thumb.startsWith("//")) "https:$thumb" else thumb
            })
        }
        return MangasPage(entries.filter { it.title.isNotEmpty() }, false)
    }

    private fun parseMangaListHtml(response: Response): MangasPage {
        val document = response.asJsoup()
        val entries = document
            .select(
                "li[id^=title_li_] > a, ul.weekly_lst li a, ul.lst_type2 li a, " +
                    "a[href*=list?title_no], a[href*=episodeList?titleNo], .daily_card li a"
            )
            .map(::mangaFromElement)
            .distinctBy { it.url }
            .filter { it.title.isNotEmpty() }
        return MangasPage(entries, false)
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
        return GET(baseUrl + manga.url, reqHeaders)
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
        return GET(baseUrl + manga.url, reqHeaders)
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
            document = client.newCall(GET(nextUrl, reqHeaders)).execute().asJsoup()
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
        return GET(baseUrl + chapter.url, reqHeaders)
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
        internal const val PREF_FILTER_WEEKDAY = "pref_filter_weekday"
        internal const val PREF_FILTER_SORT = "pref_filter_sort"
        internal const val KEY_NEO_SES = "neo_ses"
        internal const val KEY_NEO_CHK = "neo_chk"

        internal const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
        internal const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/114.0"
    }
}
