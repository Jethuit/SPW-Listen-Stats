@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.Prefs
import com.jethuit.spw.listenstats.data.*
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.config.ConfigHelper
import com.xuncorp.spw.workshop.api.config.ConfigManager
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.LocalDate

/** Models independently cached host config helpers; no real player settings are read. */
private class CachedConfig(private val saved: MutableMap<String, Any>, private val path: Path) : ConfigHelper {
    private val snapshot = mutableMapOf<String, Any>()
    var reloadSucceeds = true
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: String, defaultValue: T): T =
        (snapshot[key].takeIf { (it is String && defaultValue is String) ||
            (it is Boolean && defaultValue is Boolean) } as T?) ?: defaultValue
    override fun set(key: String, value: Any) { snapshot[key] = value }
    override fun reload(): Boolean {
        if (!reloadSucceeds) return false
        snapshot.clear(); snapshot.putAll(saved)
        return true
    }
    override fun save(): Boolean { saved.clear(); saved.putAll(snapshot); return true }
    override fun getConfigPath(): Path = path
}

fun main(args: Array<String>) {
    val today = LocalDate.of(2026, 10, 22)
    check(ChartType.fromStorage(null) == ChartType.Bar && ChartType.fromStorage("invalid") == ChartType.Bar)
    check(RangePreset.fromStorage(null) == RangePreset.Today && RangePreset.fromStorage("invalid") == RangePreset.Today)
    val scale = HeatmapScale.fromSamples(listOf(10L, 20L, 30L, 40L))
    check(scale.thresholds == listOf(17.5, 25.0, 32.5))
    check(listOf(10L, 20L, 30L, 40L).map(scale::level) == listOf(1, 2, 3, 4))
    check(scale.level(0) == 0 && scale.level(-1) == 0)
    check(HeatmapScale.fromSamples(listOf(0L, -1L)).level(5) == 1)
    check(HeatmapScale.fromSamples(listOf(10L)).level(10) == 1)
    val equal = HeatmapScale.fromSamples(List(20) { 10L })
    check(equal.level(10) == 1 && equal.level(11) == 4)
    val ties = HeatmapScale.fromSamples(listOf(10L, 10L, 20L, 20L, 30L, 30L, 40L, 40L))
    check(listOf(10L, 20L, 30L, 40L).map(ties::level) == listOf(1, 2, 3, 4))
    check(HeatmapScale.fromSamples((1L..100L).toList() + 1_000_000L).level(80L) == 4)
    println("PASS quartiles, all four tiers, zero exclusion, ties, sparse samples and outliers")

    val recentStart = today.minusYears(1).plusDays(1)
    val daily = mapOf(recentStart to 10L, today.minusDays(2) to 20L,
        today.minusDays(1) to 30L, today to 40L, recentStart.minusDays(1) to 999_999L,
        today.plusDays(1) to 999_999L, today.minusDays(5) to 0L)
    check(HeatmapScale.daily(daily, today) == scale)
    check(StatsCalculations.dayLevel(20, today, today, scale) == 2)
    check(StatsCalculations.dayLevel(40, today, today.plusDays(1), scale) == 0)
    val leapDay = LocalDate.of(2024, 2, 29)
    check(HeatmapScale.daily(mapOf(LocalDate.of(2023, 3, 1) to 10L,
        leapDay to 40L, LocalDate.of(2023, 2, 28) to 999L), leapDay) ==
        HeatmapScale.fromSamples(listOf(10L, 40L)))
    println("PASS trailing 12-month bounds, leap-day boundary and future exclusion")

    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val weekData = StatsData.empty().copy(dailyMillis = mapOf(
        monday.minusWeeks(3) to 10L, monday.minusWeeks(2) to 20L,
        monday.minusWeeks(1) to 30L, monday to 15L, monday.plusDays(1) to 25L))
    val weekScale = HeatmapScale.weekly(weekData.dailyMillis, today)
    check(weekScale == scale)
    val weeks = StatsCalculations.weeklyCells(weekData, StatsCalculations.heatmapGrid(2026), today)
    check(weeks.filter { it.millis > 0 }.map { it.level } == listOf(1, 2, 3, 4))
    check(weeks.filter { it.millis > 0 }.map { it.filledBlocks } == listOf(2, 4, 6, 7))
    val rolling = StatsCalculations.weeklyCells(weekData, StatsCalculations.heatmapGrid(2026, true, today), today)
    check(rolling.filter { it.millis > 0 }.map { it.level } == listOf(1, 2, 3, 4))
    check(weekData.dailyMillis.values.sum() == 100L)
    println("PASS separate natural-week quartiles, unchanged heights, view-independent colors and unchanged data")

    for (preset in listOf(RangePreset.Selected, RangePreset.Custom)) {
        check(StatsCalculations.rangeAfterHeatmapModeChange(preset, today) == RangePreset.Today)
    }
    for (preset in listOf(RangePreset.Last7, RangePreset.Last30, RangePreset.Month, RangePreset.Today, RangePreset.Custom)) {
        check(StatsCalculations.rangeAfterHeatmapModeChange(preset, null) == preset)
    }
    val clearedRange = StatsCalculations.range(
        StatsCalculations.rangeAfterHeatmapModeChange(RangePreset.Selected, today), today, null, null, null)
    check(clearedRange == DateRange(today, today))
    println("PASS mode switch resets heatmap-linked day/week ranges and preserves manual ranges")

    val saved = mutableMapOf<String, Any>("dailyHighHours" to "6", "chartType" to "bar")
    val helper = CachedConfig(saved, Path.of(args.single()).resolve("mock-config.json"))
    val configManager = Proxy.newProxyInstance(ConfigManager::class.java.classLoader,
        arrayOf(ConfigManager::class.java)) { _, method, _ ->
        check(method.name == "getConfig"); helper
    } as ConfigManager
    val manager = Proxy.newProxyInstance(WorkshopApi.Manager::class.java.classLoader,
        arrayOf(WorkshopApi.Manager::class.java)) { _, method, _ ->
        check(method.name == "createConfigManager"); configManager
    } as WorkshopApi.Manager
    WorkshopApi.instance = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader,
        arrayOf(WorkshopApi::class.java)) { _, method, _ ->
        check(method.name == "getManager"); manager
    } as WorkshopApi
    Prefs.initialize()
    val initial = Prefs.displayPreferences()
    saved["dailyHighHours"] = "24"
    check(Prefs.displayPreferences() == initial)
    Prefs.putString("range", "7")
    check(saved["dailyHighHours"] == "24" && saved["range"] == "7" && saved["chartType"] == "bar")
    helper.reloadSucceeds = false
    Prefs.putString("range", "30")
    check(saved["range"] == "7")
    helper.reloadSucceeds = true
    Prefs.initialize()
    println("PASS removed color preference is ignored, unrelated settings survive and failed reload does not overwrite")
    check(Prefs.displayPreferences().rankingPageSize == 25 && Prefs.displayPreferences().pageAnimations)
    saved["rankingPageSize"] = "50"
    saved["pageAnimations"] = false
    check(Prefs.displayPreferences().let { it.rankingPageSize == 50 && !it.pageAnimations })
    saved["rankingPageSize"] = "100"
    saved["pageAnimations"] = true
    Prefs.initialize()
    check(Prefs.displayPreferences().let { it.rankingPageSize == 100 && it.pageAnimations })
    Prefs.putString("chartType", "line")
    check(saved["rankingPageSize"] == "100" && saved["pageAnimations"] == true)
    println("PASS display defaults, typed switches, external edits, reinitialization and unrelated writes")
    for (raw in listOf("1", "26", "72", "200", " 60 ", "2147483647")) {
        saved["rankingPageSize"] = raw
        check(Prefs.displayPreferences().rankingPageSize == raw.trim().toInt())
    }
    for (raw in listOf<Any>("", "0", "-1", "bad", "2.5", "2147483648", 100)) {
        saved["rankingPageSize"] = raw
        check(Prefs.displayPreferences().rankingPageSize == 25)
    }
    saved["pageAnimations"] = "false"
    check(Prefs.displayPreferences().pageAnimations)
    println("PASS invalid page sizes and wrong switch types fall back to supported defaults")
    for (type in ChartType.entries) {
        Prefs.putString("chartType", type.storage)
        Prefs.initialize()
        check(ChartType.fromStorage(Prefs.getString("chartType", "line")) == type)
    }
    println("PASS bar, line and curve choices survive preference reinitialization")
    println("8 heatmap/display preference regression groups passed using a simulated host API")
}
