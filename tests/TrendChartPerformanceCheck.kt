@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontFamily
import com.moriafly.salt.ui.LocalTextStyle
import androidx.compose.ui.unit.dp
import com.jethuit.spw.listenstats.data.*
import com.jethuit.spw.listenstats.ui.*
import com.moriafly.salt.ui.SaltTheme
import java.nio.file.*
import java.time.LocalDate
import javax.swing.SwingUtilities
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat

private var timestamp = 1_000_000_000L
private fun render(scene: ImageComposeScene) { timestamp += 16_666_667L; scene.render(timestamp).close() }
private fun counts() = TrendChartProbe.calls.clone()
private fun times() = TrendChartProbe.nanos.clone()
private fun sample(wall: Long, c: LongArray, n: LongArray, invalid: Boolean) = buildJsonObject {
    put("wallNs", wall); put("invalidated", invalid)
    put("geometryPoints", TrendChartProbe.geometryPoints)
    put("calls", JsonArray(c.map(::JsonPrimitive))); put("nanos", JsonArray(n.map(::JsonPrimitive)))
}

private fun makeScene(buckets: List<ChartBucket>): ImageComposeScene = ImageComposeScene(1200, 340, density = Density(1f)) {
    SaltTheme { MaterialTheme {
        CompositionLocalProvider(LocalStatsColors provides LightStatsColors) {
            Box(Modifier.fillMaxSize().drawWith { drawRect(LightStatsColors.pageBackground) }.padding(12.dp)) {
                TrendChart(buckets, ChartType.Curve, showAverage = true, hourly = false, onBucketSelected = {})
            }
        }
    } }
}

private fun buckets(months: Long): List<ChartBucket> {
    val end = LocalDate.of(2026, 10, 31)
    val start = end.minusMonths(months).plusDays(1)
    val values = start.datesUntil(end.plusDays(1)).toList().mapIndexed { i, date ->
        date to (20L + ((i * 37) % 180)) * 60_000L
    }.toMap()
    return StatsCalculations.chartBuckets(StatsData.empty().copy(dailyMillis = values), DateRange(start, end), Granularity.Day)
}

private fun measure(months: Long, round: Int, output: Path): JsonObject {
    val values = buckets(months)
    val preStart = System.nanoTime()
    TrendChartProbe.reset()
    val scene = makeScene(values)
    render(scene)
    val first = sample(System.nanoTime() - preStart, counts(), times(), true)
    try {
        // Drain initial layout/font work; idle control does not invent hover invalidations.
        repeat(3) { render(scene) }
        val idleWasInvalid = scene.hasInvalidations()
        TrendChartProbe.reset()
        val idleStart = System.nanoTime(); render(scene)
        val idle = sample(System.nanoTime() - idleStart, counts(), times(), idleWasInvalid)
        val hover = buildJsonArray {
            repeat(120) { i ->
                TrendChartProbe.reset()
                val begin = System.nanoTime()
                scene.sendPointerEvent(PointerEventType.Move, Offset(80f + i * (1030f / 119f), 100f))
                val invalidated = scene.hasInvalidations()
                render(scene)
                add(sample(System.nanoTime() - begin, counts(), times(), invalidated))
            }
        }
        // With the point unchanged, move only y so that only tooltip position changes.
        scene.sendPointerEvent(PointerEventType.Move, Offset(600f, 80f)); render(scene)
        val sameBucket = buildJsonArray {
            repeat(60) { i ->
                TrendChartProbe.reset()
                val begin = System.nanoTime()
                scene.sendPointerEvent(PointerEventType.Move, Offset(600f, 81f + i))
                val invalidated = scene.hasInvalidations()
                render(scene)
                add(sample(System.nanoTime() - begin, counts(), times(), invalidated))
            }
        }
        if (round == 0) scene.render(timestamp + 16_666_667L).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output.resolve("curve-${months}months.png"), it.bytes) }
        }
        return buildJsonObject {
            put("months", months); put("round", round); put("points", values.size)
            put("first", first); put("idle", idle); put("hover", hover); put("sameBucket", sameBucket)
        }
    } finally { scene.close() }
}

fun main(args: Array<String>) {
    val output = Path.of(args[0]); Files.createDirectories(output)
    val mode = args[1]
    SwingUtilities.invokeAndWait {
        if (mode.endsWith("validation")) { validateCache(output, mode.startsWith("candidate")); return@invokeAndWait }
        // Warm the common native/font/Compose paths with a synthetic one-year chart.
        val warm = makeScene(buckets(12))
        try {
            repeat(35) { i ->
                warm.sendPointerEvent(PointerEventType.Move, Offset(90f + i * 25f, 100f))
                render(warm)
            }
        } finally { warm.close() }
        val results = buildJsonArray {
            val ranges = listOf(1L, 12L, 60L)
            repeat(5) { round ->
                for (months in ranges.drop(round % 3) + ranges.take(round % 3)) {
                    val row = measure(months, round, output)
                    add(row)
                    println("$mode months=$months points=${row["points"]} round=$round first_ms=${row["first"]!!.jsonObject["wallNs"]!!.jsonPrimitive.long / 1_000_000.0}")
                }
            }
        }
        Files.writeString(output.resolve("$mode.json"), results.toString())
        if (mode.endsWith("instrumented")) validateCache(output, mode.startsWith("candidate"))
    }
    println("DONE $mode: five interleaved rounds, synthetic dates only, actual TrendChart; no player launched")
}


