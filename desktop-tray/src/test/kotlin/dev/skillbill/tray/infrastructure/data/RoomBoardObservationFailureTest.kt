package dev.skillbill.tray.infrastructure.data

import dev.skillbill.tray.application.Outcome
import dev.skillbill.tray.domain.Board
import dev.skillbill.tray.domain.ProblemCode
import dev.skillbill.tray.domain.Retention
import dev.skillbill.tray.domain.WorkSection
import dev.skillbill.tray.infrastructure.storage.ExternalCommitObserver
import dev.skillbill.tray.infrastructure.storage.StoreChange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RoomBoardObservationFailureTest {
  @Test
  fun `a lost watcher emits one stale result without periodic reads and explicit refresh restores observation`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-observation-no-refresh-loop").use { fixture ->
      val attempts = AtomicInteger()
      val reads = AtomicInteger()
      val disconnect = CompletableDeferred<Unit>()
      val results = Channel<Outcome<Board>>(Channel.UNLIMITED)
      val source = RoomBoardSource(emptyMap(), fixture.tempDir, fixture.tempDir,
        Clock.fixed(Instant.now(), ZoneOffset.UTC), recordReadWork = { reads.incrementAndGet() },
        observeChanges = { selected, ready ->
          if (attempts.incrementAndGet() == 1) flow {
            emit(StoreChange(resynchronize = true))
            ready.complete(Unit)
            disconnect.await()
            throw IOException("watch disconnected")
          } else ExternalCommitObserver(selected).changes(ready)
        })
      source.selectDatabase(fixture.dbPath.toString())
      val subscription = launch {
        source.observe(WorkSection.PHASES, Retention.DAY).collect { results.send(it) }
      }
      try {
        assertIs<Outcome.Value<Board>>(withTimeout(3000) { results.receive() })
        disconnect.complete(Unit)
        val failure = assertIs<Outcome.Failure>(withTimeout(3000) { results.receive() })
        assertEquals(ProblemCode.OBSERVATION_UNAVAILABLE, failure.problem.code)
        val idleReads = reads.get()
        assertNull(withTimeoutOrNull(600) { results.receive() })
        assertEquals(idleReads, reads.get())
        assertEquals(1, attempts.get())
        source.refresh()
        assertIs<Outcome.Value<Board>>(withTimeout(3000) { results.receive() })
        assertEquals(2, attempts.get())
      } finally {
        subscription.cancelAndJoin()
        source.closeAndJoin()
      }
    }
  }
}
