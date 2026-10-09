package dev.skillbill.intellij.domain

import java.time.Instant


sealed class SkillBillStatusOutcome {
    abstract val observedAt: Instant
    abstract val diagnostic: StatusDiagnostic?

    data class Idle(
        override val observedAt: Instant,
        val summary: String,
        val repositoryIdentity: String? = null,
        
        val stale: Boolean = false,
        override val diagnostic: StatusDiagnostic? = null,
    ) : SkillBillStatusOutcome()

    
    data class Done(
        override val observedAt: Instant,
        val summary: String,
        val repositoryIdentity: String?,
        val issueKey: String?,
        val progressCompleted: Int?,
        val progressTotal: Int?,
        val startedAt: Instant?,
        val updatedAt: Instant?,
        
        val stale: Boolean = false,
        override val diagnostic: StatusDiagnostic? = null,
        
        val activeDurationMs: Long? = null,
        val activeDurationAsOf: Instant? = null,
        val subtaskActiveDurationMs: Long? = null,
        val subtaskActiveDurationAsOf: Instant? = null,
        val execution: StatusExecutionMetadata? = null,
    ) : SkillBillStatusOutcome()

    data class Active(
        override val observedAt: Instant,
        val summary: String,
        val repositoryIdentity: String,
        val issueKey: String?,
        val workflowId: String?,
        val workflowFamily: String?,
        val currentStepId: String,
        val currentStepLabel: String,
        val progressCompleted: Int?,
        val progressTotal: Int?,
        
        val startedAt: Instant?,
        val currentSubtaskId: String?,
        
        val subtaskStartedAt: Instant?,
        val updatedAt: Instant,
        override val diagnostic: StatusDiagnostic? = null,
        val planning: GoalPlanningInfo? = null,
        
        val pauseRequested: Boolean? = null,
        
        val pausedAt: Instant? = null,
        
        val activeDurationMs: Long? = null,
        val activeDurationAsOf: Instant? = null,
        val subtaskActiveDurationMs: Long? = null,
        val subtaskActiveDurationAsOf: Instant? = null,
        
        val currentModel: CurrentPhaseModel? = null,
        
        val currentPhaseExecution: CurrentPhaseExecution? = null,
        val lastAgentActivityAt: Instant? = null,
        val lastAgentActivityLabel: String? = null,
        val execution: StatusExecutionMetadata? = null,
    ) : SkillBillStatusOutcome()

    
    data class Paused(
        override val observedAt: Instant,
        val summary: String,
        val repositoryIdentity: String,
        val issueKey: String?,
        val workflowId: String?,
        val workflowFamily: String?,
        val currentStepId: String,
        val currentStepLabel: String,
        val progressCompleted: Int?,
        val progressTotal: Int?,
        val startedAt: Instant?,
        val currentSubtaskId: String?,
        val subtaskStartedAt: Instant?,
        val updatedAt: Instant,
        override val diagnostic: StatusDiagnostic? = null,
        val planning: GoalPlanningInfo? = null,
        
        val pauseRequested: Boolean? = null,
        
        val pausedAt: Instant? = null,
        
        val activeDurationMs: Long? = null,
        val activeDurationAsOf: Instant? = null,
        val subtaskActiveDurationMs: Long? = null,
        val subtaskActiveDurationAsOf: Instant? = null,
        val currentModel: CurrentPhaseModel? = null,
        val currentPhaseExecution: CurrentPhaseExecution? = null,
        val pauseReason: PauseReason? = null,
        val lastAgentActivityAt: Instant? = null,
        val lastAgentActivityLabel: String? = null,
        val execution: StatusExecutionMetadata? = null,
    ) : SkillBillStatusOutcome()

