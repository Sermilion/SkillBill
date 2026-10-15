package dev.skillbill.tray.core.ui

import dev.skillbill.tray.core.domain.model.Freshness
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.PhaseExecution
import dev.skillbill.tray.core.domain.model.PhaseExecutionKind
import dev.skillbill.tray.core.domain.model.WorkPhase
import dev.skillbill.tray.core.testing.detail
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class WorkFormatTest {
  private val now = fixtureInstant.plus(70.seconds)

  @Test
  fun `a fresh live clock ticks forward from its last reading and never runs backwards`() {
    assertEquals(70_000L, displayedDuration(40_000, fixtureInstant.plus(40.seconds), now, Freshness.FRESH, live = true))
    assertEquals(40_000L, displayedDuration(40_000, now.plus(30.seconds), now, Freshness.FRESH, live = true))
  }

  @Test
  fun `a stale, finished or unreadable clock stays where it was`() {
    assertEquals(40_000L, displayedDuration(40_000, fixtureInstant, now, Freshness.STALE, live = true))
    assertEquals(40_000L, displayedDuration(40_000, fixtureInstant, now, Freshness.FRESH, live = false))
    assertEquals(40_000L, displayedDuration(40_000, null, now, Freshness.FRESH, live = true))
    assertNull(displayedDuration(null, fixtureInstant, now, Freshness.FRESH, live = true))
  }

  @Test
  fun `age shows the two largest units`() {
    assertEquals("5s", age(5.seconds))
    assertEquals("2m 0s", age(120.seconds))
    assertEquals("1h 1m", age(3_660.seconds))
  }

  @Test
  fun `duration text follows the live clock unless the read is stale`() {
    val item = workItem("a")

    assertEquals("2m 0s", durationText(30_000, fixtureInstant, item, fixtureInstant.plus(90.seconds), stale = false))
    assertEquals("30s", durationText(30_000, fixtureInstant, item, fixtureInstant.plus(90.seconds), stale = true))
    assertEquals("1m 30s", durationText(30_000, fixtureInstant, item, fixtureInstant.plus(90.seconds), stale = true, frozen = 90_000))
    assertEquals("not available", durationText(null, null, item, now, stale = false))
  }

  @Test
  fun `phase elapsed time runs to now only while the phase is live and fresh`() {
    val item = workItem("a").copy(startedAt = fixtureInstant, updatedAt = fixtureInstant.plus(30.seconds))

    assertEquals("2m 0s", phaseElapsedText(item, fixtureInstant.plus(120.seconds), stale = false))
    assertEquals("30s", phaseElapsedText(item, fixtureInstant.plus(120.seconds), stale = true))
    assertEquals("not available", phaseElapsedText(workItem("b"), now, stale = false))
    val finished = item.copy(lifecycle = Lifecycle.TERMINAL, finishedAt = fixtureInstant.plus(45.seconds))
    assertEquals("45s", phaseElapsedText(finished, fixtureInstant.plus(120.seconds), stale = false))
  }

  @Test
  fun `freshness is stale when the read, the extras or the runtime say so`() {
    val item = workItem("a")

    assertEquals(Freshness.FRESH, effectiveFreshness(item, readStale = false))
    assertEquals(Freshness.STALE, effectiveFreshness(item, readStale = true))
    assertEquals(Freshness.STALE, effectiveFreshness(item, readStale = false, detailExtrasStale = true))
    assertEquals(Freshness.STALE, effectiveFreshness(item.copy(freshness = Freshness.STALE), readStale = false))
  }

  @Test
  fun `the pipeline keeps the active phase visible while its step is not loaded yet`() {
    val item = workItem("a").copy(
      currentPhaseId = "validation",
      phaseExecution = PhaseExecution("validation", PhaseExecutionKind.PASS, 2, null),
    )
    val loaded = WorkPhase("implement", "complete", 1, true)

    val pipeline = phasePipeline(detail(item).copy(stepPipeline = listOf("implement", "validation", "finish"), phases = listOf(loaded)))

    assertEquals(listOf("implement", "validation", "finish"), pipeline.map { it.id })
    assertEquals(loaded, pipeline[0])
    assertEquals(WorkPhase("validation", "in_progress", 2, false), pipeline[1])
    assertEquals(WorkPhase("finish", "pending", 0, false), pipeline[2])
  }
}
