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
                    try {
                        if (queueNext) prepareQueueNext(loader, song) else prepare(loader, song)
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

    private class PreparedSong(val itemType: Class<*>, val controllerType: Class<*>,
                               val controller: () -> Any, val item: () -> Any)

    private fun prepareSong(loader: ClassLoader, song: MediaItem): PreparedSong {
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
        return PreparedSong(itemType, controllerType, { instance.get(null) }, {
            constructor.newInstance(song.id, song.title, song.artist, song.album, song.albumArtist, song.path)
        })
    }

    internal fun prepare(loader: ClassLoader, song: MediaItem): () -> Unit {
        val prepared = prepareSong(loader, song)
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
        return {
            try {
                val item = prepared.item()
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
    }

    internal fun prepareQueueNext(loader: ClassLoader, song: MediaItem,
                                  track: (Any) -> AutoCloseable = QueueNotificationHooks::track): () -> Unit {
        val prepared = prepareSong(loader, song)
        val add = prepared.controllerType.methods.singleOrNull { method ->
            val types = method.parameterTypes
            method.name == "addToPlayNext" && !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE && method.isVarArgs && types.size == 2 &&
                types[0].isEnum && types[1].isArray && types[1].componentType == prepared.itemType
        } ?: throw SongPlaybackUnavailable("当前播放器的队列接口不受支持，无法添加歌曲")
        val position = add.parameterTypes[0].enumConstants.singleOrNull {
            (it as? Enum<*>)?.name == "AfterLastAdded"
        } ?: throw SongPlaybackUnavailable("当前播放器不支持按顺序插播，无法添加歌曲")
        return {
            var tracked: AutoCloseable? = null
            try {
                val items = java.lang.reflect.Array.newInstance(prepared.itemType, 1)
                val item = prepared.item()
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
    }
}
