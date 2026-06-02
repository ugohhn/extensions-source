package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import java.math.BigInteger
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DongmanManhua : HttpSource(), ConfigurableSource {

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

    private val cdnBase = "https://cdn.dongmanmanhua.cn"
    private val preferences by getPreferencesLazy()
    private val appContext by lazy { Injekt.get<android.app.Application>() }

    // ==================== 常量 ====================
    companion object {
        private const val PREF_UA = "pref_user_agent"
        private const val PREF_UA_CUSTOM = "pref_user_agent_custom"
        private const val PREF_UA_CUSTOM_FLAG = "__custom__"
        private const val PREF_ENABLE_LOGIN = "pref_enable_login"
        private const val PREF_LOGIN_USERNAME = "pref_login_username"
        private const val PREF_LOGIN_PASSWORD = "pref_login_password"
        private const val PREF_LOGOUT_TRIGGER = "pref_logout_trigger"
        private const val PREF_CLEAR_BACKUP = "pref_clear_backup"
        private const val PREF_SEARCH_MODE = "pref_search_mode"
        private const val PREF_AUTO_PAY = "pref_auto_pay"
        // 【手动备用Cookie开关】（取代原独立存储开关）
        private const val PREF_MANUAL_COOKIE_SWITCH = "pref_manual_cookie_switch"

        private const val FILE_NAME_COOKIE = "cookie.dat"
        private const val PROBE_COOL_MS = 60_000L

        private const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
        private const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/114.0"
    }

    // 内存探针缓存
    private var lastProbeTime: Long = 0L
    private var cacheLoginValid: Boolean? = null
    private var cachedFinalCookie: String? = null

    // 本地存储目录&文件
    private fun getCookieDir(): File = File(appContext.filesDir, "dongmanmanhua").apply { mkdirs() }
    private fun getCookieFile(): File = File(getCookieDir(), FILE_NAME_COOKIE)

    // 持久化保存 NEO_SES/NEO_CHK 到本地文件
    private fun saveNeoToLocal(neoSes: String, neoChk: String) {
        try {
            getCookieFile().writeText("$neoSes|$neoChk", StandardCharsets.UTF_8)
            refreshFinalCookieCache()
            Log.d("DongmanCookie", "本地保存Cookie成功")
        } catch (e: Exception) {
            Log.e("DongmanCookie", "写入本地Cookie异常", e)
        }
    }

    // 读取本地保存的双Cookie
    private fun loadNeoFromLocal(): Pair<String, String> {
        val f = getCookieFile()
        if (!f.exists()) return "" to ""
        return try {
            val content = f.readText(StandardCharsets.UTF_8)
            val arr = content.split("|")
            if (arr.size >= 2) arr[0].trim() to arr[1].trim() else "" to ""
        } catch (e: Exception) {
            "" to ""
        }
    }

    // 清空本地存储 + 关闭手动备用开关 + 刷新缓存
    private fun clearAllLocalNeo() {
        try {
            getCookieFile().delete()
        } catch (_: Exception) {}
        preferences.edit().putBoolean(PREF_MANUAL_COOKIE_SWITCH, false).apply()
        lastProbeTime = 0L
        cacheLoginValid = null
        refreshFinalCookieCache()   // FIX: 必须刷新缓存
    }

    // 读取手动备用开关状态
    private fun getManualCookieEnable(): Boolean = preferences.getBoolean(PREF_MANUAL_COOKIE_SWITCH, false)

    // 拼接 NEO Cookie 字符串
    private fun buildNeoCookieStr(ses: String, chk: String): String {
        val sb = StringBuilder()
        if (ses.isNotEmpty()) sb.append("NEO_SES=$ses; ")
        if (chk.isNotEmpty()) sb.append("NEO_CHK=$chk")
        return sb.toString().trim(';', ' ')
    }

    // 刷新最终可用 Cookie 缓存（核心修复：手动关闭时使用 WebView 原生 Cookie）
    private fun refreshFinalCookieCache() {
        cachedFinalCookie = if (getManualCookieEnable()) {
            val (ses, chk) = loadNeoFromLocal()
            buildNeoCookieStr(ses, chk).ifEmpty { null }
        } else {
            // FIX: 从 CookieManager 读取原生 Cookie
            val cmCookie = CookieManager.getInstance().getCookie(baseUrl) ?: ""
            cmCookie.ifEmpty { null }
        }
        Log.d("DongmanCookie", "刷新 Cookie 缓存 -> ${if (cachedFinalCookie != null) "有 Cookie" else "无"}")
    }

    // 探针：仅当手动开关开启且冷却已过才请求 /member/isLogin，否则使用缓存或直接返回 true
    private fun probeIsLoginValid(): Boolean {
        if (!getManualCookieEnable()) return true
        val now = System.currentTimeMillis()
        if (now - lastProbeTime < PROBE_COOL_MS && cacheLoginValid != null) {
            return cacheLoginValid!!
        }
        return try {
            val req = Request.Builder()
                .url("${baseUrl}/member/isLogin")
                .headers(headersBuilder().build())
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            resp.close()
            val valid = body.contains("true", ignoreCase = true)
            lastProbeTime = now
            cacheLoginValid = valid
            if (!valid) {
                clearAllLocalNeo()
            }
            valid
        } catch (_: Exception) {
            true
        }
    }

    // 自动扣费鉴权（仅手动开关+自动扣费双开时执行，失效自动清本地）
    private fun checkAutoPayUnlock(titleNo: String, epNo: String): Boolean {
        val autoPayOn = preferences.getBoolean(PREF_AUTO_PAY, false)
        if (!getManualCookieEnable() || !autoPayOn) return false
        val param = "title_no=$titleNo&episode_no=$epNo&platform=MWEB&client=APP_ANDROID"
        return try {
            val header = headersBuilder().build()
            val priceReq = Request.Builder()
                .url("${baseUrl}/episode/unlock/getEpisodePrice?$param")
                .headers(header)
                .build()
            val priceRes = client.newCall(priceReq).execute()
            val jsonStr = priceRes.body?.string() ?: ""
            priceRes.close()
            val jo = JSONObject(jsonStr)
            val data = jo.optJSONObject("data")
            if (data == null || (!data.getBoolean("free") && data.optInt("coinCount", 0) == 0)) {
                clearAllLocalNeo()
                return false
            }
            val isFree = data.getBoolean("free")
            if (isFree) return true
            val price = data.optInt("price")
            val ownCoin = data.optInt("coinCount")
            if (ownCoin < price) {
                // FIX: 余额不足不要抛异常，弹提示并返回 false
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, "余额不足：本话需要 $price 币，当前余额 $ownCoin 币", Toast.LENGTH_LONG).show()
                }
                return false
            }
            val payReq = Request.Builder()
                .url("${baseUrl}/episode/unlock/pay?$param")
                .headers(header)
                .build()
            val payRes = client.newCall(payReq).execute()
            val payJson = payRes.body?.string() ?: ""
            payRes.close()
            JSONObject(payJson).optInt("code", -1) == 200
        } catch (e: Exception) {
            Log.e("DongmanCookie", "自动扣费异常", e)
            false
        }
    }

    private fun hasLocalSavedNeo(): Boolean {
        val (ses, _) = loadNeoFromLocal()
        return ses.isNotBlank()
    }

    private lateinit var enableLoginSwitch: SwitchPreferenceCompat

    // ==================== 设置页 ====================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val ctx = screen.context

        // 迁移旧设置（字符串布尔转 Boolean）
        val editor = preferences.edit()
        preferences.all.forEach { (key, value) ->
            if (value is String && (value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true))) {
                editor.putBoolean(key, value.toBoolean())
            }
        }
        editor.apply()

        // 【手动备用Cookie总开关】（已取代原独立存储开关）
        SwitchPreferenceCompat(ctx).apply {
            key = PREF_MANUAL_COOKIE_SWITCH
            title = "启用手动备用Cookie模式"
            summary = buildManualSwitchSummary()
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                refreshFinalCookieCache()
                enableLoginSwitch.summary = buildLoginSummary()
                // 更新自身的 summary
                summary = buildManualSwitchSummary()
                true
            }
        }.also(screen::addPreference)

        // 登录状态指示灯（仅显示，不可手动点击）
        SwitchPreferenceCompat(ctx).apply {
            key = PREF_ENABLE_LOGIN
            title = "登录状态"
            summary = buildLoginSummary()
            setDefaultValue(false)
            setEnabled(false)
            enableLoginSwitch = this
        }.also(screen::addPreference)

        // 账号输入框
        EditTextPreference(ctx).apply {
            key = PREF_LOGIN_USERNAME
            title = "账号（手机号或邮箱）"
            summary = "用于密码登录，需先填账号再填密码"
            dialogTitle = "输入账号"
            setDefaultValue("")
        }.also(screen::addPreference)

        // 密码输入框
        EditTextPreference(ctx).apply {
            key = PREF_LOGIN_PASSWORD
            title = "密码"
            summary = "输入密码点确定后立即尝试 RSA 登录"
            dialogTitle = "输入密码"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val password = newValue as? String ?: ""
                if (password.isNotBlank()) {
                    val username = preferences.getString(PREF_LOGIN_USERNAME, "").orEmpty()
                    if (username.isBlank()) {
                        Toast.makeText(ctx, "请先填写账号", Toast.LENGTH_SHORT).show()
                    } else {
                        loginWithPassword(username, password)
                    }
                }
                preferences.edit().remove(PREF_LOGIN_PASSWORD).apply()
                false
            }
        }.also(screen::addPreference)

        // 彻底退出登录
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

        // 仅清除独立存储备份
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

        // 搜索模式开关
        SwitchPreferenceCompat(ctx).apply {
            key = PREF_SEARCH_MODE
            title = "搜索显示小说"
            summary = "开启后搜索结果包含小说（首页HTML接口）\n关闭则只显示漫画（JSON接口，速度更快）"
            setDefaultValue(false)
        }.also(screen::addPreference)

        // 自动扣费开关
        SwitchPreferenceCompat(ctx).apply {
            key = PREF_AUTO_PAY
            title = "自动购买付费章节"
            summary = "开启后，打开未购付费章节时将自动扣费解锁\n余额不足时会提示错误，需要开启登录+手动备用Cookie"
            setDefaultValue(false)
        }.also(screen::addPreference)

        // User-Agent 预设
        ListPreference(ctx).apply {
            key = PREF_UA
            title = "User-Agent 预设"
            summary = "%s"
            entries = arrayOf("移动版（默认）", "Windows Firefox", "禁用 User-Agent", "自定义（见下方输入框）")
            entryValues = arrayOf(UA_MOBILE, UA_DESKTOP, "", PREF_UA_CUSTOM_FLAG)
            setDefaultValue(UA_MOBILE)
        }.also(screen::addPreference)

        // User-Agent 自定义
        EditTextPreference(ctx).apply {
            key = PREF_UA_CUSTOM
            title = "User-Agent 自定义值"
            summary = "选择「自定义」时生效\n当前值：${preferences.getString(PREF_UA_CUSTOM, "").orEmpty().ifEmpty { "（未填写）" }}"
            dialogTitle = "输入自定义 User-Agent"
            setDefaultValue("")
        }.also(screen::addPreference)

        refreshFinalCookieCache()
    }

    private fun buildManualSwitchSummary(): String {
        val hasLocal = hasLocalSavedNeo()
        return "开启：请求头使用本地保存的NEO鉴权Cookie；关闭：使用WebView原生Cookie\n" +
                "当前本地缓存：${if (hasLocal) "存在登录Cookie" else "无登录Cookie"}"
    }

    // ==================== 密码登录 ====================
    private fun loginWithPassword(username: String, password: String) {
        Thread {
            try {
                Log.d("DongmanCookie", "=== 开始密码登录 ===")
                val rsaResp = client.newCall(GET("$baseUrl/member/login/rsa/getKeys", headers)).execute()
                val rsaJson = JSONObject(rsaResp.body.string())
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
                val loginResp = client.newCall(POST("$baseUrl/member/login/doLoginById", headers, body)).execute()
                val loginJson = JSONObject(loginResp.body.string())

                if (loginJson.optInt("loginStatus", -1) == 0) {
                    val setCookie = loginResp.header("Set-Cookie") ?: ""
                    Log.d("DongmanCookie", "密码登录 Set-Cookie: $setCookie")
                    val neoSes = extractCookieValue(setCookie, "NEO_SES")
                    val neoChk = extractCookieValue(setCookie, "NEO_CHK")
                    if (neoSes.isNotEmpty()) {
                        saveNeoToLocal(neoSes, neoChk)
                        CookieManager.getInstance().setCookie(baseUrl, "NEO_SES=$neoSes; path=/")
                        refreshFinalCookieCache()
                    }
                    Handler(Looper.getMainLooper()).post {
                        enableLoginSwitch.isChecked = true
                        enableLoginSwitch.summary = buildLoginSummary()
                        // 刷新手动开关的 summary
                        val manualPref = screen.findPreference<SwitchPreferenceCompat>(PREF_MANUAL_COOKIE_SWITCH)
                        manualPref?.summary = buildManualSwitchSummary()
                        Toast.makeText(appContext, "登录成功（需手动开启备用Cookie开关）", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val msg = loginJson.optString("loginMessage", "登录失败")
                    throw Exception(msg)
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(appContext, "登录失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun rsaEncryptToHex(data: String, modulusHex: String, exponentHex: String): String {
        val modulus = BigInteger(modulusHex, 16)
        val exponent = BigInteger(exponentHex, 16)
        val keySpec = RSAPublicKeySpec(modulus, exponent)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return encrypted.joinToString("") { "%02x".format(it) }
    }

    // ==================== WebView 登录 ====================
    private fun loginWithWebView(pref: SwitchPreferenceCompat) {
        Log.d("DongmanCookie", "=== 启动 WebView 登录 ===")
        val app = appContext
        Handler(Looper.getMainLooper()).post {
            val webView = WebView(app).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = currentUserAgent().takeIf { it.isNotEmpty() } ?: UA_MOBILE
                CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val cookieStr = CookieManager.getInstance().getCookie(baseUrl) ?: ""
                        Log.d("DongmanCookie", "WebView 登录后 CookieManager: $cookieStr")
                        val neoSes = extractCookieValue(cookieStr, "NEO_SES")
                        val neoChk = extractCookieValue(cookieStr, "NEO_CHK")
                        if (neoSes.isNotEmpty()) {
                            saveNeoToLocal(neoSes, neoChk)
                            refreshFinalCookieCache()
                            Handler(Looper.getMainLooper()).post {
                                pref.isChecked = true
                                pref.summary = buildLoginSummary()
                                val manualPref = screen.findPreference<SwitchPreferenceCompat>(PREF_MANUAL_COOKIE_SWITCH)
                                manualPref?.summary = buildManualSwitchSummary()
                                Toast.makeText(app, "WebView登录成功（需手动开启备用Cookie开关）", Toast.LENGTH_SHORT).show()
                            }
                            view?.stopLoading()
                            view?.destroy()
                        } else {
                            Log.w("DongmanCookie", "WebView 登录后未找到 NEO_SES")
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(app, "登录失败：未获取到Cookie", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                loadUrl("$baseUrl/member/mypage")
            }
            Handler(Looper.getMainLooper()).postDelayed({ webView.destroy() }, 15_000)
        }
    }

    // ==================== 退出和清除 ====================
    private fun fullLogout() {
        Log.d("DongmanCookie", "fullLogout: 彻底退出登录")
        CookieManager.getInstance().removeAllCookies(null)
        clearAllLocalNeo()   // 内部已刷新缓存
        enableLoginSwitch.isChecked = false
        enableLoginSwitch.summary = buildLoginSummary()
        val manualPref = screen.findPreference<SwitchPreferenceCompat>(PREF_MANUAL_COOKIE_SWITCH)
        manualPref?.summary = buildManualSwitchSummary()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, "已彻底退出登录", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearBackupOnly() {
        Log.d("DongmanCookie", "clearBackupOnly: 仅清除独立存储备份，不影响WebView登录态")
        clearAllLocalNeo()   // 内部已刷新缓存
        enableLoginSwitch.summary = buildLoginSummary()
        val manualPref = screen.findPreference<SwitchPreferenceCompat>(PREF_MANUAL_COOKIE_SWITCH)
        manualPref?.summary = buildManualSwitchSummary()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, "已清除独立存储备份", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== UI 文字构建 ====================
    private fun buildLoginSummary(): String {
        val isLoggedIn = cachedFinalCookie?.isNotEmpty() == true
        val status = if (isLoggedIn) "已登录" else "未登录"
        val source = if (getManualCookieEnable()) "手动备用Cookie" else "WebView原生Cookie"
        return "Cookie来源：$source\n登录状态：$status"
    }

    // ==================== 辅助函数 ====================
    private fun extractCookieValue(cookieStr: String, key: String): String =
        cookieStr.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.removePrefix("$key=")
            ?.trim() ?: ""

    private fun cookieHeader(): String {
        if (cachedFinalCookie != null) return cachedFinalCookie!!
        refreshFinalCookieCache()
        return cachedFinalCookie ?: ""
    }

    private fun currentUserAgent(): String {
        return when (val pref = preferences.getString(PREF_UA, UA_MOBILE) ?: UA_MOBILE) {
            PREF_UA_CUSTOM_FLAG -> preferences.getString(PREF_UA_CUSTOM, "").orEmpty()
            else -> pref
        }
    }

    override fun headersBuilder(): Headers.Builder {
        val builder = super.headersBuilder().set("Referer", "$baseUrl/")
        val ua = currentUserAgent()
        if (ua.isNotEmpty()) builder.set("User-Agent", ua)
        val cookie = cookieHeader()
        if (cookie.isNotEmpty()) {
            Log.d("DongmanCookie", "headersBuilder: 注入 Cookie -> $cookie")
            builder.set("Cookie", cookie)
        } else {
            Log.d("DongmanCookie", "headersBuilder: 没有 Cookie 可注入")
        }
        return builder
    }

    override val client = network.client

    // ==================== 首页（触发探针） ====================
    override fun popularMangaRequest(page: Int): Request {
        probeIsLoginValid()
        return GET("$baseUrl/?pageName=home", headers)
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

    // ==================== 最新更新 ====================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/dailySchedule?sortOrder=UPDATE&webtoonCompleteType=ONGOING", headers)

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

    // ==================== 搜索 ====================
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
        return if (response.request.url.toString().contains("/searchResult")) parseSearchResultJson(response) else parseSearchHtml(response)
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
            val keyword = extractKeywordFromBody(response)
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
                            .ifEmpty { item.optString("representGenreBackgroundImageUrl", "") }
                    )
                }
                if (manga.title.isNotEmpty()) entries.add(manga)
            }
        }
        val hasNextPage = rawCount > 0 && (start - 1 + rawCount) < total
        if (hasNextPage && isMixedMode()) {
            val keyword = extractKeywordFromBody(response)
            if (keyword.isNotEmpty()) nextStartMap[keyword] = start + rawCount
        }
        return MangasPage(entries, hasNextPage)
    }

    private fun extractKeywordFromBody(response: Response): String {
        val body = response.request.body
        if (body is FormBody) {
            for (i in 0 until body.size) {
                if (body.name(i) == "keyword") return body.value(i)
            }
        }
        return ""
    }

    // ==================== 漫画详情 ====================
    override fun mangaDetailsRequest(manga: SManga): Request {
        val reqHeaders = headersBuilder().apply {
            val cookie = cookieHeader()
            if (cookie.isNotEmpty()) set("Cookie", cookie)
        }.build()
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

    // ==================== 章节列表 ====================
    override fun chapterListRequest(manga: SManga): Request {
        val reqHeaders = headersBuilder().apply {
            val cookie = cookieHeader()
            if (cookie.isNotEmpty()) set("Cookie", cookie)
        }.build()
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
            val reqHeaders = headersBuilder().apply {
                val cookie = cookieHeader()
                if (cookie.isNotEmpty()) set("Cookie", cookie)
            }.build()
            document = client.newCall(GET(nextUrl, reqHeaders)).execute().asJsoup()
        }
        return chapters.reversed()
    }

    private val dateFormat = SimpleDateFormat("yyyy-M-d", Locale.ENGLISH)

    // ==================== 阅读页面 & 自动解锁 ====================
    override fun pageListRequest(chapter: SChapter): Request {
        val reqHeaders = headersBuilder().apply {
            val cookie = cookieHeader()
            if (cookie.isNotEmpty()) set("Cookie", cookie)
        }.build()
        val autoPay = preferences.getBoolean(PREF_AUTO_PAY, false)
        if (autoPay) {
            val titleNo = extractUrlParam(chapter.url, "title_no")
            val episodeNo = extractUrlParam(chapter.url, "episode_no")
            if (titleNo.isNotEmpty() && episodeNo.isNotEmpty()) {
                checkAutoPayUnlock(titleNo, episodeNo)
            }
        }
        return GET(baseUrl + chapter.url, reqHeaders)
    }

    private fun extractUrlParam(url: String, key: String): String {
        val regex = Regex("""[?&]$key=([^&]+)""")
        return regex.find(url)?.groupValues?.get(1) ?: ""
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

    // ==================== 工具函数 ====================
    private fun extractSerialStatus(html: String): String {
        val regex = Regex("""serial_status['":\s]+([A-Z]+)""")
        return regex.find(html)?.groupValues?.get(1) ?: ""
    }

    private fun extractUpdateTag(html: String): String {
        val regex = Regex("""在(周[一二三四五六七日天])更新""")
        val match = regex.find(html) ?: return ""
        return "每${match.groupValues[1]}更新"
    }

    private fun buildThumbnailUrl(raw: String): String {
        if (raw.isEmpty()) return ""
        return when {
            raw.startsWith("http") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$cdnBase$raw"
            else -> raw
        }
    }

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

    private fun extractThumbnailUrl(element: Element): String {
        val img = element.selectFirst(".pic img, img, a img")
        if (img != null) {
            img.attr("data-image-url").takeIf { it.isNotEmpty() }?.let { return it }
            img.attr("data-src").takeIf { it.isNotEmpty() }?.let { return it }
            img.absUrl("src").takeIf { it.isNotEmpty() && !it.contains("placeholder") && !it.contains("transparent") }?.let { return it }
            img.attr("data-original").takeIf { it.isNotEmpty() }?.let { return it }
            img.attr("data-url").takeIf { it.isNotEmpty() }?.let { return it }
            img.attr("data-cover").takeIf { it.isNotEmpty() }?.let { return it }
            extractUrlFromStyle(img.attr("style")).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val style = element.attr("style")
        if (style.isNotEmpty()) {
            extractUrlFromStyle(style).takeIf { it.isNotEmpty() }?.let { return it }
        }
        val picDiv = element.selectFirst(".pic, .thmb, .chapter-img-c")
        if (picDiv != null) {
            val picStyle = picDiv.attr("style")
            if (picStyle.isNotEmpty()) {
                extractUrlFromStyle(picStyle).takeIf { it.isNotEmpty() }?.let { return it }
            }
            val picImg = picDiv.selectFirst("img")
            if (picImg != null) {
                picImg.attr("data-image-url").takeIf { it.isNotEmpty() }?.let { return it }
                picImg.attr("data-src").takeIf { it.isNotEmpty() }?.let { return it }
                picImg.absUrl("src").takeIf { it.isNotEmpty() && !it.contains("placeholder") && !it.contains("transparent") }?.let { return it }
                picImg.attr("data-original").takeIf { it.isNotEmpty() }?.let { return it }
                picImg.attr("data-url").takeIf { it.isNotEmpty() }?.let { return it }
                picImg.attr("data-cover").takeIf { it.isNotEmpty() }?.let { return it }
                extractUrlFromStyle(picImg.attr("style")).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        element.attr("data-image-url").takeIf { it.isNotEmpty() }?.let { return it }
        element.attr("data-cover").takeIf { it.isNotEmpty() }?.let { return it }
        element.attr("data-thumbnail").takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    private fun extractUrlFromStyle(style: String): String {
        if (style.isEmpty()) return ""
        val regex = Regex("""background(?:-image)?\s*:\s*url\(['"]?([^'"()]+)['"]?\)""")
        val match = regex.find(style)
        if (match != null) return match.groupValues[1].trim()
        val from = style.indexOf("url(").takeIf { it != -1 }?.plus(4) ?: return ""
        val end = style.indexOf(")", from).takeIf { it != -1 } ?: return ""
        return style.substring(from, end).trim().removeSurrounding("\"").removeSurrounding("'")
    }
                               }
