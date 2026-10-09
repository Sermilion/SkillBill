package dev.skillbill.intellij.presentation

import dev.skillbill.intellij.domain.GoalPlanningInfo
import dev.skillbill.intellij.domain.POLL_FAILED_REASON_CODE
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.domain.StatusDiagnostic
import java.time.Duration
import java.time.Instant


object StatusUiMapper {
    const val POLL_TIMEOUT_NOTE: String = "Status poll timed out. Showing the last live snapshot."
    const val POLL_CANCELLED_NOTE: String = "Status poll was cancelled. Showing the last live snapshot."
    const val POLL_FAILED_NOTE: String = "Status poll failed. Showing the last live snapshot."

    fun map(outcome: SkillBillStatusOutcome, now: Instant): SkillBillStatusUiState =
        when (outcome) {
            is SkillBillStatusOutcome.Idle ->
                SkillBillStatusUiState.Idle(
                    headline = "Skill Bill: idle",
                    detail = outcome.summary,
                    lastUpdated = outcome.observedAt,
                    stale = outcome.stale,
                )

            is SkillBillStatusOutcome.Done ->
                SkillBillStatusUiState.Done(
                    headline = doneHeadline(outcome),
                    detail = outcome.summary,
                    goalElapsed = activeElapsed(
                        outcome.activeDurationMs,
                        outcome.activeDurationAsOf,
                        outcome.startedAt,
                        settledAt(outcome.updatedAt, now),
                    ),
                    progressCompleted = outcome.progressCompleted,
                    progressTotal = outcome.progressTotal,
                    issueKey = outcome.issueKey,
                    startedAt = outcome.startedAt,
                    lastUpdated = outcome.updatedAt ?: outcome.observedAt,
                    stale = outcome.stale,
                    executionScope = outcome.execution?.executionScope,
                    phaseId = outcome.execution?.phaseId,
                    currentActivity = outcome.execution?.currentActivity,
                )

            is SkillBillStatusOutcome.Active ->
                SkillBillStatusUiState.Active(
                    headline = activeHeadline(outcome),
                    detail = outcome.summary,
                    goalElapsed = activeElapsed(
                        outcome.activeDurationMs,
                        outcome.activeDurationAsOf,
                        outcome.startedAt,
                        now,
                    ),
                    subtaskElapsed = subtaskElapsed(
                        outcome.subtaskActiveDurationMs,
                        outcome.subtaskActiveDurationAsOf,
                        outcome.subtaskStartedAt,
                        outcome.activeDurationMs,
                        outcome.activeDurationAsOf,
                        outcome.startedAt,
                        now,
                    ),
                    progressCompleted = outcome.progressCompleted,
                    progressTotal = outcome.progressTotal,
                    issueKey = outcome.issueKey,
                    workflowId = outcome.workflowId,
                    stepLabel = outcome.currentStepLabel,
                    startedAt = outcome.startedAt,
                    subtaskStartedAt = outcome.subtaskStartedAt,
                    lastUpdated = outcome.updatedAt,
                    planning = relevantPlanning(
                        outcome.planning,
                        outcome.currentSubtaskId,
                        outcome.progressCompleted,
                    ),
                    workflowFamily = outcome.workflowFamily,
                    pauseRequested = outcome.pauseRequested,
                    currentModel = outcome.currentModel,
                    currentPhaseExecution = outcome.currentPhaseExecution,
                    problemSummary = problemSummaryWith(null, outcome.diagnostic),
                    activeDurationMs = outcome.activeDurationMs,
                    activeDurationAsOf = outcome.activeDurationAsOf,
                    subtaskActiveDurationMs = outcome.subtaskActiveDurationMs,
                    subtaskActiveDurationAsOf = outcome.subtaskActiveDurationAsOf,
                    lastAgentActivityAt = outcome.lastAgentActivityAt,
                    lastAgentActivityLabel = outcome.lastAgentActivityLabel,
                    executionScope = outcome.execution?.executionScope,
                    phaseId = outcome.execution?.phaseId,
                    currentActivity = outcome.execution?.currentActivity,
                )

            is SkillBillStatusOutcome.Paused ->
                SkillBillStatusUiState.Paused(
                    headline = pausedHeadline(outcome),
                    detail = outcome.summary,
                    goalElapsed = activeElapsed(
                        outcome.activeDurationMs,
                        outcome.activeDurationAsOf,
                        outcome.startedAt,
                        settledAt(outcome.updatedAt, now),
                    ),
                    subtaskElapsed = subtaskElapsed(
                        outcome.subtaskActiveDurationMs,
                        outcome.subtaskActiveDurationAsOf,
                        outcome.subtaskStartedAt,
                        outcome.activeDurationMs,
                        outcome.activeDurationAsOf,
                        outcome.startedAt,
                        settledAt(outcome.updatedAt, now),
                    ),
                    progressCompleted = outcome.progressCompleted,
                    progressTotal = outcome.progressTotal,
                    issueKey = outcome.issueKey,
                    workflowId = outcome.workflowId,
                    stepLabel = outcome.currentStepLabel,
                    startedAt = outcome.startedAt,
                    subtaskStartedAt = outcome.subtaskStartedAt,
                    lastUpdated = outcome.updatedAt,
                    planning = relevantPlanning(
                        outcome.planning,
                        outcome.currentSubtaskId,
                        outcome.progressCompleted,
                    ),
                    workflowFamily = outcome.workflowFamily,
                    pauseRequested = outcome.pauseRequested,
                    currentModel = outcome.currentModel,
                    currentPhaseExecution = outcome.currentPhaseExecution,
                    pauseReason = outcome.pauseReason,
                    problemSummary = problemSummaryWith(null, outcome.diagnostic),
                    lastAgentActivityAt = outcome.lastAgentActivityAt,
                    lastAgentActivityLabel = outcome.lastAgentActivityLabel,
                    executionScope = outcome.execution?.executionScope,
                    phaseId = outcome.execution?.phaseId,
                    currentActivity = outcome.execution?.currentActivity,
                )

            is SkillBillStatusOutcome.Stale ->
                SkillBillStatusUiState.Stale(
                    headline = staleHeadline(outcome),
                    detail = outcome.summary,
                    goalElapsed = activeElapsed(
                        outcome.activeDurationMs,
                        outcome.activeDurationAsOf,
                        outcome.startedAt,
                        settledAt(outcome.updatedAt, now),
                    ),
                    subtaskElapsed = subtaskElapsed(
                        outcome.subtaskActiveDurationMs,
                        outcome.subtaskActiveDurationAsOf,
                        outcome.subtaskStartedAt,
                        outcome.activeDurationMs,
                        outcome.activeDurationAsOf,
                        outcome.startedAt,
                        settledAt(outcome.updatedAt, now),
                    ),
                    progressCompleted = outcome.progressCompleted,
                    progressTotal = outcome.progressTotal,
                    issueKey = outcome.issueKey,
                    workflowId = null,
                    stepLabel = outcome.currentStepLabel,
                    startedAt = outcome.startedAt,
                    subtaskStartedAt = outcome.subtaskStartedAt,
                    lastUpdated = outcome.updatedAt ?: outcome.observedAt,
                    problemSummary = problemSummaryWith(
                        if (outcome.fromCache) "Cached status is stale" else "Status is stale",
                        outcome.diagnostic,
                    ),
                    planning = relevantPlanning(
                        outcome.planning,
                        outcome.currentSubtaskId,
                        outcome.progressCompleted,
                    ),




                    currentModel = outcome.currentModel,
                    currentPhaseExecution = outcome.currentPhaseExecution,
                    lastAgentActivityAt = outcome.lastAgentActivityAt,
                    lastAgentActivityLabel = outcome.lastAgentActivityLabel,
                    executionScope = outcome.execution?.executionScope,
                    phaseId = outcome.execution?.phaseId,
                    currentActivity = outcome.execution?.currentActivity,
                )

            is SkillBillStatusOutcome.Blocked ->
                SkillBillStatusUiState.Blocked(
                    headline = "Skill Bill: blocked",
                    detail = outcome.summary,
                    goalElapsed = activeElapsed(
                        outcome.activeDurationMs,
                        outcome.activeDurationAsOf,
                        outcome.startedAt,
                        settledAt(outcome.updatedAt, now),
                    ),
                    subtaskElapsed = subtaskElapsed(
                        outcome.subtaskActiveDurationMs,
                        outcome.subtaskActiveDurationAsOf,
                        outcome.subtaskStartedAt,
                        outcome.activeDurationMs,
                        outcome.activeDurationAsOf,
                        outcome.startedAt,
                        settledAt(outcome.updatedAt, now),
                    ),
                    issueKey = outcome.issueKey,
                    workflowId = null,
                    stepLabel = outcome.currentStepLabel,
                    startedAt = outcome.startedAt,
                    subtaskStartedAt = outcome.subtaskStartedAt,
                    lastUpdated = outcome.updatedAt ?: outcome.observedAt,
                    problemSummary = problemSummaryWith(outcome.summary, outcome.diagnostic),
                    stale = outcome.stale,
                    currentModel = outcome.currentModel,
                    currentPhaseExecution = outcome.currentPhaseExecution,
                    pauseReason = outcome.pauseReason,
                    executionScope = outcome.execution?.executionScope,
                    phaseId = outcome.execution?.phaseId,
                    currentActivity = outcome.execution?.currentActivity,
                )

            is SkillBillStatusOutcome.Failed ->
                SkillBillStatusUiState.Failed(
                    headline = "Skill Bill: failed",
                    detail = outcome.summary,
                    goalElapsed = activeElapsed(
                        outcome.activeDurationMs,
                        outcome.activeDurationAsOf,
                        outcome.startedAt,
                        settledAt(outcome.updatedAt, now),
                    ),
                    subtaskElapsed = subtaskElapsed(
                        outcome.subtaskActiveDurationMs,
                        outcome.subtaskActiveDurationAsOf,
                        outcome.subtaskStartedAt,
                        outcome.activeDurationMs,
                        outcome.activeDurationAsOf,
                        outcome.startedAt,
                        settledAt(outcome.updatedAt, now),
                    ),
                    issueKey = outcome.issueKey,
                    workflowId = null,
                    stepLabel = outcome.currentStepLabel,
                    startedAt = outcome.startedAt,
                    subtaskStartedAt = outcome.subtaskStartedAt,
                    lastUpdated = outcome.updatedAt ?: outcome.observedAt,
                    problemSummary = problemSummaryWith(outcome.summary, outcome.diagnostic),
                    stale = outcome.stale,
                    currentModel = outcome.currentModel,
                    currentPhaseExecution = outcome.currentPhaseExecution,
                    executionScope = outcome.execution?.executionScope,
                    phaseId = outcome.execution?.phaseId,
                    currentActivity = outcome.execution?.currentActivity,
                )

            is SkillBillStatusOutcome.Unavailable ->
                SkillBillStatusUiState.Unavailable(
                    headline = "Skill Bill: unavailable",
                    detail = outcome.summary,
                    reasonCode = outcome.reasonCode.name,
                    lastUpdated = outcome.observedAt,
                    problemSummary = "${outcome.reasonCode.name}: ${outcome.summary}",
                )

            is SkillBillStatusOutcome.Incompatible ->
                SkillBillStatusUiState.Incompatible(
                    headline = "Skill Bill: incompatible",
                    detail = outcome.summary,
                    foundContractVersion = outcome.foundContractVersion,
                    lastUpdated = outcome.observedAt,
                    problemSummary = buildString {
                        append("Contract mismatch")
                        outcome.foundContractVersion?.let { append(" (found ").append(it).append(')') }
                        append(": ").append(outcome.summary)
                    },
                )
        }