    data class Stale(
        override val observedAt: Instant,
        val summary: String,
        val repositoryIdentity: String?,
        val issueKey: String?,
        val currentStepId: String?,
        val currentStepLabel: String?,
        val progressCompleted: Int?,
        val progressTotal: Int?,
        val startedAt: Instant?,
        val currentSubtaskId: String?,
        val subtaskStartedAt: Instant?,
        val updatedAt: Instant?,
        val fromCache: Boolean = false,
        override val diagnostic: StatusDiagnostic? = null,
        val planning: GoalPlanningInfo? = null,
        
        val activeDurationMs: Long? = null,
        val activeDurationAsOf: Instant? = null,
        val subtaskActiveDurationMs: Long? = null,
        val subtaskActiveDurationAsOf: Instant? = null,
        val currentModel: CurrentPhaseModel? = null,
        val currentPhaseExecution: CurrentPhaseExecution? = null,
        val lastAgentActivityAt: Instant? = null,
        val lastAgentActivityLabel: String? = null,
        val execution: StatusExecutionMetadata? = null,
    ) : SkillBillStatusOutcome()

    data class Blocked(
        override val observedAt: Instant,
        val summary: String,
        val repositoryIdentity: String?,
        val issueKey: String?,
        val currentStepId: String?,
        val currentStepLabel: String?,
        val startedAt: Instant?,
        val currentSubtaskId: String?,
        val subtaskStartedAt: Instant?,
        val updatedAt: Instant?,
        
        val stale: Boolean = false,
        override val diagnostic: StatusDiagnostic? = null,
        
        val activeDurationMs: Long? = null,
        val activeDurationAsOf: Instant? = null,
        val subtaskActiveDurationMs: Long? = null,
        val subtaskActiveDurationAsOf: Instant? = null,
        val currentModel: CurrentPhaseModel? = null,
        val currentPhaseExecution: CurrentPhaseExecution? = null,
        val pauseReason: PauseReason? = null,
        val execution: StatusExecutionMetadata? = null,
    ) : SkillBillStatusOutcome()

    data class Failed(
        override val observedAt: Instant,
        val summary: String,
        val repositoryIdentity: String?,
        val issueKey: String?,
        val currentStepId: String?,
        val currentStepLabel: String?,
        val startedAt: Instant?,
        val currentSubtaskId: String?,
        val subtaskStartedAt: Instant?,
        val updatedAt: Instant?,
        
        val stale: Boolean = false,
        override val diagnostic: StatusDiagnostic? = null,
        
        val activeDurationMs: Long? = null,
        val activeDurationAsOf: Instant? = null,
        val subtaskActiveDurationMs: Long? = null,
        val subtaskActiveDurationAsOf: Instant? = null,
        val currentModel: CurrentPhaseModel? = null,
        val currentPhaseExecution: CurrentPhaseExecution? = null,
        val execution: StatusExecutionMetadata? = null,
    ) : SkillBillStatusOutcome()

    data class Unavailable(
        override val observedAt: Instant,
        val summary: String,
        val reasonCode: UnavailableReason,
        override val diagnostic: StatusDiagnostic? = null,
    ) : SkillBillStatusOutcome()

    data class Incompatible(
        override val observedAt: Instant,
        val summary: String,
        val foundContractVersion: String?,
        val expectedContractVersion: String = IDE_STATUS_CONTRACT_VERSION,
        override val diagnostic: StatusDiagnostic? = null,
    ) : SkillBillStatusOutcome()
}


fun SkillBillStatusOutcome.isUncorroboratedIdle(): Boolean =
    this is SkillBillStatusOutcome.Idle && diagnostic?.reasonCode == NO_MATCHING_WORK_REASON_CODE


fun SkillBillStatusOutcome.isLiveOutcome(): Boolean = when (this) {
    is SkillBillStatusOutcome.Active,
    is SkillBillStatusOutcome.Paused,
    is SkillBillStatusOutcome.Blocked,
    is SkillBillStatusOutcome.Failed,
    is SkillBillStatusOutcome.Stale,
    -> true

    is SkillBillStatusOutcome.Idle,
    is SkillBillStatusOutcome.Done,
    is SkillBillStatusOutcome.Unavailable,
    is SkillBillStatusOutcome.Incompatible,
    -> false
}

fun UnavailableReason.isPollTransportFailure(): Boolean = when (this) {
    UnavailableReason.TIMEOUT,
    UnavailableReason.CANCELLED,
    UnavailableReason.PROCESS_FAILURE,
    -> true

    UnavailableReason.MISSING_EXECUTABLE,
    UnavailableReason.MISCONFIGURED,
    UnavailableReason.MISSING_REPOSITORY,
    UnavailableReason.ABSENT_DATABASE,
    UnavailableReason.NO_MATCHING_WORK,
    UnavailableReason.INVALID_REPOSITORY_INPUT,
    UnavailableReason.MALFORMED_OUTPUT,
    -> false
}

