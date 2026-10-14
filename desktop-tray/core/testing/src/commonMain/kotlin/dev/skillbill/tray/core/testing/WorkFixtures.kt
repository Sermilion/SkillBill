package dev.skillbill.tray.core.testing

import dev.skillbill.tray.core.domain.model.Board
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.ExecutionScope
import dev.skillbill.tray.core.domain.model.Freshness
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.WorkFamily
import dev.skillbill.tray.core.domain.model.WorkIdentity
import dev.skillbill.tray.core.domain.model.WorkItem
import kotlin.time.Instant

val fixtureInstant: Instant = Instant.parse("2026-10-11T10:00:00Z")

fun workItem(
  id: String,
  lifecycle: Lifecycle = Lifecycle.ACTIVE,
  family: WorkFamily? = WorkFamily.FEATURE_GOAL,
  issueKey: String? = "SKILL-$id",
): WorkItem = WorkItem(
  identity = WorkIdentity(id, "repo-$id"),
  workflowId = id,
  invocationId = null,
  repositoryRoot = "/repo/$id",
  branch = "main",
  issueKey = issueKey,
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
  activeDurationMs = null,
  activeDurationAsOf = null,
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
  updatedAt = fixtureInstant,
  finishedAt = null,
  finishedAtSource = null,
)

fun board(items: List<WorkItem>, retention: Retention = Retention.DAY, observedAt: Instant = fixtureInstant): Board =
  Board(observedAt, retention, items)

fun detail(item: WorkItem): Detail =
  Detail(item, listOf("implement"), listOf("pr", "monitor"), "Feature title", "implement", 0, 1, 0, null, null, emptyMap())
