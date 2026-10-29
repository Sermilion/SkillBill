package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailAttempts
import dev.skillbill.desktop.core.domain.model.Incident
import dev.skillbill.desktop.core.domain.model.IncidentPrerequisiteDetail
import dev.skillbill.desktop.core.domain.model.IncidentProgress
import dev.skillbill.desktop.core.domain.model.IncidentRecovery
import dev.skillbill.desktop.core.domain.model.IncidentReport
import dev.skillbill.desktop.core.domain.model.IncidentTelemetry
import dev.skillbill.desktop.core.domain.model.LatestActivity
import kotlin.time.Instant

internal val epoch = Instant.fromEpochMilliseconds(0)

internal val emptyDetail = Detail(
  featureTitle = null, workflowId = null, invocationId = null, activeAgent = null, executionLiveness = null,
  updatedAt = epoch, lastAgentActivityLabel = null, lastAgentActivityAt = null, currentSubtaskStartedAt = null,
  subtaskActiveDurationMs = null, subtaskActiveDurationAsOf = null, qaVerdict = null, qaOutcome = null, stepPipeline = emptyList(),
  goalFinishPipeline = emptyList(), currentPhase = null, completeCount = 0, pendingCount = 0, blockedCount = 0, subtasks = emptyList(),
  phases = emptyList(), dependencies = emptyList(), attempts = DetailAttempts(emptyMap(), emptyMap(), emptyMap(), null, 0),
  validation = emptyList(), phaseResults = emptyList(), goalQa = null, goalAssessment = null, latestActivity = LatestActivity(null, null),
  executionHistory = emptyList(), incident = null, incidentHistory = emptyList(), goalSpecBundle = null, observedAt = epoch,
)

internal fun incident(progress: IncidentProgress, vararg prerequisiteKinds: String) = Incident(
  incidentToken = "token-1", revision = 1, progress = progress, summary = "Blocked", cause = null, certainty = "likely",
  responsibleCategory = "environment", nextAction = "Fix it", noWorkaroundKnown = false,
  prerequisites = prerequisiteKinds.map { IncidentPrerequisiteDetail(it, "Do $it") }, evidence = emptyList(), degradations = emptySet(),
  recovery = IncidentRecovery(null, null, null, null, null, null, null, null), report = IncidentReport("pending", null, null),
  telemetry = IncidentTelemetry("available", emptyMap()), observedAt = epoch, settledAt = null,
)
