package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import java.util.Calendar

data class Tag(val name: String, val value: String)

// 排序只作用于“更新”入口。题材/分类、我的漫画不会读取这个值。
// 站点更新页只接收 sortOrder=READ_COUNT / LIKEIT / UPDATE，
// 没有升序/降序参数，所以这里用 Select，避免 Filter.Sort 的 ↑/↓ 造成误解。
private val sortFilter = arrayOf(
    Tag("按人气", "READ_COUNT"),
    Tag("按点赞数", "LIKEIT"),
    Tag("按更新时间", "UPDATE"),
)

// 题材
// 第 0 项只表示“不使用题材筛选”，不负责切换到题材入口。
// “全部题材”拆成独立 state=1，避免默认 0 和用户主动选择“全部”混在一起。
private val themeFilter = arrayOf(
    Tag("不使用题材检索", ""),
    Tag("全部题材", "ALL"),
    Tag("恋爱", "LOVE"),
    Tag("少年", "BOY"),
    Tag("古风", "ANCIENTCHINESE"),
    Tag("奇幻", "FANTASY"),
    Tag("搞笑", "COMEDY"),
    Tag("校园", "CAMPUS"),
    Tag("都市", "METROPOLIS"),
    Tag("治愈", "HEALING"),
    Tag("悬疑", "SUSPENSE"),
    Tag("励志", "INSPIRATIONAL"),
    Tag("影视化", "FILMADAPTATION"),
    Tag("完结", "TERMINATION"),
)

fun getSortFilter(): Array<Tag> = sortFilter
fun getThemeFilter(): Array<Tag> = themeFilter

private fun getCurrentWeekdayCode(): String {
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

fun buildDongmanFilterList(): FilterList {
    val allWeekdays = listOf(
        "MONDAY" to "周一",
        "TUESDAY" to "周二",
        "WEDNESDAY" to "周三",
        "THURSDAY" to "周四",
        "FRIDAY" to "周五",
        "SATURDAY" to "周六",
        "SUNDAY" to "周日",
    )
    val currentWeekday = getCurrentWeekdayCode()
    val weekdayNames = mutableListOf("今天")
    val weekdayValues = mutableListOf("")
    for ((code, name) in allWeekdays) {
        if (code != currentWeekday) {
            weekdayNames.add(name)
            weekdayValues.add(code)
        }
    }
    weekdayNames.add("完结")
    weekdayValues.add("COMPLETE")
    weekdayNames.add("新作")
    weekdayValues.add("NEW")

    return FilterList(
        Filter.Header("作品检索"),
        Filter.Separator(),
        WeekdayFilter(weekdayNames.toTypedArray(), weekdayValues.toTypedArray()),
        SortFilter(),
        ThemeFilter(),
        Filter.Separator(),
        Filter.Header("我的漫画"),
        MyMangaFilter(),
    )
}

class SortFilter : Filter.Select<String>(
    "排序",
    getSortFilter().map { it.name }.toTypedArray(),
    0,
)

class WeekdayFilter(
    private val names: Array<String>,
    private val weekdayValues: Array<String>,
) : Filter.Select<String>("更新", names, 0) {
    fun getSelectedValue(): String = weekdayValues[state]
}

class ThemeFilter : Filter.Select<String>(
    "题材",
    getThemeFilter().map { it.name }.toTypedArray(),
    0,
)

class MyMangaFilter : Filter.Select<String>(
    "我的漫画",
    arrayOf("不使用我的漫画", "最近观看", "我的已购"),
    0,
) {
    fun getSelectedValue(): String = when (state) {
        1 -> "recent"
        2 -> "purchased"
        else -> ""
    }
}
