package dev.skillbill.tray.core.data.signal

import dev.skillbill.tray.core.data.RecordingDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Path
import java.nio.file.WatchService
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

// The macOS watch service polls, so every wait allows for seconds of latency. The temp directory resolves its /var
// symlink first, because the watch service reports real paths.
class NioSpecDirectoryChangesTest {
  private val created = mutableListOf<WatchService>()
  private val watcher = object : NioSpecDirectoryChanges(RecordingDiagnostics()) {
    override fun newWatchService(root: Path): WatchService = super.newWatchService(root).also(created::add)
  }
  private val specs = createTempDirectory("spec-watch").toRealPath()

  private fun CoroutineScope.collect(events: Channel<Unit>): Job =
    watcher.changes(specs.toString()).onEach { events.trySend(it) }.launchIn(this)

  /** Repeats [change] until an event arrives, because a change made before the watch registers is never reported. */
  private suspend fun Channel<Unit>.awaitEventFrom(change: (attempt: Int) -> Unit) = withTimeout(30.seconds) {
    var attempt = 0
    while (true) {
      change(attempt++)
      if (withTimeoutOrNull(2.seconds) { receive() } != null) break
    }
  }

  // The two event tests use separate watchers and separate changes, so a late event from one cannot satisfy the other.
  @Test
  fun `a new bundle folder produces an event`() = runBlocking {
    val events = Channel<Unit>(Channel.UNLIMITED)
    val job = collect(events)
    try {
      events.awaitEventFrom { attempt -> specs.resolve("SKILL-new$attempt").createDirectories() }
    } finally {
      job.cancel()
    }
  }

  @Test
  fun `a file in an existing top-level bundle produces an event`() = runBlocking {
    val existing = specs.resolve("SKILL-1-x").createDirectories()
    val events = Channel<Unit>(Channel.UNLIMITED)
    val job = collect(events)
    try {
      events.awaitEventFrom { attempt -> existing.resolve("spec$attempt.md").writeText("body") }
    } finally {
      job.cancel()
    }
  }

  @Test
  fun `cancelling the collector closes the watch service`() = runBlocking<Unit> {
    val events = Channel<Unit>(Channel.UNLIMITED)
    val job = collect(events)
    events.awaitEventFrom { attempt -> specs.resolve("SKILL-$attempt").createDirectories() }
    job.cancel()
    job.join()
    assertFailsWith<ClosedWatchServiceException> { created.single().poll() }
  }

  @Test
  fun `a missing specs folder completes without events`() = runBlocking {
    withTimeout(30.seconds) {
      watcher.changes(specs.resolve("absent").toString()).onEach { error("unexpected event") }.launchIn(this).join()
    }
  }
}