/** State transitions and date hits use actual chart callbacks; captures are compared with dev.11. */
private fun validateCache(output: Path, candidate: Boolean) {
    var values by mutableStateOf(buckets(60))
    var width by mutableStateOf(1176)
    var dark by mutableStateOf(false)
    var average by mutableStateOf(true)
    var chart by mutableStateOf(ChartType.Curve)
    var selected by mutableStateOf<Int?>(null)
    var revision by mutableStateOf(0)
    var scale by mutableStateOf(1f)
    var fontScale by mutableStateOf(1f)
    var mono by mutableStateOf(false)
    var hourly by mutableStateOf(false)
    val hits = mutableListOf<String>()
    val records = mutableListOf<JsonObject>()
    val scene = ImageComposeScene(1200, 440, density = Density(1f)) {
        SaltTheme { MaterialTheme {
            val palette = if (dark) DarkStatsColors else LightStatsColors
            CompositionLocalProvider(LocalStatsColors provides palette,
                LocalDensity provides Density(scale, fontScale),
                LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)) {
                Box(Modifier.fillMaxSize().drawWith { drawRect(palette.pageBackground) }.padding(12.dp)) {
                    Box(Modifier.width(width.dp)) {
                        TrendChart(values, chart, average, hourly, revision, selected,
                            onBucketSelected = { selected = it; hits += "$it:${values[it].start}:${values[it].end}:${values[it].hour}" },
                            onBucketDeselected = { selected = null })
                    }
                }
            }
        } }
    }
    fun stage(name: String, geometry: Int?, static: Int?, capture: Boolean = true, change: () -> Unit = {}) {
        TrendChartProbe.reset(); change(); repeat(3) { render(scene) }
        val c = counts()
        if (candidate) {
            if (geometry != null) check(if (geometry < 0) c[4] >= 1 else c[4] == geometry.toLong()) { "$name geometry=${c.toList()}" }
            if (static != null) check(c[1] == static.toLong()) { "$name static=${c.toList()}" }
        }
        records += buildJsonObject { put("name", name); put("calls", JsonArray(c.map(::JsonPrimitive)))
            put("geometryPoints", TrendChartProbe.geometryPoints) }
        if (capture) scene.render(timestamp + 16_666_667L).use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { Files.write(output.resolve("state-$name.png"), it.bytes) }
        }
    }
    fun move(x: Float, y: Float) = scene.sendPointerEvent(PointerEventType.Move, Offset(x, y))
    fun click(x: Float) {
        scene.sendPointerEvent(PointerEventType.Press, Offset(x, 100f))
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, 100f))
    }
    try {
        stage("initial", 1, 1)
        if (candidate) check(TrendChartProbe.geometryPoints == 1826)
        stage("hover", 0, 0) { move(550f, 90f); move(600f, 100f) }
        stage("same-point-tooltip", 0, 0) { move(600f, 120f) }
        stage("viewport", 0, 0) { revision++ }
        stage("select", 0, 0) { selected = 912 }
        stage("deselect", 0, 0) { selected = null }
        stage("data", 1, 1) { values = values.mapIndexed { i, b -> if (i == 912) b.copy(millis = b.millis + 60000) else b } }
        stage("resize", 1, 1) { width = 960 }
        stage("dark", 0, 1) { dark = true }
        stage("average-off", 0, 1) { average = false }
        stage("light", 0, 1) { dark = false }
        stage("font", null, 1) { mono = true }
        // Existing dev.11 misses a density dependency for widestLabel; compare stable fresh-density
        // output separately, while candidate invalidation is asserted here.
        stage("density", -1, null, capture = false) { scale = 1.25f }
        stage("font-scale", -1, null, capture = false) { fontScale = 1.15f }
        stage("reset-density", -1, null, capture = false) { scale = 1f; fontScale = 1f }
        stage("line", 1, 1) { chart = ChartType.Line }
        stage("bar", 0, 1) { chart = ChartType.Bar }
        stage("bar-hover", 0, null) { move(300f, 100f) }
        stage("curve", 1, 1) { chart = ChartType.Curve }
        for ((i, x) in listOf(64f, 480f, 940f).withIndex()) stage("hit-$i", 0, 0) { click(x) }
        stage("empty", 0, 1) { values = emptyList(); selected = null }
        stage("zeros", 0, 1) { values = buckets(1).map { it.copy(millis = 0) } }
        stage("single", 1, 1) { values = buckets(1).take(1) }
        stage("hourly", 1, 1) {
            hourly = true
            val date = java.time.LocalDate.of(2026, 10, 31)
            values = (0..23).map { ChartBucket("%02d:00".format(it), "2026-10-31 $it:00", (it + 1) * 60_000L, date, date, it) }
        }
        Files.writeString(output.resolve("validation.json"), buildJsonObject {
            put("candidate", candidate); put("stages", JsonArray(records)); put("hits", JsonArray(hits.map(::JsonPrimitive)))
        }.toString())
    } finally { scene.close() }
}
