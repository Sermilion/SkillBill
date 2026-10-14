package dev.skillbill.tray.infrastructure.storage

import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import org.junit.jupiter.api.Test
import skillbill.contracts.persistence.DesktopReadSchema
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.infrastructure.host.JdkFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.idestatus.model.AgentActivityLabel
import skillbill.idestatus.model.AgentActivityStamp
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
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
        DesktopCommitSignalObserver(fixture.dbPath) { path ->
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
        DesktopCommitSignalObserver(fixture.dbPath) { path ->
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
        DesktopCommitSignalObserver(fixture.dbPath).changes(reconnectReady).collect { received.send(it) }
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
        DesktopCommitSignalObserver(fixture.dbPath).changes(ready).collect { change ->
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
      val observer = launch { DesktopCommitSignalObserver(fixture.dbPath).changes(ready).collect { received.send(it) } }
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
      val observer = launch { DesktopCommitSignalObserver(fixture.dbPath).changes(ready).collect { received.send(it) } }
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
      val observer = launch { DesktopCommitSignalObserver(fixture.dbPath).changes(ready).collect { received.send(it) } }
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
      val observer = launch { DesktopCommitSignalObserver(fixture.dbPath).changes(ready).collect { received.send(it) } }
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
      val observer = launch { DesktopCommitSignalObserver(fixture.dbPath).changes(ready).collect { received.send(it) } }
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

  private fun writer(path: Path, mode: String) {
    val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
      "-cp", System.getProperty("java.class.path"), ExternalRuntimeWriter::class.java.name, path.toString(), mode)
      .redirectErrorStream(true).start()
    assertTrue(process.waitFor(10, TimeUnit.SECONDS))
    assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
  }
}

object ExternalRuntimeWriter {
  @JvmStatic fun main(args: Array<String>) {
    val path = Path.of(args[0])
    val mode = args[1]
    val factory = sqliteSessionFactoryForTests(path.parent, path.toString(), emptyMap())
    val now = Instant.now()
    val processOwner = if (mode == "owned-phase") {
      JdkFeatureTaskRuntimeWorkerSupervisor(object : RuntimeDiagnostics {
        override fun warning(message: String, error: Throwable?) = Unit
        override fun error(message: String, error: Throwable?) = Unit
      }, JdkHostPlatformPort).currentProcess()
    } else null
    try {
      if (mode == "activity") {
        factory.selfManagedWrite { unit ->
          unit.agentActivityStamps.record("commit", AgentActivityStamp(now, AgentActivityLabel.STDOUT))
        }
        return
      }
      factory.transaction { unit ->
        if (mode == "unrelated-commit") {
          val connection = unit::class.java.getDeclaredField("connection").apply { isAccessible = true }
            .get(unit) as java.sql.Connection
          connection.createStatement().use { statement ->
            statement.execute("CREATE TABLE unrelated_desktop_signal_probe (id TEXT PRIMARY KEY)")
            statement.execute("INSERT INTO unrelated_desktop_signal_probe VALUES ('committed')")
          }
          return@transaction
        }
        if (mode == "goal-commit" || mode == "coherent-commit") {
          unit.goalRunnerControls.persistControlState(mode, GoalRunnerControlState(pauseRequested = true))
          if (mode == "goal-commit") return@transaction
        }
        if (mode == "complete") {
          val record = requireNotNull(unit.standalonePhaseStatuses.readByInvocation("commit"))
          unit.standalonePhaseStatuses.update(StandalonePhaseStatusUpdate(record.executionId, record.statusRevision,
            record.leaseOwner, record.leaseGeneration, "terminal", "review", null, now, finishedAt = now, terminalResult = "completed"))
          return@transaction
        }
        unit.standalonePhaseStatuses.register(StandalonePhaseStatusRegistration(
          "repo-root-realpath-v1:${path.parent}", "main", "SKILL-423", null, mode, "review", "execution-$mode", "active", "review",
          now, "owned-test", 1, now.plusSeconds(300), processOwner = processOwner))
        if (mode == "rollback") error("owned rollback")
      }
      if (mode == "crash-after-commit") Runtime.getRuntime().halt(0)
      if (mode == "owned-phase") {
        println("registered")
        System.out.flush()
        System.`in`.read()
      }
    } catch (error: IllegalStateException) {
      if (mode != "rollback") throw error
    }
  }
}
