package dev.skillbill.tray.core.testing

import dev.skillbill.tray.core.domain.model.Board
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.ExecutionScope
import dev.skillbill.tray.core.domain.model.Freshness
import dev.skillbill.tray.core.domain.model.HistoryFilter
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.Incident
import dev.skillbill.tray.core.domain.model.IncidentProgress
import dev.skillbill.tray.core.domain.model.IncidentRecovery
import dev.skillbill.tray.core.domain.model.IncidentRecoveryEligibility
import dev.skillbill.tray.core.domain.model.IncidentRecoveryPreview
import dev.skillbill.tray.core.domain.model.IncidentReport
import dev.skillbill.tray.core.domain.model.IncidentTelemetry
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

/** A page the way the runtime reports it: tied to one store revision and query, which is what a section accepts. */
fun observedBoard(
  items: List<WorkItem>,
  nextCursor: String? = null,
  retention: Retention = Retention.DAY,
  filter: String = "",
  historyFilter: HistoryFilter = HistoryFilter.ALL,
  generation: Long = 1,
  revision: Long = 1,
  pageKey: String = "root",
): Board = Board(
  observedAt = fixtureInstant,
  finishedWithin = retention,
  items = items,
  nextCursor = nextCursor,
  generation = generation,
  observationSession = 1,
  storeId = "store",
  revision = revision,
  pageKey = pageKey,
  queryEpoch = 1,
  filter = filter,
  historyFilter = historyFilter,
)

fun detail(item: WorkItem): Detail =
  Detail(item, listOf("implement"), listOf("pr", "monitor"), "Feature title", "implement", 0, 1, 0, null, null, emptyMap())

fun incident(
  progress: IncidentProgress = IncidentProgress.RECOVERY_AVAILABLE,
  token: String = "bi_fixture",
): Incident = Incident(
  incidentToken = token,
  revision = 1,
  progress = progress,
  summary = "Provider returned 529 overloaded",
  cause = "Provider returned 529 overloaded",
  certainty = "suspected",
  responsibleCategory = "external_service",
  nextAction = "Reopen blocked phase",
  noWorkaroundKnown = false,
  prerequisites = emptyList(),
  evidence = emptyList(),
  degradations = emptySet(),
  recovery = IncidentRecovery(
    IncidentRecoveryEligibility.Available(
      IncidentRecoveryPreview(
        "reopen_blocked_phase", "Reopen blocked phase", "Retry the blocked phase", "workflow", null,
        "implement", token, 1,
      ),
    ),
    "Reopen blocked phase", "Retry the blocked phase", "workflow", null, null, null, null,
  ),
  report = IncidentReport("local_only", null, null),
  telemetry = IncidentTelemetry("disabled", mapOf("incident_token" to token)),
  observedAt = fixtureInstant,
  settledAt = fixtureInstant,
)
