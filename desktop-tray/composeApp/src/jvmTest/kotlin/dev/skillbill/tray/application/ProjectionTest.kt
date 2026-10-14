package dev.skillbill.tray.application

import dev.skillbill.tray.core.domain.model.*
import dev.skillbill.tray.core.testing.fixtureInstant
import dev.skillbill.tray.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ProjectionTest {
  @Test
  fun `projection sorts attention work counts completion evidence and freezes stale clocks`() {
    val observed = fixtureInstant
    val blocked = item("a", Lifecycle.BLOCKED, WorkFamily.FEATURE_GOAL)
    val running = item("b", Lifecycle.ACTIVE, WorkFamily.FEATURE_GOAL)
    val failed = item("failed", Lifecycle.FAILED, WorkFamily.FEATURE_GOAL)
    val phase = item("c", Lifecycle.TERMINAL, WorkFamily.FEATURE_VERIFY).copy(scope = ExecutionScope.STANDALONE_PHASE, finishedAt = observed - 120.seconds, displayGroup = WorkSection.PHASES)
    val oldFinished = item("d", Lifecycle.TERMINAL, WorkFamily.FEATURE_GOAL).copy(finishedAt = observed - 90_000.seconds)
    val board = Board(observed, Retention.WEEK, listOf(running, blocked, failed, phase, oldFinished))
    val projection = project(board, observed + 10.seconds)
    assertEquals(listOf("a", "failed", "b"), projection.goals.map { it.identity.id })
    assertEquals(2, projection.needsYou)
    assertEquals(1, projection.done24h)
    assertEquals(listOf("c"), projection.phases.map { it.identity.id })
    assertEquals(40_000L, displayedDuration(40_000, observed, observed + 30.seconds, Freshness.STALE, true))
    assertEquals(70_000L, displayedDuration(40_000, observed, observed + 30.seconds, Freshness.FRESH, true))
  }

  private fun item(id: String, lifecycle: Lifecycle, family: WorkFamily) = workItem(id, lifecycle, family).copy(
    activeDurationMs = 40_000,
    activeDurationAsOf = fixtureInstant,
    liveSection = when (lifecycle) {
      Lifecycle.BLOCKED, Lifecycle.FAILED -> WorkSection.NEEDS_YOU
      Lifecycle.ACTIVE -> if (family == WorkFamily.FEATURE_GOAL) WorkSection.GOALS else WorkSection.PHASES
      else -> null
    },
    requiresAttention = lifecycle in setOf(Lifecycle.BLOCKED, Lifecycle.FAILED),
    displayGroup = if (lifecycle in setOf(Lifecycle.ACTIVE, Lifecycle.BLOCKED, Lifecycle.FAILED)) WorkSection.GOALS else null,
    displayPriority = when (lifecycle) {
      Lifecycle.BLOCKED -> 0
      Lifecycle.FAILED -> 1
      Lifecycle.ACTIVE -> 2
      else -> 5
    },
  )
}
