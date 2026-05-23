package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import android.webkit.CookieManager
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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import org.json.JSONObject
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

        // 1. User-Agent 切换
        ListPreference(screen.context).apply {
            key = PREF_UA
            title = "User-Agent"
            summary = "%s"
            entries = arrayOf("移动版（默认）", "Windows Firefox", "禁用 User-Agent")
            entryValues = arrayOf(UA_MOBILE, UA_DESKTOP, "")
            setDefaultValue(UA_MOBILE)
        }.also(screen::addPreference)

        // 2. WebView 登录开关
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_WEBVIEW_LOGIN
            title = "启用登录状态浏览"
            summaryOn = buildLoginSummary()
            summaryOff = "关闭后使用下方手动粘贴的 Cookie\n重启此开关可重新从 WebView 读取登录信息"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    val cookie = readCookieFromWebView()
                    preferences.edit().putString(PREF_WEBVIEW_COOKIE, cookie).apply()
                    summaryOn = buildLoginSummary()
                }
                true
            }
        }.also(screen::addPreference)

        // 3. 手动 Cookie
        EditTextPreference(screen.context).apply {
            key = PREF_COOKIE
            title = "手动 Cookie（WebView 登录关闭时生效）"
            summary = "从浏览器复制完整 Cookie 字符串粘贴到这里\n当前值：${previewCookie(preferences.getString(PREF_COOKIE, ""))}"
            dialogTitle = "设置手动 Cookie"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    private fun readCookieFromWebView(): String = try {
        CookieManager.getInstance().getCookie(baseUrl) ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun buildLoginSummary(): String {
        val cookie = preferences.getString(PREF_WEBVIEW_COOKIE, "") ?: ""
        val status = when {
            cookie.isEmpty() -> "未登入（请先在 Mihon 内置浏览器打开咚漫并登录，然后重启此开关）"
            cookie.contains("JSESSIONID") -> "已登入"
            else -> "登入失败（未检测到会话 Cookie）"
        }
        return "启用后将使用登录状态搜寻/载入漫画\n重启此选项刷新登入信息\n登入状态：$status"
    }

    private fun previewCookie(cookie: String?): String =
        cookie?.take(40)?.ifEmpty { "（未设置）" } ?: "（未设置）"

    // ══════════════════════════════════════════════════════════════════════
    // Cookie / UA 读取
    // ══════════════════════════════════════════════════════════════════════

    private fun cookieHeader(): String {
        val useWebView = preferences.getBoolean(PREF_WEBVIEW_LOGIN, false)
        return if (useWebView) {
            preferences.getString(PREF_WEBVIEW_COOKIE, "") ?: ""
        } else {
            preferences.getString(PREF_COOKIE, "") ?: ""
        }
    }

    private fun currentUserAgent(): String =
        preferences.getString(PREF_UA, UA_MOBILE) ?: UA_MOBILE

    // ══════════════════════════════════════════════════════════════════════
    // Headers
    // ══════════════════════════════════════════════════════════════════════

    override fun headersBuilder(): Headers.Builder {
        val builder = super.headersBuilder().set("Referer", "$baseUrl/")
        val ua = currentUserAgent()
        if (ua.isNotEmpty()) builder.set("User-Agent", ua)
        return builder
    }

    override val client = network.cloudflareClient

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
    // 搜索（双接口：page=1 HTML，page>=2 JSON）
    // ══════════════════════════════════════════════════════════════════════

    private val nextStartMap = mutableMapOf<String, Int>()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return if (page == 1) {
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
            POST("$baseUrl/search", headers, body)
        } else {
            val start = nextStartMap[query] ?: (1 + (page - 1) * 20)
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
            POST("$baseUrl/searchResult", headers, body)
        }
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
                    url = "/list?title_no=$titleNo"
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
        if (hasNextPage) {
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
    //
    // HTML 结构（来自实际抓包）：
    //   <div class="detail_info v2" style="background-image:url(封面URL)">
    //     <p class="genre g_fantasy">奇幻 都市</p>
    //     <a class="_btnInfo">
    //       <p class="subj">反转练习生</p>
    //       <p class="author">Song Geukjang</p>
    //       <p class="summary"><span class="ellipsis">简介...</span></p>
    //     </a>
    //   </div>
    //   更新状态在 <div class="lst_type3 detail_white"> 上方的标签中，
    //   或者通过 meta description 里的"周X更新"/"完结"判断
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
            // 标题
            title = detailDiv?.selectFirst("p.subj")?.text()
                ?: document.selectFirst("h1.subj, h3.subj")?.text()
                ?: document.title().substringBefore("_")

            // 作者（单作者情况，咚漫页面 author 标签只有一个）
            author = detailDiv?.selectFirst("p.author")?.text()
                ?: document.selectFirst("meta[property=com-dongman:webtoon:author]")?.attr("content")
            artist = author

            // 类型标签：<p class="genre g_fantasy">奇幻 都市</p>
            genre = detailDiv?.selectFirst("p.genre")?.text()

            // 简介：<p class="summary"><span class="ellipsis">...</span></p>
            description = detailDiv?.selectFirst("p.summary span.ellipsis")?.text()
                ?: document.selectFirst("meta[property=og:description]")?.attr("content")

            // 更新状态：从页面顶部的"共计X话 来APP花式解锁"容器附近找，
            // 或者从 meta description 里的关键词判断
            val metaDesc = document.selectFirst("meta[name=description]")?.attr("content") ?: ""
            status = when {
                metaDesc.contains("完结") || document.select("p.day_info").text().contains("完结") -> SManga.COMPLETED
                metaDesc.contains("更新") || document.select("p.day_info").text().contains("更新") -> SManga.ONGOING
                document.selectFirst("p.day_info") != null -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }

            // 封面：detail_info 的 style="background-image:url(...)"
            thumbnail_url = detailDiv?.attr("style")
                ?.let { extractUrlFromStyle(it) }
                ?.takeUnless { it.isBlank() }
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: ""
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 章节列表
    //
    // HTML 结构（来自实际抓包）：
    //   <div id="_episodeList">
    //     <ul>
    //       <li id="episode_1" data-episode-no="1">
    //         <a class="workEpisodeListItem"
    //            data-href="/FANTASY/.../viewer?title_no=2795&episode_no=1&...">
    //           <p class="sub_title"><span class="ellipsis">第1话 昨天的我是金上泫(1)</span></p>
    //           <p class="date">2025-7-7</p>
    //         </a>
    //       </li>
    //       ...
    //     </ul>
    //   </div>
    //
    // 注意：
    //   1. 章节链接在 data-href 属性，不在 href（href 为空或 javascript:void）
    //   2. 付费章节 data-free="false"，此处不过滤，让用户凭 Cookie 自行访问
    //   3. 翻页：页面底部有 <div class="paginate"> 结构，检测"下一页"按钮
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
                // 章节 URL 在 data-href，需转换为相对路径
                val dataHref = a.attr("data-href").ifEmpty { a.absUrl("href") }
                if (dataHref.isEmpty()) return@forEach

                val chapter = SChapter.create().apply {
                    // 取 viewer 路径作为 URL，去掉 source/pageModelWay 等追踪参数
                    val cleanUrl = dataHref.substringBefore("&source")
                    url = if (cleanUrl.startsWith("http")) {
                        cleanUrl.removePrefix("https://m.dongmanmanhua.cn")
                            .removePrefix("//m.dongmanmanhua.cn")
                    } else {
                        cleanUrl
                    }
                    name = a.selectFirst("p.sub_title span.ellipsis")?.text()
                        ?: a.selectFirst("p.sub_title")?.text()
                        ?: "第${li.attr("data-episode-no")}话"
                    date_upload = dateFormat.tryParse(
                        a.selectFirst("p.date")?.text()?.trim().orEmpty(),
                    ) ?: 0L
                    chapter_number = li.attr("data-episode-no").toFloatOrNull() ?: -1f
                }
                chapters.add(chapter)
            }

            // 翻页：检测分页按钮
            val nextPage = document.select("div.paginate a[onclick] + a").firstOrNull()
                ?: break
            val nextUrl = nextPage.absUrl("href").ifEmpty { break.also { } }
            if (nextUrl.isEmpty()) break

            val reqHeaders = headersBuilder().apply {
                val cookie = cookieHeader()
                if (cookie.isNotEmpty()) set("Cookie", cookie)
            }.build()
            document = client.newCall(GET(nextUrl, reqHeaders)).execute().asJsoup()
        }

        return chapters
    }

    private val dateFormat = SimpleDateFormat("yyyy-M-d", Locale.ENGLISH)

    // ══════════════════════════════════════════════════════════════════════
    // 阅读页面（viewer）
    //
    // URL 格式：/viewer?titleNo=2795&episodeNo=1
    // 或：/FANTASY/.../viewer?title_no=2795&episode_no=1
    // 图片选择器：div#_imageList img，图片 URL 在 data-url 属性
    // ══════════════════════════════════════════════════════════════════════

    override fun pageListRequest(chapter: SChapter): Request {
        val reqHeaders = headersBuilder().apply {
            val cookie = cookieHeader()
            if (cookie.isNotEmpty()) set("Cookie", cookie)
        }.build()
        return GET(baseUrl + chapter.url, reqHeaders)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("div#_imageList img, div.viewer_lst img")
            .mapIndexed { i, img ->
                Page(i, imageUrl = img.attr("data-url").ifEmpty { img.absUrl("src") })
            }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ══════════════════════════════════════════════════════════════════════
    // 工具函数
    // ══════════════════════════════════════════════════════════════════════

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
        private const val PREF_COOKIE = "pref_cookie"
        private const val PREF_WEBVIEW_LOGIN = "pref_webview_login"
        private const val PREF_WEBVIEW_COOKIE = "pref_webview_cookie"

        private const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
        private const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/114.0"
    }
}
