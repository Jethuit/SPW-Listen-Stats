@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.jethuit.spw.listenstats.data.CoverStore
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.math.sin

private fun encoded(image: BufferedImage) = ByteArrayOutputStream().also {
    check(ImageIO.write(image, "png", it))
}.toByteArray()

private fun pixels(image: ImageBitmap) = IntArray(image.width * image.height).also { image.readPixels(it) }
private fun buffered(image: ImageBitmap) = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also {
    it.setRGB(0, 0, image.width, image.height, pixels(image), 0, image.width)
}

/** Previous one-pass path, kept here only as a visual/aliasing baseline. */
private fun oldThumbnail(bytes: ByteArray, size: Int): ImageBitmap = Image.makeFromEncoded(bytes).use { source ->
    Surface.makeRasterN32Premul(size, size).use { surface ->
        val scale = maxOf(size.toFloat() / source.width, size.toFloat() / source.height)
        val w = source.width * scale
        val h = source.height * scale
        surface.canvas.clear(0)
        surface.canvas.drawImageRect(source, Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
            Rect.makeXYWH((size - w) / 2, (size - h) / 2, w, h), SamplingMode.LINEAR, null, true)
        surface.makeImageSnapshot().toComposeImageBitmap()
    }
}

fun main(args: Array<String>) = runBlocking {
    val output = Path.of(args.single())
    Files.createDirectories(output)
    ImageIO.setUseCache(false)
    val checker = BufferedImage(2048, 2048, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until checker.height) for (x in 0 until checker.width)
        checker.setRGB(x, y, if ((x + y) % 2 == 0) -0x1000000 else -1)
    val detail = BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until detail.height) for (x in 0 until detail.width) {
        val wave = sin(x * 0.8 + y * 0.17) * 55
        detail.setRGB(x, y, Color((140 + wave).toInt(), (100 + wave).toInt(), (155 - wave).toInt()).rgb)
    }
    detail.createGraphics().also { g ->
        g.color = Color(240, 182, 94); g.fillOval(230, 120, 540, 640)
        g.color = Color(35, 35, 55); g.fillOval(280, 120, 460, 170)
        g.fillOval(365, 390, 45, 30); g.fillOval(610, 390, 45, 30)
        g.color = Color(155, 60, 65); g.fillArc(420, 520, 180, 100, 180, 180)
        g.color = Color(25, 40, 90); g.fillRect(180, 775, 670, 220)
        g.font = Font("SansSerif", Font.BOLD, 96); g.color = Color.WHITE
        g.drawString("SYNTHETIC", 205, 915)
        g.dispose()
    }
    val wide = BufferedImage(600, 300, BufferedImage.TYPE_INT_ARGB)
    wide.createGraphics().also { g ->
        g.color = Color.RED; g.fillRect(0, 0, 150, 300)
        g.color = Color.GREEN; g.fillRect(150, 0, 300, 300)
        g.color = Color.BLUE; g.fillRect(450, 0, 150, 300); g.dispose()
    }
    val alpha = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
    alpha.createGraphics().also { g ->
        g.color = Color(255, 0, 0, 128); g.fillRect(128, 128, 256, 256); g.dispose()
    }
    val exact = BufferedImage(60, 60, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until 60) for (x in 0 until 60) exact.setRGB(x, y, if ((x + y) % 2 == 0) Color.RED.rgb else Color.BLUE.rgb)
    val sources = mapOf("checker" to encoded(checker), "detail" to encoded(detail),
        "wide" to encoded(wide), "alpha" to encoded(alpha), "exact" to encoded(exact))
    val calls = mutableMapOf<String, Int>()
    val library = Proxy.newProxyInstance(WorkshopApi.Library::class.java.classLoader,
        arrayOf(WorkshopApi.Library::class.java)) { _, method, arguments ->
        check(method.name == "getCoverById")
        val id = arguments!![0] as String
        synchronized(calls) { calls[id] = (calls[id] ?: 0) + 1 }
        CompletableFuture.completedFuture(sources.getValue(id))
    }
    WorkshopApi.instance = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader,
        arrayOf(WorkshopApi::class.java)) { _, method, _ -> check(method.name == "getLibrary"); library } as WorkshopApi
    CoverStore.clear()
    try {
        val results = mutableMapOf<String, ImageBitmap>()
        for ((id, bytes) in sources) {
            val started = System.nanoTime()
            val actual = checkNotNull(CoverStore.load(id, 60))
            check(actual.width == 60 && actual.height == 60)
            results[id] = actual
            check(CoverStore.load(id, 60) === actual && calls[id] == 1)
            ImageIO.write(buffered(actual), "png", output.resolve("$id-new.png").toFile())
            ImageIO.write(buffered(oldThumbnail(bytes, 60)), "png", output.resolve("$id-old.png").toFile())
            println("thumbnail $id total_ms=${(System.nanoTime() - started) / 1_000_000}")
        }
        fun error(image: ImageBitmap): Double = pixels(image).map { abs((it ushr 16 and 255) - 127.5) }.average()
        val oldError = error(oldThumbnail(sources.getValue("checker"), 60))
        val newError = error(results.getValue("checker"))
        check(newError < 2 && oldError > 15) { "Checkerboard aliasing old=$oldError new=$newError" }
        println("PASS high-frequency alias error old=$oldError new=$newError (ideal 127.5 gray)")
        check(pixels(results.getValue("wide")).all { (it ushr 8 and 255) >= 250 && (it ushr 16 and 255) < 5 && (it and 255) < 5 })
        val transparent = pixels(results.getValue("alpha"))
        check(transparent.first() ushr 24 == 0)
        val middle = transparent[30 * 60 + 30]
        check((middle ushr 24) in 126..130 && (middle ushr 16 and 255) > 250 && (middle and 0xffff) == 0)
        check(pixels(results.getValue("exact")).contentEquals(exact.getRGB(0, 0, 60, 60, null, 0, 60)))
        for (size in listOf(48, 72, 96)) {
            checkNotNull(CoverStore.load("detail", size)).let { check(it.width == size && it.height == size) }
        }
        println("PASS centered square crop, alpha, exact-size fidelity, multiple DPI sizes and cached reuse")
        val sheet = BufferedImage(560, 580, BufferedImage.TYPE_INT_RGB)
        sheet.createGraphics().also { g ->
            g.color = Color(245, 245, 245); g.fillRect(0, 0, sheet.width, sheet.height)
            g.color = Color.DARK_GRAY; g.font = Font("SansSerif", Font.PLAIN, 18)
            g.drawString("OLD - one pass", 25, 28); g.drawString("NEW - progressive", 295, 28)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            for ((row, id) in listOf("detail", "checker").withIndex()) {
                val y = 45 + row * 270
                val before = buffered(oldThumbnail(sources.getValue(id), 60))
                val after = buffered(results.getValue(id))
                g.drawImage(before, 20, y, 240, 240, null)
                g.drawImage(after, 295, y, 240, 240, null)
            }
            g.dispose()
        }
        ImageIO.write(sheet, "png", output.resolve("comparison.png").toFile())
    } finally { CoverStore.clear() }
    println("PASS synthetic resampling checks; real album artwork needs user review")
}
