@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.moriafly.salt.ui.UnstableSaltUiApi::class)

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
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
        check(runCatching { HostSongPlayback.prepare(object : ClassLoader(null) {}, song) }.isFailure)
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
        Files.delete(file)
    }
    // Invoke the actual row gesture detector, with a fake submission callback. No player is started.
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
