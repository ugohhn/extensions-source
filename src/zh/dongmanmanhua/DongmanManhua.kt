package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DongmanManhua : HttpSource() {

    override val name = "Dongman Manhua"
    override val lang get() = "zh-Hans"
    override val id get() = 7275979680702931948  // 手机版 sourceId（来自 logcat）
    override val baseUrl = "https://m.dongmanmanhua.cn"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")

    override val client = network.cloudflareClient

    // ── 首页（Popular）──────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int) =
        GET("$baseUrl/?pageName=home", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val entries = document
            .select("a[href*=list?title_no], a[href*=episodeList?titleNo]")
            .distinctBy { it.attr("href") }
            .filter { it.attr("href").isNotEmpty() }
            .map(::mangaFromElement)
            .filter { it.title.isNotEmpty() }
        return MangasPage(entries, false)
    }

    // ── 最新更新（Latest）───────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/dailySchedule?sortOrder=UPDATE&webtoonCompleteType=ONGOING", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val day = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY    -> "div._list_SUNDAY"
            Calendar.MONDAY    -> "div._list_MONDAY"
            Calendar.TUESDAY   -> "div._list_TUESDAY"
            Calendar.WEDNESDAY -> "div._list_WEDNESDAY"
            Calendar.THURSDAY  -> "div._list_THURSDAY"
            Calendar.FRIDAY    -> "div._list_FRIDAY"
            Calendar.SATURDAY  -> "div._list_SATURDAY"
            else               -> "div"
        }
        val entries = document
            .select("div#dailyList > $day li > a, ul.daily_card li a")
            .map(::mangaFromElement)
            .distinctBy { it.url }
            .filter { it.title.isNotEmpty() }
        return MangasPage(entries, false)
    }

    // ── 搜索 ─────────────────────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addPathSegment("search")
            addQueryParameter("keyword", query)
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        // 手机版搜索结果结构：ul._searchResultList li a.cleFix
        val entries = document
            .select("ul._searchResultList li a.cleFix")
            .map(::searchMangaFromElement)
            .filter { it.title.isNotEmpty() }
        val hasNextPage = document.selectFirst("div.more_area, div.paginate a[onclick] + a") != null
        return MangasPage(entries, hasNextPage)
    }

    // ── mangaFromElement（首页 / 最新用）─────────────────────────────

    private fun mangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst(
            "p.subj, .subj .ellipsis, ._items_name_t, .home_genre_t, p.chapter-title-02",
        )?.text() ?: element.attr("title").ifEmpty {
            element.selectFirst("img")?.attr("alt") ?: ""
        }
        thumbnail_url = extractThumbnailUrl(element)
    }

    // ── searchMangaFromElement（搜索结果专用）────────────────────────
    //
    // 手机版搜索结果 HTML 结构：
    //   <a class="cleFix" href="//m.dongmanmanhua.cn/FANTASY/.../list?title_no=2795">
    //     <div class="">
    //       <div class="fl pic"><img src="https://cdn.dongmanmanhua.cn/...jpg"></div>
    //       <div class="fl info">
    //         <p class="subj"><span class="ellipsis">反转练习生</span></p>
    //       </div>
    //     </div>
    //   </a>
    //
    // 注意：class="fl info" 中 Jsoup 的 .info 可以匹配（包含关系）

    private fun searchMangaFromElement(element: Element): SManga = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.selectFirst(".info .subj .ellipsis, p.subj .ellipsis")?.text() ?: ""
        thumbnail_url = extractThumbnailUrl(element)
    }

    // ── 封面图提取（统一逻辑）────────────────────────────────────────
    //
    // 优先级：data-image-url → abs:src → data-src → data-original → style url()
    // 修复原版 bug：原版字节码中赋值和跳转顺序反了，导致懒加载封面始终取不到

    private fun extractThumbnailUrl(element: Element): String {
        val img = element.selectFirst(".pic img, img")
            ?: return extractUrlFromStyle(element.attr("style"))

        img.attr("data-image-url").takeIf { it.isNotEmpty() }?.let { return it }
        img.attr("abs:src").takeIf { it.isNotEmpty() }?.let { return it }
        img.absUrl("data-src").takeIf { it.isNotEmpty() }?.let { return it }
        img.absUrl("data-original").takeIf { it.isNotEmpty() }?.let { return it }
        extractUrlFromStyle(img.attr("style")).takeIf { it.isNotEmpty() }?.let { return it }
        extractUrlFromStyle(element.attr("style")).takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    private fun extractUrlFromStyle(style: String): String {
        if (style.isEmpty()) return ""
        val from = style.indexOf("url(").takeIf { it != -1 }?.plus("url(".length) ?: return ""
        val end = style.indexOf(")", from).takeIf { it != -1 } ?: return ""
        return style.substring(from, end).trim().removeSurrounding("\"").removeSurrounding("'")
    }

    // ── 漫画详情（从桌面版完整移植，选择器对手机版做了补充）────────

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
                    else             -> SManga.UNKNOWN
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
                    ?: discoverPic?.selectFirst("img")?.attr("abs:src")
            }
        }
    }

    // ── 章节列表（带自动翻页，从桌面版移植）─────────────────────────

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
        name = element.selectFirst("span.subj span")!!.text()
        setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
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