    private fun problemSummaryWith(base: String?, diagnostic: StatusDiagnostic?): String? {
        val note = pollFailureNote(diagnostic) ?: return base
        val kept = base?.takeIf { it.isNotBlank() } ?: return note
        return "$kept $note"
    }

    private fun pollFailureNote(diagnostic: StatusDiagnostic?): String? {
        if (diagnostic?.reasonCode != POLL_FAILED_REASON_CODE) return null
        return when {
            diagnostic.timedOut -> POLL_TIMEOUT_NOTE
            diagnostic.cancelled -> POLL_CANCELLED_NOTE
            else -> POLL_FAILED_NOTE
        }
    }

    
    fun settledAt(updatedAt: Instant?, now: Instant): Instant = updatedAt ?: now

    
    fun elapsed(startedAt: Instant?, now: Instant): Duration? {
        if (startedAt == null) return null
        val millis = now.toEpochMilli() - startedAt.toEpochMilli()
        return if (millis <= 0L) Duration.ZERO else Duration.ofMillis(millis)
    }

    
    fun activeElapsed(
        accumulatedMs: Long?,
        asOf: Instant?,
        startedAt: Instant?,
        now: Instant,
    ): Duration? {
        if (accumulatedMs == null) return elapsed(startedAt, now)
        val tailMillis = asOf?.let { (now.toEpochMilli() - it.toEpochMilli()).coerceAtLeast(0L) } ?: 0L
        return Duration.ofMillis(accumulatedMs + tailMillis)
    }

