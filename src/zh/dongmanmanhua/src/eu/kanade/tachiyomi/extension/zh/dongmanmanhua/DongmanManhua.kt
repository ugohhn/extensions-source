package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import android.content.Intent
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
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
        val ctx = screen.context

        // 1. 登录按钮：点击打开 WebView 登录页，完成后 Cookie 自动存入 SharedPreferences
        SwitchPreferenceCompat(ctx).apply {
            key = PREF_LOGIN_TRIGGER
            title = "咚漫账号登录"
            summary = if (DongmanLoginActivity.isLoggedIn(ctx)) {
                "已登录（NEO_SES 有效）\n点击打开登录页重新登录"
            } else {
                "未登录\n点击打开浏览器登录咚漫"
            }
            // 不保存开关状态，仅作为点击触发器
            setOnPreferenceChangeListener { _, _ ->
                ctx.startActivity(Intent(ctx, DongmanLoginActivity::class.java))
                false
            }
        }.also(screen::addPreference)

        // 2. 退出登录：清除持久化 Cookie
        SwitchPreferenceCompat(ctx).apply {
            key = PREF_LOGOUT_TRIGGER
            title = "退出登录"
            summary = "清除本地保存的登录 Cookie（NEO_SES / NEO_CHK）"
            setOnPreferenceChangeListener { _, _ ->
                DongmanLoginActivity.logout(ctx)
                false
            }
        }.also(screen::addPreference)

        // 3. 自动扣费开关
        SwitchPreferenceCompat(ctx).apply {
            key = PREF_AUTO_PAY
            title = "自动购买付费章节"
            summary = "开启后，打开未购付费章节时将自动扣费解锁\n余额不足时会提示错误\n需要先登录"
            setDefaultValue(false)
        }.also(screen::addPreference)

        // 4. User-Agent 预设选择
        ListPreference(ctx).apply {
            key = PREF_UA
            title = "User-Agent 预设"
            summary = "%s"
            entries = arrayOf("移动版（默认）", "Windows Firefox", "禁用 User-Agent", "自定义（见下方输入框）")
            entryValues = arrayOf(UA_MOBILE, UA_DESKTOP, "", PREF_UA_CUSTOM_FLAG)
            setDefaultValue(UA_MOBILE)
        }.also(screen::addPreference)

        // 5. User-Agent 自定义输入框
        EditTextPreference(ctx).apply {
            key = PREF_UA_CUSTOM
            title = "User-Agent 自定义值"
            summary = "选择「自定义」时生效\n当前值：${preferences.getString(PREF_UA_CUSTOM, "").orEmpty().ifEmpty { "（未填写）" }}"
            dialogTitle = "输入自定义 User-Agent"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Cookie / UA 读取
    // ══════════════════════════════════════════════════════════════════════

    private fun cookieHeader(): String {
        val ctx = Injekt.get<android.app.Application>()
        return DongmanLoginActivity.buildCookieHeader(ctx)
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

            // 类型标签 + 更新周期拼在一起，如"奇幻 都市 每周二更新"
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
                    name = if (isFree) rawName else "🔒 $rawName"
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

        // 倒序：最新话在前，第1话在末尾
        return chapters.reversed()
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
        val params = "title_no=$titleNo&episode_no=$episodeNo&platform=MWEB&client=APP_ANDROID"
        val savedCookie = cookieHeader()
        val reqHeaders = headersBuilder()
            .set("Referer", "$baseUrl/FANTASY/list?title_no=$titleNo")
            .set("X-Requested-With", "XMLHttpRequest")
            .apply { if (savedCookie.isNotEmpty()) set("Cookie", savedCookie) }
            .build()

        val priceResp = client.newCall(
            GET("$baseUrl/episode/unlock/getEpisodePrice?$params", reqHeaders),
        ).execute()
        val priceJson = org.json.JSONObject(priceResp.body.string())
        val data = priceJson.optJSONObject("data") ?: return

        val isFree = data.optBoolean("free", true)
        if (isFree) return

        val isLimit = data.optBoolean("isLimit", false)
        if (isLimit) return

        val price = data.optInt("price", 0)
        val coinCount = data.optInt("coinCount", 0)
        val episodeName = data.optString("episodeName", "本话")

        if (coinCount < price) {
            throw Exception("余额不足：$episodeName 需要 $price 币，当前余额 $coinCount 币，请前往咚漫充值")
        }

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
                .build(),
        )
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // ══════════════════════════════════════════════════════════════════════
    // 工具函数
    // ══════════════════════════════════════════════════════════════════════

    // 从页面内嵌 JS 提取 serial_status 值
    // SERIES=连载中, TERMINATION=已完结, REST=暂停更新
    private fun extractSerialStatus(html: String): String {
        val regex = Regex("""serial_status['":\s]+([A-Z]+)""")
        return regex.find(html)?.groupValues?.get(1) ?: ""
    }

    // 从 info_update 区域提取更新周期标签，如"每周二更新"
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

        private const val PREF_AUTO_PAY = "pref_auto_pay"
        private const val PREF_LOGIN_TRIGGER = "pref_login_trigger"
        private const val PREF_LOGOUT_TRIGGER = "pref_logout_trigger"

        private const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"
        private const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/114.0"
    }
}
