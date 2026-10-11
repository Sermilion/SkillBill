package dev.skillbill.tray.application

import dev.skillbill.tray.domain.*
import dev.skillbill.tray.domain.Retention
import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.Instant

class ProjectionTest {
  @Test
  fun `projection sorts attention work counts completion evidence and freezes stale clocks`() {
    val observed = Instant.parse("2026-10-11T10:00:00Z")
    val blocked = item("a", Lifecycle.BLOCKED, WorkFamily.FEATURE_GOAL)
    val running = item("b", Lifecycle.ACTIVE, WorkFamily.FEATURE_GOAL)
    val failed = item("failed", Lifecycle.FAILED, WorkFamily.FEATURE_GOAL)
    val phase = item("c", Lifecycle.TERMINAL, WorkFamily.FEATURE_VERIFY).copy(scope = ExecutionScope.STANDALONE_PHASE, finishedAt = observed.minusSeconds(120), displayGroup = WorkSection.PHASES)
    val oldFinished = item("d", Lifecycle.TERMINAL, WorkFamily.FEATURE_GOAL).copy(finishedAt = observed.minusSeconds(90_000))
    val board = Board(observed, Retention.WEEK, listOf(running, blocked, failed, phase, oldFinished))
    val projection = project(board, observed.plusSeconds(10))
    assertEquals(listOf("a", "failed", "b"), projection.goals.map { it.identity.id })
    assertEquals(2, projection.needsYou)
    assertEquals(1, projection.done24h)
    assertEquals(listOf("c"), projection.phases.map { it.identity.id })
    assertEquals(40_000L, displayedDuration(40_000, observed, observed.plusSeconds(30), Freshness.STALE, true))
    assertEquals(70_000L, displayedDuration(40_000, observed, observed.plusSeconds(30), Freshness.FRESH, true))
  }

  private fun item(id: String, lifecycle: Lifecycle, family: WorkFamily) = WorkItem(
    identity = WorkIdentity(id, "repo-$id"),
    workflowId = id,
    invocationId = null,
    repositoryRoot = "/repo/$id",
    branch = "main",
    issueKey = "SKILL-$id",
    family = family,
    scope = ExecutionScope.WORKFLOW,
    phaseId = null,
    activity = null,
    lifecycle = lifecycle,
    freshness = Freshness.FRESH,
    summary = "summary",
    currentStepId = "implement",
    currentStepLabel = "Implement",
    progressCompleted = null,
    progressTotal = null,
    activeDurationMs = 40_000,
    activeDurationAsOf = Instant.parse("2026-10-11T10:00:00Z"),
    subtaskActiveDurationMs = null,
    subtaskActiveDurationAsOf = null,
    pauseRequested = false,
    pauseReasonCode = null,
    pauseReason = null,
    planningState = null,
    plannedSubtasks = null,
    totalSubtasks = null,
    planningWaveSubtaskIds = emptyList(),
    executionCount = null,
    executionTotal = null,
    updatedAt = Instant.parse("2026-10-11T10:00:00Z"),
    finishedAt = null,
    finishedAtSource = null,
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
