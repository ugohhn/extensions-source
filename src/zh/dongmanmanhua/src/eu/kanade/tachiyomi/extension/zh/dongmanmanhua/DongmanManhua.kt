package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import android.os.Handler
import android.os.Looper
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DongmanManhua : HttpSource(), ConfigurableSource {

    override val name = "Dongman Manhua"
    override val lang get() = "zh-Hans"
    override val id get() = 7275979680702931948
    override val baseUrl = "https://m.dongmanmanhua.cn"
    override val supportsLatest = true

    private val cdnBase = "https://cdn.dongmanmanhua.cn"

    private val preferences by getPreferencesLazy()

    // ══════════════════════════════════════════════════════════════════════
    // 设置页
    // ══════════════════════════════════════════════════════════════════════

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val ctx = screen.context

        // ── 1. 登录开关（WebView 静默读取 Cookie）
        val enableLoginPref = SwitchPreferenceCompat(ctx).apply {
            key = PREF_ENABLE_LOGIN
            title = "启用登录状态浏览"
            summary = buildLoginSummary()
            setDefaultValue(false)
            setOnPreferenceChangeListener { pref, newValue ->
                if (newValue as Boolean) loginWithWebView(pref as SwitchPreferenceCompat)
                true
            }
        }.also(screen::addPreference)

        // ── 2. 账号输入框（密码登录用）
        EditTextPreference(ctx).apply {
            key = PREF_LOGIN_USERNAME
            title = "账号（手机号或邮箱）"
            summary = "用于密码登录，填写后再填密码触发登录"
            dialogTitle = "输入账号"
            setDefaultValue("")
        }.also(screen::addPreference)

        // ── 3. 密码输入框（填写后立即触发登录）
        EditTextPreference(ctx).apply {
            key = PREF_LOGIN_PASSWORD
            title = "密码"
            summary = "输入密码点确定后立即尝试登录"
            dialogTitle = "输入密码"
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val password = newValue as? String ?: ""
                if (password.isNotBlank()) {
                    val username = preferences.getString(PREF_LOGIN_USERNAME, "").orEmpty()
                    if (username.isBlank()) {
                        Toast.makeText(ctx, "请先填写账号", Toast.LENGTH_SHORT).show()
                    } else {
                        loginWithPassword(username, password, enableLoginPref)
                    }
                }
                preferences.edit().remove(PREF_LOGIN_PASSWORD).apply()
                false
            }
        }.also(screen::addPreference)

        // ── 4. 退出登录
        SwitchPreferenceCompat(ctx).apply {
            key = PREF_LOGOUT_TRIGGER
            title = "退出登录"
            summary = "清除本地保存的 NEO_SES / NEO_CHK"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                clearLoginCookie()
                enableLoginPref.isChecked = false
                enableLoginPref.summary = buildLoginSummary()
                false
            }
        }.also(screen::addPreference)

        // ── 5. 搜索模式（仅漫画 vs 含小说）
        ListPreference(ctx).apply {
            key = PREF_SEARCH_MODE
            title = "搜索模式"
            summary = "%s"
            entries = arrayOf("仅漫画（JSON，速度快）", "混合结果（含小说，首页HTML）")
            entryValues = arrayOf(SEARCH_MODE_JSON, SEARCH_MODE_MIXED)
            setDefaultValue(SEARCH_MODE_JSON)
        }.also(screen::addPreference)

        // ── 6. 自动扣费开关
        SwitchPreferenceCompat(ctx).apply {
            key = PREF_AUTO_PAY
            title = "自动购买付费章节"
            summary = "开启后，打开未购付费章节时将自动扣费解锁\n余额不足时会提示错误，需要先开启登录状态"
            setDefaultValue(false)
        }.also(screen::addPreference)

        // ── 7. User-Agent 预设
        ListPreference(ctx).apply {
            key = PREF_UA
            title = "User-Agent 预设"
            summary = "%s"
            entries = arrayOf("移动版（默认）", "Windows Firefox", "禁用 User-Agent", "自定义（见下方输入框）")
            entryValues = arrayOf(UA_MOBILE, UA_DESKTOP, "", PREF_UA_CUSTOM_FLAG)
            setDefaultValue(UA_MOBILE)
        }.also(screen::addPreference)

        // ── 8. User-Agent 自定义输入框
        EditTextPreference(ctx).apply {
            key = PREF_UA_CUSTOM
            title = "User-Agent 自定义值"
            summary = "选择「自定义」时生效\n当前值：${preferences.getString(PREF_UA_CUSTOM, "").orEmpty().ifEmpty { "（未填写）" }}"
            dialogTitle = "输入自定义 User-Agent"
            setDefaultValue("")
        }.also(screen::addPreference)
    }


    // ── 密码登录（RSA 加密）
    private fun loginWithPassword(username: String, password: String, enablePref: SwitchPreferenceCompat) {
        Thread {
            try {
                // 1. 获取 RSA 公钥
                val rsaResp = client.newCall(
                    GET("$baseUrl/member/login/rsa/getKeys", headers),
                ).execute()
                val rsaJson = JSONObject(rsaResp.body.string())
                val keyName = rsaJson.getString("keyName")
                val nvalue = rsaJson.getString("nvalue")
                val evalue = rsaJson.getString("evalue")
                val sessionKey = rsaJson.getString("sessionKey")

                // 2. 构造明文：lenChar(sessionKey)+sessionKey+lenChar(username)+username+lenChar(password)+password
                fun lenChar(s: String) = s.length.toChar()
                val plain = "${lenChar(sessionKey)}$sessionKey${lenChar(username)}$username${lenChar(password)}$password"

                // 3. RSA 加密输出十六进制（修正参数顺序：模数=evalue，指数=nvalue）
                val encrypted = rsaEncryptToHex(plain, evalue, nvalue)

                // 4. 提交登录
                val body = FormBody.Builder()
                    .add("encnm", keyName)
                    .add("encpw", encrypted)
                    .add("returnUrl", "$baseUrl/")
                    .add("loginType", "PHONE_NUMBER")
                    .build()
                val loginResp = client.newCall(
                    POST("$baseUrl/member/login/doLoginById", headers, body),
                ).execute()
                val loginJson = JSONObject(loginResp.body.string())

                if (loginJson.optInt("loginStatus", -1) == 0) {
                    // 从响应头读 NEO_SES
                    val setCookie = loginResp.header("Set-Cookie") ?: ""
                    val neoSes = extractCookieValue(setCookie, "NEO_SES")
                    if (neoSes.isNotEmpty()) {
                        saveLoginCookie(neoSes, "")
                        CookieManager.getInstance().setCookie(baseUrl, "NEO_SES=$neoSes; path=/")
                    }
                    Handler(Looper.getMainLooper()).post {
                        enablePref.summary = buildLoginSummary()
                        Toast.makeText(
                            Injekt.get<android.app.Application>(),
                            "登录成功",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                } else {
                    val msg = loginJson.optString("loginMessage", "登录失败")
                    throw Exception(msg)
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        Injekt.get<android.app.Application>(),
                        "登录失败：${e.message}",
                        Toast.LENGTH_LONG,
                    ).show()
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

    // ── 后台 WebView 静默读取 Cookie（参考拷贝漫画 TokenProvider.V2）
    // 在主线程创建 WebView，加载咚漫，onPageFinished 检测到 NEO_SES 后保存并更新 summary
    private fun loginWithWebView(pref: SwitchPreferenceCompat) {
        val app = Injekt.get<android.app.Application>()
        Handler(Looper.getMainLooper()).post {
            val webView = WebView(app).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        val cookieStr = CookieManager.getInstance()
                            .getCookie(baseUrl) ?: return
                        val neoSes = extractCookieValue(cookieStr, "NEO_SES")
                        val neoChk = extractCookieValue(cookieStr, "NEO_CHK")
                        if (neoSes.isNotEmpty()) {
                            saveLoginCookie(neoSes, neoChk)
                            Handler(Looper.getMainLooper()).post {
                                pref.summary = buildLoginSummary()
                            }
                            view?.stopLoading()
                            view?.destroy()
                        }
                    }
                }
                loadUrl("$baseUrl/member/mypage")
            }
            // 15 秒超时销毁
            Handler(Looper.getMainLooper()).postDelayed({ webView.destroy() }, 15_000)
        }
    }

    private fun saveLoginCookie(neoSes: String, neoChk: String) {
        preferences.edit()
            .putString(KEY_NEO_SES, neoSes)
            .putString(KEY_NEO_CHK, neoChk)
            .apply()
    }

    private fun clearLoginCookie() {
        preferences.edit()
            .remove(KEY_NEO_SES)
            .remove(KEY_NEO_CHK)
            .apply()
    }

    private fun buildLoginSummary(): String {
        val neoSes = preferences.getString(KEY_NEO_SES, "").orEmpty()
        val status = if (neoSes.isNotEmpty()) {
            "已登录（NEO_SES: ${neoSes.take(8)}...）"
        } else {
            "未登录"
        }
        return "启用后将使用登录状态搜寻/载入漫画，重启此开关刷新登录信息\n登录状态：$status"
    }

    private fun extractCookieValue(cookieStr: String, key: String): String =
        cookieStr.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.removePrefix("$key=")
            ?.trim() ?: ""

    // Cookie 头：优先用 SharedPreferences 持久化的 NEO_SES/NEO_CHK
    private fun cookieHeader(): String {
        val neoSes = preferences.getString(KEY_NEO_SES, "").orEmpty()
        val neoChk = preferences.getString(KEY_NEO_CHK, "").orEmpty()
        return buildString {
            if (neoSes.isNotEmpty()) append("NEO_SES=$neoSes; ")
            if (neoChk.isNotEmpty()) append("NEO_CHK=$neoChk")
        }.trimEnd(';', ' ')
    }

    private fun currentUserAgent(): String {
        return when (val pref = preferences.getString(PREF_UA, UA_MOBILE) ?: UA_MOBILE) {
            PREF_UA_CUSTOM_FLAG -> preferences.getString(PREF_UA_CUSTOM, "").orEmpty()
            else -> pref
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Headers
    // ══════════════════════════════════════════════════════════════════════

    override fun headersBuilder(): Headers.Builder {
        val builder = super.headersBuilder().set("Referer", "$baseUrl/")
        val ua = currentUserAgent()
        if (ua.isNotEmpty()) builder.set("User-Agent", ua)
        return builder
    }

    override val client = network.client

    // ══════════════════════════════════════════════════════════════════════
    // 首页（Popular）
    // ══════════════════════════════════════════════════════════════════════

    override fun popularMangaRequest(page: Int) =
        GET("$baseUrl/?pageName=home", headers)

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
    // 最新更新（Latest）
    // ══════════════════════════════════════════════════════════════════════

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/dailySchedule?sortOrder=UPDATE&webtoonCompleteType=ONGOING", headers)

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
    // 搜索：支持两种模式，由设置页 PREF_SEARCH_MODE 控制
    // SEARCH_MODE_JSON  → 全部用 /searchResult JSON（仅漫画，速度快）
    // SEARCH_MODE_MIXED → page=1 用 /search HTML（含小说），page>=2 用 /searchResult JSON
    private val nextStartMap = mutableMapOf<String, Int>()

    private fun isMixedMode() =
        preferences.getString(PREF_SEARCH_MODE, SEARCH_MODE_JSON) == SEARCH_MODE_MIXED

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (isMixedMode() && page == 1) {
            nextStartMap.remove(query)
            val body = FormBody.Builder()
                .add("searchType", "WEBTOON")
                .add("keyword", query)
                .build()
            val headers = headersBuilder()
                .set("Origin", baseUrl)
                .set("Referer", "$baseUrl/search")
                .set("Content-Type", "application/x-www-form-urlencoded")
                .build()
            return POST("$baseUrl/search", headers, body)
        }
        val start = if (isMixedMode()) {
            nextStartMap[query] ?: (1 + (page - 1) * 20)
        } else {
            1 + (page - 1) * 20
        }
        val body = FormBody.Builder()
            .add("keyword", query)
            .add("searchType", "WEBTOON")
            .add("start", start.toString())
            .build()
        val headers = headersBuilder()
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/search")
            .set("Content-Type", "application/x-www-form-urlencoded")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()
        return POST("$baseUrl/searchResult", headers, body)
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
                            .ifEmpty { item.optString("representGenreBackgroundImageUrl", "") },
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


    // ══════════════════════════════════════════════════════════════════════
    // 漫画详情
    // 封面直接沿用搜索/列表时已获取的 thumbnail_url，不重新提取
    // ══════════════════════════════════════════════════════════════════════

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

            // 状态：从页面内嵌 JS 的 serial_status 变量读取
            // SERIES=连载中, TERMINATION=已完结, REST=暂停更新
            status = when (extractSerialStatus(document.html())) {
                "SERIES" -> SManga.ONGOING
                "TERMINATION" -> SManga.COMPLETED
                "REST" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            // 封面不在这里赋值，Mihon 会保留已有的 thumbnail_url
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 章节列表（倒序返回，最新话在前）
    // ══════════════════════════════════════════════════════════════════════

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

                chapters.add(
                    SChapter.create().apply {
                        val cleanUrl = dataHref.substringBefore("&source")
                        url = if (cleanUrl.startsWith("http")) {
                            cleanUrl.removePrefix("https://m.dongmanmanhua.cn")
                                .removePrefix("//m.dongmanmanhua.cn")
                        } else {
                            cleanUrl
                        }
                        val isFree = a.attr("data-free") == "true"
                        val rawName = a.selectFirst("p.sub_title span.ellipsis")?.text()
                            ?: a.selectFirst("p.sub_title")?.text()
                            ?: "第${li.attr("data-episode-no")}话"
                        // 付费且未解锁的章节加🔒前缀，已购(data-free=true)不加
                        name = if (isFree) rawName else "🔒 $rawName"
                        date_upload = dateFormat.tryParse(
                            a.selectFirst("p.date")?.text()?.trim().orEmpty(),
                        ) ?: 0L
                        chapter_number = li.attr("data-episode-no").toFloatOrNull() ?: -1f
                    },
                )
            }

            val nextPage = document.select("div.paginate a[onclick] + a").firstOrNull()
                ?: break
            val nextUrl = nextPage.absUrl("href")
            if (nextUrl.isEmpty()) break

            val reqHeaders = headersBuilder().apply {
                val cookie = cookieHeader()
                if (cookie.isNotEmpty()) set("Cookie", cookie)
            }.build()
            document = client.newCall(GET(nextUrl, reqHeaders)).execute().asJsoup()
        }

        // 倒序：最新话在前，第1话在末尾
        return chapters.reversed()
    }

    private val dateFormat = SimpleDateFormat("yyyy-M-d", Locale.ENGLISH)

    // ══════════════════════════════════════════════════════════════════════
    // 阅读页面
    // ══════════════════════════════════════════════════════════════════════

    override fun pageListRequest(chapter: SChapter): Request {
        // network.client 的 CookieJar 自动保存 WebView 登录态，直接用 headers 即可
        val reqHeaders = headersBuilder().apply {
            // 如果用户额外填了手动 Cookie，叠加进去
            val cookie = cookieHeader()
            if (cookie.isNotEmpty()) set("Cookie", cookie)
        }.build()

        // 自动扣费：开关开启时执行，Cookie 由 CookieJar 自动携带
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

    // 用 client 直接发请求，CookieJar 自动带上 WebView 登录态的 Cookie
    private fun autoUnlockEpisode(titleNo: String, episodeNo: String) {
        val params = "title_no=$titleNo&episode_no=$episodeNo&platform=MWEB&client=APP_ANDROID"
        // 显式注入 SharedPreferences 里的 NEO_SES/NEO_CHK，不依赖 CookieJar
        val savedCookie = cookieHeader()
        val reqHeaders = headersBuilder()
            .set("Referer", "$baseUrl/FANTASY/list?title_no=$titleNo")
            .set("X-Requested-With", "XMLHttpRequest")
            .apply { if (savedCookie.isNotEmpty()) set("Cookie", savedCookie) }
            .build()

        // 1. 查询价格和余额
        val priceResp = client.newCall(
            GET("$baseUrl/episode/unlock/getEpisodePrice?$params", reqHeaders),
        ).execute()
        val priceJson = org.json.JSONObject(priceResp.body.string())
        val data = priceJson.optJSONObject("data") ?: return

        val isFree = data.optBoolean("free", true)
        if (isFree) return  // 已解锁或免费

        val isLimit = data.optBoolean("isLimit", false)
        if (isLimit) return  // 仅限 App，无法网页购买

        val price = data.optInt("price", 0)
        val coinCount = data.optInt("coinCount", 0)
        val episodeName = data.optString("episodeName", "本话")

        if (coinCount < price) {
            throw Exception("余额不足：$episodeName 需要 $price 币，当前余额 $coinCount 币，请前往咚漫充值")
        }

        // 2. 余额足够，静默扣费
        val payResp = client.newCall(
            GET("$baseUrl/episode/unlock/pay?$params", reqHeaders),
        ).execute()
        val payJson = org.json.JSONObject(payResp.body.string())
        if (payJson.optInt("code") != 200) return
    }

    private fun extractUrlParam(url: String, key: String): String {
        val regex = Regex("""[?&]$key=([^&]+)""")
        return regex.find(url)?.groupValues?.get(1) ?: ""
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        // 图片数据在页面内嵌 JS 的 var imageList = [{url:"...", ...}, ...]
        val imageRegex = Regex("""url\s*:\s*"(https://cdn\.dongmanmanhua\.cn/[^"]+)"""")
        return imageRegex.findAll(html)
            .mapIndexed { i, match -> Page(i, imageUrl = match.groupValues[1]) }
            .toList()
    }

    // 图片来自 cdn.dongmanmanhua.cn，需要带 Referer 才能正常加载
    override fun imageRequest(page: Page): Request {
        return GET(
            page.imageUrl!!,
            headersBuilder()
                .set("Referer", "$baseUrl/")
                .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .build(),
        )
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ══════════════════════════════════════════════════════════════════════
    // 工具函数
    // ══════════════════════════════════════════════════════════════════════

    // 从页面内嵌 JS 提取 serial_status 值
    // 格式：serial_status: 'SERIES'  或  "serial_status":"SERIES"
    private fun extractSerialStatus(html: String): String {
        val regex = Regex("""serial_status['":\s]+([A-Z]+)""")
        return regex.find(html)?.groupValues?.get(1) ?: ""
    }

    // 从 info_update 区域提取更新周期标签
    // 连载中返回如"每周二更新"，完结返回""（状态已单独处理）
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
            "p.subj, .subj .ellipsis, ._items_name_t, .home_genre_t, p.chapter-title-02, .chapter-title-01",
        )?.text() ?: element.attr("title").ifEmpty {
            element.selectFirst("img")?.attr("alt") ?: ""
        }
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

    // ══════════════════════════════════════════════════════════════════════
    // 常量
    // ══════════════════════════════════════════════════════════════════════

    companion object {
        private const val PREF_UA = "pref_user_agent"
        private const val PREF_UA_CUSTOM = "pref_user_agent_custom"
        private const val PREF_UA_CUSTOM_FLAG = "__custom__"
        private const val PREF_ENABLE_LOGIN = "pref_enable_login"
        private const val PREF_LOGIN_USERNAME = "pref_login_username"
        private const val PREF_LOGIN_PASSWORD = "pref_login_password"
        private const val PREF_LOGOUT_TRIGGER = "pref_logout_trigger"
        private const val PREF_SEARCH_MODE = "pref_search_mode"
        private const val SEARCH_MODE_JSON = "json"
        private const val SEARCH_MODE_MIXED = "mixed"
        private const val PREF_AUTO_PAY = "pref_auto_pay"
        private const val KEY_NEO_SES = "neo_ses"
        private const val KEY_NEO_CHK = "neo_chk"

        private const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
        private const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/114.0"
    }
}
