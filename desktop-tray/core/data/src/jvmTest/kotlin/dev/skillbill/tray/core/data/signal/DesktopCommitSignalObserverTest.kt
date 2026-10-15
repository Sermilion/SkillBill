package dev.skillbill.tray.core.data.signal

import dev.skillbill.tray.core.data.RecordingDiagnostics
import dev.skillbill.tray.core.data.port.StoreChange
import dev.skillbill.tray.core.data.runExternalWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.junit.jupiter.api.Test
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class DesktopCommitSignalObserverTest {
  @Test
  fun `a commit during watcher registration is present in the first observation`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-registration-commit").use { fixture ->
      val registered = CompletableDeferred<Unit>()
      val continueRegistration = CompletableDeferred<Unit>()
      val received = Channel<StoreChange>(Channel.UNLIMITED)
      val ready = CompletableDeferred<Unit>()
      val observer = launch {
        DesktopCommitSignalObserver(fixture.dbPath, RecordingDiagnostics()) { path ->
          val watch = NativeCommitSignalWatch(path)
          registered.complete(Unit)
          try { continueRegistration.await(); watch }
          catch (failure: Throwable) { watch.close(); throw failure }
        }.changes(ready).collect { received.send(it) }
      }
      try {
        withTimeout(10_000) { registered.await() }
        writer(fixture.dbPath, "commit")
        continueRegistration.complete(Unit)
        val initial = withTimeout(10_000) { ready.await(); received.receive() }
        assertTrue(initial.initial)
        assertEquals(signalTopics, initial.revisions.keys)
        assertEquals(1, fixture.connection.createStatement().use { statement ->
          statement.executeQuery("SELECT COUNT(*) FROM standalone_phase_status").use { it.next(); it.getInt(1) }
        })
        writer(fixture.dbPath, "commit-2")
        val committed = withTimeout(10_000) { received.receive() }
        assertTrue(requireNotNull(committed.revisions[DesktopReadSchema.PHASES_TOPIC]) >
          requireNotNull(initial.revisions[DesktopReadSchema.PHASES_TOPIC]))
      } finally { continueRegistration.complete(Unit); observer.cancelAndJoin() }
    }
  }

  @Test
  fun `overflow recovers lost hints and reconnect reads commits made while disconnected`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-lost-commit-hints").use { fixture ->
      val hints = Channel<Unit>(Channel.CONFLATED)
      val received = Channel<StoreChange>(Channel.UNLIMITED)
      val ready = CompletableDeferred<Unit>()
      val closed = CompletableDeferred<Unit>()
      val observer = launch {
        DesktopCommitSignalObserver(fixture.dbPath, RecordingDiagnostics()) { path ->
          val native = NativeCommitSignalWatch(path)
          object : StoreWatch {
            override suspend fun awaitHint(): StoreWatchHint { hints.receive(); return StoreWatchHint.OVERFLOW }
            override fun close() { native.close(); closed.complete(Unit) }
          }
        }.changes(ready).collect { received.send(it) }
      }
      val recovered: StoreChange
      try {
        withTimeout(10_000) { ready.await(); received.receive() }
        writer(fixture.dbPath, "crash-after-commit")
        writer(fixture.dbPath, "goal-commit")
        assertNull(withTimeoutOrNull(100) { received.receive() })
        hints.send(Unit)
        recovered = withTimeout(10_000) { received.receive() }
        assertEquals(signalTopics, recovered.revisions.keys)
      } finally { observer.cancelAndJoin() }
      withTimeout(10_000) { closed.await() }
      writer(fixture.dbPath, "commit-after-disconnect")
      val reconnectReady = CompletableDeferred<Unit>()
      val reconnect = launch {
        DesktopCommitSignalObserver(fixture.dbPath, RecordingDiagnostics()) { NativeCommitSignalWatch(it) }.changes(reconnectReady).collect { received.send(it) }
      }
      try {
        val baseline = withTimeout(10_000) { reconnectReady.await(); received.receive() }
        assertTrue(baseline.initial)
        assertEquals(signalTopics, baseline.revisions.keys)
        assertEquals(2, fixture.connection.createStatement().use { statement ->
          statement.executeQuery("SELECT COUNT(*) FROM standalone_phase_status").use { it.next(); it.getInt(1) }
        })
      } finally { reconnect.cancelAndJoin() }
    }
  }

  @Test
  fun `a slow subscriber receives every changed topic revision after commit hints coalesce`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-coalesced-commits").use { fixture ->
      val received = Channel<StoreChange>(Channel.UNLIMITED)
      val release = CompletableDeferred<Unit>()
      val firstCommit = CompletableDeferred<StoreChange>()
      val ready = CompletableDeferred<Unit>()
      var blocked = false
      val observer = launch {
        DesktopCommitSignalObserver(fixture.dbPath, RecordingDiagnostics()) { NativeCommitSignalWatch(it) }.changes(ready).collect { change ->
          if (!change.initial && change.revisions.isNotEmpty() && !blocked) {
            blocked = true
            firstCommit.complete(change)
            release.await()
          } else received.send(change)
        }
      }
      try {
        withTimeout(10_000) { ready.await(); received.receive() }
        writer(fixture.dbPath, "commit")
        val first = withTimeout(10_000) { firstCommit.await() }
        writer(fixture.dbPath, "commit-2")
        writer(fixture.dbPath, "goal-commit")
        release.complete(Unit)
        val latest = withTimeout(10_000) {
          var change = received.receive()
          while ((change.revisions[DesktopReadSchema.GOALS_TOPIC] ?: 0) == 0L) change = received.receive()
          change
        }
        assertTrue(requireNotNull(latest.revisions[DesktopReadSchema.PHASES_TOPIC]) >
          requireNotNull(first.revisions[DesktopReadSchema.PHASES_TOPIC]))
        assertTrue(requireNotNull(latest.revisions[DesktopReadSchema.GOALS_TOPIC]) > 0)
      } finally { release.complete(Unit); observer.cancelAndJoin() }
    }
  }

  @Test
  fun `database file changes do not act as commit signals`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-observer-database-files").use { fixture ->
      val received = Channel<StoreChange>(Channel.UNLIMITED)
      val ready = CompletableDeferred<Unit>()
      val observer = launch { DesktopCommitSignalObserver(fixture.dbPath, RecordingDiagnostics()) { NativeCommitSignalWatch(it) }.changes(ready).collect { received.send(it) } }
      try {
        withTimeout(10_000) { ready.await(); received.receive() }
        Files.writeString(fixture.dbPath.resolveSibling("${fixture.dbPath.fileName}-wal"), "test")
        assertNull(withTimeoutOrNull(150) { received.receive() })
        writer(fixture.dbPath, "commit")
        assertTrue(withTimeout(10_000) { received.receive() }.revisions.isNotEmpty())
      } finally {
        observer.cancelAndJoin()
        Files.deleteIfExists(fixture.dbPath.resolveSibling("${fixture.dbPath.fileName}-wal"))
      }
    }
  }

  @Test
  fun `unrelated runtime commits do not invalidate work flows`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-unrelated-commit").use { fixture ->
      val received = Channel<StoreChange>(Channel.UNLIMITED)
      val ready = CompletableDeferred<Unit>()
      val observer = launch { DesktopCommitSignalObserver(fixture.dbPath, RecordingDiagnostics()) { NativeCommitSignalWatch(it) }.changes(ready).collect { received.send(it) } }
      try {
        withTimeout(10_000) { ready.await(); received.receive() }
        writer(fixture.dbPath, "unrelated-commit")
        assertNull(withTimeoutOrNull(150) { received.receive() })
        writer(fixture.dbPath, "commit")
        assertEquals(signalTopics,
          withTimeout(10_000) { received.receive() }.revisions.keys)
      } finally { observer.cancelAndJoin() }
    }
  }

  @Test
  fun `independent runtime writer emits committed topics and rollback stays silent`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-external-writer").use { fixture ->
      val received = Channel<StoreChange>(Channel.UNLIMITED)
      val ready = CompletableDeferred<Unit>()
      val observer = launch { DesktopCommitSignalObserver(fixture.dbPath, RecordingDiagnostics()) { NativeCommitSignalWatch(it) }.changes(ready).collect { received.send(it) } }
      try {
        withTimeout(10_000) { ready.await(); received.receive() }
        assertNull(withTimeoutOrNull(100) { received.receive() })
        writer(fixture.dbPath, "rollback")
        assertNull(withTimeoutOrNull(150) { received.receive() })
        writer(fixture.dbPath, "commit")
        val change = withTimeout(10_000) { received.receive() }
        assertEquals(signalTopics, change.revisions.keys)
        assertEquals(1, fixture.connection.createStatement().use { statement ->
          statement.executeQuery("SELECT COUNT(*) FROM standalone_phase_status").use { it.next(); it.getInt(1) }
        })
      } finally { observer.cancelAndJoin() }
      writer(fixture.dbPath, "commit-after-close")
    }
  }

  @Test
  fun `one runtime transaction publishes both goal and phase topics`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-coherent-external-writer").use { fixture ->
      val received = Channel<StoreChange>(Channel.UNLIMITED)
      val ready = CompletableDeferred<Unit>()
      val observer = launch { DesktopCommitSignalObserver(fixture.dbPath, RecordingDiagnostics()) { NativeCommitSignalWatch(it) }.changes(ready).collect { received.send(it) } }
      try {
        withTimeout(10_000) { ready.await(); received.receive() }
        writer(fixture.dbPath, "coherent-commit")
        val change = withTimeout(10_000) { received.receive() }
        assertEquals(signalTopics, change.revisions.keys)
        assertTrue(DesktopReadSchema.TOPICS.all { topic -> requireNotNull(change.revisions[topic]) > 0 })
        assertEquals(0, change.revisions[DesktopReadSchema.ACTIVITY_TOPIC])
      } finally { observer.cancelAndJoin() }
    }
  }

  @Test
  fun `agent activity commits change only the activity revision`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-activity-commit").use { fixture ->
      val received = Channel<StoreChange>(Channel.UNLIMITED)
      val ready = CompletableDeferred<Unit>()
      val observer = launch { DesktopCommitSignalObserver(fixture.dbPath, RecordingDiagnostics()) { NativeCommitSignalWatch(it) }.changes(ready).collect { received.send(it) } }
      try {
        withTimeout(10_000) { ready.await(); received.receive() }
        writer(fixture.dbPath, "commit")
        val work = withTimeout(10_000) { received.receive() }
        writer(fixture.dbPath, "activity")
        val activity = withTimeout(10_000) { received.receive() }
        assertEquals(work.revisions - DesktopReadSchema.ACTIVITY_TOPIC, activity.revisions - DesktopReadSchema.ACTIVITY_TOPIC)
        assertTrue(requireNotNull(activity.revisions[DesktopReadSchema.ACTIVITY_TOPIC]) >
          requireNotNull(work.revisions[DesktopReadSchema.ACTIVITY_TOPIC]))
      } finally { observer.cancelAndJoin() }
    }
  }

  private val signalTopics = DesktopReadSchema.TOPICS.toSet() + DesktopReadSchema.ACTIVITY_TOPIC

  private fun writer(path: Path, mode: String) = runExternalWriter(path, mode)
}
