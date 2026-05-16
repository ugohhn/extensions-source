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

    // 搜索分页：记录第一页实际条目数
    private var firstPageSize = 0

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36",
        )

    override val client = network.cloudflareClient

    // ── 首页（Popular）──────────────────────────────────────────────────
    override fun popularMangaRequest(page: Int) =
        GET("$baseUrl/?pageName=home", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val entries = document
            .select("a[href*=list?title_no], a[href*=episodeList?titleNo]")
            .filter { it.attr("href").isNotEmpty() }
            .map(::mangaFromElement)
            .filter { it.title.isNotEmpty() }
            .distinctBy { extractTitleNo(it.url) }  // 基于漫画ID去重
        return MangasPage(entries, false)
    }

    // ── 最新更新（Latest）───────────────────────────────────────────────
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
            .filter { it.title.isNotEmpty() }
            .distinctBy { extractTitleNo(it.url) }  // 基于漫画ID去重
        return MangasPage(entries, false)
    }

    // ── 搜索（支持翻页，使用您提供的修复）──────────────────────────────
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val bodyBuilder = FormBody.Builder()
            .add("searchType", "WEBTOON")
            .add("keyword", query)

        // 第2页及以后，添加 start 参数
        if (page > 1) {
            // 第一页解析后会记录 firstPageSize（例如 25）
            val start = if (firstPageSize > 0) firstPageSize + (page - 2) * 20 else 25
            bodyBuilder.add("start", start.toString())
        }

        val body = bodyBuilder.build()
        val headers = headersBuilder()
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/search")
            .set("Content-Type", "application/x-www-form-urlencoded")
            .build()

        // 第一页用 /search，后续页用 /searchResult
        val url = if (page == 1) "$baseUrl/search" else "$baseUrl/searchResult"
        return POST(url, headers, body)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val url = response.request.url.toString()
        return if (url.contains("/searchResult")) {
            parseSearchJson(response)
        } else {
            parseSearchHtml(response)
        }
    }

    private fun parseSearchHtml(response: Response): MangasPage {
        val document = response.asJsoup()
        val entries = document
            .select("ul._searchResultList li a.cleFix")
            .map(::searchMangaFromElement)
            .filter { it.title.isNotEmpty() }
            .distinctBy { extractTitleNo(it.url) }  // 基于ID去重

        // 记录第一页实际条数（含置顶）
        firstPageSize = entries.size

        // 从页面中获取总条数，判断是否有下一页（选择器可能需要根据实际页面调整）
        val totalElem = document.selectFirst(".num strong, ._totalCount")
        val total = totalElem?.attr("data-total")?.toIntOrNull()
            ?: totalElem?.text()?.toIntOrNull()
            ?: 0
        val hasNextPage = total > firstPageSize

        return MangasPage(entries, hasNextPage)
    }

    private fun parseSearchJson(response: Response): MangasPage {
        val body = response.body?.string().orEmpty()
        if (body.isEmpty()) return MangasPage(emptyList(), false)

        val entries = mutableListOf<SManga>()
        try {
            val json = JSONObject(body)
            val dataArray = json.getJSONArray("data")

            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val titleNo = item.optString("titleNo")
                val title = item.optString("title")
                val thumbnail = item.optString("thumbnailMobile")

                val manga = SManga.create().apply {
                    this.title = title
                    // 构造详情页 URL（区分漫画和小说）
                    setUrlWithoutDomain(
                        if (titleNo.startsWith("100000")) {
                            "/novel/novelInfo?novelId=$titleNo"
                        } else {
                            "/viewer?titleNo=$titleNo&episodeNo=1"
                        }
                    )
                    thumbnail_url = if (thumbnail.isNotEmpty()) {
                        "https://cdn.dongmanmanhua.cn$thumbnail"
                    } else {
                        ""
                    }
                }
                entries.add(manga)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 如果返回数量少于20，说明是最后一页
        val hasNextPage = entries.size >= 20
        return MangasPage(entries, hasNextPage)
    }

    // ── 漫画条目构建（标题选择器保持第三版）─────────────────────────────
    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.selectFirst(
            "p.subj, .subj .ellipsis, ._items_name_t, .home_genre_t, p.chapter-title-02",
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

    // ── 封面提取（正则修复 + 增加 .chapter-img-c 容器）──────────────────
    private fun extractThumbnailUrl(element: Element): String {
        // 1. 尝试从 img 标签获取
        val img = element.selectFirst(".pic img, img, a img")
        if (img != null) {
            img.attr("data-image-url").takeIf { it.isNotEmpty() }?.let { return it }
            img.attr("data-src").takeIf { it.isNotEmpty() }?.let { return it }
            img.absUrl("src").takeIf { it.isNotEmpty() }?.let { return it }
            img.attr("data-original").takeIf { it.isNotEmpty() }?.let { return it }
            img.attr("data-url").takeIf { it.isNotEmpty() }?.let { return it }
            img.attr("data-cover").takeIf { it.isNotEmpty() }?.let { return it }
            extractUrlFromStyle(img.attr("style")).takeIf { it.isNotEmpty() }?.let { return it }
        }

        // 2. 从当前元素的 style 中提取
        val style = element.attr("style")
        if (style.isNotEmpty()) {
            extractUrlFromStyle(style).takeIf { it.isNotEmpty() }?.let { return it }
        }

        // 3. 检查父级容器（.pic, .thmb, .chapter-img-c）的 style 或其子元素
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
                picImg.absUrl("src").takeIf { it.isNotEmpty() }?.let { return it }
                picImg.attr("data-original").takeIf { it.isNotEmpty() }?.let { return it }
                picImg.attr("data-url").takeIf { it.isNotEmpty() }?.let { return it }
                picImg.attr("data-cover").takeIf { it.isNotEmpty() }?.let { return it }
                extractUrlFromStyle(picImg.attr("style")).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }

        // 4. 直接提取元素属性中的图片URL
        element.attr("data-image-url").takeIf { it.isNotEmpty() }?.let { return it }
        element.attr("data-cover").takeIf { it.isNotEmpty() }?.let { return it }
        element.attr("data-thumbnail").takeIf { it.isNotEmpty() }?.let { return it }

        return ""
    }

    private fun extractUrlFromStyle(style: String): String {
        if (style.isEmpty()) return ""
        // 同时匹配 background 和 background-image
        val regex = Regex("""background(?:-image)?\s*:\s*url\(['"]?([^'"()]+)['"]?\)""")
        val match = regex.find(style)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        // 兜底
        val from = style.indexOf("url(").takeIf { it != -1 }?.plus(4) ?: return ""
        val end = style.indexOf(")", from).takeIf { it != -1 } ?: return ""
        return style.substring(from, end).trim().removeSurrounding("\"").removeSurrounding("'")
    }

    // 从 URL 中提取漫画真实 ID（支持 titleNo= 和 title_no=）
    private fun extractTitleNo(url: String): String {
        val pattern = Regex("""title_?no[=:](\d+)""")
        return pattern.find(url)?.groupValues?.get(1) ?: url
    }

    // ── 漫画详情（去除 !! 防止崩溃）────────────────────────────────────
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val detailElement = document.selectFirst(".detail_header .info")
        val infoElement = document.selectFirst("#_asideDetail")

        return SManga.create().apply {
            title = document.selectFirst("h1.subj, h3.subj")?.text() ?: "无标题"
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

    // ── 章节列表（自动翻页）─────────────────────────────────────────────
    override fun chapterListParse(response: Response): List<SChapter> {
        var document = response.asJsoup()
        var continueParsing = true
        val chapters = mutableListOf<SChapter>()

        while (continueParsing) {
            document.select("ul#_listUl li").forEach { chapters.add(chapterFromElement(it)) }
            val nextPage = document.select("div.paginate a[onclick] + a")
            if (nextPage.isNotEmpty()) {
                document = client.newCall(GET(nextPage.attr("abs:href"), headers)).execute().asJsoup()
            } else {
                continueParsing = false
            }
        }
        return chapters
    }

    private fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        name = element.selectFirst("span.subj span")?.text() ?: "未知章节"
        setUrlWithoutDomain(element.selectFirst("a")?.absUrl("href") ?: "")
        date_upload = dateFormat.tryParse(element.selectFirst("span.date")?.text())
    }

    private val dateFormat = SimpleDateFormat("yyyy-M-d", Locale.ENGLISH)

    // ── 阅读页面 ─────────────────────────────────────────────────────────
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("div#_imageList img, div.viewer_lst img").mapIndexed { i, img ->
            Page(i, imageUrl = img.attr("data-url").ifEmpty { img.absUrl("src") })
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
}
