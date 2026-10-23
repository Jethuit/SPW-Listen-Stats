import com.jethuit.spw.listenstats.ui.UiGeometry
import com.jethuit.spw.listenstats.ui.TrendAxis
import com.jethuit.spw.listenstats.ui.hostStatsColors
import com.jethuit.spw.listenstats.ui.LightStatsColors
import com.jethuit.spw.listenstats.ui.DarkStatsColors
import com.jethuit.spw.listenstats.ui.materialStatsColors
import com.jethuit.spw.listenstats.ui.rankingSafePage
import com.jethuit.spw.listenstats.ui.rankingTotalPages
import com.jethuit.spw.listenstats.data.*
import java.time.LocalDate
import java.nio.file.Files
import java.nio.file.Path
import com.jethuit.spw.listenstats.ui.HostFont
import androidx.compose.ui.text.font.FontFamily

fun main(args: Array<String>) {
    val fontBytes = HostFont.readBytes("MiSansRegular", null)!!
    val fontDir = Path.of(args.single()).resolve("fonts")
    Files.createDirectories(fontDir)
    val custom = fontDir.resolve("custom.ttf")
    Files.write(custom, fontBytes)
    val fontName = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, custom.toFile())
        .getFamily(java.util.Locale.ROOT)
    check(fontName.contains("MiSans", ignoreCase = true)) { "Unexpected host font: $fontName" }
    check(HostFont.load("MiSansRegular", null) != FontFamily.Default)
    check(HostFont.load("Custom", custom.toString()) != FontFamily.Default)
    check(HostFont.load("FollowSystem", custom.toString()) == FontFamily.Default)
    Files.writeString(custom, "invalid font")
    check(HostFont.load("Custom", custom.toString()) == FontFamily.Default)
    check(HostFont.load("Custom", fontDir.resolve("missing.ttf").toString()) == FontFamily.Default)
    println("PASS host MiSans resource, custom font, system selection and invalid-file fallback")
    check(rankingTotalPages(72) == 3) { "72 tracks must use three 25-row pages" }
    check(rankingSafePage(72, 3) == 3) { "page three must survive the host clamp" }
    check(rankingSafePage(72, 4) == 3) { "out-of-range page should clamp to the last page" }
    check(rankingTotalPages(25) == 1) { "exactly 25 tracks should fit on one page" }
    check(rankingTotalPages(600) == 24) { "600 tracks must produce 24 pages, not a fixed count" }
    check(rankingTotalPages(10_000) == 400) { "large libraries must stay dynamic" }
    check(rankingTotalPages(101, 25) == 5 && rankingTotalPages(101, 50) == 3 && rankingTotalPages(101, 100) == 2)
    check(rankingSafePage(101, 5, 50) == 3 && rankingSafePage(101, 5, 100) == 2)
    check(rankingTotalPages(0, 100) == 1 && rankingTotalPages(Int.MAX_VALUE, 100) > 0)
    println("PASS default and configurable pagination, empty libraries, clamping and large counts")
    for (scale in listOf(1.0, 1.25, 1.5, 2.0)) {
        fun px(value: Int) = (value * scale).toInt()
        val y = UiGeometry.popupAxis(px(550), px(100), px(90), px(1112), px(14))
        check(y == px(550) + px(100) + px(14)) { "tooltip lost its chart anchor at $scale" }
        val flipped = UiGeometry.popupAxis(px(1000), px(70), px(90), px(1112), px(14))
        check(flipped >= 0 && flipped + px(90) <= px(1112))
        check(flipped < px(1070))
    }
    check(UiGeometry.popupAxis(-500, 10, 200, 100, 14) == 0)
    println("PASS popup anchor, edges and 100/125/150/200 percent DPI")
    for (step in listOf(0.00025, 0.025, 0.25, 0.5, 1.0, 1.25, 2.5, 25.0)) {
        val labels = (0..4).map { UiGeometry.tick(it * step, step) }
        check(labels.distinct().size == 5) { "duplicate ticks: $labels" }
        labels.forEachIndexed { index, text -> check(kotlin.math.abs(text.toDouble() - index * step) < 0.000001) }
    }
    println("PASS empty, fractional minute and hour ticks are distinct and accurate")
    for (minutes in listOf(0L, 1L, 20L, 59L, 60L, 61L)) {
        val axis = UiGeometry.trendAxis(minutes * 60_000L, hourly = true)
        check(axis.caption == "时长/min" && axis.unitMillis == 60_000.0)
        val labels = (0..4).map { UiGeometry.tick(axis.maximum * it / 4, axis.maximum / 4) }
        check(labels == listOf("0", "15", "30", "45", "60")) { "Single-day scale: $labels" }
    }
    check(UiGeometry.trendAxis(0L, hourly = false) == TrendAxis(false, 4.0))
    val multipleDays = UiGeometry.trendAxis(59 * 60_000L, hourly = false)
    check(multipleDays.caption == "时长/min" && multipleDays.maximum == 100.0)
    val fullDay = UiGeometry.trendAxis(24 * 3_600_000L, hourly = false)
    check(fullDay.caption == "时长/h" && fullDay.maximum == 50.0)
    println("PASS single-day 0/59/60-minute boundaries keep 0–60 min; multi-day scaling is preserved")
    val today = LocalDate.of(2026, 10, 18)
    val data = StatsData.empty().copy(dailyMillis = mapOf(today to 1_000L,
        today.plusDays(1) to 20_000L, LocalDate.of(2025, 12, 31) to 50_000L))
    val weeks = StatsCalculations.weeklyCells(data, StatsCalculations.heatmapGrid(2026), today)
    check(weeks.sumOf { it.millis } == 1_000L)
    for (year in listOf(2000, 2024, 2025, 2026, 2100)) {
        val grid = StatsCalculations.heatmapGrid(year)
        val count = (0 until grid.weeks * 7).count { grid.gridStart.plusDays(it.toLong()).year == year }
        check(count == grid.firstDay.lengthOfYear())
    }
    println("PASS week aggregation excludes future and adjacent-year records")
    val hours = StatsCalculations.chartBuckets(data, DateRange(today, today), Granularity.Day)
    check(hours.size == 24 && hours.last().longLabel.endsWith("23:00–24:00"))
    println("PASS midnight interval ends at 24:00")
    for (base in listOf(LightStatsColors, DarkStatsColors)) {
        for (style in listOf("WindowsAcrylic", "WindowsMica", "WindowsMicaAlt")) {
            val material = materialStatsColors(base, style, true)
            check(material.pageBackground.alpha == 0f)
            if (base.isDark && style == "WindowsAcrylic") {
                val nativeCard = androidx.compose.ui.graphics.Color(0xFF5F5F5F)
                check(material.cardBackground == nativeCard && material.controlBackground == nativeCard)
                check(material.subtle == nativeCard)
            } else {
                check(material.cardBackground.alpha in 0.1f..0.99f)
            }
            check(material.text.alpha == 1f && material.tooltipBackground == base.tooltipBackground)
            check(material.background == base.background && material.tooltipText == base.tooltipText)
            // All dark materials and light Acrylic use calibrated fills.
            // Light Mica / Mica Alt retain their surfaces, with a native darkening hover.
            if (!base.isDark && style != "WindowsAcrylic") {
                check(material == base.copy(pageBackground = androidx.compose.ui.graphics.Color.Transparent,
                    cardBackground = base.cardBackground.copy(alpha = 0.72f),
                    controlBackground = base.controlBackground.copy(alpha = 0.72f),
                    subtle = base.subtle.copy(alpha = 0.55f), hover = base.hover.copy(alpha = 0.65f),
                    rankingRowHover = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.08f)))
            }
            check(materialStatsColors(base, style, false) === base)
        }
        for (style in listOf(null, "None", "UnknownStyle")) {
            check(materialStatsColors(base, style, true) === base)
        }
    }
    for (systemDark in listOf(false, true)) {
        check(hostStatsColors(false, systemDark) === LightStatsColors)
        check(hostStatsColors(true, systemDark) === DarkStatsColors)
        check(hostStatsColors(null, systemDark).isDark == systemDark)
    }
    println("PASS host light/dark selection overrides OS; OS fallback and safe material wrapping remain supported")
    println("8 UI regression groups passed; rendered UI still requires user review")
}
