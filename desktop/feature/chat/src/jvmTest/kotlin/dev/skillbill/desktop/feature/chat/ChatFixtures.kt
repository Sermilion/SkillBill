package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailAttempts
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.LatestActivity
import dev.skillbill.desktop.core.domain.model.WorkItem
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

internal fun loaded(header: WorkItem, detail: Detail = emptyDetail) =
  DetailState(header, DetailSections.Loaded(detail, null, emptyMap(), emptySet(), stale = false))
