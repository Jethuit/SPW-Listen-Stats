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
    val default = HeatmapScale.DEFAULT_HIGH_MILLIS
    check(default == 8_100_000L && HeatmapScale.parseHours("2.25") == default)
    check(HeatmapScale.parseHours(" 6 ") == 21_600_000L)
    check(HeatmapScale.parseHours("1.5") == 5_400_000L)
    check(HeatmapScale.parseHours("24") == 86_400_000L)
    for (raw in listOf(null, "", " ", "no", "0", "-1", "24.01", "NaN", "Infinity", "1e400", "1e-100")) {
        check(HeatmapScale.parseHours(raw) == default) { "Invalid hours accepted: $raw" }
    }
    println("PASS decimal hours, default, 24-hour boundary and invalid-input fallback")

    for (high in listOf(default, 21_600_000L, 5_400_000L, 7L, 86_400_000L)) {
        val first = (high + 5L) / 6L
        val second = (high + 1L) / 2L
        check(HeatmapScale.level(0, high) == 0)
        check(HeatmapScale.level(-1, high) == 0)
        check(HeatmapScale.level(first, high) == 2)
        check(HeatmapScale.level(second - 1, high) == 2)
        check(HeatmapScale.level(second, high) == 3)
        check(HeatmapScale.level(high - 1, high) == 3)
        check(HeatmapScale.level(high, high) == 4)
        check(HeatmapScale.level(Long.MAX_VALUE, high) == 4)
        if (first > 1) check(HeatmapScale.level(first - 1, high) == 1)
    }
    check(HeatmapScale.level(1_350_000L - 1) == 1)
    check(HeatmapScale.level(1_350_000L) == 2)
    check(HeatmapScale.level(4_050_000L) == 3)
    check(HeatmapScale.level(8_100_000L) == 4)
    check(HeatmapScale.level(default, 0) == 4)
    println("PASS exact millisecond boundaries at one-sixth, half and full threshold")

    val today = LocalDate.of(2026, 10, 20)
    check(StatsCalculations.dayLevel(3_600_000L, today, today, 21_600_000L) == 2)
    check(StatsCalculations.dayLevel(3_600_000L, today, today.minusDays(1), default) == 2)
    check(StatsCalculations.dayLevel(Long.MAX_VALUE, today, today.plusDays(1), default) == 0)
    println("PASS setting reaches daily heatmap calculation while future days stay empty")

    val saved = mutableMapOf<String, Any>(HeatmapScale.PREFERENCE_KEY to "6", "chartType" to "bar")
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
    check(Prefs.dailyHighMillis() == 21_600_000L)
    saved[HeatmapScale.PREFERENCE_KEY] = "12"
    check(Prefs.dailyHighMillis() == 43_200_000L)
    saved[HeatmapScale.PREFERENCE_KEY] = "6"
    Prefs.putString("range", "7")
    check(saved[HeatmapScale.PREFERENCE_KEY] == "6" && saved["range"] == "7" && saved["chartType"] == "bar")
    Prefs.initialize()
    check(Prefs.dailyHighMillis() == 21_600_000L)
    println("PASS cached preferences reload host edits, survive reinitialization and preserve unrelated settings")

    for (raw in listOf<Any>("bad", "0", "30", 6.0)) {
        saved[HeatmapScale.PREFERENCE_KEY] = raw
        check(Prefs.dailyHighMillis() == default)
    }
    saved[HeatmapScale.PREFERENCE_KEY] = "6"
    helper.reloadSucceeds = false
    Prefs.putString("range", "30")
    check(saved["range"] == "7" && saved[HeatmapScale.PREFERENCE_KEY] == "6")
    println("PASS invalid saved values fall back safely; failed reload never overwrites host settings")

    val data = StatsData.empty().copy(dailyMillis = mapOf(today to 3_600_000L, today.minusDays(7) to 1_800_000L))
    helper.reloadSucceeds = true
    val weekly = StatsCalculations.weeklyCells(data, StatsCalculations.heatmapGrid(2026), today)
    Prefs.dailyHighMillis()
    saved[HeatmapScale.PREFERENCE_KEY] = "2.25"
    Prefs.dailyHighMillis()
    check(StatsCalculations.weeklyCells(data, StatsCalculations.heatmapGrid(2026), today) == weekly)
    check(data.dailyMillis.values.sum() == 5_400_000L)
    println("PASS weekly mode and stored listening durations are unaffected")
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
    println("8 heatmap/display preference regression groups passed using a simulated host API")
}
