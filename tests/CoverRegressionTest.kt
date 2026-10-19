@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

import com.jethuit.spw.listenstats.data.CoverStore
import androidx.compose.ui.graphics.ImageBitmap
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

@Volatile private var fetch: (String) -> CompletableFuture<ByteArray?> = { error("Unexpected cover: $it") }
private var groups = 0

private suspend fun test(name: String, action: suspend CoroutineScope.() -> Unit) = coroutineScope {
    CoverStore.clear()
    try {
        withTimeout(6_000) { action() }
        groups++
        println("PASS $name")
    } finally {
        CoverStore.clear()
    }
}

fun main() = runBlocking(Dispatchers.IO) {
    val library = Proxy.newProxyInstance(WorkshopApi.Library::class.java.classLoader,
        arrayOf(WorkshopApi.Library::class.java)) { _, method, args ->
        check(method.name == "getCoverById") { "Unexpected method: ${method.name}" }
        fetch(args!![0] as String)
    }
    WorkshopApi.instance = Proxy.newProxyInstance(WorkshopApi::class.java.classLoader,
        arrayOf(WorkshopApi::class.java)) { _, method, _ ->
        check(method.name == "getLibrary") { "Unexpected method: ${method.name}" }
        library
    } as WorkshopApi

    test("missing covers retain the failure cooldown") {
        val calls = AtomicInteger()
        fetch = { calls.incrementAndGet(); CompletableFuture.completedFuture(null) }
        check(CoverStore.load("missing", 44) == null)
        check(CoverStore.load("missing", 44) == null)
        check(calls.get() == 1)
    }

    test("leaving the only row allows an immediate fresh request") {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val pending = CompletableFuture<ByteArray?>()
        fetch = {
            if (calls.incrementAndGet() == 1) { entered.complete(Unit); pending }
            else CompletableFuture.completedFuture(null)
        }
        val initial = async(start = CoroutineStart.UNDISPATCHED) { CoverStore.load("return", 44) }
        entered.await()
        initial.cancelAndJoin()
        check(CoverStore.load("return", 44) == null)
        check(calls.get() == 2) { "Canceled row incorrectly created a failure cooldown" }
        pending.complete(null)
    }

    test("leaving the initiating row preserves an existing shared waiter") {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val pending = CompletableFuture<ByteArray?>()
        fetch = { calls.incrementAndGet(); entered.complete(Unit); pending }
        val initial = async(start = CoroutineStart.UNDISPATCHED) { CoverStore.load("shared", 44) }
        entered.await()
        val joined = async(start = CoroutineStart.UNDISPATCHED) { CoverStore.load("shared", 44) }
        check(!joined.isCompleted)
        initial.cancelAndJoin()
        check(!joined.isCompleted)
        pending.complete(null)
        check(joined.await() == null)
        check(calls.get() == 1)
    }

    test("canceling a joined waiter leaves other waiters running") {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val pending = CompletableFuture<ByteArray?>()
        fetch = { calls.incrementAndGet(); entered.complete(Unit); pending }
        val owner = async(start = CoroutineStart.UNDISPATCHED) { CoverStore.load("joined", 44) }
        entered.await()
        val joined = async(start = CoroutineStart.UNDISPATCHED) { CoverStore.load("joined", 44) }
        check(!joined.isCompleted)
        joined.cancelAndJoin()
        check(!owner.isCompleted)
        pending.complete(null)
        check(owner.await() == null)
        check(calls.get() == 1)
    }

    test("clear cancels old work without poisoning or removing the new request") {
        val calls = AtomicInteger()
        val oldEntered = CompletableDeferred<Unit>()
        val newEntered = CompletableDeferred<Unit>()
        val oldResult = CompletableFuture<ByteArray?>()
        val newResult = CompletableFuture<ByteArray?>()
        fetch = {
            when (calls.incrementAndGet()) {
                1 -> { oldEntered.complete(Unit); oldResult }
                2 -> { newEntered.complete(Unit); newResult }
                else -> error("Duplicate fetch after clear")
            }
        }
        val old = async(start = CoroutineStart.UNDISPATCHED) { CoverStore.load("cleared", 44) }
        oldEntered.await()
        CoverStore.clear()
        val fresh = async(start = CoroutineStart.UNDISPATCHED) { CoverStore.load("cleared", 44) }
        newEntered.await()
        old.join()
        check(old.isCancelled)
        oldResult.complete(null)
        val joined = async(start = CoroutineStart.UNDISPATCHED) { CoverStore.load("cleared", 44) }
        check(!joined.isCompleted) { "Old request must not restore a cooldown" }
        newResult.complete(null)
        check(fresh.await() == null && joined.await() == null)
        check(calls.get() == 2)
    }

    test("an API cancellation returns a retryable result with a cooldown") {
        val calls = AtomicInteger()
        fetch = {
            if (calls.incrementAndGet() == 1) CompletableFuture<ByteArray?>().also { it.cancel(false) }
            else CompletableFuture.completedFuture(null)
        }
        val first = async(start = CoroutineStart.UNDISPATCHED) { CoverStore.load("api-canceled", 44) }
        check(first.await() == null)
        check(!first.isCancelled)
        check(CoverStore.load("api-canceled", 44) == null)
        check(calls.get() == 1) { "API cancellation must respect the normal retry cooldown" }
    }

    test("API cancellation preserves the displayed row's retry loop") {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val pending = CompletableFuture<ByteArray?>()
        fetch = { calls.incrementAndGet(); entered.complete(Unit); pending }
        val firstReturned = CompletableDeferred<Unit>()
        val retryDelay = CompletableDeferred<Unit>()
        val nextReturned = CompletableDeferred<Unit>()
        var image: ImageBitmap? = null
        var attempts = 0
        // Same load/retry boundary as Cover's LaunchedEffect. A gate replaces
        // the 30-second delay, keeping this test deterministic and lightweight.
        val row = launch(start = CoroutineStart.UNDISPATCHED) {
            while (image == null) {
                image = CoverStore.load("displayed-row", 44)
                attempts++
                if (attempts == 1) {
                    firstReturned.complete(Unit)
                    retryDelay.await()
                } else {
                    nextReturned.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        entered.await()
        pending.cancel(false)
        firstReturned.await()
        check(row.isActive && attempts == 1)
        retryDelay.complete(Unit)
        nextReturned.await()
        check(row.isActive && attempts == 2) { "API cancellation stopped the row's retry loop" }
        check(calls.get() == 1) { "The early retry must still respect the real 30-second cooldown" }
        row.cancelAndJoin()
        check(row.isCancelled) { "Leaving the row must still cancel its retry loop" }
    }

    test("real API errors still fall back and are rate limited") {
        val calls = AtomicInteger()
        fetch = {
            calls.incrementAndGet()
            CompletableFuture<ByteArray?>().also { it.completeExceptionally(IOException("temporary failure")) }
        }
        check(CoverStore.load("failed", 44) == null)
        check(CoverStore.load("failed", 44) == null)
        check(calls.get() == 1)
    }
    println("$groups cover regression groups passed; no player or real library accessed")
}
