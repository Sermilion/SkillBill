package dev.skillbill.tray.core.data.repository

import dev.skillbill.tray.core.data.ExternalRuntimeWriter
import dev.skillbill.tray.core.data.workStack
import dev.skillbill.tray.core.domain.model.Board
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import java.nio.file.Path
import java.util.concurrent.TimeUnit
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

  private suspend fun awaitValue(counter: AtomicInteger, expected: Int) {
    withTimeout(10_000) { while (counter.get() != expected) delay(20) }
  }
}