fun SkillBillStatusOutcome.withPollFailure(reason: UnavailableReason): SkillBillStatusOutcome {
    val marker = StatusDiagnostic(
        timedOut = reason == UnavailableReason.TIMEOUT,
        cancelled = reason == UnavailableReason.CANCELLED,
        reasonCode = POLL_FAILED_REASON_CODE,
    )
    return when (this) {
        is SkillBillStatusOutcome.Active -> copy(diagnostic = marker)
        is SkillBillStatusOutcome.Paused -> copy(diagnostic = marker)
        is SkillBillStatusOutcome.Blocked -> SkillBillStatusOutcome.Stale(
            observedAt = observedAt,
            summary = summary,
            repositoryIdentity = repositoryIdentity,
            issueKey = issueKey,
            currentStepId = currentStepId,
            currentStepLabel = currentStepLabel,
            progressCompleted = null,
            progressTotal = null,
            startedAt = startedAt,
            currentSubtaskId = currentSubtaskId,
            subtaskStartedAt = subtaskStartedAt,
            updatedAt = updatedAt,
            diagnostic = marker,
            activeDurationMs = activeDurationMs,
            activeDurationAsOf = activeDurationAsOf,
            subtaskActiveDurationMs = subtaskActiveDurationMs,
            subtaskActiveDurationAsOf = subtaskActiveDurationAsOf,
            currentModel = currentModel,
            currentPhaseExecution = currentPhaseExecution,
            execution = execution,
        )
        is SkillBillStatusOutcome.Failed -> SkillBillStatusOutcome.Stale(
            observedAt = observedAt,
            summary = summary,
            repositoryIdentity = repositoryIdentity,
            issueKey = issueKey,
            currentStepId = currentStepId,
            currentStepLabel = currentStepLabel,
            progressCompleted = null,
            progressTotal = null,
            startedAt = startedAt,
            currentSubtaskId = currentSubtaskId,
            subtaskStartedAt = subtaskStartedAt,
            updatedAt = updatedAt,
            diagnostic = marker,
            activeDurationMs = activeDurationMs,
            activeDurationAsOf = activeDurationAsOf,
            subtaskActiveDurationMs = subtaskActiveDurationMs,
            subtaskActiveDurationAsOf = subtaskActiveDurationAsOf,
            currentModel = currentModel,
            currentPhaseExecution = currentPhaseExecution,
            execution = execution,
        )
        is SkillBillStatusOutcome.Stale -> copy(diagnostic = marker)

        is SkillBillStatusOutcome.Idle,
        is SkillBillStatusOutcome.Done,
        is SkillBillStatusOutcome.Unavailable,
        is SkillBillStatusOutcome.Incompatible,
        -> this
    }
}


data class GoalPlanningInfo(
    val state: String,
    val sharedPreplanPrepared: Boolean,
    val plannedSubtaskCount: Int,
    val totalSubtaskCount: Int,
    val currentPlanningSubtaskId: String? = null,
    val reason: String? = null,
)


data class CurrentPhaseModel(
    val model: String,
    val effort: String? = null,
    
    val phaseId: String? = null,
)


data class CurrentPhaseExecution(
    val phaseId: String,
    val kind: String,
    val count: Int,
    val total: Int? = null,
)

data class PauseReason(
    val code: String,
    val label: String? = null,
) {
    val awaitsOperatorDecision: Boolean
        get() = code == PAUSE_REASON_AWAITING_OPERATOR_DECISION
}

enum class UnavailableReason {
    MISSING_EXECUTABLE,
    MISCONFIGURED,
    MISSING_REPOSITORY,
    ABSENT_DATABASE,
    
    NO_MATCHING_WORK,
    INVALID_REPOSITORY_INPUT,
    PROCESS_FAILURE,
    TIMEOUT,
    CANCELLED,
    MALFORMED_OUTPUT,
}


data class StatusDiagnostic(
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val contractVersionMismatch: Boolean = false,
    val foundContractVersion: String? = null,
    val reasonCode: String? = null,
)
