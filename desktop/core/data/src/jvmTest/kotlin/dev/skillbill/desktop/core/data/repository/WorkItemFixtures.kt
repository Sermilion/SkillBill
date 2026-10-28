package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailAttempts
import dev.skillbill.desktop.core.domain.model.LatestActivity
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkControlIdentity
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkLiveSection
import kotlin.time.Instant

internal fun workItem(id: String, membershipDeadline: Instant? = null) = WorkItem(
  id = id, kind = null, issueKey = null, title = id, summary = id, lifecycle = Lifecycle.Active, planningPrepared = false,
  stalledSince = null, pauseRequested = false, fresh = true, stepLabel = "implement", phaseExecution = null,
  phaseExecutionLabel = null, progress = null, planningProgress = null, activeDurationMs = null, activeDurationAsOf = null,
  startedAt = null, finishedAt = null, model = null, effort = null, agentId = null, branch = null, repositoryName = "repo",
  repositoryRoot = "/repo", liveSection = WorkLiveSection.Goals, displayGroup = null, displayPriority = 5,
  requiresAttention = false, controlEligible = false, pauseEligible = false, running = true, recentlyFinishedPhase = false,
  doneWithinDay = false, freshnessDeadline = null, membershipDeadline = membershipDeadline, exactPauseReason = null,
  awaitsOperatorDecision = false, currentSubtaskId = null, reason = null,
  control = WorkControlIdentity(
    sessionId = 1L, workflowId = id, invocationId = null, repositoryIdentity = "repo", executionId = null, statusStoreId = null,
    runSequence = null, statusRevision = null, phaseId = null, updatedAt = Instant.fromEpochMilliseconds(0),
    controlLeaseOwner = null, controlLeaseGeneration = null, storeId = null, storeRevision = null, databaseFile = null,
  ),
)

internal fun emptyDetail(observedAt: Instant = Instant.fromEpochMilliseconds(0)) = Detail(
  featureTitle = null, workflowId = null, invocationId = null, activeAgent = null, executionLiveness = null,
  updatedAt = observedAt, lastAgentActivityLabel = null, lastAgentActivityAt = null, currentSubtaskStartedAt = null,
  subtaskActiveDurationMs = null, subtaskActiveDurationAsOf = null, qaVerdict = null, qaOutcome = null,
  stepPipeline = emptyList(), goalFinishPipeline = emptyList(), currentPhase = null, completeCount = 0, pendingCount = 0,
  blockedCount = 0, subtasks = emptyList(), phases = emptyList(), dependencies = emptyList(),
  attempts = DetailAttempts(emptyMap(), emptyMap(), emptyMap(), null, 0), validation = emptyList(), phaseResults = emptyList(),
  goalQa = null, goalAssessment = null, latestActivity = LatestActivity(null, null), executionHistory = emptyList(),
  incident = null, incidentHistory = emptyList(), goalSpecBundle = null, observedAt = observedAt,
)
