package com.jethuit.spw.listenstats.ui

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import com.xuncorp.spw.workshop.api.PluginPermission
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class SongPlaybackUnavailable(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** A request owns no host instances. Leaving the page/session invalidates its final UI action. */
internal class SongPlaybackRequest(
    private val lookup: suspend (String) -> MediaItem?,
    private val prepare: suspend (MediaItem) -> (() -> Unit),
    private val dispatch: suspend ((() -> Unit)) -> Unit,
    private val fileAvailable: (String) -> Boolean = { Files.isRegularFile(Path.of(it)) }
) {
    suspend fun play(id: String, stillCurrent: () -> Boolean) {
        if (!stillCurrent()) return
        val song = lookup(id) ?: throw SongPlaybackUnavailable("这首歌曲已不在当前曲库中，无法播放")
        coroutineContext.ensureActive()
        if (!stillCurrent()) return
        if (song.id != id) throw SongPlaybackUnavailable("歌曲身份不一致，已取消播放")
        if (song.path.isBlank() || !runCatching { fileAvailable(song.path) }.getOrDefault(false)) {
            throw SongPlaybackUnavailable("歌曲文件不可用，无法播放")
        }
        val submit = prepare(song)
        val requestContext = coroutineContext
        requestContext.ensureActive()
        dispatch {
            // Recheck after any queued UI work, including disable/re-enable or navigation.
            requestContext.ensureActive()
            if (stillCurrent()) submit()
        }
    }
}

/** Internal SPW adapter, currently inspected against 1.19.3; not part of the public Workshop API. */
internal object HostSongPlayback {
    suspend fun play(id: String, stillCurrent: () -> Boolean) {
        val loader = WorkshopApi.instance.javaClass.classLoader
        withTimeout(10_000) {
            SongPlaybackRequest(
                lookup = { trackId ->
                    suspendCancellableCoroutine { continuation ->
                        // Do not strip foreign-library prefixes or guess a match from display names.
                        WorkshopApi.library.getTrackById(trackId).whenComplete { song, failure ->
                            if (failure != null) continuation.resumeWithException(failure)
                            else continuation.resume(song)
                        }
                    }
                },
                prepare = { song ->
                    try { prepare(loader, song) } catch (failure: ReflectiveOperationException) {
                        throw SongPlaybackUnavailable("当前播放器的播放接口不可用，请更新插件后重试", failure)
                    }
                },
                dispatch = { action ->
                    withContext(Dispatchers.Main) {
                        coroutineContext.ensureActive()
                        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
                            throw SongPlaybackUnavailable("未获得修改应用行为权限，无法播放歌曲")
                        }
                        action()
                    }
                }
            ).let { request ->
                // Query registration, filesystem checks and reflection preparation stay off the UI thread.
                withContext(Dispatchers.IO) { request.play(id, stillCurrent) }
            }
        }
    }

    internal fun prepare(loader: ClassLoader, song: MediaItem): () -> Unit {
        val itemType = Class.forName("com.xuncorp.pisces.PiscesMediaItem", false, loader)
        val controllerType = Class.forName("com.xuncorp.voxzen.service.PlaybackController", false, loader)
        val properties = listOf("id", "title", "artist", "album", "albumArtist", "path")
        // The constructor uses six Strings. Reject an unknown property order instead of guessing.
        check(itemType.getAnnotation(Metadata::class.java)?.data2?.filter { it in properties } == properties) {
            "当前播放器的歌曲结构不受支持，无法播放"
        }
        val constructor = itemType.getConstructor(*Array(6) { String::class.java })
        val instance = controllerType.getField("INSTANCE")
        val addToPlay = controllerType.getMethod("addToPlay", itemType)
        val prepare = controllerType.getMethod("prepare")
        val play = controllerType.getMethod("play")
        check(Modifier.isStatic(instance.modifiers) && instance.type == controllerType &&
            listOf(addToPlay, prepare, play).all {
                !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE
            }) {
            "当前播放器的播放接口不受支持"
        }
        return {
            try {
                val item = constructor.newInstance(song.id, song.title, song.artist, song.album, song.albumArtist, song.path)
                val controller = instance.get(null)
                // Match the native single-song action in SPW 1.19.3, including engine preparation.
                // addToPlay alone updates the item/queue but does not start audio playback.
                addToPlay.invoke(controller, item)
                prepare.invoke(controller)
                play.invoke(controller)
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
    }
}
