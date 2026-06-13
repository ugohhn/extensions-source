package eu.kanade.tachiyomi.extension.zh.dongmanmanhua

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import java.util.Calendar

data class Tag(val name: String, val value: String)

// 入口：这些不是组合筛选，只能三选一。
private val browseModeFilter = arrayOf(
    Tag("更新", "update"),
    Tag("题材", "theme"),
    Tag("我的漫画", "mine"),
)

// 只给“更新”入口使用。
private val sortFilter = arrayOf(
    Tag("按人气", "READ_COUNT"),
    Tag("按点赞数", "LIKEIT"),
    Tag("按更新时间", "UPDATE"),
)

// 只给“我的漫画”入口使用。
private val migrateFilter = arrayOf(
    Tag("最近观看", "recent"),
    Tag("我的已购", "purchased"),
)

// 只给“题材”入口使用。题材接口不带 sortOrder，也不读取排序。
private val themeFilter = arrayOf(
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

fun getBrowseModeFilter(): Array<Tag> = browseModeFilter
fun getSortFilter(): Array<Tag> = sortFilter
fun getMigrateFilter(): Array<Tag> = migrateFilter
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
        "MONDAY" to "周一", "TUESDAY" to "周二", "WEDNESDAY" to "周三",
        "THURSDAY" to "周四", "FRIDAY" to "周五", "SATURDAY" to "周六", "SUNDAY" to "周日",
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
        Filter.Header("先选入口；入口互斥，不会组合筛选；排序只管更新里的周几/完结"),
        BrowseModeFilter(),
        Filter.Separator(),
        Filter.Header("更新入口：默认今天；排序只对周几/完结有效，新作不吃排序"),
        WeekdayFilter(weekdayNames.toTypedArray(), weekdayValues.toTypedArray()),
        SortFilter(),
        Filter.Separator(),
        Filter.Header("题材入口"),
        ThemeFilter(),
        Filter.Separator(),
        Filter.Header("我的漫画入口"),
        MigrateFilter(),
    )
}

class BrowseModeFilter : Filter.Select<String>(
    "入口",
    getBrowseModeFilter().map { it.name }.toTypedArray(),
    0,
)

class SortFilter : Filter.Sort(
    "更新排序",
    getSortFilter().map { it.name }.toTypedArray(),
    Selection(0, true),
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

class MigrateFilter : Filter.Select<String>(
    "我的漫画",
    getMigrateFilter().map { it.name }.toTypedArray(),
    0,
)
