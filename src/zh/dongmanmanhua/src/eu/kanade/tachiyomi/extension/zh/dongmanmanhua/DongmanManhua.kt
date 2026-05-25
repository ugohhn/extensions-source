package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
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

        // 1. User-Agent 预设选择
        ListPreference(screen.context).apply {
            key = PREF_UA
            title = "User-Agent 预设"
            summary = "%s"
            entries = arrayOf("移动版（默认）", "Windows Firefox", "禁用 User-Agent", "自定义（见下方输入框）")
            entryValues = arrayOf(UA_MOBILE, UA_DESKTOP, "", PREF_UA_CUSTOM_FLAG)
            setDefaultValue(UA_MOBILE)
        }.also(screen::addPreference)

        // 2. User-Agent 自定义输入框
        EditTextPreference(screen.context).apply {
            key = PREF_UA_CUSTOM
            title = "User-Agent 自定义值"
            summary = "选择「自定义」时生效\n当前值：${preferences.getString(PREF_UA_CUSTOM, "").orEmpty().ifEmpty { "（未填写）" }}"
            dialogTitle = "输入自定义 User-Agent"
            setDefaultValue("")
        }.also(screen::addPreference)

        // 3. 手动 Cookie
        EditTextPreference(screen.context).apply {
            key = PREF_COOKIE
            title = "Cookie（登录后粘贴）"
            summary = "从浏览器复制完整 Cookie 字符串到这里，用于访问已购付费章节\n当前值：${previewCookie(preferences.getString(PREF_COOKIE, ""))}"
            dialogTitle = "设置 Cookie"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    private fun previewCookie(cookie: String?): String =
        cookie?.take(40)?.ifEmpty { "（未设置）" } ?: "（未设置）"

    private fun cookieHeader(): String =
        preferences.getString(PREF_COOKIE, "").orEmpty()

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
    // 搜索（全部用 /searchResult JSON，start=1+(page-1)*20）
    // ══════════════════════════════════════════════════════════════════════

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val start = 1 + (page - 1) * 20
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
        val json = JSONObject(response.body.string())
        val total = json.optInt("total", 0)
        val start = json.optInt("start", 0)
        val titleList = json.optJSONArray("titleList")

        val entries = mutableListOf<SManga>()
        if (titleList != null) {
            for (i in 0 until titleList.length()) {
                val item = titleList.getJSONObject(i)
                val platform = item.optString("displayPlatform", "ALL")
                if (platform != "ALL" && platform != "WEB") continue
                val manga = SManga.create().apply {
                    val titleNo = item.optString("titleNo", "")
                    // episodeList?titleNo=XXXX 会被服务器301跳转到完整路径
                    // OkHttp跟随跳转后拿到正确的详情页，选择器正常工作
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
        val hasNextPage = (start - 1 + (titleList?.length() ?: 0)) < total
        return MangasPage(entries, hasNextPage)
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
                        name = a.selectFirst("p.sub_title span.ellipsis")?.text()
                            ?: a.selectFirst("p.sub_title")?.text()
                            ?: "第${li.attr("data-episode-no")}话"
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
        val cookie = cookieHeader()
        val reqHeaders = headersBuilder().apply {
            if (cookie.isNotEmpty()) set("Cookie", cookie)
        }.build()
        return GET(baseUrl + chapter.url, reqHeaders)
    }

    // 从 URL query 参数里提取指定字段值
    private fun extractParam(url: String, key: String): String {
        val regex = Regex("""[?&]$key=([^&]+)""")
        return regex.find(url)?.groupValues?.get(1) ?: ""
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        val imageRegex = Regex("""url\s*:\s*"(https://cdn\.dongmanmanhua\.cn/[^"]+)"""")

        // 先尝试直接提取图片（免费或已解锁章节）
        val pages = imageRegex.findAll(html)
            .mapIndexed { i, match -> Page(i, imageUrl = match.groupValues[1]) }
            .toList()
        if (pages.isNotEmpty()) return pages

        // 图片列表为空说明章节未解锁，有登录态则自动扣费后重新请求
        // 已购章节：服务器对 pay 接口幂等处理，不会重复扣费
        val cookie = cookieHeader()
        if (cookie.isEmpty()) return emptyList()

        val reqUrl = response.request.url.toString()
        val titleNo = extractParam(reqUrl, "title_no")
        val episodeNo = extractParam(reqUrl, "episode_no")
        if (titleNo.isEmpty() || episodeNo.isEmpty()) return emptyList()

        val reqHeaders = headersBuilder().apply { set("Cookie", cookie) }.build()

        return try {
            val payUrl = "$baseUrl/episode/unlock/pay" +
                "?title_no=$titleNo&episode_no=$episodeNo&platform=MWEB&client=APP_ANDROID"
            val payResp = client.newCall(GET(payUrl, reqHeaders)).execute()
            val payJson = org.json.JSONObject(payResp.body.string())
            if (payJson.optInt("code") != 200) return emptyList()

            val newHtml = client.newCall(GET(reqUrl, reqHeaders)).execute().body.string()
            imageRegex.findAll(newHtml)
                .mapIndexed { i, match -> Page(i, imageUrl = match.groupValues[1]) }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
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
        private const val PREF_COOKIE = "pref_cookie"

        private const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
        private const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/114.0"
    }
}
