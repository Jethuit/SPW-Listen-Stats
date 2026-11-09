@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.dp
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.jetbrains.skia.EncodedImageFormat
import com.jethuit.spw.listenstats.data.*
import com.jethuit.spw.listenstats.ui.*
import com.moriafly.salt.ui.SaltTheme
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.WorkshopApi
import kotlinx.coroutines.*
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.swing.SwingUtilities

fun main(args: Array<String>) = runBlocking {
    ImageIO.setUseCache(false)
    var passed = 0
    suspend fun test(name: String, body: suspend () -> Unit) { body(); passed++; println("PASS $name") }
    val song = MediaItem("Synthetic", "Artist", "Album", "Album artist", "synthetic.flac", id = "local-id")
    test("only the queried current song is submitted") {
        val submitted = mutableListOf<String>()
        SongPlaybackRequest({ id -> check(id == song.id); song }, { item -> { submitted += item.id } },
            { it() }, { true }).play(song.id) { true }
        check(submitted == listOf(song.id))
    }
    test("missing, foreign and mismatched IDs never prepare a playback command") {
        for (resolved in listOf(null, song.copy(id = "different"))) {
            var prepared = false
            check(runCatching { SongPlaybackRequest({ resolved }, { prepared = true; {} }, { it() }, { true })
                .play("foreign-library:local-id") { true } }.isFailure)
            check(!prepared)
        }
    }
    test("unavailable file never prepares playback") {
        var prepared = false
        check(runCatching { SongPlaybackRequest({ song }, { prepared = true; {} }, { it() }, { false })
            .play(song.id) { true } }.isFailure)
        check(!prepared)
    }
    test("navigation or disable during lookup discards its result") {
        val result = CompletableDeferred<MediaItem?>()
        var current = true
        var submitted = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            SongPlaybackRequest({ result.await() }, { { submitted = true } }, { it() }, { true })
                .play(song.id) { current }
        }
        current = false
        result.complete(song)
        job.join()
        check(!submitted)
    }
    test("queued UI work checks session identity again") {
        var current = true
        var submitted = false
        SongPlaybackRequest({ song }, { { submitted = true } }, { current = false; it() }, { true })
            .play(song.id) { current }
        check(!submitted)
    }
    test("cancelled older request cannot submit after a newer request") {
        val result = CompletableDeferred<MediaItem?>()
        val submitted = mutableListOf<String>()
        val old = launch(start = CoroutineStart.UNDISPATCHED) {
            SongPlaybackRequest({ result.await() }, { { submitted += "old" } }, { it() }, { true })
                .play(song.id) { true }
        }
        old.cancelAndJoin()
        result.complete(song)
        SongPlaybackRequest({ song }, { { submitted += "new" } }, { it() }, { true }).play(song.id) { true }
        check(submitted == listOf("new"))
    }
    test("reflection failure does not submit or fall back to replacing the queue") {
        var submitted = false
        check(runCatching { SongPlaybackRequest({ song }, { error("unsupported host") }, { submitted = true; it() }, { true })
            .play(song.id) { true } }.isFailure)
        check(!submitted)
    }
    test("installed 1.19.3 internal signatures and constructor order prepare without playback") {
        HostSongPlayback.prepare(SongPlaybackRequest::class.java.classLoader, song)
        HostSongPlayback.prepareQueueNext(SongPlaybackRequest::class.java.classLoader, song)
        check(runCatching { HostSongPlayback.prepare(object : ClassLoader(null) {}, song) }.isFailure)
        check(runCatching { HostSongPlayback.prepareQueueNext(object : ClassLoader(null) {}, song) }.isFailure)
    }
    // Child-first loading applies only to two synthetic internal host classes. Never invoke the real engine.
    fun fakeHost(): URLClassLoader = object : URLClassLoader(arrayOf(Path.of(args[1]).toUri().toURL()),
        SongPlaybackRequest::class.java.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            if (name == "com.xuncorp.pisces.PiscesMediaItem" || name == "com.xuncorp.voxzen.service.PlaybackController") {
                (findLoadedClass(name) ?: findClass(name)).also { if (resolve) resolveClass(it) }
            } else super.loadClass(name, resolve)
        }
    }
    test("actual reflection adapter selects, prepares and starts one song in native order") {
        fakeHost().use { loader ->
            val submit = HostSongPlayback.prepare(loader, song)
            SwingUtilities.invokeAndWait { submit() }
            val controller = loader.loadClass("com.xuncorp.voxzen.service.PlaybackController")
            check(controller.getField("calls").get(null) == listOf("addToPlay", "prepare", "play"))
            check(controller.getField("engineReady").getBoolean(null) && controller.getField("playing").getBoolean(null))
            val item = controller.getField("item").get(null)
            for ((property, expected) in listOf("Id" to song.id, "Title" to song.title, "Artist" to song.artist,
                "Album" to song.album, "AlbumArtist" to song.albumArtist, "Path" to song.path)) {
                check(item.javaClass.getMethod("get$property").invoke(item) == expected)
            }
        }
    }
    test("preparation failure does not call play, retry or replace the queue") {
        fakeHost().use { loader ->
            val controller = loader.loadClass("com.xuncorp.voxzen.service.PlaybackController")
            controller.getField("failPreparation").setBoolean(null, true)
            val failure = runCatching { HostSongPlayback.prepare(loader, song).invoke() }.exceptionOrNull()
            check(failure?.message == "synthetic preparation failure")
            check(controller.getField("calls").get(null) == listOf("addToPlay", "prepare"))
            check(!controller.getField("playing").getBoolean(null))
        }
    }
    test("queue adapter resolves the insertion enum by name and submits only the selected song") {
        fakeHost().use { loader ->
            val controller = loader.loadClass("com.xuncorp.voxzen.service.PlaybackController")
            controller.getField("playing").setBoolean(null, true)
            var tracked = false
            SwingUtilities.invokeAndWait {
                HostSongPlayback.prepareQueueNext(loader, song, track = { tracked = true; AutoCloseable {} }).invoke()
            }
            check(tracked)
            check(controller.getField("calls").get(null) == listOf("addToPlayNext"))
            check(controller.getField("queuePosition").get(null) == "AfterLastAdded")
            val queued = controller.getField("queued").get(null) as List<*>
            check(queued.size == 1 && queued.single()!!.javaClass.getMethod("getId").invoke(queued.single()) == song.id)
            check(controller.getField("playing").getBoolean(null))
            check(!controller.getField("engineReady").getBoolean(null))
        }
    }
    test("queue failure never falls back to play or retries") {
        fakeHost().use { loader ->
            val controller = loader.loadClass("com.xuncorp.voxzen.service.PlaybackController")
            controller.getField("failQueue").setBoolean(null, true)
            var released = false
            val failure = runCatching {
                HostSongPlayback.prepareQueueNext(loader, song, track = { AutoCloseable { released = true } }).invoke()
            }.exceptionOrNull()
            check(released)
            check(failure?.message == "synthetic queue failure")
            check(controller.getField("calls").get(null) == listOf("addToPlayNext"))
            check((controller.getField("queued").get(null) as List<*>).isEmpty())
        }
    }
    test("rapid queue clicks are queried and submitted in order without cancelling earlier clicks") {
        val firstLookup = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val submitted = mutableListOf<String>()
        val queue = SongQueueRequests(this, { id, current ->
            started += id
            if (id == "A") firstLookup.await()
            if (current()) submitted += id
        }, { throw it })
        try {
            queue.start()
            queue.add("A") { true }; queue.add("B") { true }; queue.add("C") { true }
            yield()
            check(started == listOf("A") && submitted.isEmpty())
            firstLookup.complete(Unit)
            withTimeout(2_000) { while (submitted.size < 3) yield() }
            check(started == listOf("A", "B", "C") && submitted == started)
        } finally { queue.close() }
    }
    test("queue failures do not discard later clicks and stale queued rows are skipped") {
        val submitted = mutableListOf<String>()
        var failures = 0
        val queue = SongQueueRequests(this, { id, _ ->
            if (id == "failure") error("synthetic lookup failure")
            submitted += id
        }, { failures++ })
        try {
            queue.start()
            queue.add("failure") { true }; queue.add("stale") { false }; queue.add("next") { true }
            withTimeout(2_000) { while (submitted.isEmpty()) yield() }
            check(failures == 1 && submitted == listOf("next"))
        } finally { queue.close() }
    }
    test("closing queue requests cancels an active lookup and all waiting clicks") {
        val lookup = CompletableDeferred<Unit>()
        val submitted = mutableListOf<String>()
        val queue = SongQueueRequests(this, { id, _ -> lookup.await(); submitted += id }, { throw it })
        queue.start()
        queue.add("old") { true }; queue.add("waiting") { true }; yield()
        queue.close(); lookup.complete(Unit); yield()
        queue.add("closed") { true }; yield()
        check(submitted.isEmpty())
    }
    test("production async lookup and UI dispatch stop at denied permission, without playing") {
        val file = Path.of(args.first()).resolve("synthetic.flac")
        Files.write(file, byteArrayOf())
        var queried = false
        var permissionChecked = false
        val library = Proxy.newProxyInstance(WorkshopApi.Library::class.java.classLoader,
            arrayOf(WorkshopApi.Library::class.java)) { _, method, values ->
            check(method.name == "getTrackById" && values!!.single() == song.id)
            check(!SwingUtilities.isEventDispatchThread())
            queried = true
            CompletableFuture.completedFuture(song.copy(path = file.toString()))
        }
        val manager = Proxy.newProxyInstance(WorkshopApi.Manager::class.java.classLoader,
            arrayOf(WorkshopApi.Manager::class.java)) { _, method, _ ->
            check(method.name == "isPermissionGranted")
            check(SwingUtilities.isEventDispatchThread())
            permissionChecked = true
            false // No command can reach the real host controller.
        }
        WorkshopApi.instance = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader,
            arrayOf(WorkshopApi::class.java)) { _, method, _ ->
            when (method.name) { "getLibrary" -> library; "getManager" -> manager; else -> error(method.name) }
        } as WorkshopApi
        val failure = runCatching { HostSongPlayback.play(song.id) { true } }.exceptionOrNull()
        check(queried && permissionChecked && failure is SongPlaybackUnavailable) { "Unexpected failure: $failure" }
        queried = false; permissionChecked = false
        val queueFailure = runCatching { HostSongPlayback.enqueueNext(song.id) { true } }.exceptionOrNull()
        check(queried && permissionChecked && queueFailure is SongPlaybackUnavailable)
        Files.delete(file)
    }
    // Real row input: an isolated callback records additions; no host command can execute.
    for (metric in RankingMetric.entries) for (width in listOf(360, 760)) {
        test("$metric queue button at $width px: hover, independent clicks, stable metric and exit") {
            var played = 0
            var queued = 0
            val exiting = mutableStateOf(false)
            lateinit var scene: ImageComposeScene
            val duration = 3_945_000L
            val widths = tableWidths(width.dp, songActions = true)
            check(widths.actions == 42.dp)
            check(kotlin.math.abs((widths.rank + widths.song + widths.album + widths.actions + widths.count).value - width) < 0.01f)
            check(tableWidths(width.dp).actions == 0.dp)
            var x = 0f
            var metricStart = 0
            SwingUtilities.invokeAndWait {
                scene = ImageComposeScene(width, 90) {
                    SaltTheme { MaterialTheme {
                        CompositionLocalProvider(LocalStatsColors provides LightStatsColors,
                            LocalStatsPageExiting provides exiting.value) {
                            val columns = if (metric == RankingMetric.Duration)
                                rememberDurationColumnWidths(duration, AppType.table, false) else null
                            val layout = tableWidths(width.dp, columns?.total, songActions = true)
                            x = width - layout.count.value - layout.actions.value / 2
                            metricStart = (width - layout.count.value).toInt()
                            SongRow(RankingEntry(RankingKey(RankingMode.Song, song.id), "Synthetic", "Artist", "Album", 2, null, duration),
                                1, metric, durationColumns = columns,
                                queueSong = { id, current -> check(id == song.id); if (current()) queued++ },
                                playSong = { _, current -> if (current()) played++ })
                        }
                    } }
                }
            }
            fun snapshot(): java.awt.image.BufferedImage {
                lateinit var bytes: ByteArray
                SwingUtilities.invokeAndWait {
                    scene.render().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { bytes = it.bytes } }
                }
                return ImageIO.read(ByteArrayInputStream(bytes))
            }
            fun click() { SwingUtilities.invokeAndWait {
                scene.sendPointerEvent(PointerEventType.Press, Offset(x, 30f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(x, 30f))
            } }
            try {
                repeat(3) { snapshot(); delay(15) }
                val hidden = snapshot()
                SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Move, Offset(220f, 30f)) }
                repeat(3) { snapshot(); delay(15) }
                val shown = snapshot()
                // Ignore the row's changing grey hover fill; the dark metric glyphs must stay put.
                fun ink(image: java.awt.image.BufferedImage, px: Int, py: Int): Boolean =
                    (image.getRGB(px, py) shr 16 and 255) < 150
                check((metricStart until width).all { px ->
                    (0 until 65).all { py -> ink(hidden, px, py) == ink(shown, px, py) }
                }) { "Metric text moved on hover" }
                check((x.toInt() - 15..x.toInt() + 15).any { px ->
                    (10 until 55).any { py -> !ink(hidden, px, py) && ink(shown, px, py) }
                }) { "Queue icon did not appear on row hover" }
                SwingUtilities.invokeAndWait { scene.sendPointerEvent(PointerEventType.Move, Offset(x, 30f)) }
                snapshot()
                click(); delay(70); click(); delay(50)
                check(queued == 2 && played == 0) { "Queue click leaked to play: queued=$queued played=$played" }
                SwingUtilities.invokeAndWait {
                    scene.sendPointerEvent(PointerEventType.Exit, Offset(width + 30f, 100f))
                }
                repeat(3) { snapshot(); delay(15) }
                val afterLeaving = snapshot()
                check((x.toInt() - 15..x.toInt() + 15).all { px ->
                    (10 until 55).none { py -> ink(afterLeaving, px, py) }
                }) { "Mouse-click focus left the queue icon visible after leaving its row" }
                SwingUtilities.invokeAndWait { exiting.value = true; scene.render().close() }
                click(); delay(70); click(); delay(50)
                check(queued == 2 && played == 0)
                if (metric == RankingMetric.Count) {
                    SwingUtilities.invokeAndWait { exiting.value = false; scene.render().close() }
                    repeat(2) { snapshot(); delay(15) }
                    Files.write(Path.of(args.first()).resolve("queue-button-$width.png"),
                        java.io.ByteArrayOutputStream().also { ImageIO.write(snapshot(), "png", it) }.toByteArray())
                }
            } finally { SwingUtilities.invokeAndWait { scene.close() } }
        }
    }
    // Invoke the actual row gesture detector, with a fake submission callback. No player is started.
    test("settings gear independently invokes its configuration callback") {
        var opened = 0
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(80, 60) {
                SaltTheme { MaterialTheme {
                    CompositionLocalProvider(LocalStatsColors provides LightStatsColors) {
                        SettingsButton { opened++ }
                    }
                } }
            }
        }
        try {
            repeat(3) { SwingUtilities.invokeAndWait { scene.render().close() }; delay(15) }
            SwingUtilities.invokeAndWait {
                scene.sendPointerEvent(PointerEventType.Press, Offset(20f, 20f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(20f, 20f))
            }
            delay(50)
            check(opened == 1)
        } finally { SwingUtilities.invokeAndWait { scene.close() } }
    }
    for (mode in RankingMode.entries) test("$mode row: single click, double click and exit") {
        var calls = 0
        val exiting = mutableStateOf(false)
        lateinit var scene: ImageComposeScene
        SwingUtilities.invokeAndWait {
            scene = ImageComposeScene(760, 90) {
                SaltTheme { MaterialTheme {
                    CompositionLocalProvider(LocalStatsColors provides LightStatsColors,
                        LocalStatsPageExiting provides exiting.value) {
                        SongRow(RankingEntry(RankingKey(mode, song.id), "Synthetic", "Artist", "Album", 2, null),
                            1, RankingMetric.Count) { id, current -> check(id == song.id); if (current()) calls++ }
                    }
                } }
            }
        }
        try {
            repeat(3) { SwingUtilities.invokeAndWait { scene.render().close() }; delay(15) }
            fun click() { SwingUtilities.invokeAndWait {
                scene.sendPointerEvent(PointerEventType.Press, Offset(240f, 30f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(240f, 30f))
            } }
            click(); delay(400); check(calls == 0)
            click(); delay(70); click(); delay(50)
            check(calls == if (mode == RankingMode.Song) 1 else 0)
            val before = calls
            SwingUtilities.invokeAndWait { exiting.value = true; scene.render().close() }
            delay(30); click(); delay(70); click(); delay(50)
            check(calls == before)
        } finally { SwingUtilities.invokeAndWait { scene.close() } }
    }
    println("$passed song playback regression cases passed; no real tracks read or playback submitted")
}
