package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.tryParse
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DongmanManhua : HttpSource() {

    override val name = "Dongman Manhua"
    override val lang get() = "zh-Hans"
    override val id get() = 7275979680702931948
    override val baseUrl = "https://m.dongmanmanhua.cn"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
        )

    override val client = network.cloudflareClient

    // 首页（Popular）
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

    // 最新更新（Latest）
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

    // 搜索
    //
    // 第1页：POST /search → HTML，包含小说+漫画混合，共约24条
    // 第2页起：POST /searchResult → JSON，只含漫画（searchType=WEBTOON）
    //   start = 上一页累计条目数（含小说）+ 1，动态维护，不能用 (page-1)*20
    //   必须带 X-Requested-With: XMLHttpRequest，否则返回500
    //   start=0 会500，start=1 起有效
    //
    // nextStartMap：key=关键词, value=下一页的start值
    // 在 parse 时计算存入，在 request 时取出使用，避免成员变量跨搜索污染
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

    // 解析 /search 返回的 HTML（第1页）
    // 保留所有条目（小说+漫画），不过滤
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
            if (keyword.isNotEmpty()) {
                nextStartMap[keyword] = totalEntries + 1
            }
        }

        return MangasPage(entries, hasNextPage)
    }

    // 解析 /searchResult 返回的 JSON（第2页起，只含漫画）
    private fun parseSearchResultJson(response: Response): MangasPage {
        val json = JSONObject(response.body.string())
        val total = json.optInt("total", 0)
        val start = json.optInt("start", 0)
        val titleList = json.optJSONArray("titleList")
        val returnedCount = titleList?.length() ?: 0

        val entries = mutableListOf<SManga>()
        if (titleList != null) {
            for (i in 0 until titleList.length()) {
                val item = titleList.getJSONObject(i)
                val manga = SManga.create().apply {
                    val titleNo = item.optString("titleNo", "")
                    url = "/list?title_no=$titleNo"
                    title = item.optString("title", "")
                    thumbnail_url = item.optString("thumbnail", "")
                        .ifEmpty { item.optString("thumbnailMobile", "") }
                        .ifEmpty { item.optString("representGenreBackgroundImageUrl", "") }
                }
                if (manga.title.isNotEmpty()) entries.add(manga)
            }
        }

        // start 是1-based，已显示条数 = start - 1 + 本页条数
        val hasNextPage = returnedCount > 0 && (start - 1 + returnedCount) < total

        if (hasNextPage) {
            val keyword = extractKeywordFromBody(response)
            if (keyword.isNotEmpty()) {
                nextStartMap[keyword] = start + returnedCount
            }
        }

        return MangasPage(entries, hasNextPage)
    }

    // 从请求 body 里取回 keyword，用作 nextStartMap 的 key
    private fun extractKeywordFromBody(response: Response): String {
        val body = response.request.body
        if (body is FormBody) {
            for (i in 0 until body.size) {
                if (body.name(i) == "keyword") return body.value(i)
            }
        }
        return ""
    }

    // 条目构建
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

    // 封面提取
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

    private fun extractTitleNo(url: String): String {
        val pattern = Regex("""titleNo[=:](\d+)""")
        return pattern.find(url)?.groupValues?.get(1) ?: url
    }

    // 漫画详情
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val detailElement = document.selectFirst(".detail_header .info")
        val infoElement = document.selectFirst("#_asideDetail")
        return SManga.create().apply {
            title = document.selectFirst("h1.subj, h3.subj")!!.text()
            author = detailElement?.selectFirst(".author:nth-of-type(1)")?.ownText()
                ?: detailElement?.selectFirst(".author_area")?.ownText()
            artist = detailElement?.selectFirst(".author:nth-of-type(2)")?.ownText()
                ?: detailElement?.selectFirst(".author_area")?.ownText()
                ?: author
            genre = detailElement?.select(".genre").orEmpty().joinToString { it.text() }
            description = infoElement?.selectFirst("p.summary")?.text()
            status = with(infoElement?.selectFirst("p.day_info")?.text().orEmpty()) {
                when {
                    contains("更新") -> SManga.ONGOING
                    contains("完结") -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            }
            thumbnail_url = run {
                val picElement = document.selectFirst("div.detail_body, div.thmb")
                val discoverPic = document.selectFirst("span.thmb, div.thmb")
                picElement?.attr("style")
                    ?.substringAfter("url(")
                    ?.substringBeforeLast(")")
                    ?.removeSurrounding("\"")
                    ?.removeSurrounding("'")
                    ?.takeUnless { it.isBlank() }
                    ?: discoverPic?.selectFirst("img")?.absUrl("src")
                    ?: ""
            }
        }
    }

    // 章节列表（自动翻页）
    override fun chapterListParse(response: Response): List<SChapter> {
        var document = response.asJsoup()
        var continueParsing = true
        val chapters = mutableListOf<SChapter>()
        while (continueParsing) {
            document.select("ul#_listUl li").forEach { chapters.add(chapterFromElement(it)) }
            val nextPage = document.select("div.paginate a[onclick] + a")
            if (nextPage.isNotEmpty()) {
                val nextUrl = nextPage.first()!!.absUrl("href")
                document = client.newCall(GET(nextUrl, headers)).execute().asJsoup()
            } else {
                continueParsing = false
            }
        }
        return chapters
    }

    private fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        name = element.selectFirst("span.subj span")!!.text()
        setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
        date_upload = dateFormat.tryParse(element.selectFirst("span.date")?.text().orEmpty()) ?: 0L
    }

    private val dateFormat = SimpleDateFormat("yyyy-M-d", Locale.ENGLISH)

    // 阅读页面
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("div#_imageList img, div.viewer_lst img").mapIndexed { i, img ->
            Page(i, imageUrl = img.attr("data-url").ifEmpty { img.absUrl("src") })
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
