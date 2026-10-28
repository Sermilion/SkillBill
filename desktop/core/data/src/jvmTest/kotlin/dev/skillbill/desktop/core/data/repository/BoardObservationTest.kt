package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.data.runtime.BoardPage
import dev.skillbill.desktop.core.data.runtime.BoardReads
import dev.skillbill.desktop.core.data.signal.ChangedIds
import dev.skillbill.desktop.core.data.signal.SignalSnapshot
import dev.skillbill.desktop.core.domain.model.BoardCounts
import dev.skillbill.desktop.core.domain.model.BoardQuery
import dev.skillbill.desktop.core.domain.model.TrayStatus
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.settings.HistoryWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val QUERY = BoardQuery(HistoryWindow.Day, filter = "", pageSize = 20)

class BoardObservationTest {
  @Test
  fun `three owners of one query share one read per goals change`() = runTest {
    val (signals, reads, board) = fixture()
    repeat(3) { backgroundScope.launch { board.observe(QUERY).collect {} } }
    runCurrent()
    signals.value = signal(goals = 2)
    runCurrent()
    assertEquals(2, reads.pageReads)
  }

  @Test
  fun `an activity-only change does not re-read the board`() = runTest {
    val (signals, reads, board) = fixture()
    backgroundScope.launch { board.observe(QUERY).collect {} }
    runCurrent()
    signals.value = signal(activity = 2)
    runCurrent()
    assertEquals(1, reads.pageReads)
  }

  @Test
  fun `one re-query runs just after the earliest deadline and none before it`() = runTest {
    val deadline = Instant.fromEpochMilliseconds(currentTime) + 10.minutes
    val (_, reads, board) = fixture(listOf(workItem("goal", membershipDeadline = deadline)))
    backgroundScope.launch { board.observe(QUERY).collect {} }
    runCurrent()
    advanceTimeBy(10.minutes - 1.seconds)
    runCurrent()
    assertEquals(1, reads.pageReads)
    advanceTimeBy(1.seconds + 1.milliseconds)
    runCurrent()
    assertEquals(2, reads.pageReads)
    advanceTimeBy(30.minutes)
    assertEquals(2, reads.pageReads)
  }

  private fun TestScope.fixture(
    items: List<WorkItem> = listOf(workItem("goal")),
  ): Triple<MutableStateFlow<SignalSnapshot>, FakeBoardReads, BoardObservations> {
    val clock = object : Clock {
      override fun now() = Instant.fromEpochMilliseconds(testScheduler.currentTime)
    }
    val signals = MutableStateFlow(signal())
    val reads = FakeBoardReads(items, clock)
    return Triple(signals, reads, BoardObservations(backgroundScope, signals, reads, clock))
  }

  private class FakeBoardReads(private val items: List<WorkItem>, private val clock: Clock) : BoardReads {
    var pageReads = 0

    override suspend fun pages(query: BoardQuery, pageCount: Int): Outcome<BoardPage, Nothing> {
      pageReads++
      return Outcome.Success(BoardPage(items, nextCursor = null, observedAt = clock.now()))
    }

    override suspend fun page(query: BoardQuery, cursor: String) = pages(query, 1)

    override fun counts(items: List<WorkItem>) = BoardCounts(TrayStatus.Idle, 0, 0, 0, 0, 0, 0, 0, 0)
  }
}

internal fun signal(
  goals: Long = 1,
  phases: Long = 1,
  activity: Long = 1,
  changed: Map<String, ChangedIds> = emptyMap(),
) = SignalSnapshot(
  generation = "g1",
  revisions = mapOf("goals" to goals, "phases" to phases, "activity" to activity, "specs" to 1L),
  changed = changed,
  resynchronize = false,
  resyncEpoch = 0,
)
