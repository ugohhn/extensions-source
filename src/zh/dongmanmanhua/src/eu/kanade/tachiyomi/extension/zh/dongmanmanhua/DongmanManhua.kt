package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
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
import okhttp3.Interceptor
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
import java.io.InterruptedIOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    // 详情页“新”只以真实 /new 响应中的当前 titleNo 集合为准。
    // 这是内存短快照：不写入 SP / 文件，也不接收普通列表、JSON 或首页卡片的历史 true。
    private data class DetailNewPageSnapshot(
        val titleNos: Set<String>,
        val fetchedAtMs: Long,
        val source: String,
    )


    @Volatile
    private var detailNewPageSnapshot: DetailNewPageSnapshot? = null
    private val detailNewPageSnapshotLock = Any()

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

        SwitchPreferenceCompat(ctx).apply {
            key = PREF_LIST_INFLIGHT_COALESCE
            title = "合并短时间重复刷新请求"
            summary = "开启后，仅合并正在进行中的相同列表请求；第一页仍然真实请求，不使用旧缓存"
            setDefaultValue(false)
        }.also(screen::addPreference)

        ListPreference(ctx).apply {
            key = PREF_HOME_COVER_MODE
            title = "首页封面获取模式"
            entries = arrayOf("速度优先（可见封面等待）", "真实封面优先（实验）")
            entryValues = arrayOf(HOME_COVER_MODE_FAST, HOME_COVER_MODE_OFFICIAL_FIRST)
            setDefaultValue(HOME_COVER_MODE_FAST)
            bindHomeCoverModeSummary(HOME_COVER_MODE_FAST)
        }.also(screen::addPreference)

        ListPreference(ctx).apply {
            key = PREF_DETAIL_COVER_REFRESH_MODE
            title = "详情页手动刷新封面"
            entries = arrayOf("同步刷新封面（默认）", "保留现有封面")
            entryValues = arrayOf(DETAIL_COVER_REFRESH_SYNC, DETAIL_COVER_REFRESH_PRESERVE)
            setDefaultValue(DETAIL_COVER_REFRESH_SYNC)
            bindDetailCoverRefreshModeSummary(DETAIL_COVER_REFRESH_SYNC)
        }.also(screen::addPreference)

        MultiSelectListPreference(ctx).apply {
            key = PREF_POPULAR_GENRE_ENABLED
            title = "首页热门题材模块"
            summary = "控制首页热门里的题材推荐块；默认排除悬疑、搞笑"
            val tags = getThemeFilter().filter { it.value.isNotBlank() && it.value != "ALL" }
            entries = tags.map { it.name }.toTypedArray()
            entryValues = tags.map { it.value }.toTypedArray()
            setDefaultValue(defaultPopularGenreValues())
        }.also(screen::addPreference)

        ListPreference(ctx).apply {
            key = PREF_UA
            title = "User-Agent 预设"
            entries = arrayOf("移动版（默认）", "Windows Firefox", "禁用 User-Agent", "自定义（见下方输入框）")
            entryValues = arrayOf(UA_MOBILE, UA_DESKTOP, "", PREF_UA_CUSTOM_FLAG)
            setDefaultValue(UA_MOBILE)
            bindEntrySummary(UA_MOBILE)
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

    private fun ListPreference.bindEntrySummary(defaultValue: String) {
        updateEntrySummary(this@DongmanManhua.preferences.getString(key, defaultValue) ?: defaultValue)
        setOnPreferenceChangeListener { preference, newValue ->
            (preference as? ListPreference)?.updateEntrySummary(newValue as? String)
            true
        }
    }

    private fun ListPreference.updateEntrySummary(selectedValue: String?) {
        val index = findIndexOfValue(selectedValue ?: value)
        summary = if (index >= 0) entries[index] else ""
    }

    private fun ListPreference.bindHomeCoverModeSummary(defaultValue: String) {
        updateHomeCoverModeSummary(this@DongmanManhua.preferences.getString(key, defaultValue) ?: defaultValue)
        setOnPreferenceChangeListener { preference, newValue ->
            val oldMode = getHomeCoverMode()
            val newMode = normalizeHomeCoverMode(newValue as? String)
            (preference as? ListPreference)?.updateHomeCoverModeSummary(newMode)
            if (oldMode != newMode) {
                clearCoverModeSensitiveCaches("home-cover-mode-change:$oldMode->$newMode")
            }
            true
        }
    }

    private fun ListPreference.updateHomeCoverModeSummary(selectedValue: String?) {
        val normalized = normalizeHomeCoverMode(selectedValue)
        val index = findIndexOfValue(normalized)
        val current = if (index >= 0) entries[index] else ""
        summary = "当前：$current\n切换后刷新首页生效；列表保持真实可加载封面，详情页以详情解析的最终封面为准"
    }

    private fun ListPreference.bindDetailCoverRefreshModeSummary(defaultValue: String) {
        updateDetailCoverRefreshModeSummary(this@DongmanManhua.preferences.getString(key, defaultValue) ?: defaultValue)
        setOnPreferenceChangeListener { preference, newValue ->
            val normalized = normalizeDetailCoverRefreshMode(newValue as? String)
            (preference as? ListPreference)?.updateDetailCoverRefreshModeSummary(normalized)
            true
        }
    }

    private fun ListPreference.updateDetailCoverRefreshModeSummary(selectedValue: String?) {
        val normalized = normalizeDetailCoverRefreshMode(selectedValue)
        val index = findIndexOfValue(normalized)
        val current = if (index >= 0) entries[index] else ""
        summary = when (normalized) {
            DETAIL_COVER_REFRESH_PRESERVE -> "当前：$current\n手动刷新详情时不写入 thumbnail_url；只更新简介、标签、状态、章节等。"
            else -> "当前：$current\n手动刷新详情时检查官方封面；不同才更新，相同 canonical 只跳过重复写入。"
        }
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
            if (request.url.encodedPath == OFFICIAL_COVER_VIRTUAL_PATH) {
                executeOfficialCoverVirtualRequest(request, chain)
            } else if (request.url.encodedPath == LOCAL_GENRE_CACHE_PATH || request.url.encodedPath == LOCAL_UPDATE_CACHE_PATH) {
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
        .addInterceptor { chain ->
            val request = chain.request()
            val listKey = listInflightCoalesceKey(request)
            val inflightEnabled = getListInflightCoalesceEnable()
            val refreshAction = when {
                listKey == null -> "notEligible"
                !inflightEnabled -> "disabled"
                else -> "eligible"
            }
            logRefreshModeProbe(request, listKey, inflightEnabled, refreshAction)
            if (listKey != null && inflightEnabled) {
                executeListRequestWithInflightCoalesce(listKey, request, chain)
            } else {
                chain.proceed(request)
            }
        }
        .addInterceptor { chain ->
            val request = chain.request()
            val detailCacheKey = detailHtmlCacheKey(request)
            if (detailCacheKey == null) {
                chain.proceed(request)
            } else {
                executeDetailHtmlRequestWithCache(detailCacheKey, request, chain)
            }
        }
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val isPopularHomeRequest = request.url.encodedPath == "/" && request.url.queryParameter("pageName") == "home"
            val startedAt = System.currentTimeMillis()
            try {
                val response = chain.proceed(request)
                mergeSetCookieFromResponse(response)
                val elapsed = System.currentTimeMillis() - startedAt
                if (isPopularHomeRequest) {
                    dlog("popularPerf networkHome elapsed=${elapsed}ms code=${response.code}")
                }
                if (elapsed >= SLOW_NETWORK_LOG_MS && isDetailLikePath(request.url.encodedPath)) {
                    dlog("networkSlow path=${request.url.encodedPath} url=${request.url} elapsed=${elapsed}ms code=${response.code}")
                }
                response
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - startedAt
                if (isPopularHomeRequest) {
                    dlog("popularPerf networkHomeFailed elapsed=${elapsed}ms error=${e.javaClass.simpleName}")
                }
                if (isDetailLikePath(request.url.encodedPath)) {
                    wlog("networkFailed path=${request.url.encodedPath} url=${request.url} elapsed=${elapsed}ms", e)
                }
                throw e
            }
        }
        .build()


    // ══════════════════════════════════════════════════════════════════════
    // 首页（触发探针）
    // ══════════════════════════════════════════════════════════════════════

    override fun popularMangaRequest(page: Int): Request {
        probeIsLoginValid()
        if (page == 1) {
            resetFilterSessionState("popular-page1")
            scheduleCanonicalMangaIdentityStoreLoadAsync()
            scheduleLegacyNewWorkPersistentCachesClear()
            // v96：首页标题只使用本次 home HTML 的 data-sc-event-parameter。
            // v100.7.9：封面只接受已验证官方来源；不使用虚拟 URL、detail key、空封面伪装或未验证兜底。
        }
        return GET("$baseUrl/?pageName=home", headersBuilder().build())
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val parseStartedAt = System.currentTimeMillis()
        val document = response.asJsoup()
        val afterDocumentAt = System.currentTimeMillis()
        val rawElements = collectPopularMangaElements(document)
        val afterCollectAt = System.currentTimeMillis()
        rememberCanonicalMangaIdentitiesFromElements(rawElements)
        // 这里只整理同一次 home HTML 中普通模块本来就有的资料；
        // “新作登场”不会读取该内存资料，而是直接验证自己的事件字段。
        preseedOfficialMetaFromPopularRawElements(rawElements)
        val afterHomeMetaAt = System.currentTimeMillis()
        val elements = selectPopularMangaElements(rawElements)
        val coverMode = getHomeCoverMode()
        val foregroundOfficialLimit = if (coverMode == HOME_COVER_MODE_OFFICIAL_FIRST) {
            OFFICIAL_FIRST_COVER_LIMIT
        } else {
            OFFICIAL_FAST_COVER_WAIT_LIMIT
        }
        val warmupStats = if (coverMode == HOME_COVER_MODE_OFFICIAL_FIRST) {
            prepareOfficialNewWorkCoversForPopularOfficialFirst(elements)
        } else {
            prepareOfficialNewWorkCoversForPopularVisibleNoBlank(elements)
        }
        val backgroundOfficialStats = scheduleRemainingOfficialNewWorkCoversForPopularAsync(elements, foregroundOfficialLimit)
        val afterCoverWaitAt = System.currentTimeMillis()
        val entries = elements
            .map { mangaFromElement(it, it.attr("data-mihon-origin").ifBlank { "popular" }) }
            .filter { it.title.isNotEmpty() }
            .distinctBy { mangaIdentityDedupKey(titleNoFromUrl(it.url), it.url) }
        val afterEntriesAt = System.currentTimeMillis()
        dlog("popularMangaParse modules raw=${rawElements.size} selected=${elements.size} entries=${entries.size}")
        dlog(
            "popularPerf parse document=${afterDocumentAt - parseStartedAt}ms " +
                "collect=${afterCollectAt - afterDocumentAt}ms " +
                "homeMeta=${afterHomeMetaAt - afterCollectAt}ms " +
                "officialCoverWait=${afterCoverWaitAt - afterHomeMetaAt}ms " +
                "nativeBuild=${afterEntriesAt - afterCoverWaitAt}ms " +
                "mainpathExtraRequests=0 " +
                "mainpathOfficialDetailRequests=0 " +
                "warmupTargets=${warmupStats.titleNos} " +
                "warmupAlreadyReady=${warmupStats.alreadyReady} " +
                "warmupTrusted=${warmupStats.trustedReady} " +
                "warmupScheduled=${warmupStats.scheduled} " +
                "backgroundOfficialTargets=${backgroundOfficialStats.titleNos} " +
                "backgroundOfficialScheduled=${backgroundOfficialStats.scheduled} " +
                "coverMode=$coverMode " +
                "coverWait=${afterCoverWaitAt - afterHomeMetaAt}ms " +
                "marketingRequests=0 " +
                "total=${afterEntriesAt - parseStartedAt}ms"
        )
        logCleanAuditSummary("popular", entries)
        return MangasPage(entries, false)
    }

    private fun collectPopularMangaElements(document: org.jsoup.nodes.Document): List<Element> {
        val result = mutableListOf<Element>()
        val seen = mutableSetOf<Int>()
        fun addModule(origin: String, selector: String, includeElement: (Element) -> Boolean = { true }) {
            val items = document.select(selector)
                .filter { it.attr("href").isNotBlank() }
                .filter { includeElement(it) }
            val added = mutableListOf<Element>()
            items.forEachIndexed { index, element ->
                val key = System.identityHashCode(element)
                if (seen.add(key)) {
                    element.attr("data-mihon-origin", origin)
                    element.attr("data-mihon-origin-index", index.toString())
                    element.attr("data-mihon-raw-index", result.size.toString())
                    result.add(element)
                    added.add(element)
                }
            }
            logPopularModuleProbe(origin, added)
        }

        // 首页热门按模块拆开，避免继续用一条全局选择器粗暴扫。
        addModule(
            "popular-banner",
            ".main_banner a[href], a[data-sc-name=M_discover-page_banner][href]"
        )
        addModule(
            "popular-ranking",
            "a.ranking_list_items[href], a[data-sc-name=M_discover-page_rank-list-item][href]"
        )
        addModule(
            "popular-genre-category",
            ".homeGenreContent a.lst_item[href], .genre_content_c a.lst_item[href], " +
                "a[data-sc-name=M_discover-page_genre-title-list-item][href]"
        ) { hasCleanOrKnownCanonicalIdentity(it) && isPopularGenreEnabled(it) }
        addModule(
            "popular-common-card",
            "li[id^=title_li_] > a[href], ul.lst_type2 li a[href], ul.weekly_lst li a[href], " +
                "a[href*=list?title_no], a[href*=episodeList?titleNo]"
        ) {
            hasCleanOrKnownCanonicalIdentity(it) &&
                !isPopularGenreCategoryElement(it)
        }

        return result
    }

    private fun selectPopularMangaElements(rawElements: List<Element>): List<Element> {
        val groups = linkedMapOf<String, MutableList<Element>>()
        rawElements.forEach { element ->
            val key = popularElementIdentityKey(element)
            if (key.isBlank()) return@forEach
            groups.getOrPut(key) { mutableListOf() }.add(element)
        }

        var bannerOnlyNoThumbnail = 0
        var preferredNonBanner = 0
        data class PopularSelection(
            val element: Element,
            val displayOrder: Int,
            val displayIndex: Int,
            val groupIndex: Int,
        )

        val selected = groups.values.mapIndexedNotNull { groupIndex, candidates ->
            val chosen = choosePopularElement(candidates) ?: return@mapIndexedNotNull null
            val hasBanner = candidates.any { it.attr("data-mihon-origin") == "popular-banner" }
            if (hasBanner && chosen.attr("data-mihon-origin") != "popular-banner") {
                preferredNonBanner += 1
            }
            if (chosen.attr("data-mihon-origin") == "popular-banner") {
                bannerOnlyNoThumbnail += 1
            }
            PopularSelection(
                element = chosen,
                displayOrder = popularGroupDisplayOrder(candidates, chosen),
                displayIndex = popularGroupDisplayIndex(candidates, chosen),
                groupIndex = groupIndex,
            )
        }
        val ordered = selected
            .sortedWith(
                compareBy<PopularSelection> { it.displayOrder }
                    .thenBy { it.displayIndex }
                    .thenBy { it.groupIndex }
            )
            .map { it.element }

        dlog(
            "popularSelect bannerThumbnail=false raw=${rawElements.size} groups=${groups.size} " +
                "selected=${ordered.size} bannerOnlyNoThumbnail=$bannerOnlyNoThumbnail " +
                "preferredNonBanner=$preferredNonBanner"
        )
        return ordered
    }

    private fun popularGroupDisplayOrder(candidates: List<Element>, chosen: Element): Int {
        return if (candidates.any { it.attr("data-mihon-origin") == "popular-banner" }) {
            0
        } else {
            popularDisplayOrder(chosen)
        }
    }

    private fun popularGroupDisplayIndex(candidates: List<Element>, chosen: Element): Int {
        val bannerCandidate = candidates
            .filter { it.attr("data-mihon-origin") == "popular-banner" }
            .minByOrNull(::popularElementOriginIndex)
        return popularElementOriginIndex(bannerCandidate ?: chosen)
    }

    private fun popularElementOriginIndex(element: Element): Int {
        return element.attr("data-mihon-origin-index").toIntOrNull()
            ?: element.attr("data-mihon-raw-index").toIntOrNull()
            ?: Int.MAX_VALUE
    }

    private fun popularElementIdentityKey(element: Element): String {
        val titleNo = titleNoFromElementIdentity(element)
        if (!titleNo.isNullOrBlank()) return "titleNo:$titleNo"
        val rawHref = element.attr("href")
        val href = element.absUrl("href").ifEmpty { rawHref }
        val cleanPath = cleanMangaDetailPath(href)
        return cleanPath.ifBlank { href }
    }

    private fun choosePopularElement(candidates: List<Element>): Element? {
        val nonBannerCandidates = candidates.filter { it.attr("data-mihon-origin") != "popular-banner" }
        return if (nonBannerCandidates.isNotEmpty()) {
            nonBannerCandidates.maxByOrNull { popularElementScore(it) }
        } else {
            // 轮播独占条目保留漫画身份，但不写横幅图到 thumbnailUrl。
            candidates.maxByOrNull { popularElementScore(it) }
        }
    }

    private fun popularDisplayOrder(element: Element): Int {
        val origin = element.attr("data-mihon-origin")
        return when {
            origin == "popular-banner" -> 0
            origin == "popular-common-card" && isPopularCommonCardNewWork(element) -> 1
            origin == "popular-ranking" -> 2
            origin == "popular-genre-category" -> 3
            origin == "popular-common-card" -> 4
            else -> 5
        }
    }

    private fun popularElementScore(element: Element): Int {
        val rawHref = element.attr("href")
        val href = element.absUrl("href").ifEmpty { rawHref }
        val cleanPath = cleanMangaDetailPath(href)
        val origin = element.attr("data-mihon-origin")
        var score = 0
        if (isCleanWorkPagePath(cleanPath)) score += 10_000
        if (hasCleanOrKnownCanonicalIdentity(element)) score += 1_000
        if (hasUsableNonBannerThumbnail(element)) score += 500
        score += when (origin) {
            "popular-ranking" -> 90
            "popular-common-card" -> if (isPopularCommonCardNewWork(element)) 60 else 80
            "popular-genre-category" -> 70
            "popular-banner" -> 10
            else -> 10
        }
        return score
    }

    private fun hasUsableNonBannerThumbnail(element: Element): Boolean {
        val img = element.selectFirst("img")
        val rawUrl = img?.attr("data-original")
            ?.ifEmpty { img.attr("data-src") }
            ?.ifEmpty { img.attr("src") }
            ?: ""
        if (rawUrl.isNotBlank() && !rawUrl.contains("/banner/")) return true
        val style = element.selectFirst("[style*=background]")?.attr("style").orEmpty()
        val match = Regex("""url\(['"]?([^)'"]+)['"]?\)""").find(style)
        val styleUrl = match?.groupValues?.getOrNull(1).orEmpty()
        return styleUrl.isNotBlank() && !styleUrl.contains("/banner/")
    }

    private fun isPopularCommonCardNewWork(element: Element): Boolean {
        val rawHref = element.attr("href")
        val href = element.absUrl("href").ifEmpty { rawHref }
        return rawHref.contains("新作登场") ||
            rawHref.contains("新作登場") ||
            href.contains("新作登场") ||
            href.contains("新作登場")
    }

    private fun logPopularModuleProbe(origin: String, elements: List<Element>) {
        if (elements.isEmpty()) {
            if (DEBUG_POPULAR_MODULE_LOG) dlog("popularModule origin=$origin count=0")
            return
        }
        val clean = elements.count { element ->
            val rawHref = element.attr("href")
            val href = element.absUrl("href").ifEmpty { rawHref }
            isCleanWorkPagePath(cleanMangaDetailPath(href))
        }
        val episode = elements.count { element ->
            val rawHref = element.attr("href")
            val href = element.absUrl("href").ifEmpty { rawHref }
            cleanMangaDetailPath(href).startsWith("/episodeList")
        }
        val emptyTitle = elements.count { mangaTitleFromElement(it).isBlank() }
        if (DEBUG_POPULAR_MODULE_LOG) {
            dlog("popularModule origin=$origin count=${elements.size} clean=$clean episode=$episode emptyTitle=$emptyTitle")
        }
    }

    private fun defaultPopularGenreValues(): Set<String> {
        return getThemeFilter()
            .map { it.value }
            .filter { it.isNotBlank() && it != "ALL" && it != "COMEDY" && it != "SUSPENSE" }
            .toSet()
    }

    private fun enabledPopularGenreValues(): Set<String> {
        return preferences.getStringSet(PREF_POPULAR_GENRE_ENABLED, defaultPopularGenreValues())
            ?.takeIf { it.isNotEmpty() }
            ?: emptySet()
    }

    private fun isPopularGenreEnabled(element: Element): Boolean {
        val genreCode = popularElementGenreCode(element)
        if (genreCode.isNotBlank()) {
            val enabled = enabledPopularGenreValues()
            val allowed = enabled.contains(genreCode)
            if (!allowed) {
                val titleNo = titleNoFromElementIdentity(element).orEmpty()
                if (DEBUG_POPULAR_GENRE_FILTER_LOG) {
                    dlog("popularGenreFiltered titleNo=$titleNo genre=$genreCode")
                }
            }
            return allowed
        }

        val genreName = element.parents()
            .firstOrNull { it.hasClass("genre_content_c") }
            ?.id()
            ?.trim()
            .orEmpty()
        if (genreName.isBlank()) return true
        val mappedCode = getThemeFilter()
            .firstOrNull { it.name == genreName || it.value == genreName }
            ?.value
            ?: genreName
        return enabledPopularGenreValues().contains(mappedCode)
    }

    private fun isPopularGenreCategoryElement(element: Element): Boolean {
        val scName = element.attr("data-sc-name").trim()
        if (scName == "M_discover-page_genre-title-list-item") return true
        return element.parents().any { parent ->
            parent.hasClass("genre_content_c") || parent.hasClass("homeGenreContent")
        }
    }

    private fun popularElementGenreCode(element: Element): String {
        val rawHref = element.attr("href")
        val href = element.absUrl("href").ifEmpty { rawHref }
        val cleanPath = cleanMangaDetailPath(href)
        val directGenre = genreCodeFromCleanWorkPath(cleanPath)
        if (directGenre.isNotBlank()) return directGenre
        val knownPath = knownCanonicalMangaIdentity(titleNoFromElementIdentity(element))
        return genreCodeFromCleanWorkPath(knownPath)
    }

    private fun genreCodeFromCleanWorkPath(path: String): String {
        if (!isCleanWorkPagePath(path)) return ""
        return path.trimStart('/').substringBefore('/').uppercase(Locale.ROOT)
    }

    private fun titleNoFromElementIdentity(element: Element): String? {
        val rawHref = element.attr("href")
        val href = element.absUrl("href").ifEmpty { rawHref }
        val cleanPath = cleanMangaDetailPath(href)
        return titleNoFromUrl(cleanPath)
            ?: titleNoFromUrl(href)
            ?: titleNoFromUrl(rawHref)
            ?: element.attr("data-title-no").takeIf { it.isNotBlank() }
            ?: scEventParameterValue(element, "recommended_titleNo")
    }

    private fun knownCanonicalMangaIdentity(titleNo: String?): String {
        if (titleNo.isNullOrBlank()) return ""
        synchronized(canonicalMangaUrlByTitleNo) {
            ensureCanonicalMangaIdentityStoreLoadedLocked()
            return canonicalMangaUrlByTitleNo[titleNo].orEmpty()
        }
    }

    private fun hasCleanOrKnownCanonicalIdentity(element: Element): Boolean {
        val rawHref = element.attr("href")
        val href = element.absUrl("href").ifEmpty { rawHref }
        val cleanPath = cleanMangaDetailPath(href)
        if (isCleanWorkPagePath(cleanPath)) return true
        return knownCanonicalMangaIdentity(titleNoFromElementIdentity(element)).isNotBlank()
    }

    private fun shouldIncludePopularBannerElement(element: Element): Boolean {
        return true
    }

    // ══════════════════════════════════════════════════════════════════════
    // 最新更新
    // ══════════════════════════════════════════════════════════════════════

    override fun latestUpdatesRequest(page: Int): Request {
        val weekday = preferences.getString(PREF_FILTER_WEEKDAY, "").orEmpty()
        val sort = preferences.getString(PREF_FILTER_SORT, "READ_COUNT").orEmpty().ifBlank { "READ_COUNT" }
        val todayCode = currentWeekdayCode()
        val startWeekday = normalizedWeekdayCode(weekday) ?: todayCode
        val requestPage = page.coerceAtLeast(1)
        val url = "$baseUrl/dailySchedule?weekday=$startWeekday&sortOrder=$sort" +
            "&mihonLatest=1&mihonLatestPage=$requestPage"
        dlog(
            "latestUpdatesRequest sessionWeekday=$startWeekday sort=$sort " +
                "combined=true page=$requestPage url=$url"
        )
        return GET(url, headersBuilder().build())
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        if (response.request.url.encodedPath == "/new") {
            val document = response.asJsoup()
            rememberDetailNewPageSnapshot(
                titleNos = newPageTitleNosFromDocument(document),
                source = "new-page-list",
            )
            val newItems = document.select(".new_works_items")
            val newPageCoverWaitStats = prepareOfficialNewWorkCoversForNewPageVisibleNoBlank(newItems)
            dlog(
                "latestUpdatesParse NEW coverWait mode=${getHomeCoverMode()} " +
                    "targets=${newPageCoverWaitStats.titleNos} alreadyReady=${newPageCoverWaitStats.alreadyReady} " +
                    "scheduled=${newPageCoverWaitStats.scheduled} success=${newPageCoverWaitStats.success} " +
                    "missing=${newPageCoverWaitStats.missing} waited=${newPageCoverWaitStats.waitedMs}ms " +
                    "noVirtual=true noBlank=true marketingRequests=0"
            )
            val cachedItems = newItems
                .mapNotNull { item ->
                    val cached = cachedMangaItemFromElement(item, "new")
                    if (cached.title.isBlank()) return@mapNotNull null
                    if (entriesNewProbeShouldLog()) {
                        val rawHref = item.attr("href")
                        val href = item.absUrl("href").ifEmpty { rawHref }
                        dlog(
                            "latestUpdatesParse NEW probe titleNo=${cached.titleNo} isNewBadge=${cached.hasNew} " +
                                "hasIcoNewCn=${item.selectFirst(".ico_new_cn") != null} " +
                                "hasClassIcoNew=${item.selectFirst("[class*=ico_new]") != null} " +
                                "hasClassIconNew=${item.selectFirst("[class*=icon_new]") != null} " +
                                "hasImgIconNew=${item.selectFirst("img[src*=icon_new], img[src*=ico_new]") != null} " +
                                "class=${item.className()} href=$href cleanPath=${cached.url}"
                        )
                    }
                    // v100：/new 的真实响应本身就是当前新作集合；标题已经是真实标题。
                    // .new_works_items 的 thumbnail 可能是营销封面。
                    // v100.7.10：列表禁用虚拟封面；输出前先等待可见/新作官方封面元信息。
                    // 需要官方封面的条目只接受已验证官方封面；不把未验证图片包装成兜底。
                    val titleNo = cached.titleNo?.trim().orEmpty()
                    val verifiedOfficialCover = verifiedDetailOfficialCoverForTitleNo(titleNo)
                    val trustedRuntimeOfficialCover = if (verifiedOfficialCover.isBlank()) {
                        trustedRuntimeOfficialCoverForTitleNo(titleNo)
                    } else {
                        ""
                    }
                    val virtualOfficialCover = if (
                        titleNo.isNotBlank() &&
                        verifiedOfficialCover.isBlank() &&
                        trustedRuntimeOfficialCover.isBlank() &&
                        allowVirtualOfficialCoverFallbackInList()
                    ) {
                        val detailUrl = officialNewWorkDetailUrlForElement(item, titleNo)
                        buildOfficialCoverVirtualUrl(titleNo, detailUrl)
                    } else {
                        ""
                    }
                    val rawOfficialThumbnail = verifiedOfficialCover.ifBlank {
                        trustedRuntimeOfficialCover.ifBlank { virtualOfficialCover }
                    }
                    val officialThumbnail = officialListThumbnailForUi(rawOfficialThumbnail)
                        .ifBlank { officialListThumbnailForUi(cached.thumbnailUrl) }
                    dlog(
                        "latestUpdatesParse NEW officialCover titleNo=$titleNo " +
                            "marketingThumbnailSuppressed=${cached.thumbnailUrl.isNotBlank()} " +
                            "officialCoverPresent=${verifiedOfficialCover.isNotBlank() || trustedRuntimeOfficialCover.isNotBlank()} " +
                            "officialCoverVirtual=${virtualOfficialCover.isNotBlank()} " +
                            "officialThumbnailPresent=${officialThumbnail.isNotBlank()} " +
                            "strictOfficialFirstNoVirtual=${isHomeCoverOfficialFirst()} " +
                            "coverMode=${getHomeCoverMode()} " +
                            "mainpathOfficialDetailRequests=${if (isHomeCoverOfficialFirst()) "strict-wait" else "0"} marketingRequests=0"
                    )
                    cached.copy(thumbnailUrl = officialThumbnail, hasNew = true)
                }
                .distinctBy { mangaIdentityDedupKey(it.titleNo, it.url) }
            putUpdatePageCache(updateCacheKey("NEW", ""), cachedItems)
            val entries = cachedItems.map(::mangaFromCachedItem)
            val newCount = cachedItems.count { it.hasNew }
            dlog(
                "latestUpdatesParse NEW url=${response.request.url} count=${entries.size} newCount=$newCount " +
                    "cacheWrite=true detailSnapshot=true source=new-page-official-cover " +
                    "mainpathOfficialDetailRequests=0 marketingRequests=0"
            )
            logCleanAuditSummary("new-page", entries)
            return MangasPage(entries, false)
        }

        return parseDailyScheduleHtml(response)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 搜索
    // ══════════════════════════════════════════════════════════════════════

    private val nextStartMap = mutableMapOf<String, Int>()

    private val canonicalMangaUrlByTitleNo = mutableMapOf<String, String>()
    private val updateWeekdaysByTitleNo = mutableMapOf<String, MutableSet<String>>()
    private var canonicalMangaUrlStoreLoaded = false
    private var canonicalMangaUrlStoreLoadScheduled = false
    private var canonicalMangaUrlStorePersistScheduled = false
    private val identityProbeUrlsByTitleNo = mutableMapOf<String, MutableSet<String>>()
    private var identityProbeSeenLogCount = 0
    private var identityProbeConflictLogCount = 0
    private var identityFallbackEpisodeLogCount = 0
    private var identityReuseCanonicalLogCount = 0
    private var cleanAuditDirtyLogCount = 0

    private data class DetailHtmlCache(
        val titleNo: String,
        val createdAt: Long,
        val body: String,
        val contentType: String,
    )

    private class DetailHtmlInflightState {
        @Volatile
        var completed: Boolean = false

        @Volatile
        var failed: Boolean = false

        val latch = CountDownLatch(1)
    }

    private class ListInflightState(
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        @Volatile
        var completed: Boolean = false

        @Volatile
        var failed: Boolean = false

        @Volatile
        var code: Int = 599

        @Volatile
        var message: String = ""

        @Volatile
        var headers: Headers = Headers.Builder().build()

        @Volatile
        var body: String? = null

        val latch = CountDownLatch(1)
    }

    private val detailHtmlCache = mutableMapOf<String, DetailHtmlCache>()
    private val detailHtmlInflight = mutableMapOf<String, DetailHtmlInflightState>()
    private val detailHtmlCacheTtlMs = 2500L
    private val detailHtmlInflightWaitMs = 12 * 1000L
    private val detailHtmlCacheMaxEntries = 24

    private val listInflight = mutableMapOf<String, ListInflightState>()
    private val listInflightJoinWindowMs = 2500L
    private val listInflightWaitMs = 8 * 1000L

    private data class CachedMangaItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String,
        val titleNo: String?,
        val hasNew: Boolean,
    )

    private data class OfficialMangaMeta(
        val title: String,
        val thumbnailUrl: String,
        val source: String,
        val priority: Int,
        val createdAt: Long,
    )

    private val officialMangaMetaByTitleNo = mutableMapOf<String, OfficialMangaMeta>()

    // 只接收详情页 HTML 社交元数据明确声明的 cdn-sns 正式封面。
    // v94 允许首页新作受控轻量扫描详情页 meta 获取正式封面；不使用首页营销卡图、不写持久化封面缓存。
    private val verifiedDetailOfficialCoverByTitleNo = mutableMapOf<String, String>()

    // v97：虚拟封面 URL 对外保持稳定，只带 titleNo。
    // detailUrl 仅存在进程内，供虚拟加载器解析官方 cdn-sns 封面时使用；不写持久化、不缓存图片 bytes。
    private val officialCoverDetailUrlByTitleNo = mutableMapOf<String, String>()
    private val detailEntryThumbnailByTitleNo = mutableMapOf<String, String>()
    private val detailRefreshLastRequestAtByTitleNo = mutableMapOf<String, Long>()
    private var detailRefreshSeq = 0

    private class OfficialNewWorkCoverFetchState(
        val titleNo: String,
        val detailUrl: String,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        @Volatile
        var completed: Boolean = false

        @Volatile
        var failed: Boolean = false

        @Volatile
        var coverUrl: String = ""

        @Volatile
        var failureReason: String = ""

        @Volatile
        var submitted: Boolean = false

        @Volatile
        var demandSubmitted: Boolean = false

        @Volatile
        var prefetchSubmitted: Boolean = false

        @Volatile
        var running: Boolean = false

        @Volatile
        var fetchKind: String = "prefetch-inflight"

        val latch = CountDownLatch(1)
    }

    private enum class OfficialCoverFetchPriority {
        DEMAND,
        VISIBLE,
        PREFETCH,
    }

    private data class OfficialCoverWaitStats(
        val titleNos: Int = 0,
        val alreadyReady: Int = 0,
        val trustedReady: Int = 0,
        val scheduled: Int = 0,
        val success: Int = 0,
        val missing: Int = 0,
        val waitedMs: Long = 0L,
    )

    private data class OfficialNewWorkCoverTarget(
        val titleNo: String,
        val detailUrl: String,
        val origin: String,
        val rawIndex: Int,
    )

    private data class FastOfficialCoverScanResult(
        val coverUrl: String,
        val bytesScanned: Int,
        val earlyClose: Boolean,
        val source: String,
    )

    private val officialNewWorkCoverFetchStates = mutableMapOf<String, OfficialNewWorkCoverFetchState>()
    private val officialNewWorkCoverDemandExecutor = Executors.newFixedThreadPool(OFFICIAL_NEW_WORK_COVER_DEMAND_PARALLELISM)
    private val officialNewWorkCoverPrefetchExecutor = Executors.newFixedThreadPool(OFFICIAL_NEW_WORK_COVER_PREFETCH_PARALLELISM)
    private val legacyNewWorkPersistentCacheClearLock = Any()

    @Volatile
    private var legacyNewWorkPersistentCacheCleared = false

    private data class GenrePageCache(
        val genre: String,
        val createdAt: Long,
        val items: List<CachedMangaItem>,
    )

    private data class UpdatePageCache(
        val key: String,
        val createdAt: Long,
        val items: List<CachedMangaItem>,
    )

    private val genrePageCache = mutableMapOf<String, GenrePageCache>()
    private val updatePageCache = LinkedHashMap<String, UpdatePageCache>()

    private data class FilterSnapshot(
        val weekdayState: Int,
        val sortState: Int,
        val themeState: Int,
        val myMangaState: Int,
        val activeGroup: String,
    )

    private var cachedLastFilterSnapshot: FilterSnapshot? = null

    private fun loadOrGetLastSnapshot(): FilterSnapshot? {
        if (preferences.getInt(PREF_FILTER_SCHEMA_VERSION, 0) != FILTER_SCHEMA_VERSION) {
            cachedLastFilterSnapshot = null
            preferences.edit()
                .putInt(PREF_FILTER_SCHEMA_VERSION, FILTER_SCHEMA_VERSION)
                .remove(PREF_FILTER_WEEKDAY)
                .remove(PREF_FILTER_SORT)
                .remove(PREF_FILTER_THEME)
                .remove(PREF_FILTER_ACTIVE_GROUP)
                .remove(PREF_FILTER_WEEKDAY_STATE)
                .remove(PREF_FILTER_SORT_STATE)
                .remove(PREF_FILTER_THEME_STATE)
                .remove(PREF_FILTER_MY_MANGA_STATE)
                .apply()
            dlog("filterSnapshotReset schemaVersion=$FILTER_SCHEMA_VERSION")
            return null
        }

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

    private fun resetFilterSessionState(reason: String) {
        cachedLastFilterSnapshot = null
        preferences.edit()
            .remove(PREF_FILTER_WEEKDAY)
            .remove(PREF_FILTER_SORT)
            .remove(PREF_FILTER_THEME)
            .remove(PREF_FILTER_ACTIVE_GROUP)
            .remove(PREF_FILTER_WEEKDAY_STATE)
            .remove(PREF_FILTER_SORT_STATE)
            .remove(PREF_FILTER_THEME_STATE)
            .remove(PREF_FILTER_MY_MANGA_STATE)
            .putInt(PREF_FILTER_SCHEMA_VERSION, FILTER_SCHEMA_VERSION)
            .apply()
        dlog("filterSessionReset reason=$reason")
    }

    private fun dlog(message: String) = Log.d(TAG, message)
    private fun wlog(message: String, throwable: Throwable? = null) = Log.w(TAG, message, throwable)

    private fun refreshProbeType(request: Request): String {
        val url = request.url
        val path = url.encodedPath
        return when {
            path == "/" && url.queryParameter("pageName") == "home" -> "popular"
            path == "/dailySchedule" || path == "/new" -> "latest"
            path == LOCAL_GENRE_CACHE_PATH -> "filter-local-genre"
            path == LOCAL_UPDATE_CACHE_PATH -> "filter-local-update"
            path == "/search" || path == "/searchResult" -> "search"
            path == "/recent/more" || path == "/episode/unlock/titleList" -> "my-manga"
            path.contains("/list") -> "detail-or-list"
            else -> "other"
        }
    }

    private fun logRefreshModeProbe(
        request: Request,
        listKey: String?,
        inflightEnabled: Boolean,
        action: String,
    ) {
        val url = request.url
        val path = url.encodedPath
        val type = refreshProbeType(request)
        if (type == "other" || type == "detail-or-list") return
        val page = url.queryParameter("mihonPage")
            ?: url.queryParameter("mihonLatestPage")
            ?: url.queryParameter("page")
            ?: "1"
        val source = when (path) {
            LOCAL_GENRE_CACHE_PATH, LOCAL_UPDATE_CACHE_PATH -> "localSynthetic"
            else -> "networkCandidate"
        }
        dlog(
            "refreshModeProbe type=$type method=${request.method} page=$page path=$path " +
                "source=$source inflightEnabled=$inflightEnabled inflightEligible=${listKey != null} " +
                "inflightAction=$action key=${listKey.orEmpty()} url=$url"
        )
    }

    private fun cleanAuditDirtyStoredUrl(storedUrl: String): Boolean {
        return storedUrl.contains("source=") ||
            storedUrl.contains("pageModel=") ||
            storedUrl.contains("mihonLatest=") ||
            storedUrl.contains("mihonLatestPage=") ||
            storedUrl.contains("mihonPage=") ||
            storedUrl.contains("__dongman_cache__")
    }

    private fun cleanAuditPlaceholderTitle(title: String): Boolean {
        return Regex("""^作品\d+""").containsMatchIn(title.trim())
    }

    private fun logCleanAuditSummary(origin: String, entries: List<SManga>) {
        if (entries.isEmpty()) {
            dlog("cleanAuditSummary origin=$origin entries=0")
            return
        }
        var storedEpisode = 0
        var dirtyStoredUrl = 0
        var ossParam = 0
        var listDetailCoverKey = 0
        var emptyTitle = 0
        var placeholderTitle = 0
        entries.forEach { manga ->
            val storedUrl = normalizeMangaPath(manga.url)
            val thumbnail = manga.thumbnail_url.orEmpty()
            val isStoredEpisode = storedUrl.startsWith("/episodeList")
            val isDirtyStoredUrl = cleanAuditDirtyStoredUrl(storedUrl)
            val hasOssParam = thumbnail.contains("x-oss-process")
            val hasListDetailCoverKey = thumbnail.contains("dongman_detail_cover=1")
            val listDetailCoverKeyAllowed = false
            val hasEmptyTitle = manga.title.isBlank()
            val hasPlaceholderTitle = cleanAuditPlaceholderTitle(manga.title)
            if (isStoredEpisode) storedEpisode += 1
            if (isDirtyStoredUrl) dirtyStoredUrl += 1
            if (hasOssParam) ossParam += 1
            if (hasListDetailCoverKey) listDetailCoverKey += 1
            if (hasEmptyTitle) emptyTitle += 1
            if (hasPlaceholderTitle) placeholderTitle += 1
            val dirty = isStoredEpisode || isDirtyStoredUrl || hasOssParam ||
                (hasListDetailCoverKey && !listDetailCoverKeyAllowed) || hasEmptyTitle || hasPlaceholderTitle
            if (dirty && cleanAuditDirtyLogCount < CLEAN_AUDIT_DIRTY_LOG_LIMIT) {
                cleanAuditDirtyLogCount += 1
                dlog(
                    "cleanAuditDirty origin=$origin title=${manga.title} storedUrl=$storedUrl " +
                        "storedEpisode=$isStoredEpisode dirtyStoredUrl=$isDirtyStoredUrl " +
                        "ossParam=$hasOssParam listDetailCoverKey=$hasListDetailCoverKey " +
                        "emptyTitle=$hasEmptyTitle placeholderTitle=$hasPlaceholderTitle thumbnail=$thumbnail"
                )
            }
        }
        dlog(
            "cleanAuditSummary origin=$origin entries=${entries.size} storedEpisode=$storedEpisode " +
                "dirtyStoredUrl=$dirtyStoredUrl ossParam=$ossParam listDetailCoverKey=$listDetailCoverKey " +
                "listDetailCoverKeyAllowed=false " +
                "emptyTitle=$emptyTitle placeholderTitle=$placeholderTitle"
        )
    }

    private fun isMixedMode() = preferences.getBoolean(PREF_SEARCH_MODE, false)

    private fun getListInflightCoalesceEnable(): Boolean = preferences.getBoolean(PREF_LIST_INFLIGHT_COALESCE, false)

    private fun normalizeHomeCoverMode(value: String?): String {
        return when (value) {
            HOME_COVER_MODE_OFFICIAL_FIRST -> HOME_COVER_MODE_OFFICIAL_FIRST
            else -> HOME_COVER_MODE_FAST
        }
    }

    private fun getHomeCoverMode(): String {
        val value = preferences.getString(PREF_HOME_COVER_MODE, HOME_COVER_MODE_FAST) ?: HOME_COVER_MODE_FAST
        return normalizeHomeCoverMode(value)
    }

    private fun isHomeCoverOfficialFirst(): Boolean = getHomeCoverMode() == HOME_COVER_MODE_OFFICIAL_FIRST

    private fun normalizeDetailCoverRefreshMode(value: String?): String {
        return when (value) {
            DETAIL_COVER_REFRESH_PRESERVE -> DETAIL_COVER_REFRESH_PRESERVE
            else -> DETAIL_COVER_REFRESH_SYNC
        }
    }

    private fun getDetailCoverRefreshMode(): String {
        val value = preferences.getString(PREF_DETAIL_COVER_REFRESH_MODE, DETAIL_COVER_REFRESH_SYNC) ?: DETAIL_COVER_REFRESH_SYNC
        return normalizeDetailCoverRefreshMode(value)
    }

    private fun isDetailCoverRefreshPreserve(): Boolean = getDetailCoverRefreshMode() == DETAIL_COVER_REFRESH_PRESERVE

    // v100.7.7：所有列表模式都禁用虚拟官方封面回退。
    // 列表 thumbnail_url 只能是站点真实图片 URL；详情最终封面由详情 HTML 覆盖。
    // 速度优先也先等可见官方封面，避免 __mihon_official_cover 和 blank 两个旧坑复发。
    private fun allowVirtualOfficialCoverFallbackInList(): Boolean = false

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
        val themeActive = themeValue.isNotEmpty()

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

            val updateChangedToExplicit = updateChanged && weekdayValue.isNotEmpty()
            val updateChangedToDefault = updateChanged && weekdayValue.isEmpty()
            val visibleThemeShouldStay = previous != null &&
                prevActiveGroup == "theme" &&
                themeActive &&
                themeState == previous.themeState &&
                myMangaValue.isEmpty() &&
                !themeChanged &&
                !myMangaChanged &&
                updateChangedToDefault

            val activeGroup = when {
                // 分页请求必须沿用上一页入口，避免残留 state 在 page>1 时抢路由。
                previous != null && page > 1 -> prevActiveGroup
                // 从未保存过筛选快照时，按当前有效筛选决定入口。
                // themeState=0 是“不使用题材筛选”，不再代表“全部题材”。
                previous == null -> when {
                    myMangaValue.isNotEmpty() -> "migrate"
                    themeActive -> "theme"
                    else -> "update"
                }
                // 我的漫画发生变化时优先处理；取消我的漫画后，如果题材仍有效则回到题材，否则回到更新。
                myMangaChanged -> when {
                    myMangaValue.isNotEmpty() -> "migrate"
                    themeActive -> "theme"
                    else -> "update"
                }
                // 题材发生变化时：state=0 只是“不使用题材筛选”，不进入题材分支；state>=1 才进入题材分支。
                themeChanged -> if (themeActive) "theme" else "update"
                // 明确更新项（周几/新作/完结）发生变化时，更新入口必须抢回路由。
                updateChangedToExplicit -> "update"
                // 只回到“今天/默认空值”的更新变化，通常是 Mihon 残留 state 回跳，不能抢走当前题材。
                visibleThemeShouldStay -> "theme"
                // 没有任何 changed 时，沿用上次成功入口。
                // 竞争控件的 state 会在下面按 activeGroup 归零保存，避免下次同项点击无法产生变化。
                prevActiveGroup == "theme" && !themeActive -> "update"
                updateChanged -> "update"
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
                    val genreCacheKey = if (themeValue == "ALL") "ALL" else themeValue
                    val cachedGenrePage = getValidGenrePageCache(genreCacheKey)
                    val useCachedGenrePage = page > 1 && cachedGenrePage != null
                    branch = when {
                        themeValue == "ALL" && useCachedGenrePage -> "theme-all-cache"
                        themeValue == "ALL" -> "theme-all"
                        useCachedGenrePage -> "theme-cache"
                        else -> "theme"
                    }
                    // 题材不读取“排序”控件，不拼 sortOrder。排序只属于更新。
                    // “全部”不再回落到更新/新作；使用题材页自身携带的全集合数据，并走题材缓存。
                    val url = when {
                        useCachedGenrePage -> "$baseUrl$LOCAL_GENRE_CACHE_PATH?genre=$genreCacheKey&mihonPage=$page"
                        themeValue == "ALL" -> "$baseUrl/${themeAllCarrierGenre()}/?themeAll=1&mihonPage=$page"
                        page > 1 -> "$baseUrl/$themeValue/?mihonPage=$page"
                        else -> "$baseUrl/$themeValue/"
                    }
                    request = GET(url, headersBuilder().build())
                }
                else -> {
                    val updateKey = updateCacheKey(weekdayValue, sortValue)
                    val useCachedUpdatePage = page > 1 && getValidUpdatePageCache(updateKey) != null
                    branch = when {
                        useCachedUpdatePage && weekdayValue == "NEW" -> "new-cache"
                        useCachedUpdatePage && weekdayValue == "COMPLETE" -> "update-complete-cache"
                        useCachedUpdatePage -> "daily-cache"
                        weekdayValue == "NEW" -> "new"
                        weekdayValue == "COMPLETE" -> "update-complete"
                        weekdayValue.isNotEmpty() -> "update-explicit"
                        else -> "update-default"
                    }
                    val url = if (useCachedUpdatePage) {
                        "$baseUrl$LOCAL_UPDATE_CACHE_PATH?weekday=${updateCacheWeekday(weekdayValue)}&sort=$sortValue&mihonPage=$page"
                    } else {
                        updateUrl
                    }
                    request = GET(url, headersBuilder().build())
                }
            }

            // 保存快照时清掉其它入口的残留 state。
            // 这不是新增入口，而是让“题材/更新/我的漫画”真正互斥：
            // 当前在题材时，下次点击同一个更新项也能产生 updateChanged；当前在更新时，下次点击同一个题材也能产生 themeChanged。
            val savedWeekdayState = if (activeGroup == "update") weekdayState else 0
            val savedSortState = if (activeGroup == "update") sortState else 0
            val savedThemeState = if (activeGroup == "theme") themeState else 0
            val savedMyMangaState = if (activeGroup == "migrate") myMangaState else 0
            val savedWeekdayValue = if (activeGroup == "update") weekdayValue else ""
            val savedSortValue = if (activeGroup == "update") sortValue else "READ_COUNT"
            val savedThemeValue = if (activeGroup == "theme") themeValue else ""

            when (activeGroup) {
                "theme" -> {
                    weekdayFilter?.state = 0
                    sortFilter?.state = 0
                    myMangaFilter?.state = 0
                }
                "update" -> {
                    themeFilter?.state = 0
                    myMangaFilter?.state = 0
                }
                "migrate" -> {
                    weekdayFilter?.state = 0
                    sortFilter?.state = 0
                    themeFilter?.state = 0
                }
            }

            cachedLastFilterSnapshot = FilterSnapshot(
                weekdayState = savedWeekdayState,
                sortState = savedSortState,
                themeState = savedThemeState,
                myMangaState = savedMyMangaState,
                activeGroup = activeGroup,
            )

            preferences.edit()
                .putString(PREF_FILTER_WEEKDAY, savedWeekdayValue)
                .putString(PREF_FILTER_SORT, savedSortValue)
                .putString(PREF_FILTER_THEME, savedThemeValue)
                .putString(PREF_FILTER_ACTIVE_GROUP, activeGroup)
                .putInt(PREF_FILTER_SCHEMA_VERSION, FILTER_SCHEMA_VERSION)
                .putInt(PREF_FILTER_WEEKDAY_STATE, savedWeekdayState)
                .putInt(PREF_FILTER_SORT_STATE, savedSortState)
                .putInt(PREF_FILTER_THEME_STATE, savedThemeState)
                .putInt(PREF_FILTER_MY_MANGA_STATE, savedMyMangaState)
                .apply()

            dlog(
                "searchMangaRequest filter branch=$branch activeGroup=$activeGroup page=$page " +
                    "prevActiveGroup=$prevActiveGroup changed(update=$updateChanged, theme=$themeChanged, myManga=$myMangaChanged) " +
                    "visibleThemeShouldStay=$visibleThemeShouldStay themeActive=$themeActive " +
                    "savedStates(w=$savedWeekdayState,s=$savedSortState,t=$savedThemeState,m=$savedMyMangaState) " +
                    "weekdayState=$weekdayState/${previous?.weekdayState ?: -1} weekday=$weekdayValue sort=$sortValue " +
                    "themeState=$themeState/${previous?.themeState ?: -1} theme=${themeTag?.name.orEmpty()}/$themeValue " +
                    "myMangaState=$myMangaState/${previous?.myMangaState ?: -1} myManga=$myMangaName/$myMangaValue " +
                    "url=${request.url}"
            )
            dlog(
                "refreshModeProbe type=filter branch=$branch activeGroup=$activeGroup page=$page " +
                    "source=${if (request.url.encodedPath == LOCAL_GENRE_CACHE_PATH || request.url.encodedPath == LOCAL_UPDATE_CACHE_PATH) "localSynthetic" else "networkCandidate"} " +
                    "inflightEnabled=${getListInflightCoalesceEnable()} url=${request.url}"
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
            val request = POST("$baseUrl/search", headers, body)
            dlog("refreshModeProbe type=search mode=mixed-initial page=$page source=networkCandidate inflightEnabled=${getListInflightCoalesceEnable()} url=${request.url}")
            return request
        }
        val start = if (isMixedMode()) nextStartMap[query] ?: (1 + (page - 1) * 20) else 1 + (page - 1) * 20
        val body = FormBody.Builder().add("keyword", query).add("searchType", "WEBTOON").add("start", start.toString()).build()
        val headers = headersBuilder()
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/search")
            .set("Content-Type", "application/x-www-form-urlencoded")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        val request = POST("$baseUrl/searchResult", headers, body)
        dlog("refreshModeProbe type=search mode=json page=$page start=$start source=networkCandidate inflightEnabled=${getListInflightCoalesceEnable()} url=${request.url}")
        return request
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val url = response.request.url.toString()
        dlog("searchMangaParse url=$url contentType=${response.header("Content-Type").orEmpty()}")
        return when {
            response.request.url.encodedPath == LOCAL_GENRE_CACHE_PATH -> parseCachedGenrePage(response)
            response.request.url.encodedPath == LOCAL_UPDATE_CACHE_PATH -> parseCachedUpdatePage(response)
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
                    val rawHref = a.attr("href")
                    val href = a.absUrl("href").ifEmpty { rawHref }
                    val titleNo = titleNoFromUrl(href) ?: titleNoFromUrl(rawHref) ?: a.attr("data-title-no")
                    url = canonicalMangaIdentityPath(rawUrl = href, titleNoHint = titleNo)
                    title = a.selectFirst(".tit, .items_content_right .tit")?.text()
                        ?: a.selectFirst("img")?.attr("alt")
                        ?: ""
                    thumbnail_url = buildThumbnailUrl(a.selectFirst("img")?.attr("src").orEmpty())
                    logIdentityProbe("recent", titleNo, title, rawHref, href, url)
                }
            }
            .filter { it.title.isNotEmpty() && it.url.isNotEmpty() }
            .distinctBy { mangaIdentityDedupKey(titleNoFromUrl(it.url), it.url) }
        dlog(
            "parseRecentMangaPage url=${response.request.url} " +
                "candidates=${candidates.size} realItems=${realItems.size} entries=${entries.size}"
        )
        logCleanAuditSummary("recent", entries)
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
        val dedupedEntries = entries.distinctBy { mangaIdentityDedupKey(titleNoFromUrl(it.url), it.url) }
        logCleanAuditSummary("purchased", dedupedEntries)
        return MangasPage(dedupedEntries, false)
    }

    private fun parseDailyScheduleHtml(response: Response): MangasPage {
        val document = response.asJsoup()
        val requestedWeekday = response.request.url.queryParameter("weekday").orEmpty()
        val weekday = updateCacheWeekday(requestedWeekday.ifBlank { currentWeekdayCode() })
        val sort = response.request.url.queryParameter("sortOrder") ?: "READ_COUNT"
        val selector = "a.updatePage_lst_item[data-week=$weekday]"
        val allWeekElements = document.select("a.updatePage_lst_item[data-week]")
        rememberCanonicalMangaIdentitiesFromElements(allWeekElements)
        val groupedElements = allWeekElements.groupBy { it.attr("data-week").trim() }.filterKeys { it.isNotEmpty() }

        groupedElements.forEach { (week, elements) ->
            rememberUpdateWeekdaysFromElements(week, elements)
            if (week == "COMPLETE" && weekday != "COMPLETE") {
                dlog("parseDailyScheduleHtml skipCompleteCache sort=$sort requestedWeekday=$weekday raw=${elements.size}")
                return@forEach
            }
            val items = elements
                .map { cachedMangaItemFromElement(it, "dailySchedule-cache-$week") }
                .distinctBy { mangaIdentityDedupKey(it.titleNo, it.url) }
                .filter { it.title.isNotEmpty() }
            putUpdatePageCache(updateCacheKey(week, sort), items)
        }

        val requestedItems = groupedElements[weekday]
            ?.map { cachedMangaItemFromElement(it, "dailySchedule-requested-$weekday") }
            ?.distinctBy { mangaIdentityDedupKey(it.titleNo, it.url) }
            ?.filter { it.title.isNotEmpty() }
            ?: document.select(selector)
                .map { cachedMangaItemFromElement(it, "dailySchedule-selector-$weekday") }
                .distinctBy { mangaIdentityDedupKey(it.titleNo, it.url) }
                .filter { it.title.isNotEmpty() }
                .also { putUpdatePageCache(updateCacheKey(weekday, sort), it) }

        val latestCombined = response.request.url.queryParameter("mihonLatest") == "1"
        val pageItems = if (latestCombined && weekday != "COMPLETE") {
            orderedUpdateWeekdaysFrom(weekday)
                .flatMap { week ->
                    groupedElements[week]
                        ?.map { cachedMangaItemFromElement(it, "dailySchedule-latest-$week") }
                        .orEmpty()
                }
                .distinctBy { mangaIdentityDedupKey(it.titleNo, it.url) }
                .filter { it.title.isNotEmpty() }
        } else {
            requestedItems
        }

        val latestPage = response.request.url.queryParameter("mihonLatestPage")
            ?.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: 1
        val startIndex = if (latestCombined) 0 else (latestPage - 1) * UPDATE_PAGE_SIZE
        val entries = if (latestCombined) {
            pageItems.map(::mangaFromCachedItem)
        } else {
            pageItems.drop(startIndex).take(UPDATE_PAGE_SIZE).map(::mangaFromCachedItem)
        }
        val hasNextPage = if (latestCombined) false else pageItems.size > startIndex + UPDATE_PAGE_SIZE
        val weekCounts = groupedElements.entries.joinToString(",") { "${it.key}=${it.value.size}" }
        val newCount = pageItems.count { it.hasNew }
        dlog(
            "parseDailyScheduleHtml url=${response.request.url} weekday=$weekday sort=$sort " +
                "selector=$selector grouped=${groupedElements.size} weekCounts=$weekCounts " +
                "combinedLatest=$latestCombined page=$latestPage start=$startIndex raw=${pageItems.size} entries=${entries.size} " +
                "newCount=$newCount hasNextPage=$hasNextPage cacheWrite=true onePageLatest=$latestCombined"
        )
        logCleanAuditSummary("dailySchedule", entries)
        return MangasPage(entries, hasNextPage)
    }

    private fun mangaFromJsonTitle(item: org.json.JSONObject): SManga {
        val titleNo = item.optInt("titleNo", 0)
        val titleNoText = if (titleNo > 0) titleNo.toString() else item.optString("titleNo", "")
        val newTitle = item.optBoolean("newTitle", false)
        return SManga.create().apply {
            val genreSeo = firstNonBlankJsonString(
                item,
                "representGenreSeoCode",
                "genreSeoCode",
                "genreSeo",
                "mainGenreSeoCode",
            )
            val groupName = firstNonBlankJsonString(
                item,
                "groupName",
                "seoName",
                "urlName",
                "titleSeoName",
            )
            val rawUrl = firstNonBlankJsonString(
                item,
                "url",
                "linkUrl",
                "titleUrl",
                "mobileUrl",
            )
            url = canonicalMangaIdentityPath(rawUrl = rawUrl, titleNoHint = titleNoText, genreSeoHint = genreSeo, groupNameHint = groupName)
            dlog(
                "mangaFromJsonTitle titleNo=$titleNoText title=${item.optString("title", "")} " +
                    "representGenre=${item.optString("representGenre", "")} genreSeo=$genreSeo " +
                    "groupName=$groupName newTitle=$newTitle url=$url"
            )
            logIdentityProbe("json-title", titleNoText, item.optString("title", ""), "", "", url)
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
        val prefTheme = preferences.getString(PREF_FILTER_THEME, "").orEmpty()
        val isThemeAll = prefTheme == "ALL" || response.request.url.queryParameter("themeAll") == "1"
        val requestedGenre = if (isThemeAll) {
            "ALL"
        } else {
            prefTheme.ifEmpty { genreCodeFromUrl(requestUrl).orEmpty() }
        }
        val filterGenre = if (requestedGenre == "ALL") "" else requestedGenre
        val genreItems = document.select("a.genrePageContentItem")
        val genreIndex = if (filterGenre.isNotEmpty()) {
            document.select("#genreList li").firstOrNull { li ->
                li.attr("data-genre") == filterGenre || li.attr("data-genre-seo") == filterGenre
            }?.selectFirst("a")?.attr("data-index")?.toIntOrNull()
        } else {
            null
        }

        if (genreItems.isNotEmpty() && isGenrePageUrl(requestUrl)) {
            putGenrePageCachesFromDocument(document, includeAll = isThemeAll)
        }
        rememberCanonicalMangaIdentitiesFromElements(
            document.select("a[href*=list?title_no], a[href*=episodeList?titleNo]")
        )

        val elements = if (genreItems.isNotEmpty()) {
            val sectionItems = genreIndex
                ?.let { document.select("div._genreFlick div.flick-ct").getOrNull(it) }
                ?.select("a.genrePageContentItem")
                .orEmpty()

            when {
                sectionItems.isNotEmpty() -> sectionItems
                filterGenre.isEmpty() -> genreItems
                else -> genreItems.filter { item ->
                    normalizeAbsoluteUrl(item.attr("href")).contains("/$filterGenre/")
                }
            }
        } else {
            val fallback = document.select(
                "li[id^=title_li_] > a, ul.weekly_lst li a, ul.lst_type2 li a, " +
                    "a[href*=list?title_no], a[href*=episodeList?titleNo], .daily_card li a"
            )
            if (filterGenre.isEmpty()) {
                fallback
            } else {
                fallback.filter { item ->
                    normalizeAbsoluteUrl(item.attr("href")).contains("/$filterGenre/")
                }
            }
        }
        val page = response.request.url.queryParameter("mihonPage")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val dedupedElements = distinctMangaElementsByIdentity(elements)
        val shouldClientPaginate = requestedGenre.isNotEmpty() && isGenrePageUrl(requestUrl)
        val cacheKey = if (requestedGenre == "ALL") "ALL" else filterGenre

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
                .map { cachedMangaItemFromElement(it, "mangaList-cache-$cacheKey") }
                .filter { it.title.isNotEmpty() && it.url.isNotEmpty() }
                .distinctBy { mangaIdentityDedupKey(it.titleNo, it.url) }
            putGenrePageCache(cacheKey, cachedItems)
            totalSize = cachedItems.size
            val pageItems = cachedItems.drop((page - 1) * GENRE_PAGE_SIZE).take(GENRE_PAGE_SIZE)
            entries = pageItems.map(::mangaFromCachedItem)
            hasNextPage = cachedItems.size > page * GENRE_PAGE_SIZE
        } else {
            entries = dedupedElements
                .map { mangaFromElement(it, "mangaList") }
                .distinctBy { mangaIdentityDedupKey(titleNoFromUrl(it.url), it.url) }
                .filter { it.title.isNotEmpty() }
            totalSize = dedupedElements.size
            hasNextPage = false
        }

        dlog(
            "parseMangaListHtml url=${response.request.url} requestedGenre=$requestedGenre " +
                "genreIndex=$genreIndex rawGenreItems=${genreItems.size} " +
                "selectedElements=${elements.size} deduped=${dedupedElements.size} " +
                "cacheWrite=$shouldClientPaginate cacheKey=$cacheKey total=$totalSize " +
                "page=$page pageSize=$GENRE_PAGE_SIZE entries=${entries.size} hasNextPage=$hasNextPage"
        )
        logCleanAuditSummary("manga-list", entries)
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
        logCleanAuditSummary("cache-genre", entries)
        return MangasPage(entries, hasNextPage)
    }

    private fun parseCachedUpdatePage(response: Response): MangasPage {
        val weekday = response.request.url.queryParameter("weekday").orEmpty()
        val sort = response.request.url.queryParameter("sort").orEmpty()
        val page = response.request.url.queryParameter("mihonPage")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val key = updateCacheKey(weekday, sort)
        val cache = getValidUpdatePageCache(key)
        if (cache == null) {
            dlog("parseCachedUpdatePage key=$key weekday=$weekday sort=$sort page=$page cacheHit=false")
            return MangasPage(emptyList(), false)
        }
        val fromIndex = (page - 1) * UPDATE_PAGE_SIZE
        if (fromIndex >= cache.items.size) {
            dlog("parseCachedUpdatePage key=$key page=$page cacheHit=true total=${cache.items.size} entries=0 hasNextPage=false")
            return MangasPage(emptyList(), false)
        }
        val toIndex = minOf(fromIndex + UPDATE_PAGE_SIZE, cache.items.size)
        val entries = cache.items.subList(fromIndex, toIndex).map(::mangaFromCachedItem)
        val hasNextPage = toIndex < cache.items.size
        dlog(
            "parseCachedUpdatePage key=$key weekday=$weekday sort=$sort page=$page cacheHit=true " +
                "total=${cache.items.size} entries=${entries.size} hasNextPage=$hasNextPage"
        )
        logCleanAuditSummary("cache-update", entries)
        return MangasPage(entries, hasNextPage)
    }

    private fun parseSearchHtml(response: Response): MangasPage {
        val document = response.asJsoup()
        rememberCanonicalMangaIdentitiesFromElements(document.select("ul._searchResultList a[href*=list?title_no]"))
        val allItems = document.select("ul._searchResultList > li")
        val totalEntries = allItems.size
        val entries = allItems
            .mapNotNull { li -> li.selectFirst("a.cleFix")?.let { searchMangaFromElement(it, "search-html") } }
            .filter { it.title.isNotEmpty() }
        val total = document.select("._totalCount").attr("data-total").toIntOrNull() ?: 0
        val hasNextPage = total > totalEntries
        if (hasNextPage) {
            val keyword = extractKeywordFromBody(response.request.body)
            if (keyword.isNotEmpty()) nextStartMap[keyword] = totalEntries + 1
        }
        logCleanAuditSummary("search-html", entries)
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
                    val genreSeo = firstNonBlankJsonString(
                        item,
                        "representGenreSeoCode",
                        "genreSeoCode",
                        "genreSeo",
                        "mainGenreSeoCode",
                    )
                    val groupName = firstNonBlankJsonString(
                        item,
                        "groupName",
                        "seoName",
                        "urlName",
                        "titleSeoName",
                    )
                    val rawUrl = firstNonBlankJsonString(
                        item,
                        "url",
                        "linkUrl",
                        "titleUrl",
                        "mobileUrl",
                    )
                    url = canonicalMangaIdentityPath(rawUrl = rawUrl, titleNoHint = titleNo, genreSeoHint = genreSeo, groupNameHint = groupName)
                    title = item.optString("title", "")
                    thumbnail_url = buildThumbnailUrl(
                        item.optString("thumbnailMobile", "")
                            .ifEmpty { item.optString("thumbnail", "") }
                            .ifEmpty { item.optString("representGenreBackgroundImageUrl", "") },
                        cdnBase
                    )
                    logIdentityProbe("search-json", titleNo, title, "", "", url)
                }
                if (manga.title.isNotEmpty()) entries.add(manga)
            }
        }
        val hasNextPage = rawCount > 0 && (start - 1 + rawCount) < total
        if (hasNextPage && isMixedMode()) {
            val keyword = extractKeywordFromBody(response.request.body)
            if (keyword.isNotEmpty()) nextStartMap[keyword] = start + rawCount
        }
        logCleanAuditSummary("search-json", entries)
        return MangasPage(entries, hasNextPage)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 漫画详情
    // ══════════════════════════════════════════════════════════════════════

    override fun mangaDetailsRequest(manga: SManga): Request {
        val reqHeaders = headersBuilder().build()
        val cleanPath = cleanMangaDetailPath(manga.url)
        val requestPath = canonicalDetailRequestPath(manga.url)
        val finalUrl = baseUrl + requestPath
        val titleNo = titleNoFromUrl(requestPath) ?: titleNoFromUrl(manga.url)
        val thumbnailBefore = manga.thumbnail_url.orEmpty()
        val now = System.currentTimeMillis()
        val (seq, deltaFromLast) = synchronized(detailRefreshLastRequestAtByTitleNo) {
            detailRefreshSeq += 1
            val currentSeq = detailRefreshSeq
            val key = titleNo.orEmpty()
            val last = if (key.isNotBlank()) detailRefreshLastRequestAtByTitleNo[key] else null
            val delta = last?.let { now - it } ?: -1L
            if (key.isNotBlank()) {
                detailRefreshLastRequestAtByTitleNo[key] = now
                detailEntryThumbnailByTitleNo[key] = thumbnailBefore
            }
            currentSeq to delta
        }
        dlog(
            "detailRefreshProbe action=request requestType=details seq=$seq titleNo=${titleNo.orEmpty()} " +
                "deltaFromLast=${deltaFromLast}ms coverMode=${getHomeCoverMode()} " +
                "detailCoverRefreshMode=${getDetailCoverRefreshMode()} " +
                "thumbnailBefore=$thumbnailBefore storedUrl=${normalizeMangaPath(manga.url)} " +
                "requestPath=$requestPath finalUrl=$finalUrl"
        )
        dlog(
            "mangaDetailsRequest title=${manga.title} manga.url=${manga.url} " +
                "cleanPath=$cleanPath requestPath=$requestPath finalUrl=$finalUrl"
        )
        dlog(
            "detailHealthProbe requestType=details title=${manga.title} storedUrl=${normalizeMangaPath(manga.url)} " +
                "storedEpisode=${normalizeMangaPath(manga.url).startsWith("/episodeList")} requestPath=$requestPath finalUrl=$finalUrl"
        )
        return GET(finalUrl, reqHeaders)
    }

    private fun detailTitleFromDocument(document: org.jsoup.nodes.Document): String {
        val detailDiv = document.selectFirst("div.detail_info")
        return detailDiv?.selectFirst("p.subj")?.text()
            ?: document.selectFirst("h1.subj, h3.subj")?.text()
            ?: document.title().substringBefore("_")
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val parseStartedAt = System.currentTimeMillis()
        val networkMs = response.receivedResponseAtMillis - response.sentRequestAtMillis
        val document = response.asJsoup()
        val detailDiv = document.selectFirst("div.detail_info")
        val titleNo = titleNoFromUrl(response.request.url.toString())
        return SManga.create().apply {
            title = detailTitleFromDocument(document)
            author = detailDiv?.selectFirst("p.author")?.text()
                ?: document.selectFirst("meta[property=com-dongman:webtoon:author]")?.attr("content")
            artist = author
            val verifiedDetailThumbnail = verifiedDetailOfficialCoverFromDocument(document)
            val rememberedDetailThumbnail = if (verifiedDetailThumbnail.isNotBlank()) {
                rememberVerifiedDetailOfficialCover(titleNo, verifiedDetailThumbnail)
            } else {
                ""
            }
            val cachedDetailThumbnail = verifiedDetailOfficialCoverForTitleNo(titleNo)
            val finalDetailThumbnail = rememberedDetailThumbnail
                .ifBlank { verifiedDetailThumbnail }
                .ifBlank { cachedDetailThumbnail }
            if (finalDetailThumbnail.isNotBlank()) {
                val thumbnailBefore = detailEntryThumbnailForTitleNo(titleNo)
                val detailThumbnailForUi = normalizeCoverKeyForCompare(finalDetailThumbnail)
                val beforeCanonicalEqual = normalizeCoverKeyForCompare(thumbnailBefore) == detailThumbnailForUi
                val beforeHasDetailKey = hasDetailCoverKey(thumbnailBefore)
                val afterHasDetailKey = hasDetailCoverKey(detailThumbnailForUi)
                val detailCoverRefreshMode = getDetailCoverRefreshMode()
                val preserveExistingCover = detailCoverRefreshMode == DETAIL_COVER_REFRESH_PRESERVE
                val detailThumbnailUrlChanged = thumbnailBefore != detailThumbnailForUi
                val shouldApplyDetailThumbnail = !preserveExistingCover
                val sameCanonicalRewrite = shouldApplyDetailThumbnail && beforeCanonicalEqual &&
                    thumbnailBefore.isNotBlank() &&
                    !isVirtualOfficialCoverUrl(thumbnailBefore) &&
                    !beforeHasDetailKey
                val coverDecisionReason = when {
                    preserveExistingCover -> "preserve-existing-cover-mode"
                    thumbnailBefore.isBlank() -> "detail-official-from-empty"
                    isVirtualOfficialCoverUrl(thumbnailBefore) -> "detail-official-replaces-virtual"
                    beforeHasDetailKey -> "strip-old-detail-key"
                    beforeCanonicalEqual -> "sync-refresh-same-canonical-applied"
                    else -> "detail-official-refresh"
                }
                if (shouldApplyDetailThumbnail) {
                    thumbnail_url = detailThumbnailForUi
                }
                rememberOfficialMangaMeta(titleNo, title, detailThumbnailForUi, "detail")
                dlog(
                    "detailFinalCoverSelected mode=${getHomeCoverMode()} titleNo=${titleNo.orEmpty()} " +
                        "source=${if (rememberedDetailThumbnail.isNotBlank()) "detail-html-og-twitter" else "runtime-cache"} " +
                        "detailCoverRefreshMode=$detailCoverRefreshMode preserveExistingCover=$preserveExistingCover " +
                        "librarySafeCover=true noExtraRequest=true sharedHtml=true " +
                        "before=$thumbnailBefore final=$detailThumbnailForUi canonicalEqual=$beforeCanonicalEqual " +
                        "beforeHasDetailKey=$beforeHasDetailKey afterHasDetailKey=$afterHasDetailKey " +
                        "changed=$detailThumbnailUrlChanged applied=$shouldApplyDetailThumbnail " +
                        "sameCanonicalRewrite=$sameCanonicalRewrite " +
                        "reason=$coverDecisionReason"
                )
                dlog(
                    "detailCoverFinalAssert mode=${getHomeCoverMode()} titleNo=${titleNo.orEmpty()} " +
                        "isCdnSns=${isVerifiedDetailOfficialCoverUrl(detailThumbnailForUi)} " +
                        "hasOssParam=${detailThumbnailForUi.contains("x-oss-process")} " +
                        "hasDetailKey=$afterHasDetailKey isVirtual=${isVirtualOfficialCoverUrl(detailThumbnailForUi)} " +
                        "final=$detailThumbnailForUi"
                )
                dlog(
                    "detailOfficialCoverRuntime titleNo=${titleNo.orEmpty()} " +
                        "coverPresent=true source=og-twitter-cdn-sns runtimeOnly=true " +
                        "detailCoverRefreshMode=$detailCoverRefreshMode preserveExistingCover=$preserveExistingCover " +
                        "finalThumbnailApplied=$shouldApplyDetailThumbnail detailCacheKeyApplied=false " +
                        "sameCanonicalRewrite=$sameCanonicalRewrite urlChanged=$detailThumbnailUrlChanged " +
                        "thumbnailUrl=$detailThumbnailForUi canonicalThumbnail=$detailThumbnailForUi " +
                        "thumbnailBefore=$thumbnailBefore coverMode=${getHomeCoverMode()}"
                )
                dlog(
                    "coverLifecycleProbe stage=detailAfter titleNo=${titleNo.orEmpty()} mode=${getHomeCoverMode()} " +
                        "oldThumb=$thumbnailBefore newThumb=$detailThumbnailForUi canonical=$detailThumbnailForUi " +
                        "detailCoverRefreshMode=$detailCoverRefreshMode preserveExistingCover=$preserveExistingCover " +
                        "changed=$detailThumbnailUrlChanged applied=$shouldApplyDetailThumbnail keepExistingOfficial=$preserveExistingCover " +
                        "detailKeyApplied=false sameImageDifferentKey=${sameCoverImageDifferentKey(thumbnailBefore, detailThumbnailForUi)}"
                )
            } else {
                dlog(
                    "detailOfficialCoverRuntime titleNo=${titleNo.orEmpty()} " +
                        "coverPresent=false source=unverified runtimeOnly=true finalThumbnailApplied=false librarySafeCover=false"
                )
            }
            val genreBase = detailDiv?.selectFirst("p.genre")?.text() ?: ""
            val html = document.html()
            val updateTag = extractUpdateTag(html, titleNo)
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
            dlog(
                "detailHealthProbe parse titleNo=${titleNo.orEmpty()} title=$title " +
                    "authorEmpty=${author.isNullOrBlank()} descEmpty=${description.isNullOrBlank()} " +
                    "genreEmpty=${genre.isNullOrBlank()} status=$status thumbnail=${thumbnail_url.orEmpty().ifBlank { detailEntryThumbnailForTitleNo(titleNo) }} " +
                    "networkMs=$networkMs parseMs=${System.currentTimeMillis() - parseStartedAt}"
            )
            dlog(
                "detailRefreshProbe action=parse titleNo=${titleNo.orEmpty()} coverMode=${getHomeCoverMode()} " +
                    "detailCoverRefreshMode=${getDetailCoverRefreshMode()} " +
                    "source=networkOrDetailCache thumbnailBefore=${detailEntryThumbnailForTitleNo(titleNo)} " +
                    "thumbnailAfter=${thumbnail_url.orEmpty().ifBlank { detailEntryThumbnailForTitleNo(titleNo) }} " +
                    "detailCoverChanged=${thumbnail_url.orEmpty().isNotBlank()} " +
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
        val requestPath = canonicalDetailRequestPath(manga.url)
        val finalUrl = baseUrl + requestPath
        val titleNo = titleNoFromUrl(requestPath) ?: titleNoFromUrl(manga.url)
        dlog(
            "detailRefreshProbe action=request requestType=chapters titleNo=${titleNo.orEmpty()} " +
                "coverMode=${getHomeCoverMode()} thumbnailBefore=${manga.thumbnail_url.orEmpty()} " +
                "storedUrl=${normalizeMangaPath(manga.url)} requestPath=$requestPath finalUrl=$finalUrl"
        )
        dlog(
            "chapterListRequest title=${manga.title} manga.url=${manga.url} " +
                "cleanPath=$cleanPath requestPath=$requestPath finalUrl=$finalUrl"
        )
        dlog(
            "detailHealthProbe requestType=chapters title=${manga.title} storedUrl=${normalizeMangaPath(manga.url)} " +
                "storedEpisode=${normalizeMangaPath(manga.url).startsWith("/episodeList")} requestPath=$requestPath finalUrl=$finalUrl"
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
        dlog("detailHealthProbe chapters url=${response.request.url} chapters=${result.size}")
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

    private fun clearCoverModeSensitiveCaches(reason: String) {
        val updateSize = synchronized(updatePageCache) {
            val size = updatePageCache.size
            updatePageCache.clear()
            size
        }
        val genreSize = synchronized(genrePageCache) {
            val size = genrePageCache.size
            genrePageCache.clear()
            size
        }
        cachedLastFilterSnapshot = null
        dlog("coverModeSensitiveCachesCleared reason=$reason updateCaches=$updateSize genreCaches=$genreSize")
    }

    private fun updateCacheWeekday(weekday: String): String {
        return weekday.ifBlank { currentWeekdayCode() }
    }

    private fun coverModeCachePrefix(): String = "coverMode=${getHomeCoverMode()}"

    private fun updateCacheKey(weekday: String, sort: String): String {
        val normalizedWeekday = updateCacheWeekday(weekday)
        val baseKey = if (normalizedWeekday == "NEW") "NEW" else "$normalizedWeekday|$sort"
        return "${coverModeCachePrefix()}|$baseKey"
    }

    private fun genreCacheKey(genre: String): String {
        return "${coverModeCachePrefix()}|${genre.ifBlank { "UNKNOWN" }}"
    }

    private fun getValidUpdatePageCache(key: String): UpdatePageCache? {
        if (key.isBlank()) return null
        val now = System.currentTimeMillis()
        return synchronized(updatePageCache) {
            val cache = updatePageCache[key] ?: return@synchronized null
            if (now - cache.createdAt <= UPDATE_PAGE_CACHE_TTL_MS) {
                cache
            } else {
                updatePageCache.remove(key)
                null
            }
        }
    }

    private fun putUpdatePageCache(key: String, items: List<CachedMangaItem>) {
        if (key.isBlank() || items.isEmpty()) return
        synchronized(updatePageCache) {
            updatePageCache[key] = UpdatePageCache(key, System.currentTimeMillis(), items)
            while (updatePageCache.size > UPDATE_PAGE_CACHE_MAX_ENTRIES) {
                val firstKey = updatePageCache.keys.firstOrNull() ?: break
                updatePageCache.remove(firstKey)
            }
        }
        dlog("putUpdatePageCache key=$key total=${items.size}")
    }

    private fun getValidGenrePageCache(genre: String): GenrePageCache? {
        if (genre.isBlank()) return null
        val key = genreCacheKey(genre)
        val now = System.currentTimeMillis()
        return synchronized(genrePageCache) {
            val cache = genrePageCache[key] ?: return@synchronized null
            if (now - cache.createdAt <= GENRE_PAGE_CACHE_TTL_MS) {
                cache
            } else {
                genrePageCache.remove(key)
                null
            }
        }
    }

    private fun putGenrePageCache(genre: String, items: List<CachedMangaItem>) {
        if (genre.isBlank() || items.isEmpty()) return
        val key = genreCacheKey(genre)
        synchronized(genrePageCache) {
            genrePageCache[key] = GenrePageCache(genre, System.currentTimeMillis(), items)
            while (genrePageCache.size > GENRE_PAGE_CACHE_MAX_ENTRIES) {
                val firstKey = genrePageCache.keys.firstOrNull() ?: break
                genrePageCache.remove(firstKey)
            }
        }
        dlog("putGenrePageCache genre=$genre key=$key total=${items.size}")
    }

    private fun putGenrePageCachesFromDocument(document: org.jsoup.nodes.Document, includeAll: Boolean) {
        val sections = document.select("div._genreFlick div.flick-ct")
        if (sections.isEmpty()) return

        var cachedGenres = 0
        var cachedItems = 0
        getThemeFilter().forEach { tag ->
            val genre = tag.value
            if (genre.isBlank() || genre == "ALL" || getValidGenrePageCache(genre) != null) return@forEach
            val index = document.select("#genreList li").firstOrNull { li ->
                li.attr("data-genre") == genre || li.attr("data-genre-seo") == genre
            }?.selectFirst("a")?.attr("data-index")?.toIntOrNull() ?: return@forEach
            val items = sections.getOrNull(index)
                ?.select("a.genrePageContentItem")
                .orEmpty()
                .map { cachedMangaItemFromElement(it, "genreBulk-cache-$genre") }
                .filter { it.title.isNotEmpty() && it.url.isNotEmpty() }
                .distinctBy { mangaIdentityDedupKey(it.titleNo, it.url) }
            if (items.isNotEmpty()) {
                putGenrePageCache(genre, items)
                cachedGenres += 1
                cachedItems += items.size
            }
        }

        if (includeAll) {
            val allItems = genreAllItemsFromSectionCaches()
            if (allItems.isNotEmpty()) {
                putGenrePageCache("ALL", allItems)
                cachedItems += allItems.size
            }
        }

        dlog("putGenrePageCachesFromDocument includeAll=$includeAll cachedGenres=$cachedGenres cachedItems=$cachedItems")
    }

    private fun genreAllItemsFromSectionCaches(): List<CachedMangaItem> {
        val result = mutableListOf<CachedMangaItem>()
        synchronized(genrePageCache) {
            getThemeFilter().forEach { tag ->
                val genre = tag.value
                if (genre.isBlank() || genre == "ALL") return@forEach
                val items = genrePageCache[genreCacheKey(genre)]?.items.orEmpty()
                if (items.isNotEmpty()) result.addAll(items)
            }
        }
        return result
    }

    private fun themeAllCarrierGenre(): String {
        return getThemeFilter().firstOrNull { it.value.isNotBlank() && it.value != "ALL" }?.value ?: "LOVE"
    }

    private fun cachedMangaItemFromElement(element: Element, origin: String = "cached-element"): CachedMangaItem {
        val rawHref = element.attr("href")
        val href = element.absUrl("href").ifEmpty { rawHref }
        val cleanPath = cleanMangaDetailPath(href)
        val titleNo = titleNoFromUrl(cleanPath)
            ?: titleNoFromUrl(href)
            ?: titleNoFromUrl(rawHref)
            ?: element.attr("data-title-no").takeIf { it.isNotBlank() }
            ?: scEventParameterValue(element, "recommended_titleNo")
        val identityPath = canonicalMangaIdentityPath(rawUrl = href, titleNoHint = titleNo)
        val titleText = mangaTitleFromElement(element)
        val rawThumbnailUrl = extractThumbnailUrl(element, origin, titleNo)
        val thumbnailUrl = officialListThumbnailForUi(rawThumbnailUrl)
        rememberOfficialMangaMetaFromList(titleNo, titleText, rawThumbnailUrl, origin)
        val hasNew = hasNewBadge(element)
        logIdentityProbe(origin, titleNo, titleText, rawHref, href, identityPath)
        return CachedMangaItem(
            url = identityPath,
            title = titleText,
            thumbnailUrl = thumbnailUrl,
            titleNo = titleNo,
            hasNew = hasNew,
        )
    }

    private fun mangaFromCachedItem(item: CachedMangaItem): SManga = SManga.create().apply {
        url = item.url
        title = item.title
        val detailOfficialCover = verifiedDetailOfficialCoverForTitleNo(item.titleNo)
        val selectedThumbnail = if (detailOfficialCover.isNotBlank()) {
            officialListThumbnailForUi(detailOfficialCover)
        } else {
            officialListThumbnailForUi(item.thumbnailUrl)
        }
        thumbnail_url = selectedThumbnail
        logCoverLifecycleListEmit(
            origin = "cached-page",
            titleNo = item.titleNo,
            title = title,
            storedUrl = url,
            rawThumbnail = item.thumbnailUrl,
            finalThumbnail = selectedThumbnail,
            mode = getHomeCoverMode(),
            officialCoverRequired = item.thumbnailUrl.startsWith(baseUrl + OFFICIAL_COVER_VIRTUAL_PATH),
            officialCoverVirtual = selectedThumbnail.startsWith(baseUrl + OFFICIAL_COVER_VIRTUAL_PATH),
            officialCoverPresent = detailOfficialCover.isNotBlank(),
        )
        if (detailOfficialCover.isNotBlank() && detailOfficialCover != item.thumbnailUrl) {
            dlog(
                "cachedPageDetailCoverOverride titleNo=${item.titleNo.orEmpty()} " +
                    "title=$title oldThumbnail=${item.thumbnailUrl} finalThumbnail=$selectedThumbnail canonicalThumbnail=$detailOfficialCover"
            )
        }
        logIdentityProbe("cached-page", item.titleNo, title, "", "", url)
    }

    private fun mangaTitleFromElement(element: Element): String {
        return element.selectFirst(
            "p.subj, .subj .ellipsis, ._items_name_t, .home_genre_t, .works_tit, " +
                "p.chapter-title-02, .chapter-title-01, .tit_content"
        )?.text()
            ?.takeIf { it.isNotBlank() }
            ?: scEventParameterValue(element, "recommend_title_title")
            ?: element.attr("title").takeIf { it.isNotBlank() }
            ?: element.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: ""
    }

    private fun scEventParameterValue(element: Element, key: String): String? {
        val raw = element.attr("data-sc-event-parameter")
        if (raw.isBlank()) return null
        val quotedSingle = Regex("""(?:^|[,\{])\s*${Regex.escape(key)}\s*:\s*'([^']*)'""").find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        if (!quotedSingle.isNullOrBlank()) return quotedSingle
        val quotedDouble = Regex("""(?:^|[,\{])\s*${Regex.escape(key)}\s*:\s*\"([^\"]*)\"""").find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        if (!quotedDouble.isNullOrBlank()) return quotedDouble
        val bare = Regex("""(?:^|[,\{])\s*${Regex.escape(key)}\s*:\s*([^,\}]+)""").find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trim('\'', '\"')
        return bare?.takeIf { it.isNotBlank() }
    }

    private var newProbeLogCount = 0

    private fun entriesNewProbeShouldLog(): Boolean {
        if (newProbeLogCount >= NEW_PROBE_LOG_LIMIT) return false
        newProbeLogCount += 1
        return true
    }

    private fun needsPopularOfficialCoverFallback(
        origin: String,
        titleNo: String?,
        parsedThumbnail: String,
        isMarketingNewWork: Boolean,
    ): Boolean {
        if (titleNo.isNullOrBlank()) return false
        if (isMarketingNewWork) return true
        // 轮播图本身是横幅，不能当竖版封面；轮播独占条目也不能把 thumbnail_url 留空。
        return origin == "popular-banner" && parsedThumbnail.isBlank()
    }

    private fun nativeEventTitleForMarketingNewWork(element: Element, titleNo: String?): String {
        val normalizedTitleNo = titleNo?.trim().orEmpty()
        val eventTitleNo = scEventParameterValue(element, "recommended_titleNo")?.trim().orEmpty()
        val eventTitle = scEventParameterValue(element, "recommend_title_title")?.trim().orEmpty()
        return eventTitle.takeIf {
            normalizedTitleNo.isNotBlank() &&
                eventTitleNo == normalizedTitleNo &&
                it.isNotBlank()
        }.orEmpty()
    }

    private fun mangaFromElement(element: Element, origin: String = "manga-element"): SManga = SManga.create().apply {
        val rawHref = element.attr("href")
        val href = element.absUrl("href").ifEmpty { rawHref }
        val cleanPath = cleanMangaDetailPath(href)
        val titleNo = titleNoFromUrl(cleanPath)
            ?: titleNoFromUrl(href)
            ?: titleNoFromUrl(rawHref)
            ?: element.attr("data-title-no").takeIf { it.isNotBlank() }
            ?: scEventParameterValue(element, "recommended_titleNo")
        val identityPath = canonicalMangaIdentityPath(rawUrl = href, titleNoHint = titleNo)
        val hasNew = hasNewBadge(element)
        val parsedTitle = mangaTitleFromElement(element)
        val isMarketingNewWork = origin == "popular-common-card" && isPopularCommonCardNewWork(element)
        // 新作卡/轮播图可能不是可信竖封面：只接受详情官方封面或已验证运行时封面。
        // 不使用虚拟 URL、不使用 detail key、不把未验证图片包装成兜底。
        val rawParsedThumbnail = if (isMarketingNewWork) "" else extractThumbnailUrl(element, origin, titleNo)
        val parsedThumbnail = officialListThumbnailForUi(rawParsedThumbnail)
        val needsOfficialCoverFallback = needsPopularOfficialCoverFallback(origin, titleNo, rawParsedThumbnail, isMarketingNewWork)
        val nativeEventTitle = if (isMarketingNewWork) {
            nativeEventTitleForMarketingNewWork(element, titleNo)
        } else {
            ""
        }
        val verifiedOfficialCover = if (needsOfficialCoverFallback) {
            verifiedDetailOfficialCoverForTitleNo(titleNo)
        } else {
            ""
        }
        val trustedRuntimeOfficialCover = if (needsOfficialCoverFallback && verifiedOfficialCover.isBlank()) {
            trustedRuntimeOfficialCoverForTitleNo(titleNo)
        } else {
            ""
        }
        val virtualOfficialCover = if (
            needsOfficialCoverFallback &&
            verifiedOfficialCover.isBlank() &&
            trustedRuntimeOfficialCover.isBlank() &&
            allowVirtualOfficialCoverFallbackInList()
        ) {
            val detailUrl = titleNo?.let { officialNewWorkDetailUrlForElement(element, it) }.orEmpty()
            buildOfficialCoverVirtualUrl(titleNo, detailUrl)
        } else {
            ""
        }
        val rawSelectedOfficialCover = verifiedOfficialCover.ifBlank { trustedRuntimeOfficialCover.ifBlank { virtualOfficialCover } }
        val selectedOfficialCover = officialListThumbnailForUi(rawSelectedOfficialCover)
        val officialCoverMissing = needsOfficialCoverFallback && rawSelectedOfficialCover.isBlank()

        if (!needsOfficialCoverFallback) {
            rememberOfficialMangaMetaFromList(titleNo, parsedTitle, rawParsedThumbnail, origin)
        }
        url = identityPath
        title = if (isMarketingNewWork) nativeEventTitle else parsedTitle
        // v100.7.9：列表不再给虚拟官方封面 URL，也不把未验证图片包装成兜底。
        // 需要官方封面的条目只接受 verified/runtime official cover；缺失会写明 missing，不能伪装。
        thumbnail_url = if (needsOfficialCoverFallback) {
            selectedOfficialCover
        } else {
            parsedThumbnail
        }
        logCoverLifecycleListEmit(
            origin = origin,
            titleNo = titleNo,
            title = title,
            storedUrl = url,
            rawThumbnail = rawParsedThumbnail,
            finalThumbnail = thumbnail_url.orEmpty(),
            mode = getHomeCoverMode(),
            officialCoverRequired = needsOfficialCoverFallback,
            officialCoverVirtual = virtualOfficialCover.isNotBlank(),
            officialCoverPresent = verifiedOfficialCover.isNotBlank() || trustedRuntimeOfficialCover.isNotBlank(),
        )
        logIdentityProbe(origin, titleNo, title, rawHref, href, url)
        if (isMarketingNewWork) {
            val eventTitleNo = scEventParameterValue(element, "recommended_titleNo")?.trim().orEmpty()
            val eventTitle = scEventParameterValue(element, "recommend_title_title")?.trim().orEmpty()
            val titleValid = title.isNotBlank()
            dlog(
                "popularCommonCardNewWorkOfficialCover titleNo=${titleNo.orEmpty()} " +
                    "eventTitleNo=$eventTitleNo eventTitle=$eventTitle " +
                    "titleValid=$titleValid marketingTitle=$parsedTitle " +
                    "officialCoverPresent=${verifiedOfficialCover.isNotBlank() || trustedRuntimeOfficialCover.isNotBlank()} " +
                    "officialCoverVirtual=${virtualOfficialCover.isNotBlank()} " +
                    "strictOfficialFirstNoVirtual=${isHomeCoverOfficialFirst()} " +
                    "marketingThumbnailSuppressed=true " +
                    "officialDetailFetchEnabled=true " +
                    "blankPlaceholderApplied=false " +
                    "officialCoverMissing=$officialCoverMissing " +
                    "unverifiedSubstituteUsed=false strictVerifiedOnly=true " +
                    "uiCoverMode=${popularNewWorkCoverMode(verifiedOfficialCover, trustedRuntimeOfficialCover, virtualOfficialCover)} " +
                    "coverClass=${popularNewWorkCoverClass(verifiedOfficialCover, trustedRuntimeOfficialCover, virtualOfficialCover)}"
            )
        } else if (needsOfficialCoverFallback) {
            dlog(
                "popularBannerOnlyOfficialCover titleNo=${titleNo.orEmpty()} origin=$origin title=$title " +
                    "officialCoverPresent=${verifiedOfficialCover.isNotBlank() || trustedRuntimeOfficialCover.isNotBlank()} " +
                    "officialCoverVirtual=${virtualOfficialCover.isNotBlank()} " +
                    "strictOfficialFirstNoVirtual=${isHomeCoverOfficialFirst()} " +
                    "bannerThumbnailSuppressed=true blankPlaceholderApplied=false " +
                    "officialCoverMissing=$officialCoverMissing " +
                    "unverifiedSubstituteUsed=false strictVerifiedOnly=true " +
                    "uiCoverMode=${popularNewWorkCoverMode(verifiedOfficialCover, trustedRuntimeOfficialCover, virtualOfficialCover)} " +
                    "coverClass=${popularNewWorkCoverClass(verifiedOfficialCover, trustedRuntimeOfficialCover, virtualOfficialCover)}"
            )
        }
        if (VERBOSE_LIST_LOG) {
            dlog(
                "mangaFromElement rawHref=$rawHref absHref=$href cleanPath=$cleanPath storedUrl=$url " +
                    "titleNo=$titleNo hasNew=$hasNew title=$title"
            )
        }
    }

    private fun preseedOfficialMetaFromPopularRawElements(rawElements: List<Element>) {
        var count = 0
        rawElements.forEach { element ->
            val origin = element.attr("data-mihon-origin").ifBlank { return@forEach }
            val titleNo = titleNoFromElementIdentity(element)
            val title = mangaTitleFromElement(element)
            if (origin == "popular-banner") {
                // 轮播图只作为标题/身份线索预种；是否能当竖封面必须另行验证，不能直接作为新作封面。
                if (rememberOfficialMangaMeta(titleNo, title, "", "popular-banner-title") != null) {
                    count += 1
                }
                return@forEach
            }
            if (!isTrustedOfficialMetaSource(origin)) return@forEach
            if (origin == "popular-common-card" && isPopularCommonCardNewWork(element)) return@forEach
            val thumbnail = extractThumbnailUrl(element, origin, titleNo)
            if (rememberOfficialMangaMetaFromList(titleNo, title, thumbnail, origin) != null) {
                count += 1
            }
        }
        if (count > 0) dlog("popularOfficialMetaPreseed count=$count")
    }

    private data class OfficialCoverResolveResult(
        val coverUrl: String,
        val source: String,
    )


    private fun normalizeCoverKeyForCompare(url: String): String {
        val stripped = stripImageProcessParams(url.trim())
        if (stripped.isBlank()) return ""
        val withoutDetailFlag = stripped
            .replace("?dongman_detail_cover=1&", "?")
            .replace("&dongman_detail_cover=1", "")
            .replace("?dongman_detail_cover=1", "")
        return withoutDetailFlag.trimEnd('?')
    }

    private fun sameCoverImageDifferentKey(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank() || left == right) return false
        return normalizeCoverKeyForCompare(left) == normalizeCoverKeyForCompare(right)
    }

    private fun isVirtualOfficialCoverUrl(url: String): Boolean {
        return url.contains(OFFICIAL_COVER_VIRTUAL_PATH)
    }

    private fun coverLifecycleThumbKind(url: String, officialCoverRequired: Boolean, officialCoverVirtual: Boolean, officialCoverPresent: Boolean): String {
        return when {
            url.isBlank() -> "blank"
            isVirtualOfficialCoverUrl(url) || officialCoverVirtual -> "virtual-official"
            officialCoverPresent && isVerifiedDetailOfficialCoverUrl(url) -> "official-cdn-sns"
            isVerifiedDetailOfficialCoverUrl(url) -> "cdn-sns"
            officialCoverRequired -> "official-missing"
            else -> "normal"
        }
    }

    private fun logCoverLifecycleListEmit(
        origin: String,
        titleNo: String?,
        title: String,
        storedUrl: String,
        rawThumbnail: String,
        finalThumbnail: String,
        mode: String,
        officialCoverRequired: Boolean,
        officialCoverVirtual: Boolean,
        officialCoverPresent: Boolean,
    ) {
        dlog(
            "coverLifecycleProbe stage=listEmit origin=$origin titleNo=${titleNo.orEmpty()} title=$title " +
                "mode=$mode thumbKind=${coverLifecycleThumbKind(finalThumbnail, officialCoverRequired, officialCoverVirtual, officialCoverPresent)} " +
                "officialCoverRequired=$officialCoverRequired officialCoverPresent=$officialCoverPresent " +
                "officialCoverVirtual=$officialCoverVirtual raw=$rawThumbnail final=$finalThumbnail " +
                "hasOssParam=${finalThumbnail.contains("x-oss-process")} isCdnSns=${isVerifiedDetailOfficialCoverUrl(finalThumbnail)} " +
                "isDetailKey=${hasDetailCoverKey(finalThumbnail)} storedUrl=$storedUrl"
        )
        dlog(
            "coverFinalUrlProbe mode=$mode origin=$origin titleNo=${titleNo.orEmpty()} " +
                "final=$finalThumbnail isCdnSns=${isVerifiedDetailOfficialCoverUrl(finalThumbnail)} " +
                "hasOssParam=${finalThumbnail.contains("x-oss-process")} " +
                "isDetailKey=${hasDetailCoverKey(finalThumbnail)} " +
                "isVirtual=${isVirtualOfficialCoverUrl(finalThumbnail)} " +
                "source=${if (officialCoverPresent) "official-meta" else if (officialCoverVirtual) "virtual" else if (officialCoverRequired) "missing-official" else "list"}"
        )
    }

    private fun detailEntryThumbnailForTitleNo(titleNo: String?): String {
        val key = titleNo?.trim().orEmpty()
        if (key.isBlank()) return ""
        return synchronized(detailEntryThumbnailByTitleNo) { detailEntryThumbnailByTitleNo[key].orEmpty() }
    }

    private fun shouldKeepExistingOfficialCoverInDetail(titleNo: String?, thumbnailBefore: String, finalDetailThumbnail: String): Boolean {
        if (titleNo.isNullOrBlank()) return false
        if (thumbnailBefore.isBlank() || isVirtualOfficialCoverUrl(thumbnailBefore)) return false
        if (!isVerifiedDetailOfficialCoverUrl(thumbnailBefore)) return false
        if (!hasDetailCoverKey(thumbnailBefore)) return false
        return normalizeCoverKeyForCompare(thumbnailBefore) == normalizeCoverKeyForCompare(finalDetailThumbnail)
    }

    private fun trustedRuntimeOfficialCoverForTitleNo(titleNo: String?): String {
        val meta = getOfficialMangaMeta(titleNo) ?: return ""
        val cover = stripImageProcessParams(meta.thumbnailUrl)
        if (cover.isBlank()) return ""
        if (meta.source == "popular-banner-title") return ""
        if (cover.contains("/banner/", ignoreCase = true)) return ""
        if (!isTrustedOfficialMetaSource(meta.source)) return ""
        return cover
    }

    private fun popularNewWorkCoverMode(
        verifiedOfficialCover: String,
        trustedRuntimeOfficialCover: String,
        virtualOfficialCover: String,
    ): String {
        return when {
            verifiedOfficialCover.isNotBlank() -> "official-cover"
            trustedRuntimeOfficialCover.isNotBlank() -> "trusted-runtime-official-cover"
            virtualOfficialCover.isNotBlank() -> "virtual-official-cover"
            else -> "missing-official-cover"
        }
    }

    private fun popularNewWorkCoverClass(
        verifiedOfficialCover: String,
        trustedRuntimeOfficialCover: String,
        virtualOfficialCover: String,
    ): String {
        return when {
            verifiedOfficialCover.isNotBlank() -> "detail-official-runtime"
            trustedRuntimeOfficialCover.isNotBlank() -> "trusted-runtime-official"
            virtualOfficialCover.isNotBlank() -> "virtual-official-loader"
            else -> "official-cover-missing"
        }
    }

    private fun buildOfficialCoverVirtualUrl(titleNo: String?, detailUrl: String): String {
        val key = titleNo?.trim().orEmpty()
        val normalizedDetailUrl = rememberOfficialCoverDetailUrl(key, detailUrl)
        if (key.isBlank() || normalizedDetailUrl.isBlank()) return ""
        return "$baseUrl$OFFICIAL_COVER_VIRTUAL_PATH?titleNo=$key"
    }

    private fun executeOfficialCoverVirtualRequest(request: Request, chain: Interceptor.Chain): Response {
        val startedAt = System.currentTimeMillis()
        val titleNo = request.url.queryParameter("titleNo")?.trim().orEmpty()
        val detailUrl = request.url.queryParameter("detail")
            ?.trim()
            .orEmpty()
            .ifBlank { officialCoverDetailUrlForTitleNo(titleNo) }
            .ifBlank { titleNo.takeIf { it.isNotBlank() }?.let { "$baseUrl/episodeList?titleNo=$it" }.orEmpty() }
        dlog(
            "coverLifecycleProbe stage=virtualImageRequest titleNo=$titleNo mode=${getHomeCoverMode()} " +
                "virtualUrl=${request.url} detailUrl=$detailUrl"
        )
        val cachedDetailCover = verifiedDetailOfficialCoverForTitleNo(titleNo)
        val trustedRuntimeCover = if (cachedDetailCover.isBlank()) trustedRuntimeOfficialCoverForTitleNo(titleNo) else ""
        val resolved = when {
            cachedDetailCover.isNotBlank() -> OfficialCoverResolveResult(cachedDetailCover, "detail-official-runtime-cache")
            trustedRuntimeCover.isNotBlank() -> OfficialCoverResolveResult(trustedRuntimeCover, "trusted-runtime-official-meta")
            else -> resolveOfficialCoverForVirtualRequestViaInflight(titleNo, detailUrl, startedAt)
        }
        if (resolved.coverUrl.isBlank()) {
            dlog(
                "officialCoverVirtualImage titleNo=$titleNo coverResolved=false source=${resolved.source.ifBlank { "unresolved" }} " +
                    "detailUrl=$detailUrl elapsed=${System.currentTimeMillis() - startedAt}ms marketingRequests=0"
            )
            dlog(
                "coverLifecycleProbe stage=virtualImageResolved titleNo=$titleNo mode=${getHomeCoverMode()} " +
                    "coverResolved=false source=${resolved.source.ifBlank { "unresolved" }} elapsed=${System.currentTimeMillis() - startedAt}ms"
            )
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Official cover not resolved")
                .headers(Headers.Builder().set("Content-Type", "text/plain; charset=UTF-8").build())
                .body("official cover not resolved".toResponseBody("text/plain; charset=UTF-8".toMediaType()))
                .build()
        }

        val imageStartedAt = System.currentTimeMillis()
        val imageHeaders = headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", detailUrl.ifBlank { "$baseUrl/" })
            .build()
        val imageRequest = GET(resolved.coverUrl, imageHeaders)
        val imageResponse = chain.proceed(imageRequest)
        dlog(
            "officialCoverVirtualImage titleNo=$titleNo coverResolved=true source=${resolved.source} " +
                "imageCode=${imageResponse.code} resolveMs=${imageStartedAt - startedAt}ms " +
                "imageElapsed=${System.currentTimeMillis() - imageStartedAt}ms " +
                "virtualUrl=${request.url} finalCoverUrl=${resolved.coverUrl} marketingRequests=0"
        )
        dlog(
            "coverLifecycleProbe stage=virtualImageResolved titleNo=$titleNo mode=${getHomeCoverMode()} " +
                "coverResolved=true source=${resolved.source} imageCode=${imageResponse.code} " +
                "resolveMs=${imageStartedAt - startedAt}ms imageElapsed=${System.currentTimeMillis() - imageStartedAt}ms " +
                "finalCoverUrl=${resolved.coverUrl}"
        )
        return imageResponse
    }

    private fun resolveOfficialCoverForVirtualRequestViaInflight(
        titleNo: String,
        detailUrl: String,
        overallStartedAt: Long,
    ): OfficialCoverResolveResult {
        if (titleNo.isBlank() || detailUrl.isBlank()) return OfficialCoverResolveResult("", "missing-title-or-detail")
        val state = ensureOfficialNewWorkCoverFetchStarted(
            titleNo,
            detailUrl,
            OfficialCoverFetchPriority.DEMAND,
        )
            ?: return verifiedDetailOfficialCoverForTitleNo(titleNo)
                .takeIf { it.isNotBlank() }
                ?.let { OfficialCoverResolveResult(it, "detail-official-runtime-cache") }
                ?: OfficialCoverResolveResult("", "inflight-not-started")

        val waitStartedAt = System.currentTimeMillis()
        if (!state.completed) {
            runCatching { state.latch.await(OFFICIAL_COVER_VIRTUAL_INFLIGHT_WAIT_MS, TimeUnit.MILLISECONDS) }
        }
        val waitedMs = System.currentTimeMillis() - waitStartedAt
        val remembered = verifiedDetailOfficialCoverForTitleNo(titleNo)
        if (remembered.isNotBlank()) {
            dlog(
                "officialCoverVirtualResolveJoin titleNo=$titleNo coverPresent=true " +
                    "source=virtual-inflight-official-cover completed=${state.completed} failed=${state.failed} " +
                    "waited=${waitedMs}ms overallElapsed=${System.currentTimeMillis() - overallStartedAt}ms " +
                    "detailUrl=$detailUrl marketingRequests=0"
            )
            return OfficialCoverResolveResult(remembered, "virtual-inflight-official-cover")
        }

        dlog(
            "officialCoverVirtualResolveJoin titleNo=$titleNo coverPresent=false " +
                "source=${if (state.completed) "virtual-inflight-failed" else "virtual-inflight-pending"} " +
                "completed=${state.completed} failed=${state.failed} waited=${waitedMs}ms " +
                "overallElapsed=${System.currentTimeMillis() - overallStartedAt}ms detailUrl=$detailUrl marketingRequests=0"
        )
        return OfficialCoverResolveResult(
            "",
            if (state.completed) "virtual-inflight-failed" else "virtual-inflight-pending",
        )
    }

    private fun resolveOfficialCoverForVirtualRequest(
        titleNo: String,
        detailUrl: String,
        chain: Interceptor.Chain,
        overallStartedAt: Long,
    ): OfficialCoverResolveResult {
        if (titleNo.isBlank() || detailUrl.isBlank()) return OfficialCoverResolveResult("", "missing-title-or-detail")
        return try {
            val requestHeaders = headersBuilder()
                .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .set("X-Mihon-Official-Cover-Meta-Only", "1")
                .build()
            val detailRequest = GET(detailUrl, requestHeaders)
            val detailStartedAt = System.currentTimeMillis()
            chain.proceed(detailRequest).use { response ->
                val responseAt = System.currentTimeMillis()
                val scan = scanOfficialCoverMetaFast(response, titleNo)
                val scanEndedAt = System.currentTimeMillis()
                val remembered = if (scan.coverUrl.isNotBlank()) rememberVerifiedDetailOfficialCover(titleNo, scan.coverUrl) else ""
                dlog(
                    "officialCoverVirtualResolve titleNo=$titleNo coverPresent=${remembered.isNotBlank()} " +
                        "source=${scan.source} code=${response.code} requestedUrl=$detailUrl " +
                        "finalUrl=${response.request.url} ttfb=${responseAt - detailStartedAt}ms " +
                        "readMeta=${scanEndedAt - responseAt}ms elapsed=${scanEndedAt - detailStartedAt}ms " +
                        "overallElapsed=${scanEndedAt - overallStartedAt}ms bytesScanned=${scan.bytesScanned} " +
                        "earlyClose=${scan.earlyClose} fullJsoup=false cacheWrite=false " +
                        "directDetail=${!detailUrl.contains("/episodeList")} marketingRequests=0"
                )
                OfficialCoverResolveResult(remembered, if (remembered.isNotBlank()) "virtual-detail-meta-fast" else scan.source)
            }
        } catch (e: Exception) {
            wlog(
                "officialCoverVirtualResolveFailed titleNo=$titleNo detailUrl=$detailUrl " +
                    "elapsed=${System.currentTimeMillis() - overallStartedAt}ms error=${e.javaClass.simpleName}",
                e,
            )
            OfficialCoverResolveResult("", "virtual-detail-meta-failed")
        }
    }

    // 只清理历史版本可能遗留的持久化标题/封面键；v95 不再读写这些键，也不保留旧 /new 或营销封面预取实现。
    private fun selectedOfficialNewWorkTargets(elements: List<Element>): List<OfficialNewWorkCoverTarget> {
        val targets = linkedMapOf<String, OfficialNewWorkCoverTarget>()
        elements
            .asSequence()
            .filter { element ->
                val origin = element.attr("data-mihon-origin")
                (origin == "popular-common-card" && isPopularCommonCardNewWork(element)) || origin == "popular-banner"
            }
            .forEach { element ->
                val titleNo = titleNoFromElementIdentity(element)?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                val detailUrl = officialNewWorkDetailUrlForElement(element, titleNo)
                val origin = element.attr("data-mihon-origin")
                val rawIndex = element.attr("data-mihon-raw-index").toIntOrNull() ?: Int.MAX_VALUE
                targets.putIfAbsent(titleNo, OfficialNewWorkCoverTarget(titleNo, detailUrl, origin, rawIndex))
            }
        return targets.values.toList()
    }

    private fun officialNewWorkDetailUrlForElement(element: Element, titleNo: String): String {
        val rawHref = element.attr("href")
        val href = element.absUrl("href").ifEmpty { rawHref }
        val cleanPath = cleanMangaDetailPath(href)
        val knownPath = knownCanonicalMangaIdentity(titleNo)
        val selectedPath = when {
            isCleanWorkPagePath(cleanPath) -> cleanPath
            isCleanWorkPagePath(knownPath) -> knownPath
            cleanPath.isNotBlank() -> cleanPath
            else -> "/episodeList?titleNo=$titleNo"
        }
        return if (selectedPath.startsWith("http://") || selectedPath.startsWith("https://")) {
            selectedPath
        } else {
            baseUrl + selectedPath
        }
    }


    private fun prepareOfficialNewWorkCoversForPopularOfficialFirst(elements: List<Element>): OfficialCoverWaitStats {
        val targets = selectedOfficialNewWorkTargets(elements).take(OFFICIAL_FIRST_COVER_LIMIT)
        return prepareOfficialCoverTargetsForListBlocking(
            origin = "popular",
            targets = targets,
            waitMs = OFFICIAL_FIRST_COVER_WAIT_MS,
            limitForLog = OFFICIAL_FIRST_COVER_LIMIT,
        )
    }

    private fun prepareOfficialNewWorkCoversForPopularVisibleNoBlank(elements: List<Element>): OfficialCoverWaitStats {
        val targets = selectedOfficialNewWorkTargets(elements).take(OFFICIAL_FAST_COVER_WAIT_LIMIT)
        return prepareOfficialCoverTargetsForListBlocking(
            origin = "popular-fast-no-virtual",
            targets = targets,
            waitMs = OFFICIAL_FAST_COVER_WAIT_MS,
            limitForLog = OFFICIAL_FAST_COVER_WAIT_LIMIT,
        )
    }

    private fun selectedNewPageOfficialCoverTargets(elements: List<Element>): List<OfficialNewWorkCoverTarget> {
        val targets = linkedMapOf<String, OfficialNewWorkCoverTarget>()
        elements.forEachIndexed { index, element ->
            val titleNo = titleNoFromElementIdentity(element)?.trim()?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val detailUrl = officialNewWorkDetailUrlForElement(element, titleNo)
            targets.putIfAbsent(titleNo, OfficialNewWorkCoverTarget(titleNo, detailUrl, "new-page", index))
        }
        return targets.values.toList()
    }

    private fun prepareOfficialNewWorkCoversForNewPageOfficialFirst(elements: List<Element>): OfficialCoverWaitStats {
        val targets = selectedNewPageOfficialCoverTargets(elements).take(OFFICIAL_FIRST_COVER_LIMIT)
        return prepareOfficialCoverTargetsForListBlocking(
            origin = "new-page",
            targets = targets,
            waitMs = OFFICIAL_FIRST_COVER_WAIT_MS,
            limitForLog = OFFICIAL_FIRST_COVER_LIMIT,
        )
    }

    private fun prepareOfficialNewWorkCoversForNewPageVisibleNoBlank(elements: List<Element>): OfficialCoverWaitStats {
        val targets = selectedNewPageOfficialCoverTargets(elements).take(OFFICIAL_FAST_COVER_WAIT_LIMIT)
        return prepareOfficialCoverTargetsForListBlocking(
            origin = "new-page-no-virtual",
            targets = targets,
            waitMs = if (isHomeCoverOfficialFirst()) OFFICIAL_FIRST_COVER_WAIT_MS else OFFICIAL_FAST_COVER_WAIT_MS,
            limitForLog = OFFICIAL_FAST_COVER_WAIT_LIMIT,
        )
    }

    private fun prepareOfficialCoverTargetsForListBlocking(
        origin: String,
        targets: List<OfficialNewWorkCoverTarget>,
        waitMs: Long,
        limitForLog: Int,
    ): OfficialCoverWaitStats {
        if (targets.isEmpty()) {
            dlog("coverModeProbe mode=${getHomeCoverMode()} origin=$origin targets=0 waited=0ms success=0 missing=0")
            return OfficialCoverWaitStats()
        }
        var alreadyReady = 0
        val states = mutableListOf<OfficialNewWorkCoverFetchState>()
        targets.forEach { target ->
            if (verifiedDetailOfficialCoverForTitleNo(target.titleNo).isNotBlank() || trustedRuntimeOfficialCoverForTitleNo(target.titleNo).isNotBlank()) {
                alreadyReady += 1
            } else {
                ensureOfficialNewWorkCoverFetchStarted(target.titleNo, target.detailUrl, OfficialCoverFetchPriority.VISIBLE)?.let { states.add(it) }
            }
        }
        val waitStartedAt = System.currentTimeMillis()
        val waitedMs = awaitOfficialNewWorkCoverStates(states, waitMs)
        val success = targets.count {
            verifiedDetailOfficialCoverForTitleNo(it.titleNo).isNotBlank() || trustedRuntimeOfficialCoverForTitleNo(it.titleNo).isNotBlank()
        }
        val missing = targets.size - success
        val pending = states.count { !it.completed }
        val networkError = states.count { it.failed && it.failureReason.startsWith("networkError") }
        val noMeta = states.count { it.failed && it.failureReason.startsWith("noMeta") }
        val failedOther = states.count { it.failed && it.failureReason.isNotBlank() && !it.failureReason.startsWith("networkError") && !it.failureReason.startsWith("noMeta") }
        val missingTitleNos = targets.filter {
            verifiedDetailOfficialCoverForTitleNo(it.titleNo).isBlank() && trustedRuntimeOfficialCoverForTitleNo(it.titleNo).isBlank()
        }.joinToString("|") { it.titleNo }
        dlog(
            "coverModeProbe mode=${getHomeCoverMode()} origin=$origin targets=${targets.size} " +
                "titleNos=${targets.joinToString("|") { it.titleNo }} alreadyReady=$alreadyReady " +
                "scheduled=${states.size} success=$success missing=$missing waited=${System.currentTimeMillis() - waitStartedAt}ms " +
                "awaitMs=$waitedMs waitLimitMs=$waitMs limit=$limitForLog " +
                "strictNoVirtual=${!allowVirtualOfficialCoverFallbackInList()} " +
                "failurePending=$pending failureNetworkError=$networkError failureNoMeta=$noMeta failureOther=$failedOther " +
                "missingTitleNos=$missingTitleNos marketingRequests=0"
        )
        return OfficialCoverWaitStats(
            titleNos = targets.size,
            alreadyReady = alreadyReady,
            scheduled = states.size,
            success = success,
            missing = missing,
            waitedMs = System.currentTimeMillis() - waitStartedAt,
        )
    }

    private fun scheduleRemainingOfficialNewWorkCoversForPopularAsync(
        elements: List<Element>,
        foregroundLimit: Int,
    ): OfficialCoverWaitStats {
        val targets = selectedOfficialNewWorkTargets(elements)
            .drop(foregroundLimit)
            .take(OFFICIAL_BACKGROUND_COVER_PREFETCH_LIMIT)
        if (targets.isEmpty()) {
            dlog("officialCoverBackgroundSchedule origin=popular targets=0 scheduled=0 mode=${getHomeCoverMode()} strictVerifiedOnly=true")
            return OfficialCoverWaitStats()
        }
        var alreadyReady = 0
        var trustedReady = 0
        var scheduled = 0
        val scheduledTitleNos = mutableListOf<String>()
        targets.forEach { target ->
            when {
                verifiedDetailOfficialCoverForTitleNo(target.titleNo).isNotBlank() -> alreadyReady += 1
                trustedRuntimeOfficialCoverForTitleNo(target.titleNo).isNotBlank() -> trustedReady += 1
                else -> {
                    val state = ensureOfficialNewWorkCoverFetchStarted(target.titleNo, target.detailUrl, OfficialCoverFetchPriority.PREFETCH)
                    if (state != null) {
                        scheduled += 1
                        scheduledTitleNos += target.titleNo
                    }
                }
            }
        }
        dlog(
            "officialCoverBackgroundSchedule origin=popular targets=${targets.size} " +
                "foregroundLimit=$foregroundLimit alreadyReady=$alreadyReady trustedReady=$trustedReady " +
                "scheduled=$scheduled scheduledTitleNos=${scheduledTitleNos.joinToString("|")} " +
                "limit=$OFFICIAL_BACKGROUND_COVER_PREFETCH_LIMIT strictVerifiedOnly=true unverifiedSubstituteUsed=false " +
                "prefetchParallelism=$OFFICIAL_NEW_WORK_COVER_PREFETCH_PARALLELISM marketingRequests=0"
        )
        return OfficialCoverWaitStats(
            titleNos = targets.size,
            alreadyReady = alreadyReady,
            trustedReady = trustedReady,
            scheduled = scheduled,
        )
    }

    private fun prewarmOfficialNewWorkCoversForPopularAsync(elements: List<Element>): OfficialCoverWaitStats {
        val targets = selectedOfficialNewWorkTargets(elements).take(OFFICIAL_NEW_WORK_COVER_PREFETCH_LIMIT)
        if (targets.isEmpty()) return OfficialCoverWaitStats()
        val visibleTargets = visibleFirstOfficialCoverTargets(targets)
        val visibleTitleNos = visibleTargets.map { it.titleNo }.toSet()
        val backgroundTargets = targets.filter { it.titleNo !in visibleTitleNos }

        var alreadyReady = 0
        var trustedReady = 0
        var demandScheduled = 0
        var prefetchScheduled = 0
        val demandTitleNos = mutableListOf<String>()
        val prefetchTitleNos = mutableListOf<String>()

        fun scheduleTarget(target: OfficialNewWorkCoverTarget, priority: OfficialCoverFetchPriority) {
            when {
                verifiedDetailOfficialCoverForTitleNo(target.titleNo).isNotBlank() -> alreadyReady += 1
                trustedRuntimeOfficialCoverForTitleNo(target.titleNo).isNotBlank() -> trustedReady += 1
                else -> {
                    val state = ensureOfficialNewWorkCoverFetchStarted(target.titleNo, target.detailUrl, priority)
                    if (state != null) {
                        if (priority == OfficialCoverFetchPriority.PREFETCH) {
                            prefetchScheduled += 1
                            prefetchTitleNos += target.titleNo
                        } else {
                            demandScheduled += 1
                            demandTitleNos += target.titleNo
                        }
                    }
                }
            }
        }

        visibleTargets.forEach { scheduleTarget(it, OfficialCoverFetchPriority.VISIBLE) }
        backgroundTargets.forEach { scheduleTarget(it, OfficialCoverFetchPriority.PREFETCH) }

        dlog(
            "officialCoverWarmupSchedule titleNos=${targets.joinToString("|") { it.titleNo }} " +
                "visibleTitleNos=${visibleTargets.joinToString("|") { it.titleNo }} " +
                "backgroundTitleNos=${backgroundTargets.joinToString("|") { it.titleNo }} " +
                "alreadyReady=$alreadyReady trustedReady=$trustedReady " +
                "demandScheduled=$demandScheduled prefetchScheduled=$prefetchScheduled " +
                "demandTitleNos=${demandTitleNos.joinToString("|")} " +
                "prefetchTitleNos=${prefetchTitleNos.joinToString("|")} " +
                "limit=$OFFICIAL_NEW_WORK_COVER_PREFETCH_LIMIT " +
                "visibleLimit=$OFFICIAL_NEW_WORK_COVER_VISIBLE_PREFETCH_LIMIT " +
                "demandParallelism=$OFFICIAL_NEW_WORK_COVER_DEMAND_PARALLELISM " +
                "prefetchParallelism=$OFFICIAL_NEW_WORK_COVER_PREFETCH_PARALLELISM " +
                "totalParallelism=$OFFICIAL_NEW_WORK_COVER_FETCH_PARALLELISM " +
                "mode=demand-prefetch-strict-official wait=0ms strictVerifiedOnly=true unverifiedSubstituteUsed=false marketingRequests=0"
        )
        return OfficialCoverWaitStats(
            titleNos = targets.size,
            alreadyReady = alreadyReady,
            trustedReady = trustedReady,
            scheduled = demandScheduled + prefetchScheduled,
        )
    }

    private fun visibleFirstOfficialCoverTargets(targets: List<OfficialNewWorkCoverTarget>): List<OfficialNewWorkCoverTarget> {
        val result = linkedMapOf<String, OfficialNewWorkCoverTarget>()
        fun add(target: OfficialNewWorkCoverTarget) {
            if (result.size < OFFICIAL_NEW_WORK_COVER_VISIBLE_PREFETCH_LIMIT) {
                result.putIfAbsent(target.titleNo, target)
            }
        }

        // 轮播第一张和新作卡属于更可能被立刻看到的目标，走 demand/visible 队列。
        targets.filter { it.origin == "popular-banner" }.take(1).forEach(::add)
        targets.filter { it.origin == "popular-common-card" }.forEach(::add)
        targets.sortedBy { it.rawIndex }.forEach(::add)
        return result.values.toList()
    }

    private fun prepareOfficialNewWorkCoversForPopular(elements: List<Element>): OfficialCoverWaitStats {
        val targets = selectedOfficialNewWorkTargets(elements)
        if (targets.isEmpty()) return OfficialCoverWaitStats()
        var alreadyReady = 0
        val states = mutableListOf<OfficialNewWorkCoverFetchState>()
        targets.forEach { target ->
            if (verifiedDetailOfficialCoverForTitleNo(target.titleNo).isNotBlank()) {
                alreadyReady += 1
            } else {
                ensureOfficialNewWorkCoverFetchStarted(target.titleNo, target.detailUrl, OfficialCoverFetchPriority.DEMAND)?.let { states.add(it) }
            }
        }

        val waitStartedAt = System.currentTimeMillis()
        val primaryWaitMs = awaitOfficialNewWorkCoverStates(states, OFFICIAL_NEW_WORK_COVER_PRIMARY_WAIT_MS)
        val incompleteAfterPrimary = states.count { !it.completed }
        val tailWaitMs = if (incompleteAfterPrimary > 0) {
            awaitOfficialNewWorkCoverStates(states.filter { !it.completed }, OFFICIAL_NEW_WORK_COVER_TAIL_WAIT_MS)
        } else {
            0L
        }
        val waitedMs = System.currentTimeMillis() - waitStartedAt
        val success = targets.count { verifiedDetailOfficialCoverForTitleNo(it.titleNo).isNotBlank() }
        val missing = targets.size - success
        dlog(
            "officialNewWorkCoverWait titleNos=${targets.joinToString("|") { it.titleNo }} " +
                "alreadyReady=$alreadyReady scheduled=${states.size} success=$success missing=$missing " +
                "waited=${waitedMs}ms primaryWait=${primaryWaitMs}ms tailWait=${tailWaitMs}ms " +
                "primaryTimeoutMs=$OFFICIAL_NEW_WORK_COVER_PRIMARY_WAIT_MS " +
                "tailTimeoutMs=$OFFICIAL_NEW_WORK_COVER_TAIL_WAIT_MS " +
                "parallelism=$OFFICIAL_NEW_WORK_COVER_FETCH_PARALLELISM mode=legacy-fast-meta-unused marketingRequests=0"
        )
        return OfficialCoverWaitStats(
            titleNos = targets.size,
            alreadyReady = alreadyReady,
            scheduled = states.size,
            success = success,
            missing = missing,
            waitedMs = waitedMs,
        )
    }

    private fun awaitOfficialNewWorkCoverStates(states: List<OfficialNewWorkCoverFetchState>, timeoutMs: Long): Long {
        if (states.isEmpty() || timeoutMs <= 0L) return 0L
        val startedAt = System.currentTimeMillis()
        states.forEach { state ->
            val elapsed = System.currentTimeMillis() - startedAt
            val remaining = timeoutMs - elapsed
            if (remaining > 0 && !state.completed) {
                runCatching { state.latch.await(remaining, TimeUnit.MILLISECONDS) }
            }
        }
        return System.currentTimeMillis() - startedAt
    }

    private fun ensureOfficialNewWorkCoverFetchStarted(
        titleNo: String,
        detailUrl: String,
        priority: OfficialCoverFetchPriority = OfficialCoverFetchPriority.DEMAND,
    ): OfficialNewWorkCoverFetchState? {
        val key = titleNo.trim()
        if (key.isBlank()) return null
        if (verifiedDetailOfficialCoverForTitleNo(key).isNotBlank()) return null
        val now = System.currentTimeMillis()
        val state = synchronized(officialNewWorkCoverFetchStates) {
            pruneOfficialNewWorkCoverFetchStatesLocked(now)
            val existing = officialNewWorkCoverFetchStates[key]
            if (existing != null && (!existing.completed || (!existing.failed && now - existing.createdAt <= OFFICIAL_NEW_WORK_COVER_FETCH_RETRY_TTL_MS))) {
                existing
            } else {
                OfficialNewWorkCoverFetchState(key, detailUrl).also { officialNewWorkCoverFetchStates[key] = it }
            }
        }
        submitOfficialNewWorkCoverFetch(state, priority)
        return state
    }

    private fun submitOfficialNewWorkCoverFetch(
        state: OfficialNewWorkCoverFetchState,
        priority: OfficialCoverFetchPriority,
    ) {
        synchronized(state) {
            if (state.completed) return
            val isDemandLike = priority == OfficialCoverFetchPriority.DEMAND || priority == OfficialCoverFetchPriority.VISIBLE
            if (isDemandLike) {
                if (!state.demandSubmitted && !state.running) {
                    state.demandSubmitted = true
                    state.submitted = true
                    state.fetchKind = if (priority == OfficialCoverFetchPriority.VISIBLE) "visible-inflight" else "demand-inflight"
                    officialNewWorkCoverDemandExecutor.execute { fetchOfficialNewWorkCoverFromDetail(state.titleNo, state) }
                }
            } else {
                if (!state.prefetchSubmitted && !state.demandSubmitted && !state.running) {
                    state.prefetchSubmitted = true
                    state.submitted = true
                    state.fetchKind = "prefetch-inflight"
                    officialNewWorkCoverPrefetchExecutor.execute { fetchOfficialNewWorkCoverFromDetail(state.titleNo, state) }
                }
            }
        }
    }

    private fun pruneOfficialNewWorkCoverFetchStatesLocked(now: Long) {
        val iterator = officialNewWorkCoverFetchStates.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val state = entry.value
            if (state.completed && now - state.createdAt > OFFICIAL_NEW_WORK_COVER_FETCH_RETRY_TTL_MS) {
                iterator.remove()
            }
        }
        while (officialNewWorkCoverFetchStates.size > OFFICIAL_NEW_WORK_COVER_FETCH_MAX_STATES) {
            val firstKey = officialNewWorkCoverFetchStates.keys.firstOrNull() ?: break
            officialNewWorkCoverFetchStates.remove(firstKey)
        }
    }

    private fun fetchOfficialNewWorkCoverFromDetail(titleNo: String, state: OfficialNewWorkCoverFetchState) {
        val fetchKind = synchronized(state) {
            if (state.completed || state.running) return
            state.running = true
            state.fetchKind
        }
        val startedAt = System.currentTimeMillis()
        var failed = false
        val requestUrl = state.detailUrl.ifBlank { "$baseUrl/episodeList?titleNo=$titleNo" }
        try {
            val requestHeaders = headersBuilder()
                .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .set("X-Mihon-Official-Cover-Meta-Only", "1")
                .build()
            val request = GET(requestUrl, requestHeaders)
            val call = client.newCall(request)
            call.timeout().timeout(OFFICIAL_NEW_WORK_COVER_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                val responseAt = System.currentTimeMillis()
                val scan = scanOfficialCoverMetaFast(response, titleNo)
                val scanEndedAt = System.currentTimeMillis()
                if (scan.coverUrl.isNotBlank()) {
                    val remembered = rememberVerifiedDetailOfficialCover(titleNo, scan.coverUrl)
                    state.coverUrl = remembered
                    state.failureReason = if (remembered.isNotBlank()) "" else "unverified"
                    dlog(
                        "officialNewWorkCoverFetchResult titleNo=$titleNo coverPresent=${remembered.isNotBlank()} " +
                            "source=${scan.source} fetchKind=$fetchKind code=${response.code} requestedUrl=$requestUrl " +
                            "finalUrl=${response.request.url} ttfb=${responseAt - startedAt}ms " +
                            "readMeta=${scanEndedAt - responseAt}ms elapsed=${scanEndedAt - startedAt}ms " +
                            "bytesScanned=${scan.bytesScanned} earlyClose=${scan.earlyClose} " +
                            "fullJsoup=false cacheWrite=false directDetail=${!requestUrl.contains("/episodeList")} " +
                            "marketingRequests=0"
                    )
                    failed = remembered.isBlank()
                } else {
                    failed = true
                    state.failureReason = "noMeta:${scan.source.ifBlank { "detail-meta-fast-unverified" }}"
                    dlog(
                        "officialNewWorkCoverFetchResult titleNo=$titleNo coverPresent=false " +
                            "source=${scan.source.ifBlank { "detail-meta-fast-unverified" }} code=${response.code} " +
                            "requestedUrl=$requestUrl finalUrl=${response.request.url} " +
                            "ttfb=${responseAt - startedAt}ms readMeta=${scanEndedAt - responseAt}ms " +
                            "elapsed=${scanEndedAt - startedAt}ms bytesScanned=${scan.bytesScanned} " +
                            "earlyClose=${scan.earlyClose} fullJsoup=false cacheWrite=false marketingRequests=0"
                    )
                }
            }
        } catch (e: Exception) {
            failed = true
            state.failureReason = "networkError:${e.javaClass.simpleName}"
            wlog(
                "officialNewWorkCoverFetchFailed titleNo=$titleNo " +
                    "requestedUrl=$requestUrl elapsed=${System.currentTimeMillis() - startedAt}ms error=${e.javaClass.simpleName}",
                e,
            )
        } finally {
            synchronized(state) {
                state.failed = failed
                state.completed = true
                state.running = false
            }
            state.latch.countDown()
        }
    }

    private fun scanOfficialCoverMetaFast(response: Response, titleNo: String): FastOfficialCoverScanResult {
        val body = response.body ?: return FastOfficialCoverScanResult("", 0, false, "detail-meta-fast-no-body")
        val input = body.byteStream()
        val buffer = ByteArray(8 * 1024)
        val html = StringBuilder()
        var bytesScanned = 0
        while (bytesScanned < OFFICIAL_NEW_WORK_COVER_META_SCAN_MAX_BYTES) {
            val maxRead = minOf(buffer.size, OFFICIAL_NEW_WORK_COVER_META_SCAN_MAX_BYTES - bytesScanned)
            val read = input.read(buffer, 0, maxRead)
            if (read <= 0) break
            bytesScanned += read
            html.append(String(buffer, 0, read, Charsets.UTF_8))
            val cover = verifiedDetailOfficialCoverFromHtmlSnippet(html.toString())
            if (cover.isNotBlank()) {
                return FastOfficialCoverScanResult(cover, bytesScanned, true, "detail-meta-fast-og-twitter-cdn-sns")
            }
            if (html.contains("</head>", ignoreCase = true)) break
        }
        val cover = verifiedDetailOfficialCoverFromHtmlSnippet(html.toString())
        return FastOfficialCoverScanResult(
            cover,
            bytesScanned,
            cover.isNotBlank(),
            if (cover.isNotBlank()) "detail-meta-fast-og-twitter-cdn-sns" else "detail-meta-fast-unverified",
        )
    }

    private fun verifiedDetailOfficialCoverFromHtmlSnippet(html: String): String {
        if (html.isBlank()) return ""
        var ogImage = ""
        var twitterImage = ""
        Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            val tag = match.value
            val property = htmlAttributeValue(tag, "property").lowercase(Locale.ROOT)
            val name = htmlAttributeValue(tag, "name").lowercase(Locale.ROOT)
            val content = htmlAttributeValue(tag, "content")
            if (content.isBlank()) return@forEach
            when {
                property == "og:image" -> ogImage = buildThumbnailUrl(content, cdnBase)
                name == "twitter:image" -> twitterImage = buildThumbnailUrl(content, cdnBase)
            }
        }
        val normalizedOg = stripImageProcessParams(ogImage)
        val normalizedTwitter = stripImageProcessParams(twitterImage)
        return normalizedOg.takeIf {
            it.isNotBlank() &&
                it == normalizedTwitter &&
                isVerifiedDetailOfficialCoverUrl(it)
        }.orEmpty()
    }

    private fun htmlAttributeValue(tag: String, name: String): String {
        val regex = Regex("""\b${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
        val match = regex.find(tag) ?: return ""
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    private fun clearLegacyNewWorkPersistentCaches() {
        if (legacyNewWorkPersistentCacheCleared) return
        synchronized(legacyNewWorkPersistentCacheClearLock) {
            if (legacyNewWorkPersistentCacheCleared) return
            preferences.edit()
                .remove(PREF_NEW_PAGE_TITLE_CACHE)
                .remove(PREF_NEW_WORK_COVER_CACHE)
                .apply()
            legacyNewWorkPersistentCacheCleared = true
            dlog("legacyNewWorkPersistentCacheCleared")
        }
    }

    private fun scheduleLegacyNewWorkPersistentCachesClear() {
        if (legacyNewWorkPersistentCacheCleared) return
        Thread({
            clearLegacyNewWorkPersistentCaches()
        }, "DongmanLegacyNewWorkCacheClear").start()
    }

    private fun rememberOfficialMangaMetaFromList(titleNo: String?, title: String, thumbnailUrl: String, source: String): OfficialMangaMeta? {
        if (titleNo.isNullOrBlank()) return null
        if (!isTrustedOfficialMetaSource(source)) return null
        return rememberOfficialMangaMeta(titleNo, title, thumbnailUrl, source)
    }

    private fun rememberOfficialMangaMeta(titleNo: String?, title: String, thumbnailUrl: String, source: String): OfficialMangaMeta? {
        val key = titleNo?.trim().orEmpty()
        if (key.isBlank()) return null
        val normalizedTitle = title.trim()
        val normalizedThumbnail = thumbnailUrl.trim()
        if (normalizedTitle.isBlank() && normalizedThumbnail.isBlank()) return null
        val priority = officialMetaSourcePriority(source)
        if (priority <= 0) return null
        synchronized(officialMangaMetaByTitleNo) {
            val existing = officialMangaMetaByTitleNo[key]
            if (existing != null && existing.priority > priority) return existing
            val merged = OfficialMangaMeta(
                title = normalizedTitle.ifBlank { existing?.title.orEmpty() },
                thumbnailUrl = normalizedThumbnail.ifBlank { existing?.thumbnailUrl.orEmpty() },
                source = source,
                priority = priority,
                createdAt = System.currentTimeMillis(),
            )
            officialMangaMetaByTitleNo[key] = merged
            return merged
        }
    }

    private fun getOfficialMangaMeta(titleNo: String?): OfficialMangaMeta? {
        val key = titleNo?.trim().orEmpty()
        if (key.isBlank()) return null
        return synchronized(officialMangaMetaByTitleNo) { officialMangaMetaByTitleNo[key] }
    }

    private fun isTrustedOfficialMetaSource(source: String): Boolean {
        return source == "detail" ||
            source == "detail-marketing" ||
            source == "detail-memory" ||
            source == "popular-ranking" ||
            source == "popular-genre-category" ||
            source == "popular-banner-title" ||
            source == "new-page-title" ||
            source.startsWith("dailySchedule") ||
            source.startsWith("mangaList") ||
            source.startsWith("genreBulk") ||
            source.startsWith("search")
    }

    private fun officialMetaSourcePriority(source: String): Int {
        return when {
            source == "detail" -> 1000
            source.startsWith("dailySchedule") -> 900
            source.startsWith("mangaList") -> 850
            source.startsWith("genreBulk") -> 825
            source == "popular-ranking" -> 780
            source == "popular-genre-category" -> 760
            source == "popular-banner-title" -> 740
            source.startsWith("search") -> 700
            else -> 0
        }
    }

    private fun isVerifiedDetailOfficialCoverUrl(rawUrl: String): Boolean {
        val normalized = stripImageProcessParams(rawUrl).lowercase(Locale.ROOT)
        return normalized.startsWith("https://cdn-sns.dongmanmanhua.cn/") ||
            normalized.startsWith("http://cdn-sns.dongmanmanhua.cn/")
    }

    private fun verifiedDetailOfficialCoverFromDocument(document: org.jsoup.nodes.Document): String {
        val ogImage = buildThumbnailUrl(
            document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty(),
            cdnBase,
        )
        val twitterImage = buildThumbnailUrl(
            document.selectFirst("meta[name=twitter:image]")?.attr("content").orEmpty(),
            cdnBase,
        )
        val normalizedOg = stripImageProcessParams(ogImage)
        val normalizedTwitter = stripImageProcessParams(twitterImage)
        return normalizedOg.takeIf {
            it.isNotBlank() &&
                it == normalizedTwitter &&
                isVerifiedDetailOfficialCoverUrl(it)
        }.orEmpty()
    }

    private fun rememberVerifiedDetailOfficialCover(titleNo: String?, thumbnailUrl: String): String {
        val key = titleNo?.trim().orEmpty()
        val normalized = stripImageProcessParams(thumbnailUrl)
        if (key.isBlank() || !isVerifiedDetailOfficialCoverUrl(normalized)) return ""
        synchronized(verifiedDetailOfficialCoverByTitleNo) {
            verifiedDetailOfficialCoverByTitleNo[key] = normalized
        }
        return normalized
    }

    private fun hasDetailCoverKey(thumbnailUrl: String): Boolean {
        return thumbnailUrl.contains("dongman_detail_cover=1")
    }

    private fun detailCoverCacheKeyUrl(thumbnailUrl: String): String {
        val normalized = stripImageProcessParams(thumbnailUrl.trim())
        if (normalized.isBlank()) return ""
        if (hasDetailCoverKey(normalized)) return normalized
        val separator = if (normalized.contains("?")) "&" else "?"
        return normalized + separator + "dongman_detail_cover=1"
    }

    private fun officialListThumbnailForUi(thumbnailUrl: String): String {
        val normalized = stripImageProcessParams(thumbnailUrl.trim())
        if (normalized.isBlank()) return ""
        // 列表封面只做 CDN/OSS 规范化，绝不写入详情专用 key。
        // Mihon 会按列表卡片尺寸加载 thumbnail_url；把详情 key 提前交给列表会污染详情大图缓存。
        return normalizeCoverKeyForCompare(normalized)
    }

    private fun verifiedDetailOfficialCoverForTitleNo(titleNo: String?): String {
        val key = titleNo?.trim().orEmpty()
        if (key.isBlank()) return ""
        return synchronized(verifiedDetailOfficialCoverByTitleNo) {
            verifiedDetailOfficialCoverByTitleNo[key].orEmpty()
        }
    }

    private fun rememberOfficialCoverDetailUrl(titleNo: String?, detailUrl: String): String {
        val key = titleNo?.trim().orEmpty()
        val normalized = detailUrl.trim()
        if (key.isBlank() || normalized.isBlank()) return ""
        synchronized(officialCoverDetailUrlByTitleNo) {
            officialCoverDetailUrlByTitleNo[key] = normalized
        }
        return normalized
    }

    private fun officialCoverDetailUrlForTitleNo(titleNo: String?): String {
        val key = titleNo?.trim().orEmpty()
        if (key.isBlank()) return ""
        return synchronized(officialCoverDetailUrlByTitleNo) {
            officialCoverDetailUrlByTitleNo[key].orEmpty()
        }
    }

    private fun searchMangaFromElement(element: Element, origin: String = "search-html"): SManga = SManga.create().apply {
        val rawHref = element.attr("href")
        val href = element.absUrl("href").ifEmpty { rawHref }
        val cleanPath = cleanMangaDetailPath(href)
        val titleNo = titleNoFromUrl(cleanPath)
            ?: titleNoFromUrl(href)
            ?: titleNoFromUrl(rawHref)
            ?: element.attr("data-title-no").takeIf { it.isNotBlank() }
            ?: scEventParameterValue(element, "recommended_titleNo")
        val identityPath = canonicalMangaIdentityPath(rawUrl = href, titleNoHint = titleNo)
        url = identityPath
        title = element.selectFirst(".info .subj .ellipsis, p.subj .ellipsis")?.text() ?: ""
        thumbnail_url = extractThumbnailUrl(element, origin, titleNo)
        logIdentityProbe(origin, titleNo, title, rawHref, href, url)
        if (VERBOSE_LIST_LOG) {
            dlog("searchMangaFromElement rawHref=$rawHref absHref=$href cleanPath=$cleanPath storedUrl=$url titleNo=$titleNo title=$title")
        }
    }


    private fun logIdentityProbe(
        origin: String,
        titleNo: String?,
        title: String,
        rawHref: String,
        absHref: String,
        storedUrl: String,
    ) {
        val normalizedStored = normalizeMangaPath(storedUrl).trim()
        val titleNoValue = titleNo?.takeIf { it.isNotBlank() }
            ?: titleNoFromUrl(normalizedStored)
            ?: titleNoFromUrl(absHref)
            ?: titleNoFromUrl(rawHref)
            ?: return
        val requestPath = canonicalDetailRequestPath(normalizedStored)
        var shouldLogSeen = false
        var shouldLogConflict = false
        var knownUrls = ""
        synchronized(identityProbeUrlsByTitleNo) {
            val urls = identityProbeUrlsByTitleNo.getOrPut(titleNoValue) { linkedSetOf() }
            val storedIsClean = isCleanWorkPagePath(normalizedStored)
            if (storedIsClean) {
                urls.removeAll { it.startsWith("/episodeList?titleNo=$titleNoValue") }
            }
            val shouldTrack = storedIsClean || urls.none(::isCleanWorkPagePath)
            val added = if (shouldTrack) urls.add(normalizedStored) else false
            knownUrls = urls.joinToString(" | ")
            if (added && identityProbeSeenLogCount < IDENTITY_PROBE_SEEN_LOG_LIMIT) {
                identityProbeSeenLogCount += 1
                shouldLogSeen = true
            }
            if (added && urls.size > 1 && identityProbeConflictLogCount < IDENTITY_PROBE_CONFLICT_LOG_LIMIT) {
                identityProbeConflictLogCount += 1
                shouldLogConflict = true
            }
        }
        if (shouldLogConflict) {
            dlog(
                "identityProbe CONFLICT titleNo=$titleNoValue title=$title origin=$origin " +
                    "storedUrl=$normalizedStored requestPath=$requestPath rawHref=$rawHref absHref=$absHref knownUrls=$knownUrls"
            )
        } else if (shouldLogSeen && DEBUG_IDENTITY_SEEN_LOG) {
            dlog(
                "identityProbe seen titleNo=$titleNoValue title=$title origin=$origin " +
                    "storedUrl=$normalizedStored requestPath=$requestPath rawHref=$rawHref absHref=$absHref"
            )
        }
    }

    private fun listInflightCoalesceKey(request: Request): String? {
        if (request.method != "GET") return null
        val url = request.url
        if (url.host != "m.dongmanmanhua.cn") return null
        val path = url.encodedPath
        if (path == LOCAL_GENRE_CACHE_PATH || path == LOCAL_UPDATE_CACHE_PATH) return null
        if (path == "/episodeList" || path.contains("/list")) return null
        if (path.startsWith("/member/")) return null
        return url.newBuilder()
            .fragment(null)
            .build()
            .toString()
    }

    private fun listInflightResponse(request: Request, state: ListInflightState): Response? {
        val bodyString = state.body ?: return null
        val contentType = state.headers["Content-Type"].orEmpty().ifBlank { "text/html;charset=UTF-8" }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(state.code)
            .message(state.message.ifBlank { "OK" })
            .headers(state.headers)
            .body(bodyString.toResponseBody(contentType.toMediaType()))
            .build()
    }

    private fun executeListRequestWithInflightCoalesce(
        key: String,
        request: Request,
        chain: okhttp3.Interceptor.Chain,
    ): Response {
        val now = System.currentTimeMillis()
        val state: ListInflightState
        val isOwner: Boolean
        synchronized(listInflight) {
            val existing = listInflight[key]
            if (existing != null && now - existing.createdAt <= listInflightJoinWindowMs) {
                state = existing
                isOwner = false
            } else {
                state = ListInflightState(now)
                listInflight[key] = state
                isOwner = true
            }
        }
        dlog(
            "listInflightProbe action=${if (isOwner) "owner" else "join"} key=$key " +
                "age=${System.currentTimeMillis() - state.createdAt}ms url=${request.url}"
        )

        if (!isOwner) {
            val startedAt = System.currentTimeMillis()
            dlog("listInflightProbe action=wait key=$key age=${System.currentTimeMillis() - state.createdAt}ms url=${request.url}")
            val finished = runCatching {
                state.latch.await(listInflightWaitMs, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            listInflightResponse(request, state)?.let { response ->
                dlog("listInflightJoined key=$key waited=${System.currentTimeMillis() - startedAt}ms code=${state.code}")
                return response
            }
            if (finished && state.completed) {
                val status = if (state.failed) "ownerFailed" else "ownerCompletedNoBody"
                wlog("listInflightDirectProceed key=$key status=$status waited=${System.currentTimeMillis() - startedAt}ms")
            } else {
                wlog("listInflightTimeout key=$key waited=${System.currentTimeMillis() - startedAt}ms")
            }
            return chain.proceed(request)
        }

        var ownerFailed = false
        try {
            dlog("listInflightProbe action=ownerNetwork key=$key url=${request.url}")
            val response = chain.proceed(request)
            val contentType = response.body.contentType()?.toString().orEmpty()
                .ifBlank { response.header("Content-Type").orEmpty().ifBlank { "text/html;charset=UTF-8" } }
            val bodyString = response.body.string()
            state.code = response.code
            state.message = response.message
            state.headers = response.headers.newBuilder()
                .set("Content-Type", contentType)
                .build()
            state.body = bodyString
            ownerFailed = !response.isSuccessful || bodyString.isBlank()
            if (ownerFailed) {
                wlog("listInflightOwnerNoBody key=$key code=${response.code} bytes=${bodyString.length}")
            } else {
                dlog("listInflightOwnerPut key=$key bytes=${bodyString.length} code=${response.code}")
            }
            return response.newBuilder()
                .body(bodyString.toResponseBody(contentType.toMediaType()))
                .build()
        } catch (e: Exception) {
            ownerFailed = true
            throw e
        } finally {
            state.completed = true
            state.failed = ownerFailed
            state.latch.countDown()
            synchronized(listInflight) {
                if (listInflight[key] === state) listInflight.remove(key)
            }
        }
    }

    private fun detailHtmlCacheKey(request: Request): String? {
        if (request.header("X-Mihon-Official-Cover-Meta-Only") == "1") return null
        val url = request.url
        if (url.encodedPath != "/episodeList") return null
        val titleNo = url.queryParameter("titleNo")?.trim().orEmpty()
        return titleNo.takeIf { it.isNotEmpty() }
    }

    private fun getValidDetailHtmlCache(titleNo: String): DetailHtmlCache? {
        if (titleNo.isBlank()) return null
        val now = System.currentTimeMillis()
        return synchronized(detailHtmlCache) {
            val cache = detailHtmlCache[titleNo] ?: return@synchronized null
            if (now - cache.createdAt <= detailHtmlCacheTtlMs) {
                cache
            } else {
                detailHtmlCache.remove(titleNo)
                null
            }
        }
    }

    private fun putDetailHtmlCache(titleNo: String, body: String, contentType: String) {
        if (titleNo.isBlank() || body.isBlank()) return
        synchronized(detailHtmlCache) {
            detailHtmlCache[titleNo] = DetailHtmlCache(titleNo, System.currentTimeMillis(), body, contentType)
            while (detailHtmlCache.size > detailHtmlCacheMaxEntries) {
                val firstKey = detailHtmlCache.keys.firstOrNull() ?: break
                detailHtmlCache.remove(firstKey)
            }
        }
        dlog("detailHtmlCachePut titleNo=$titleNo bytes=${body.length}")
    }

    private fun cachedDetailHtmlResponse(request: Request, cache: DetailHtmlCache): Response {
        val mediaType = cache.contentType.ifBlank { "text/html;charset=UTF-8" }.toMediaType()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .headers(Headers.Builder().set("Content-Type", cache.contentType.ifBlank { "text/html;charset=UTF-8" }).build())
            .body(cache.body.toResponseBody(mediaType))
            .build()
    }

    private fun executeDetailHtmlRequestWithCache(
        titleNo: String,
        request: Request,
        chain: okhttp3.Interceptor.Chain,
    ): Response {
        getValidDetailHtmlCache(titleNo)?.let { cache ->
            dlog("detailHtmlCacheHit titleNo=$titleNo age=${System.currentTimeMillis() - cache.createdAt}ms url=${request.url}")
            dlog("detailRefreshProbe action=html titleNo=$titleNo source=cacheHit cacheAge=${System.currentTimeMillis() - cache.createdAt}ms owner=false url=${request.url}")
            return cachedDetailHtmlResponse(request, cache)
        }

        val state: DetailHtmlInflightState
        val isOwner: Boolean
        synchronized(detailHtmlInflight) {
            val existing = detailHtmlInflight[titleNo]
            if (existing == null) {
                state = DetailHtmlInflightState()
                detailHtmlInflight[titleNo] = state
                isOwner = true
            } else {
                state = existing
                isOwner = false
            }
        }

        if (!isOwner) {
            val startedAt = System.currentTimeMillis()
            dlog("detailHealthProbe html action=wait titleNo=$titleNo url=${request.url}")
            dlog("detailRefreshProbe action=html titleNo=$titleNo source=inflightWait owner=false url=${request.url}")
            val cacheBeforeWait = getValidDetailHtmlCache(titleNo)
            if (cacheBeforeWait != null) {
                dlog("detailHtmlCacheHit titleNo=$titleNo age=${System.currentTimeMillis() - cacheBeforeWait.createdAt}ms url=${request.url}")
                dlog("detailRefreshProbe action=html titleNo=$titleNo source=cacheHitBeforeWait cacheAge=${System.currentTimeMillis() - cacheBeforeWait.createdAt}ms owner=false url=${request.url}")
                return cachedDetailHtmlResponse(request, cacheBeforeWait)
            }

            val finished = runCatching {
                state.latch.await(detailHtmlInflightWaitMs, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)

            getValidDetailHtmlCache(titleNo)?.let { cache ->
                dlog("detailHtmlInflightJoined titleNo=$titleNo waited=${System.currentTimeMillis() - startedAt}ms url=${request.url}")
                dlog("detailRefreshProbe action=html titleNo=$titleNo source=inflightJoined waited=${System.currentTimeMillis() - startedAt}ms owner=false url=${request.url}")
                return cachedDetailHtmlResponse(request, cache)
            }

            if (finished && state.completed) {
                val status = if (state.failed) "ownerFailed" else "ownerCompletedNoCache"
                wlog("detailHtmlInflightDirectProceed titleNo=$titleNo status=$status waited=${System.currentTimeMillis() - startedAt}ms url=${request.url}")
            } else {
                wlog("detailHtmlInflightTimeout titleNo=$titleNo waited=${System.currentTimeMillis() - startedAt}ms url=${request.url}")
            }
            return chain.proceed(request)
        }

        var ownerFailed = false
        try {
            dlog("detailHealthProbe html action=owner titleNo=$titleNo url=${request.url}")
            dlog("detailRefreshProbe action=html titleNo=$titleNo source=networkOwner owner=true url=${request.url}")
            val response = chain.proceed(request)
            val contentType = response.body.contentType()?.toString().orEmpty().ifBlank { "text/html;charset=UTF-8" }
            val bodyString = response.body.string()
            val cacheable = response.isSuccessful && bodyString.isNotBlank()
            if (cacheable) {
                putDetailHtmlCache(titleNo, bodyString, contentType)
            } else {
                ownerFailed = true
                wlog("detailHtmlOwnerNoCache titleNo=$titleNo code=${response.code} bytes=${bodyString.length} url=${request.url}")
            }
            return response.newBuilder()
                .body(bodyString.toResponseBody(contentType.toMediaType()))
                .build()
        } catch (e: Exception) {
            ownerFailed = true
            throw e
        } finally {
            state.completed = true
            state.failed = ownerFailed
            state.latch.countDown()
            synchronized(detailHtmlInflight) {
                if (detailHtmlInflight[titleNo] === state) detailHtmlInflight.remove(titleNo)
            }
        }
    }

    private fun firstNonBlankJsonString(item: org.json.JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            val value = item.optString(key, "").trim()
            if (value.isNotEmpty() && value != "null") return value
        }
        return ""
    }

    private fun normalizeSeoSegment(value: String): String {
        return value.trim().trim('/').substringBefore('?').substringBefore('&')
    }

    private fun workPagePathFromSeo(titleNo: String, genreSeo: String?, groupName: String?): String {
        val genre = normalizeSeoSegment(genreSeo.orEmpty())
        val group = normalizeSeoSegment(groupName.orEmpty())
        if (titleNo.isBlank() || genre.isBlank() || group.isBlank()) return ""
        return "/$genre/$group/list?title_no=$titleNo"
    }

    private fun isCleanWorkPagePath(path: String): Boolean {
        return path.contains("/list?title_no=") && !path.substringBefore("?").endsWith("/episodeList")
    }

    private fun ensureCanonicalMangaIdentityStoreLoadedLocked() {
        if (canonicalMangaUrlStoreLoaded) return
        canonicalMangaUrlStoreLoaded = true
        val json = preferences.getString(PREF_CANONICAL_IDENTITY_MAP, "").orEmpty()
        if (json.isBlank()) return
        try {
            val obj = JSONObject(json)
            val keys = obj.keys()
            var loaded = 0
            while (keys.hasNext()) {
                val titleNo = keys.next().trim()
                val path = obj.optString(titleNo, "").trim()
                if (titleNo.isNotBlank() && isCleanWorkPagePath(path) && !canonicalMangaUrlByTitleNo.containsKey(titleNo)) {
                    canonicalMangaUrlByTitleNo[titleNo] = path
                    loaded += 1
                }
            }
            if (loaded > 0) dlog("identityCanonicalStoreLoad count=$loaded")
        } catch (e: Exception) {
            wlog("identityCanonicalStoreLoad failed", e)
        }
    }

    private fun scheduleCanonicalMangaIdentityStoreLoadAsync() {
        synchronized(canonicalMangaUrlByTitleNo) {
            if (canonicalMangaUrlStoreLoaded || canonicalMangaUrlStoreLoadScheduled) return
            canonicalMangaUrlStoreLoadScheduled = true
        }
        Thread({
            synchronized(canonicalMangaUrlByTitleNo) {
                ensureCanonicalMangaIdentityStoreLoadedLocked()
                canonicalMangaUrlStoreLoadScheduled = false
            }
        }, "DongmanCanonicalIdentityLoad").start()
    }

    private fun persistCanonicalMangaIdentityStoreLocked() {
        try {
            val obj = JSONObject()
            canonicalMangaUrlByTitleNo.entries
                .asSequence()
                .filter { it.key.isNotBlank() && isCleanWorkPagePath(it.value) }
                .take(CANONICAL_IDENTITY_STORE_MAX_ENTRIES)
                .forEach { (titleNo, path) -> obj.put(titleNo, path) }
            preferences.edit().putString(PREF_CANONICAL_IDENTITY_MAP, obj.toString()).apply()
        } catch (e: Exception) {
            wlog("identityCanonicalStoreSave failed", e)
        }
    }

    private fun scheduleCanonicalMangaIdentityStorePersistLocked() {
        if (canonicalMangaUrlStorePersistScheduled) return
        canonicalMangaUrlStorePersistScheduled = true
        Thread({
            runCatching { Thread.sleep(CANONICAL_IDENTITY_STORE_PERSIST_DELAY_MS) }
            synchronized(canonicalMangaUrlByTitleNo) {
                persistCanonicalMangaIdentityStoreLocked()
                canonicalMangaUrlStorePersistScheduled = false
            }
        }, "DongmanCanonicalIdentityPersist").start()
    }

    private fun rememberCanonicalMangaIdentity(titleNo: String, path: String): String {
        if (titleNo.isBlank() || !isCleanWorkPagePath(path)) return path
        synchronized(canonicalMangaUrlByTitleNo) {
            ensureCanonicalMangaIdentityStoreLoadedLocked()
            val existing = canonicalMangaUrlByTitleNo[titleNo]
            val shouldWrite = existing.isNullOrBlank() || existing.startsWith("/episodeList")
            if (shouldWrite) {
                canonicalMangaUrlByTitleNo[titleNo] = path
                scheduleCanonicalMangaIdentityStorePersistLocked()
            }
            return canonicalMangaUrlByTitleNo[titleNo].orEmpty().ifBlank { path }
        }
    }

    private fun canonicalMangaIdentityPath(
        rawUrl: String,
        titleNoHint: String? = null,
        genreSeoHint: String? = null,
        groupNameHint: String? = null,
    ): String {
        val cleanPath = cleanMangaDetailPath(rawUrl)
        val titleNo = titleNoHint?.takeIf { it.isNotBlank() }
            ?: titleNoFromUrl(cleanPath)
            ?: titleNoFromUrl(rawUrl)
            ?: ""
        if (titleNo.isBlank()) return cleanPath

        val seoPath = workPagePathFromSeo(titleNo, genreSeoHint, groupNameHint)
        if (seoPath.isNotBlank()) return rememberCanonicalMangaIdentity(titleNo, seoPath)

        if (isCleanWorkPagePath(cleanPath)) return rememberCanonicalMangaIdentity(titleNo, cleanPath)

        synchronized(canonicalMangaUrlByTitleNo) {
            ensureCanonicalMangaIdentityStoreLoadedLocked()
            canonicalMangaUrlByTitleNo[titleNo]?.takeIf { it.isNotBlank() }?.let { knownPath ->
                if (DEBUG_IDENTITY_REUSE_LOG && identityReuseCanonicalLogCount < IDENTITY_PROBE_CONFLICT_LOG_LIMIT) {
                    identityReuseCanonicalLogCount += 1
                    dlog("identityReuseCanonical titleNo=$titleNo storedUrl=$knownPath rawUrl=$rawUrl cleanPath=$cleanPath")
                }
                return knownPath
            }
        }

        if (identityFallbackEpisodeLogCount < IDENTITY_PROBE_CONFLICT_LOG_LIMIT) {
            identityFallbackEpisodeLogCount += 1
            dlog("identityFallbackEpisode titleNo=$titleNo rawUrl=$rawUrl cleanPath=$cleanPath")
        }
        return "/episodeList?titleNo=$titleNo"
    }

    private fun mangaIdentityDedupKey(titleNo: String?, url: String): String {
        return titleNo?.takeIf { it.isNotBlank() }
            ?: titleNoFromUrl(url)
            ?: normalizeMangaPath(url)
    }

    private fun mangaElementDedupKey(element: Element): String {
        val rawHref = element.attr("href")
        val href = element.absUrl("href").ifEmpty { rawHref }
        val titleNo = titleNoFromUrl(href) ?: titleNoFromUrl(rawHref) ?: element.attr("data-title-no")
        return mangaIdentityDedupKey(titleNo, href)
    }

    private fun rememberCanonicalMangaIdentitiesFromElements(elements: Iterable<Element>) {
        elements.forEach { element ->
            val rawHref = element.attr("href")
            if (rawHref.isBlank()) return@forEach
            val href = element.absUrl("href").ifEmpty { rawHref }
            val cleanPath = cleanMangaDetailPath(href)
            val titleNo = titleNoFromUrl(cleanPath)
            ?: titleNoFromUrl(href)
            ?: titleNoFromUrl(rawHref)
            ?: element.attr("data-title-no").takeIf { it.isNotBlank() }
            ?: scEventParameterValue(element, "recommended_titleNo")
            if (!titleNo.isNullOrBlank() && isCleanWorkPagePath(cleanPath)) {
                rememberCanonicalMangaIdentity(titleNo, cleanPath)
            }
        }
    }

    private fun isWorkPageElement(element: Element): Boolean {
        val rawHref = element.attr("href")
        val href = element.absUrl("href").ifEmpty { rawHref }
        return isCleanWorkPagePath(cleanMangaDetailPath(href))
    }

    private fun distinctMangaElementsByIdentity(elements: List<Element>): List<Element> {
        return elements.groupBy(::mangaElementDedupKey).values.map { group ->
            group.firstOrNull(::isWorkPageElement) ?: group.first()
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

    private fun canonicalDetailRequestPath(rawUrl: String): String {
        val cleanPath = cleanMangaDetailPath(rawUrl)
        val titleNo = titleNoFromUrl(cleanPath) ?: titleNoFromUrl(rawUrl) ?: return cleanPath
        return "/episodeList?titleNo=$titleNo"
    }

    private fun isDetailLikePath(path: String): Boolean {
        return path.endsWith("/episodeList") || path.contains("/list")
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

    private fun rememberNormalThumbnail(titleNo: String?, thumbnailUrl: String, source: String) {
        // 不缓存封面。轮播图关闭时只在本次热门解析里优先选用非轮播候选项，避免跨天/跨轮播复用旧封面。
    }

    private fun normalThumbnailForTitleNo(titleNo: String?): String {
        return ""
    }

    private fun rememberUpdateWeekdaysFromElements(weekday: String, elements: List<Element>) {
        val normalizedWeekday = normalizedWeekdayCode(weekday) ?: return
        elements.forEach { element ->
            val rawHref = element.attr("href")
            val href = element.absUrl("href").ifEmpty { rawHref }
            val cleanPath = cleanMangaDetailPath(href)
            val titleNo = titleNoFromUrl(cleanPath)
                ?: titleNoFromUrl(href)
                ?: titleNoFromUrl(rawHref)
                ?: element.attr("data-title-no").takeIf { it.isNotBlank() }
                ?: scEventParameterValue(element, "recommended_titleNo")
            if (titleNo.isNullOrBlank()) return@forEach
            synchronized(updateWeekdaysByTitleNo) {
                updateWeekdaysByTitleNo.getOrPut(titleNo) { linkedSetOf() }.add(normalizedWeekday)
            }
        }
    }

    private fun extractUpdateTag(html: String, titleNo: String? = null): String {
        val fromText = extractUpdateTagFromText(html)
        if (fromText.isNotBlank()) return fromText
        val fromCache = updateTagFromScheduleCache(titleNo)
        if (fromCache.isNotBlank()) return fromCache
        return ""
    }

    private fun extractUpdateTagFromText(html: String): String {
        val patterns = listOf(
            Regex("""每周\s*([周星期礼拜一二三四五六日天、,，/和与及&\+至到~\-—\s]+?)\s*更新"""),
            Regex("""在\s*([周星期礼拜一二三四五六日天、,，/和与及&\+至到~\-—\s]+?)\s*更新"""),
            Regex("""周\s*([一二三四五六日天、,，/和与及&\+至到~\-—\s]+?)\s*更新"""),
        )
        patterns.forEach { pattern ->
            val expression = pattern.find(html)?.groupValues?.getOrNull(1).orEmpty()
            val weekdays = parseChineseWeekdayExpression(expression)
            val tag = updateTagFromWeekdayCodes(weekdays)
            if (tag.isNotBlank()) return tag
        }
        val englishDays = Regex("""\b(MONDAY|TUESDAY|WEDNESDAY|THURSDAY|FRIDAY|SATURDAY|SUNDAY)\b""")
            .findAll(html)
            .map { it.groupValues[1] }
            .toSet()
        if (englishDays.size in 1..5) {
            return updateTagFromWeekdayCodes(englishDays)
        }
        return ""
    }

    private fun parseChineseWeekdayExpression(expression: String): Set<String> {
        val normalized = expression.replace("周", "").replace("星期", "").replace("礼拜", "").replace("天", "日")
        val range = Regex("""([一二三四五六日])\s*(?:至|到|~|-|—)\s*([一二三四五六日])""").find(normalized)
        if (range != null) {
            val start = chineseWeekdayToIndex(range.groupValues[1])
            val end = chineseWeekdayToIndex(range.groupValues[2])
            if (start != null && end != null && start <= end) {
                return (start..end).mapNotNull { weekdayCodeByIndex(it) }.toSet()
            }
        }
        return normalized.mapNotNull { chineseWeekdayToCode(it.toString()) }.toSet()
    }

    private fun updateTagFromScheduleCache(titleNo: String?): String {
        if (titleNo.isNullOrBlank()) return ""
        val weekdays = synchronized(updateWeekdaysByTitleNo) {
            updateWeekdaysByTitleNo[titleNo]?.toSet().orEmpty()
        }
        val tag = updateTagFromWeekdayCodes(weekdays)
        if (tag.isNotBlank()) {
            dlog("updateTagFromScheduleCache titleNo=$titleNo weekdays=${weekdays.joinToString("|")} tag=$tag")
        }
        return tag
    }

    private fun updateTagFromWeekdayCodes(weekdays: Set<String>): String {
        val indexes = weekdays.mapNotNull(::weekdayIndex).distinct().sorted()
        if (indexes.isEmpty()) return ""
        val label = if (indexes.size >= 3 && indexes.zipWithNext().all { (a, b) -> b == a + 1 }) {
            "${weekdayCnByIndex(indexes.first())}至${weekdayCnByIndex(indexes.last())}"
        } else {
            indexes.joinToString("") { weekdayCnByIndex(it) }
        }
        return "每周${label}更新"
    }

    private fun orderedUpdateWeekdaysFrom(startWeekday: String): List<String> {
        val startIndex = weekdayIndex(startWeekday) ?: weekdayIndex(currentWeekdayCode()) ?: 1
        return (0 until 7).mapNotNull { offset ->
            val index = ((startIndex - 1 + offset) % 7) + 1
            weekdayCodeByIndex(index)
        }
    }

    private fun normalizedWeekdayCode(weekday: String): String? {
        return when (weekday.uppercase(Locale.ROOT)) {
            "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY" -> weekday.uppercase(Locale.ROOT)
            else -> null
        }
    }

    private fun weekdayIndex(weekday: String): Int? {
        return when (weekday.uppercase(Locale.ROOT)) {
            "MONDAY" -> 1
            "TUESDAY" -> 2
            "WEDNESDAY" -> 3
            "THURSDAY" -> 4
            "FRIDAY" -> 5
            "SATURDAY" -> 6
            "SUNDAY" -> 7
            else -> null
        }
    }

    private fun weekdayCodeByIndex(index: Int): String? {
        return when (index) {
            1 -> "MONDAY"
            2 -> "TUESDAY"
            3 -> "WEDNESDAY"
            4 -> "THURSDAY"
            5 -> "FRIDAY"
            6 -> "SATURDAY"
            7 -> "SUNDAY"
            else -> null
        }
    }

    private fun weekdayCnByIndex(index: Int): String {
        return when (index) {
            1 -> "一"
            2 -> "二"
            3 -> "三"
            4 -> "四"
            5 -> "五"
            6 -> "六"
            7 -> "日"
            else -> ""
        }
    }

    private fun chineseWeekdayToIndex(value: String): Int? {
        return weekdayIndex(chineseWeekdayToCode(value) ?: return null)
    }

    private fun chineseWeekdayToCode(value: String): String? {
        return when (value) {
            "一" -> "MONDAY"
            "二" -> "TUESDAY"
            "三" -> "WEDNESDAY"
            "四" -> "THURSDAY"
            "五" -> "FRIDAY"
            "六" -> "SATURDAY"
            "日", "天" -> "SUNDAY"
            else -> null
        }
    }

    private fun buildThumbnailUrl(rawUrl: String, base: String = cdnBase): String {
        val url = stripImageProcessParams(rawUrl.trim())
        val built = when {
            url.isBlank() -> ""
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$base$url"
            else -> "$base/$url"
        }
        return normalizeOfficialCdnThumbnailUrl(built)
    }

    private fun normalizeOfficialCdnThumbnailUrl(url: String): String {
        val normalized = stripImageProcessParams(url.trim())
        if (normalized.isBlank()) return ""
        return when {
            normalized.startsWith("https://cdn.dongmanmanhua.cn/") -> {
                "http://cdn-sns.dongmanmanhua.cn/" + normalized.removePrefix("https://cdn.dongmanmanhua.cn/")
            }
            normalized.startsWith("http://cdn.dongmanmanhua.cn/") -> {
                "http://cdn-sns.dongmanmanhua.cn/" + normalized.removePrefix("http://cdn.dongmanmanhua.cn/")
            }
            else -> normalized
        }
    }

    private fun stripImageProcessParams(rawUrl: String): String {
        if (rawUrl.isBlank()) return ""
        return rawUrl
            .substringBefore("?x-oss-process=")
            .substringBefore("&x-oss-process=")
    }

    private fun extractThumbnailUrl(element: Element, origin: String = "", titleNo: String? = null): String {
        if (origin == "popular-banner") {
            return ""
        }

        val img = element.selectFirst("img")
        val rawUrl = img?.attr("data-original")
            ?.ifEmpty { img.attr("data-src") }
            ?.ifEmpty { img.attr("src") }
            ?: ""
        if (rawUrl.isNotBlank() && !rawUrl.contains("/banner/")) {
            val thumbnail = buildThumbnailUrl(rawUrl, cdnBase)
            if (thumbnail.startsWith("http://cdn-sns.dongmanmanhua.cn/") && rawUrl.contains("cdn.dongmanmanhua.cn")) {
                dlog(
                    "thumbnailCdnSnsNormalize origin=$origin titleNo=${titleNo.orEmpty()} " +
                        "raw=$rawUrl final=$thumbnail"
                )
            }
            return thumbnail
        }

        val style = element.selectFirst("[style*=background]")?.attr("style").orEmpty()
        val match = Regex("""url\(['"]?([^)'"]+)['"]?\)""").find(style)
        val styleUrl = match?.groupValues?.getOrNull(1).orEmpty()
        return if (styleUrl.isNotBlank() && !styleUrl.contains("/banner/")) {
            val thumbnail = buildThumbnailUrl(styleUrl, cdnBase)
            if (thumbnail.startsWith("http://cdn-sns.dongmanmanhua.cn/") && styleUrl.contains("cdn.dongmanmanhua.cn")) {
                dlog(
                    "thumbnailCdnSnsNormalize origin=$origin titleNo=${titleNo.orEmpty()} " +
                        "raw=$styleUrl final=$thumbnail"
                )
            }
            thumbnail
        } else {
            ""
        }
    }

    private fun extractThumbnailUrlAllowBanner(element: Element, origin: String = "", titleNo: String? = null): String {
        val img = element.selectFirst("img")
        val rawUrl = img?.attr("data-original")
            ?.ifEmpty { img.attr("data-src") }
            ?.ifEmpty { img.attr("src") }
            ?: ""
        if (rawUrl.isNotBlank()) {
            val thumbnail = buildThumbnailUrl(rawUrl, cdnBase)
            if (thumbnail.startsWith("http://cdn-sns.dongmanmanhua.cn/") && rawUrl.contains("cdn.dongmanmanhua.cn")) {
                dlog(
                    "thumbnailCdnSnsNormalizeEmergency origin=$origin titleNo=${titleNo.orEmpty()} " +
                        "raw=$rawUrl final=$thumbnail"
                )
            }
            return thumbnail
        }

        val style = element.selectFirst("[style*=background]")?.attr("style").orEmpty()
        val match = Regex("""url\(['"]?([^)'"]+)['"]?\)""").find(style)
        val styleUrl = match?.groupValues?.getOrNull(1).orEmpty()
        return if (styleUrl.isNotBlank()) {
            val thumbnail = buildThumbnailUrl(styleUrl, cdnBase)
            if (thumbnail.startsWith("http://cdn-sns.dongmanmanhua.cn/") && styleUrl.contains("cdn.dongmanmanhua.cn")) {
                dlog(
                    "thumbnailCdnSnsNormalizeEmergency origin=$origin titleNo=${titleNo.orEmpty()} " +
                        "raw=$styleUrl final=$thumbnail"
                )
            }
            thumbnail
        } else {
            ""
        }
    }

    private fun extractDetailThumbnailUrl(document: org.jsoup.nodes.Document): String {
        val metaUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.ifBlank { document.selectFirst("meta[name=twitter:image]")?.attr("content").orEmpty() }
            .orEmpty()
        if (metaUrl.isNotBlank()) return buildThumbnailUrl(metaUrl, cdnBase)

        val img = document.selectFirst(
            "div.detail_info img, .detail_info img, .detail_header img, .detail_img img, " +
                ".detail_lst_thumb img, .pic img, img[src*=dongmanmanhua]"
        )
        val rawUrl = img?.attr("data-original")
            ?.ifEmpty { img.attr("data-src") }
            ?.ifEmpty { img.attr("src") }
            .orEmpty()
        return buildThumbnailUrl(rawUrl, cdnBase)
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

    private fun newPageTitleNosFromDocument(document: org.jsoup.nodes.Document): Set<String> {
        return document.select(".new_works_items")
            .asSequence()
            .mapNotNull { item -> titleNoFromElementIdentity(item)?.trim()?.takeIf { it.isNotBlank() } }
            .toSet()
    }

    private fun rememberDetailNewPageSnapshot(titleNos: Set<String>, source: String): DetailNewPageSnapshot? {
        if (titleNos.isEmpty()) {
            dlog("detailNewPageSnapshot source=$source ignored=empty")
            return null
        }
        val snapshot = DetailNewPageSnapshot(
            titleNos = titleNos.toSet(),
            fetchedAtMs = System.currentTimeMillis(),
            source = source,
        )
        synchronized(detailNewPageSnapshotLock) {
            detailNewPageSnapshot = snapshot
        }
        dlog(
            "detailNewPageSnapshot source=$source titles=${snapshot.titleNos.size} " +
                "ttl=${DETAIL_NEW_PAGE_SNAPSHOT_TTL_MS}ms"
        )
        return snapshot
    }

    private fun detailNewPageSnapshotIfFresh(now: Long): DetailNewPageSnapshot? {
        return detailNewPageSnapshot?.takeIf { now - it.fetchedAtMs < DETAIL_NEW_PAGE_SNAPSHOT_TTL_MS }
    }

    private fun getDetailNewPageSnapshot(): DetailNewPageSnapshot? {
        val now = System.currentTimeMillis()
        detailNewPageSnapshotIfFresh(now)?.let { snapshot ->
            dlog(
                "detailNewPageSnapshot source=ttl-cache age=${now - snapshot.fetchedAtMs}ms " +
                    "titles=${snapshot.titleNos.size} origin=${snapshot.source}"
            )
            return snapshot
        }

        val lockWaitStartedAt = System.currentTimeMillis()
        return synchronized(detailNewPageSnapshotLock) {
            val lockedNow = System.currentTimeMillis()
            detailNewPageSnapshotIfFresh(lockedNow)?.let { snapshot ->
                dlog(
                    "detailNewPageSnapshot source=ttl-cache-after-wait wait=${lockedNow - lockWaitStartedAt}ms " +
                        "age=${lockedNow - snapshot.fetchedAtMs}ms titles=${snapshot.titleNos.size} " +
                        "origin=${snapshot.source}"
                )
                return@synchronized snapshot
            }

            runCatching {
                val startedAt = System.currentTimeMillis()
                val document = client.newCall(GET("$baseUrl/new", headersBuilder().build())).execute().use { newResponse ->
                    newResponse.asJsoup()
                }
                val titleNos = newPageTitleNosFromDocument(document)
                if (titleNos.isEmpty()) {
                    dlog("detailNewPageSnapshot source=fresh ignored=empty")
                    return@runCatching null
                }
                DetailNewPageSnapshot(
                    titleNos = titleNos,
                    fetchedAtMs = System.currentTimeMillis(),
                    source = "detail-new-page-fresh",
                ).also { snapshot ->
                    detailNewPageSnapshot = snapshot
                    dlog(
                        "detailNewPageSnapshot source=fresh titles=${snapshot.titleNos.size} " +
                            "elapsed=${snapshot.fetchedAtMs - startedAt}ms " +
                            "ttl=${DETAIL_NEW_PAGE_SNAPSHOT_TTL_MS}ms"
                    )
                }
            }.getOrElse { e ->
                wlog("detailNewPageSnapshot failed", e)
                null
            }
        }
    }

    private fun isNewTitleDetail(url: String, updateTag: String): Boolean {
        val titleNo = titleNoFromUrl(url) ?: run {
            dlog("isNewTitleDetail noTitleNo url=$url")
            return false
        }
        val snapshot = getDetailNewPageSnapshot()
        val value = snapshot?.titleNos?.contains(titleNo) == true
        dlog(
            "isNewTitleDetail titleNo=$titleNo source=detail-new-page-snapshot value=$value " +
                "updateTag=$updateTag snapshotOrigin=${snapshot?.source ?: "none"}"
        )
        return value
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
        private const val UPDATE_PAGE_SIZE = 50
        private const val GENRE_PAGE_CACHE_TTL_MS = 10 * 60 * 1000L
        private const val GENRE_PAGE_CACHE_MAX_ENTRIES = 16
        private const val UPDATE_PAGE_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val UPDATE_PAGE_CACHE_MAX_ENTRIES = 10
        private const val DETAIL_NEW_PAGE_SNAPSHOT_TTL_MS = 2 * 60 * 1000L
        private const val OFFICIAL_NEW_WORK_COVER_FETCH_PARALLELISM = 20
        private const val OFFICIAL_NEW_WORK_COVER_DEMAND_PARALLELISM = 16
        private const val OFFICIAL_NEW_WORK_COVER_PREFETCH_PARALLELISM = 4
        private const val OFFICIAL_NEW_WORK_COVER_PREFETCH_LIMIT = 32
        private const val OFFICIAL_NEW_WORK_COVER_VISIBLE_PREFETCH_LIMIT = 12
        private const val OFFICIAL_BACKGROUND_COVER_PREFETCH_LIMIT = 20
        private const val OFFICIAL_NEW_WORK_COVER_PRIMARY_WAIT_MS = 0L
        private const val OFFICIAL_NEW_WORK_COVER_TAIL_WAIT_MS = 0L
        private const val OFFICIAL_NEW_WORK_COVER_REQUEST_TIMEOUT_MS = 6_000L
        private const val OFFICIAL_FIRST_COVER_LIMIT = 32
        private const val OFFICIAL_FIRST_COVER_WAIT_MS = 4_500L
        private const val OFFICIAL_FAST_COVER_WAIT_LIMIT = 24
        private const val OFFICIAL_FAST_COVER_WAIT_MS = 6_500L
        private const val OFFICIAL_COVER_VIRTUAL_INFLIGHT_WAIT_MS = 3_000L
        private const val OFFICIAL_NEW_WORK_COVER_META_SCAN_MAX_BYTES = 64 * 1024
        private const val OFFICIAL_NEW_WORK_COVER_FETCH_RETRY_TTL_MS = 30_000L
        private const val OFFICIAL_NEW_WORK_COVER_FETCH_MAX_STATES = 96
        private const val SLOW_NETWORK_LOG_MS = 10_000L
        private const val LOCAL_GENRE_CACHE_PATH = "/__dongman_cache__/genre"
        private const val LOCAL_UPDATE_CACHE_PATH = "/__dongman_cache__/update"
        private const val OFFICIAL_COVER_VIRTUAL_PATH = "/__mihon_official_cover"
        private const val NEW_PROBE_LOG_LIMIT = 5
        private const val VERBOSE_LIST_LOG = false
        private const val DEBUG_POPULAR_MODULE_LOG = false
        private const val DEBUG_POPULAR_GENRE_FILTER_LOG = false
        private const val DEBUG_IDENTITY_SEEN_LOG = false
        private const val DEBUG_IDENTITY_REUSE_LOG = false
        private const val IDENTITY_PROBE_SEEN_LOG_LIMIT = 120
        private const val IDENTITY_PROBE_CONFLICT_LOG_LIMIT = 240
        private const val CLEAN_AUDIT_DIRTY_LOG_LIMIT = 240

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
        private const val PREF_NEW_PAGE_TITLE_CACHE = "pref_new_page_title_cache_v1"
        private const val PREF_NEW_WORK_COVER_CACHE = "pref_new_work_cover_cache_v1"
        internal const val PREF_AUTO_PAY = "pref_auto_pay"
        internal const val PREF_LIST_INFLIGHT_COALESCE = "pref_list_inflight_coalesce"
        internal const val PREF_HOME_COVER_MODE = "pref_home_cover_mode"
        internal const val HOME_COVER_MODE_FAST = "fast"
        internal const val HOME_COVER_MODE_OFFICIAL_FIRST = "official_first"
        internal const val PREF_DETAIL_COVER_REFRESH_MODE = "pref_detail_cover_refresh_mode"
        internal const val DETAIL_COVER_REFRESH_SYNC = "sync"
        internal const val DETAIL_COVER_REFRESH_PRESERVE = "preserve"
        internal const val PREF_POPULAR_GENRE_ENABLED = "pref_popular_genre_enabled"
        private const val PREF_CANONICAL_IDENTITY_MAP = "pref_canonical_identity_map"
        private const val CANONICAL_IDENTITY_STORE_MAX_ENTRIES = 1200
        private const val CANONICAL_IDENTITY_STORE_PERSIST_DELAY_MS = 1_200L
        internal const val PREF_FILTER_WEEKDAY = "pref_filter_weekday"
        internal const val PREF_FILTER_SORT = "pref_filter_sort"
        internal const val PREF_FILTER_THEME = "pref_filter_theme"
        internal const val PREF_FILTER_ACTIVE_GROUP = "pref_filter_active_group"
        internal const val PREF_FILTER_WEEKDAY_STATE = "pref_filter_weekday_state"
        internal const val PREF_FILTER_SORT_STATE = "pref_filter_sort_state"
        internal const val PREF_FILTER_THEME_STATE = "pref_filter_theme_state"
        internal const val PREF_FILTER_MY_MANGA_STATE = "pref_filter_my_manga_state"
        internal const val PREF_FILTER_SCHEMA_VERSION = "pref_filter_schema_version"
        private const val FILTER_SCHEMA_VERSION = 3
        internal const val KEY_NEO_SES = "neo_ses"
        internal const val KEY_NEO_CHK = "neo_chk"

        internal const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
        internal const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/114.0"
    }
}
