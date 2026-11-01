import com.jethuit.spw.listenstats.updates.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

private fun release(tag: String): ReleaseLookup.Found = ReleaseLookup.Found(
    GitHubRelease(tag, checkNotNull(PluginVersion.parse(tag))))

fun main(args: Array<String>) = runBlocking {
    val root = Path.of(args.single())
    Files.createDirectories(root)
    fun version(text: String) = checkNotNull(PluginVersion.parse(text))
    check(version("0.3.10") > version("0.3.9"))
    check(version("v0.3.5") > version("0.3.5-dev.1"))
    check(version("0.3.5-dev.10") > version("0.3.5-dev.2"))
    check(version("0.3.5-dev.1") > version("0.3.4"))
    check(version("1.0.0+one") == version("1.0.0+two"))
    check(version("1.0.0--1") > version("1.0.0-1"))
    val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
        "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
    check(ordered.zipWithNext().all { (a, b) -> version(a) < version(b) })
    for (bad in listOf("", "unknown", "0.03.5", "1.0", "1.0.0-dev.01", "1.0.0+", "../1.0.0")) {
        check(PluginVersion.parse(bad) == null) { bad }
    }
    println("PASS semantic ordering, development versions, metadata and malformed versions")

    val metadata = """{"tag_name":"v0.3.5","draft":false,"prerelease":false,"html_url":"https://example.invalid/ignored"}"""
    val decoded = GitHubReleases.decode(200, metadata) as ReleaseLookup.Found
    check(decoded.release.url == "$ReleasesUrl/tag/v0.3.5")
    for (body in listOf("oops", "{}", metadata.replace("\"draft\":false", "\"draft\":true"),
        metadata.replace("\"prerelease\":false", "\"prerelease\":true"), metadata.replace("v0.3.5", "v0.3.5-dev.1"))) {
        check(GitHubReleases.decode(200, body) is ReleaseLookup.Failed)
    }
    for (status in listOf(403, 429, 404, 500)) check(GitHubReleases.decode(status, "") is ReleaseLookup.Failed)
    println("PASS official-release filtering, error responses and fixed project links")

    var now = 1_000_000L
    var enabled = false
    var calls = 0
    var notifications = 0
    val file = root.resolve("success.json")
    fun engine() = UpdateChecker("0.3.5-dev.3", file, { enabled },
        fetch = { calls++; release("v0.3.5") }, clock = { now }, notify = { notifications++ })
    var checker = engine()
    checker.check(manual = false)
    check(calls == 0)
    checker.check(manual = true)
    check(calls == 1 && checker.notice.hasUpdate && notifications == 0)
    check(checker.notice.checkedAt == now && checker.notice.releaseVersion == "0.3.5")
    enabled = true
    checker = engine()
    check(checker.notice.hasUpdate && checker.notice.checkedAt == now && checker.notice.releaseVersion == "0.3.5")
    checker.check(manual = false)
    check(calls == 1) // Manual attempts also defer the next automatic request.
    now += UpdateChecker.IntervalMillis
    checker.check(manual = false)
    check(calls == 2 && notifications == 1)
    checker = engine()
    now += UpdateChecker.IntervalMillis
    checker.check(manual = false)
    check(calls == 3 && notifications == 1) // Notification deduplication survives restart.
    checker.check(manual = true)
    check(calls == 4 && checker.notice.hasUpdate)
    check(UpdateChecker.due(now - 1, now)) // Clock rollback does not disable checks forever.
    println("PASS manual checks, disabled reminders, daily cadence, persistence and one reminder per version")

    var failures = 0
    val failed = UpdateChecker("0.3.4", root.resolve("failure.json"), { true },
        fetch = { failures++; throw IOException("offline") }, clock = { now }, notify = { error("unexpected reminder") })
    failed.check(manual = false)
    check(failures == 1 && !failed.notice.error && failed.notice.checkedAt == 0L && !failed.notice.checking)
    failed.check(manual = false)
    check(failures == 1)
    failed.check(manual = true)
    check(failures == 2 && failed.notice.error && failed.notice.message.contains("失败"))
    val ahead = UpdateChecker("0.3.6-dev.1", root.resolve("ahead.json"), { false },
        fetch = { release("0.3.5") }, clock = { now })
    ahead.check(manual = true)
    check(!ahead.notice.hasUpdate && !ahead.notice.error)
    check(ahead.notice.releaseVersion == "0.3.5" && ahead.notice.message == "未发现更高的正式版本")
    val unknown = UpdateChecker("unknown", root.resolve("unknown.json"), { true },
        fetch = { release("0.3.5") }, clock = { now })
    unknown.check(manual = true)
    check(unknown.notice.error && !unknown.notice.hasUpdate)
    val disabledDuringFetch = UpdateChecker("0.3.4", root.resolve("disabled.json"), { enabled },
        fetch = { enabled = false; release("0.3.5") }, clock = { now }, notify = { error("disabled reminder") })
    disabledDuringFetch.check(manual = false)
    println("PASS silent automatic failure, explicit manual failure, no downgrade and disabled in-flight reminders")

    val entered = CountDownLatch(1)
    val finish = CountDownLatch(1)
    var concurrentCalls = 0
    val concurrent = UpdateChecker("0.3.4", root.resolve("concurrent.json"), { true }, fetch = {
        concurrentCalls++; entered.countDown(); check(finish.await(3, TimeUnit.SECONDS)); release("0.3.5")
    }, clock = { now })
    val job = launch(Dispatchers.Default) { concurrent.check(manual = true) }
    check(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
    concurrent.check(manual = true)
    finish.countDown()
    job.join()
    check(concurrentCalls == 1 && !concurrent.notice.checking)
    val started = CountDownLatch(1)
    val cancelled = UpdateChecker("0.3.4", root.resolve("cancelled.json"), { true }, fetch = {
        started.countDown(); Thread.sleep(30_000); release("0.3.5")
    }, clock = { now }, notify = { error("cancelled reminder") })
    val pending = launch(Dispatchers.Default) { cancelled.check(manual = false) }
    check(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
    withTimeout(3_000) { pending.cancelAndJoin() }
    check(!cancelled.notice.checking && !cancelled.notice.hasUpdate)
    println("PASS single in-flight request and cancellation without late update results")
    println("5 update checker regression groups passed; no network or personal data accessed")
}
