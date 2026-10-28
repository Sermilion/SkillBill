package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.data.runtime.DetailRead
import dev.skillbill.desktop.core.data.runtime.DetailReads
import dev.skillbill.desktop.core.data.signal.ChangedIds
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.RunOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

private val ROW = workItem("goal-1")
private val OPTIONS =
  RunOptions(canCancel = false, canLaunch = true, launchLabel = "Resume", agentId = "claude", agentChoices = emptyList(), unavailableReason = null)

class DetailRepositoryTest {
  @Test
  fun `the first emission is the row header with no read`() = runTest {
    val reads = FakeDetailReads()
    val details = DetailObservations(backgroundScope, MutableStateFlow(signal()), reads)

    val first = details.observe(ROW).first()

    assertEquals(DetailState(ROW, DetailSections.Pending), first)
    assertEquals(0, reads.firstReads)
  }

  @Test
  fun `the first load is one read that carries the run options`() = runTest {
    val reads = FakeDetailReads()
    val details = DetailObservations(backgroundScope, MutableStateFlow(signal()), reads)
    val states = mutableListOf<DetailState>()
    backgroundScope.launch { details.observe(ROW).collect { states += it } }
    runCurrent()

    assertEquals(1, reads.firstReads)
    assertEquals(OPTIONS, assertIs<DetailSections.Loaded>(states.last().sections).runOptions)
  }

  @Test
  fun `only a change listing the open id refreshes it, in place`() = runTest {
    val signals = MutableStateFlow(signal())
    val reads = FakeDetailReads()
    val details = DetailObservations(backgroundScope, signals, reads)
    val states = mutableListOf<DetailState>()
    backgroundScope.launch { details.observe(ROW).collect { states += it } }
    runCurrent()

    signals.value = signal(activity = 2, changed = mapOf("activity" to ChangedIds.Ids(base = 1, ids = listOf("other"))))
    runCurrent()
    assertEquals(0, reads.refreshes)

    signals.value = signal(activity = 3, changed = mapOf("activity" to ChangedIds.Ids(base = 2, ids = listOf(ROW.id))))
    runCurrent()
    assertEquals(1, reads.refreshes)
    assertTrue(states.drop(1).none { it.sections == DetailSections.Pending })
  }

  private class FakeDetailReads : DetailReads {
    var firstReads = 0
    var refreshes = 0

    override suspend fun first(id: String): DetailRead {
      firstReads++
      return loaded()
    }

    override suspend fun refresh(id: String, extended: Map<DetailList, Int>): DetailRead {
      refreshes++
      return loaded(observedAtMs = refreshes.toLong())
    }

    override suspend fun list(id: String, list: DetailList, cursor: String?): DetailRead = loaded()

    private fun loaded(observedAtMs: Long = 0) = DetailRead.Loaded(
      ROW,
      DetailSections.Loaded(
        emptyDetail(Instant.fromEpochMilliseconds(observedAtMs)), OPTIONS, emptyMap(), emptySet(), stale = false,
      ),
    )
  }
}