    fun subtaskElapsed(
        subtaskAccumulatedMs: Long?,
        subtaskAsOf: Instant?,
        subtaskStartedAt: Instant?,
        goalAccumulatedMs: Long?,
        goalAsOf: Instant?,
        goalStartedAt: Instant?,
        now: Instant,
    ): Duration? {
        val goalElapsed = activeElapsed(goalAccumulatedMs, goalAsOf, goalStartedAt, now)
        val raw = if (subtaskAccumulatedMs != null) {
            activeElapsed(subtaskAccumulatedMs, subtaskAsOf, subtaskStartedAt, now)
        } else {
            elapsed(subtaskStartedAt, now)
        } ?: return null
        return goalElapsed?.let { goal -> if (raw > goal) goal else raw } ?: raw
    }

    
    fun withElapsed(state: SkillBillStatusUiState, now: Instant): SkillBillStatusUiState =
        when (state) {
            is SkillBillStatusUiState.Active -> state.copy(
                goalElapsed = activeElapsed(
                    state.activeDurationMs,
                    state.activeDurationAsOf,
                    state.startedAt,
                    now,
                ),
                subtaskElapsed = subtaskElapsed(
                    state.subtaskActiveDurationMs,
                    state.subtaskActiveDurationAsOf,
                    state.subtaskStartedAt,
                    state.activeDurationMs,
                    state.activeDurationAsOf,
                    state.startedAt,
                    now,
                ),
            )
            else -> state
        }

    
    private fun relevantPlanning(
        planning: GoalPlanningInfo?,
        currentSubtaskId: String?,
        progressCompleted: Int?,
    ): GoalPlanningInfo? {
        if (planning == null || planning.state == "prepared") return null
        if (!currentSubtaskId.isNullOrBlank() || (progressCompleted ?: 0) > 0) return null
        return planning
    }

    private fun doneHeadline(outcome: SkillBillStatusOutcome.Done): String {
        val key = outcome.issueKey?.let { "$it · " }.orEmpty()
        return "Skill Bill: ${key}done"
    }

    private fun activeHeadline(outcome: SkillBillStatusOutcome.Active): String {
        val key = outcome.issueKey?.let { "$it · " }.orEmpty()
        return "Skill Bill: $key${outcome.currentStepLabel}"
    }

    private fun pausedHeadline(outcome: SkillBillStatusOutcome.Paused): String {
        val key = outcome.issueKey?.let { "$it · " }.orEmpty()
        return "Skill Bill: ${key}paused"
    }

    private fun staleHeadline(outcome: SkillBillStatusOutcome.Stale): String {
        val label = outcome.currentStepLabel
        return if (label.isNullOrBlank()) {
            "Skill Bill: stale"
        } else {
            "Skill Bill: $label (stale)"
        }
    }
}
