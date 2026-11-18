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
    private val fileAvailable: (String) -> Boolean = { Files.isRegularFile(Path.of(it)) },
    private val action: String = "播放"
) {
    suspend fun play(id: String, stillCurrent: () -> Boolean) {
        if (!stillCurrent()) return
        val song = lookup(id) ?: throw SongPlaybackUnavailable("这首歌曲已不在当前曲库中，无法$action")
        coroutineContext.ensureActive()
        if (!stillCurrent()) return
        if (song.id != id) throw SongPlaybackUnavailable("歌曲身份不一致，已取消播放")
        if (song.path.isBlank() || !runCatching { fileAvailable(song.path) }.getOrDefault(false)) {
            throw SongPlaybackUnavailable("歌曲文件不可用，无法$action")
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
    suspend fun play(id: String, stillCurrent: () -> Boolean) = perform(id, stillCurrent, queueNext = false)

    suspend fun enqueueNext(id: String, stillCurrent: () -> Boolean) = perform(id, stillCurrent, queueNext = true)

    private suspend fun perform(id: String, stillCurrent: () -> Boolean, queueNext: Boolean) {
        val action = if (queueNext) "添加到队列" else "播放歌曲"
        val targets = HostUiRuntime.current?.songPlayback ?: SongPlaybackTargets(WorkshopApi.instance.javaClass.classLoader)
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
                    try {
                        if (queueNext) targets.prepareQueueNext(song) else targets.prepare(song)
                    } catch (failure: ReflectiveOperationException) {
                        throw SongPlaybackUnavailable("当前播放器的${if (queueNext) "队列" else "播放"}接口不可用，请更新插件后重试", failure)
                    }
                },
                dispatch = { submit ->
                    withContext(Dispatchers.Main) {
                        coroutineContext.ensureActive()
                        if (!WorkshopApi.manager.isPermissionGranted(PluginPermission.CLASS_TRANSFORM)) {
                            throw SongPlaybackUnavailable("未获得修改应用行为权限，无法$action")
                        }
                        submit()
                    }
                },
                action = action
            ).let { request ->
                // Query registration, filesystem checks and reflection preparation stay off the UI thread.
                withContext(Dispatchers.IO) { request.play(id, stillCurrent) }
            }
        }
    }

}

internal class SongPlaybackTargets(private val loader: ClassLoader) {
    private class PreparedSong(val itemType: Class<*>, val controllerType: Class<*>,
                               val controller: () -> Any, val item: (MediaItem) -> Any)

    private val prepared: PreparedSong by lazy {
        val itemType = Class.forName("com.xuncorp.pisces.PiscesMediaItem", false, loader)
        val controllerType = Class.forName("com.xuncorp.voxzen.service.PlaybackController", false, loader)
        val properties = listOf("id", "title", "artist", "album", "albumArtist", "path")
        // The constructor uses six Strings. Reject an unknown property order instead of guessing.
        check(itemType.getAnnotation(Metadata::class.java)?.data2?.filter { it in properties } == properties) {
            "当前播放器的歌曲结构不受支持，无法播放"
        }
        val constructor = itemType.getConstructor(*Array(6) { String::class.java })
        val instance = controllerType.getField("INSTANCE")
        check(Modifier.isStatic(instance.modifiers) && instance.type == controllerType) {
            "当前播放器的歌曲接口不受支持"
        }
        PreparedSong(itemType, controllerType, { instance.get(null) }, { song ->
            constructor.newInstance(song.id, song.title, song.artist, song.album, song.albumArtist, song.path)
        })
    }

    private val playSong: (MediaItem) -> Unit by lazy {
        val itemType = prepared.itemType
        val controllerType = prepared.controllerType
        val addToPlay = controllerType.getMethod("addToPlay", itemType)
        val prepare = controllerType.getMethod("prepare")
        val play = controllerType.getMethod("play")
        check(listOf(addToPlay, prepare, play).all {
                !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE
            }) {
            "当前播放器的播放接口不受支持"
        }
        val submit: (MediaItem) -> Unit = { song ->
            try {
                val item = prepared.item(song)
                val controller = prepared.controller()
                // Match the native single-song action in SPW 1.19.3, including engine preparation.
                // addToPlay alone updates the item/queue but does not start audio playback.
                addToPlay.invoke(controller, item)
                prepare.invoke(controller)
                play.invoke(controller)
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
        submit
    }

    private val enqueueSong: (MediaItem, (Any) -> AutoCloseable) -> Unit by lazy {
        val add = prepared.controllerType.methods.singleOrNull { method ->
            val types = method.parameterTypes
            method.name == "addToPlayNext" && !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE && method.isVarArgs && types.size == 2 &&
                types[0].isEnum && types[1].isArray && types[1].componentType == prepared.itemType
        } ?: throw SongPlaybackUnavailable("当前播放器的队列接口不受支持，无法添加歌曲")
        val position = add.parameterTypes[0].enumConstants.singleOrNull {
            (it as? Enum<*>)?.name == "AfterLastAdded"
        } ?: throw SongPlaybackUnavailable("当前播放器不支持按顺序插播，无法添加歌曲")
        val submit: (MediaItem, (Any) -> AutoCloseable) -> Unit = { song, track ->
            var tracked: AutoCloseable? = null
            try {
                val items = java.lang.reflect.Array.newInstance(prepared.itemType, 1)
                val item = prepared.item(song)
                java.lang.reflect.Array.set(items, 0, item)
                tracked = track(item)
                // Native queue behavior is retained; only this item's success notification is scoped out.
                add.invoke(prepared.controller(), position, items)
            } catch (failure: InvocationTargetException) {
                tracked?.close()
                throw failure.targetException
            } catch (failure: Throwable) {
                tracked?.close()
                throw failure
            }
        }
        submit
    }

    fun diagnostics(): String {
        fun resolve(check: () -> Unit): String = runCatching(check).fold(
            onSuccess = { "available" }, onFailure = { "unavailable (${it.javaClass.simpleName}: ${it.message})" })
        return "singleSongTarget=${resolve { playSong }}; insertNextTarget=${resolve { enqueueSong }}"
    }

    fun prepare(song: MediaItem): () -> Unit {
        val submit = playSong
        return { submit(song) }
    }

    fun prepareQueueNext(song: MediaItem, track: (Any) -> AutoCloseable = QueueNotificationHooks::track): () -> Unit {
        val submit = enqueueSong
        return { submit(song, track) }
    }
}
