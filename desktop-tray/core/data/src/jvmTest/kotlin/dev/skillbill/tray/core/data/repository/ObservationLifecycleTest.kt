package dev.skillbill.tray.core.data.repository

import dev.skillbill.tray.core.data.ExternalRuntimeWriter
import dev.skillbill.tray.core.data.workStack
import dev.skillbill.tray.core.domain.model.Board
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.StorageFailure
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import skillbill.infrastructure.sqlite.TemporarySchemaDatabase
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ObservationLifecycleTest {
  @Test
  fun `cancelling collectors and closing the session release every owner watch, file watch and connection`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-observation-lifecycle").use { fixture ->
      // A phase owned by a live child process, so the selected detail must watch its owner.
      val process = ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"), ExternalRuntimeWriter::class.java.name,
        fixture.dbPath.toString(), "owned-phase",
      ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
      val stack = workStack(fixture.tempDir.toRealPath())
      val phases = Channel<Outcome<Board>>(Channel.UNLIMITED)
      val details = Channel<Outcome<Detail>>(Channel.UNLIMITED)
      var phaseJob: Job? = null
      var detailJob: Job? = null
      try {
        assertEquals("registered", withTimeout(10_000) {
          runInterruptible(Dispatchers.IO) { process.inputStream.bufferedReader().readLine() }
        })
        stack.selectDatabase(fixture.dbPath.toString())
        phaseJob = launch { stack.observe(WorkSection.PHASES, Retention.DAY).collect { phases.send(it) } }
        val board = assertIs<Outcome.Value<Board>>(withTimeout(10_000) { phases.receive() }).value
        val boardOwners = stack.ownerRegistrations.get()
        detailJob = launch { stack.observeDetail(board.items.single().identity).collect { details.send(it) } }
        assertIs<Outcome.Value<Detail>>(withTimeout(10_000) { details.receive() })
        // Preconditions that make the zero assertions below meaningful: something is registered and watched.
        assertTrue(stack.ownerRegistrations.get() > boardOwners, "the detail subscription watches its owner")
        assertEquals(1, stack.signalRegistrations.get())
        assertEquals(1, stack.openConnections.get())

        detailJob.cancelAndJoin()
        awaitValue(stack.ownerRegistrations, boardOwners)
        phaseJob.cancelAndJoin()
        stack.closeAndJoin()
        awaitValue(stack.ownerRegistrations, 0)
        awaitValue(stack.signalRegistrations, 0)
        awaitValue(stack.openConnections, 0)
      } finally {
        process.destroyForcibly()
        runInterruptible(Dispatchers.IO) { process.waitFor(5, TimeUnit.SECONDS) }
        phaseJob?.cancelAndJoin()
        detailJob?.cancelAndJoin()
        stack.closeAndJoin()
      }
    }
  }

  @Test
  fun `replacing the database during an in-flight read completes without a stale board or a leaked connection`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-replace-first").use { first ->
      establishTemporarySchemaReadiness("desktop-replace-second").use { second ->
        registerLivePhase(first, "first-branch")
        registerLivePhase(second, "second-branch")
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val armed = AtomicBoolean(true)
        val stack = workStack(first.tempDir.toRealPath(), onCandidateQuery = {
          // Holds only the first candidate query, so the replacement below runs while that read is still in flight.
          if (armed.compareAndSet(true, false)) {
            entered.complete(Unit)
            release.await()
          }
        })
        try {
          stack.selectDatabase(first.dbPath.toString())
          val inFlight = async(Dispatchers.IO) { stack.board(WorkSection.PHASES, Retention.DAY) }
          withTimeout(10_000) { entered.await() }
          val replacement = launch { stack.selectDatabase(second.dbPath.toString()) }
          release.countDown()
          val staleRead = withTimeout(10_000) { inFlight.await() }
          withTimeout(10_000) { replacement.join() }

          // Whatever the in-flight read returned, it never carries the replacement store's rows.
          if (staleRead is Outcome.Value) assertTrue(staleRead.value.items.all { it.branch == "first-branch" })
          val current = assertIs<Outcome.Value<Board>>(stack.board(WorkSection.PHASES, Retention.DAY))
          assertEquals(listOf("second-branch"), current.value.items.map { it.branch })
          awaitValue(stack.openConnections, 1)
          stack.closeAndJoin()
          awaitValue(stack.openConnections, 0)
          awaitValue(stack.signalRegistrations, 0)
        } finally {
          release.countDown()
          stack.closeAndJoin()
        }
      }
    }
  }

  @Test
  fun `a store that fails initialization leaves no connection or watch behind`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-failed-initialization").use { fixture ->
      val stack = workStack(fixture.tempDir.toRealPath())
      try {
        fixture.connection.createStatement().use { it.execute("PRAGMA user_version = 51") }
        stack.selectDatabase(fixture.dbPath.toString())
        val outcome = stack.board(WorkSection.PHASES, Retention.DAY)
        assertIs<Outcome.Failure>(outcome, outcome.toString())
        assertEquals(StorageFailure.INCOMPATIBLE_SCHEMA, outcome.problem.storageFailure)
        awaitValue(stack.openConnections, 0)
        awaitValue(stack.ownerRegistrations, 0)
        awaitValue(stack.signalRegistrations, 0)
      } finally {
        stack.closeAndJoin()
      }
    }
  }

  private fun registerLivePhase(fixture: TemporarySchemaDatabase, branch: String) {
    val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
    val now = Instant.now()
    factory.transaction { unit ->
      unit.standalonePhaseStatuses.register(
        StandalonePhaseStatusRegistration(
          "repo-root-realpath-v1:${fixture.tempDir.toRealPath()}", branch, "SKILL-1", null, "live-$branch", "review",
          "execution-$branch", "active", "review", now.minusSeconds(60), "owned-test", 1, now.plusSeconds(300),
        ),
      )
    }
  }

  private suspend fun awaitValue(counter: AtomicInteger, expected: Int) {
    withTimeout(10_000) { while (counter.get() != expected) delay(20) }
  }
}
