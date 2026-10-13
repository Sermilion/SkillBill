package dev.skillbill.tray.infrastructure.data

import dev.skillbill.tray.application.Outcome
import dev.skillbill.tray.domain.Board
import dev.skillbill.tray.domain.ProblemCode
import dev.skillbill.tray.domain.Retention
import dev.skillbill.tray.domain.WorkSection
import dev.skillbill.tray.infrastructure.storage.DesktopCommitSignalObserver
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomBoardObservationFailureTest {
  @Test
  fun `a change during a board read publishes that snapshot before the pending refresh`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-observation-read-starvation").use { fixture ->
      val armed = AtomicBoolean()
      val reads = AtomicInteger()
      val reading = CompletableDeferred<Unit>()
      val delivered = CompletableDeferred<Unit>()
      val releaseRead = CountDownLatch(1)
      val releaseRefresh = CountDownLatch(1)
      val changes = Channel<StoreChange>(Channel.UNLIMITED)
      val results = Channel<Outcome<Board>>(Channel.UNLIMITED)
      val source = RoomBoardSource(emptyMap(), fixture.tempDir, fixture.tempDir,
        Clock.fixed(Instant.now(), ZoneOffset.UTC), recordReadWork = {
          if (armed.get()) when (reads.incrementAndGet()) {
            2 -> {
              reading.complete(Unit)
              check(releaseRead.await(10, TimeUnit.SECONDS))
            }
            3 -> check(releaseRefresh.await(10, TimeUnit.SECONDS))
          }
        }, observeChanges = { _, ready -> flow {
          emit(StoreChange(initial = true))
          ready.complete(Unit)
          for (change in changes) {
            emit(change)
            delivered.complete(Unit)
          }
        } })
      source.selectDatabase(fixture.dbPath.toString())
      assertIs<Outcome.Value<Board>>(source.board(WorkSection.HISTORY, Retention.DAY))
      armed.set(true)
      val subscription = launch {
        source.observe(WorkSection.HISTORY, Retention.DAY).collect { results.send(it) }
      }
      try {
        withTimeout(3000) { reading.await() }
        changes.send(StoreChange(resynchronize = true))
        withTimeout(3000) { delivered.await() }
        releaseRead.countDown()
        val first = assertIs<Outcome.Value<Board>>(withTimeout(3000) { results.receive() })
        assertTrue(first.value.items.isEmpty())
        releaseRefresh.countDown()
        val refreshed = assertIs<Outcome.Value<Board>>(withTimeout(3000) { results.receive() })
        assertEquals(first.value.queryEpoch, refreshed.value.queryEpoch)
      } finally {
        releaseRead.countDown()
        releaseRefresh.countDown()
        subscription.cancelAndJoin()
        source.closeAndJoin()
      }
    }
  }

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
            emit(StoreChange(initial = true))
            ready.complete(Unit)
            disconnect.await()
            throw IOException("watch disconnected")
          } else DesktopCommitSignalObserver(selected).changes(ready)
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
