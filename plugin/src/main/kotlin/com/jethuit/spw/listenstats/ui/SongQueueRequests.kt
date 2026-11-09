package com.jethuit.spw.listenstats.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** A page owns one ordered stream. Closing it cancels both the lookup and queued clicks. */
internal class SongQueueRequests(
    private val scope: CoroutineScope,
    private val enqueue: suspend (String, () -> Boolean) -> Unit,
    private val failed: (Exception) -> Unit
) : AutoCloseable {
    private class Request(val id: String, val current: () -> Boolean)
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private var worker: Job? = null

    fun start() {
        check(worker == null)
        worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            for (request in requests) {
                if (!request.current()) continue
                try {
                    enqueue(request.id, request.current)
                } catch (failure: TimeoutCancellationException) {
                    if (request.current()) failed(failure)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (request.current()) failed(failure)
                }
            }
        }
    }

    fun add(id: String, stillCurrent: () -> Boolean) { requests.trySend(Request(id, stillCurrent)) }

    override fun close() {
        requests.cancel()
        worker?.cancel()
    }
}
