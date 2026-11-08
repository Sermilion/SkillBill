package dev.skillbill.desktop.core.data.repository

import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailAttempts
import dev.skillbill.desktop.core.domain.model.LatestActivity
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkControlIdentity
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkLiveSection
import kotlin.time.Instant
import skillbill.goalrunner.model.GoalRunnerCiCheck
import skillbill.goalrunner.model.GoalRunnerCiCycle
import skillbill.goalrunner.model.GoalRunnerHistoryReceipt
import skillbill.goalrunner.model.GoalRunnerPhaseCard
import skillbill.goalrunner.model.GoalRunnerPhaseCardEdit
import skillbill.goalrunner.model.GoalRunnerPhaseCardOutput
import skillbill.goalrunner.model.GoalRunnerPhaseCardState
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.goalrunner.model.GoalRunnerSubtaskPhaseResults
import java.time.Instant as JavaInstant

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

internal fun phaseCardRuntimeFixture(nullFacts: Boolean = false): GoalRunnerPhaseCard {
  val at = JavaInstant.parse("2026-11-08T10:00:00Z")
  return GoalRunnerPhaseCard(
    phaseId = "implement", state = if (nullFacts) GoalRunnerPhaseCardState.BLOCKED else GoalRunnerPhaseCardState.RUNNING,
    round = if (nullFacts) null else 2, startedAt = at.takeUnless { nullFacts }, finishedAt = null,
    durationMillis = null, agentId = "agent".takeUnless { nullFacts }, model = "model".takeUnless { nullFacts },
    effort = "high".takeUnless { nullFacts }, blockedReason = "reason".takeUnless { nullFacts },
    files = if (nullFacts) emptyList() else listOf("src/Example.kt"),
    edit = if (nullFacts) null else GoalRunnerPhaseCardEdit(at, 1, 4, 2, listOf("src/Example.kt")),
    activityAt = at.takeUnless { nullFacts }, output = if (nullFacts) null else GoalRunnerPhaseCardOutput(
      "passed", null, null, GoalRunnerHistoryReceipt(listOf("src/Example.kt"), true, false),
      "abc123", "feature/527", true, "https://example.test/pr/7", 7,
    ), pullRequestUrl = "https://example.test/pr/7".takeUnless { nullFacts }, pullRequestNumber = 7.takeUnless { nullFacts },
    ciCycles = if (nullFacts) emptyList() else listOf(GoalRunnerCiCycle(1, at, at, at, "passed", "abc123", listOf(GoalRunnerCiCheck("check", "pass", null, at, at, 0)))),
    repairedChecks = emptyList(), conflictBaseBranch = null, fixCommitSha = null,
  )
}

internal fun phaseCardProjectionRuntimeFixture(): GoalRunnerStatusProjection = GoalRunnerStatusProjection(
  issueKey = "SKILL-527", completeCount = 0, pendingCount = 0, blockedCount = 0, currentSubtaskId = 2,
  currentStep = "implement", activeAgent = "agent",
  completedSubtaskPhaseResults = listOf(GoalRunnerSubtaskPhaseResults(2, phaseCards = listOf(phaseCardRuntimeFixture()))),
  goalFinishCards = listOf("goal_assessment", "qa", "qa_fix", "validate", "pr", "monitor", "monitor_fix").map { phaseCardRuntimeFixture().copy(phaseId = it) },
)
