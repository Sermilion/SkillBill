package skillbill.goalrunner.model

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.idestatus.model.WorktreeEditSummary
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalobservability.GoalObservabilityDiffStat
import skillbill.workflow.model.goalobservability.GoalObservabilityEvent
import skillbill.workflow.model.goalobservability.GoalObservabilitySelectedDiffHunks
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationEvidence
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateExecutionEvidence

enum class GoalPlanningStatusState(val wireValue: String) {
  NOT_STARTED("not_started"),
  PREPLANNED("preplanned"),
  PARTIALLY_PLANNED("partially_planned"),
  BLOCKED("blocked"),
  PREPARED("prepared"),
  ;

  companion object {
    fun fromWire(value: String?): GoalPlanningStatusState? =
      value?.trim()?.let { candidate -> entries.firstOrNull { it.wireValue == candidate } }
  }
}

object GoalPlanningStatusReasons {
  const val RESUME_MARKER: String = "planning can resume"

  const val NOT_STARTED: String = "Goal planning has not started."

  fun preplannedResume(firstMissingSubtaskId: Int): String =
    "Shared preplan is saved; planning can resume at subtask $firstMissingSubtaskId."

  fun partiallyPlannedResume(firstMissingSubtaskId: Int): String =
    "Saved plans will be reused; planning can resume at subtask $firstMissingSubtaskId."

  fun claimsResume(reason: String?): Boolean = reason?.contains(RESUME_MARKER) == true
}

enum class ExecutionLiveness(val wireValue: String) {
  LIVE("live"),
  IDLE("idle"),
  UNKNOWN("unknown"),
  ;

  companion object {
    fun fromWire(value: String?): ExecutionLiveness? =
      value?.trim()?.let { candidate -> entries.firstOrNull { it.wireValue == candidate } }
  }
}

data class GoalPlanningStatusSnapshot(
  val state: GoalPlanningStatusState,
  val sharedPreplanPrepared: Boolean,
  val plannedSubtaskCount: Int,
  val totalSubtaskCount: Int,
  val currentPlanningSubtaskId: Int?,
  val planningWaveSubtaskIds: List<Int> = emptyList(),
  val reason: String?,
) {
  init {
    require(planningWaveSubtaskIds.isEmpty() || currentPlanningSubtaskId == planningWaveSubtaskIds.min()) {
      "currentPlanningSubtaskId must equal the lowest planning wave subtask id, " +
        "was $currentPlanningSubtaskId for wave $planningWaveSubtaskIds."
    }
  }
}

data class GoalRunnerStatusProjection(
  val issueKey: String,
  val completeCount: Int,
  val pendingCount: Int,
  val blockedCount: Int,
  val currentSubtaskId: Int?,
  val currentChildWorkflowId: String? = null,
  val currentSubtaskStatus: DecompositionStatus? = null,
  val currentSubtaskBlockedReason: String? = null,
  val currentStep: String?,
  val activeAgent: String?,
  val executionLiveness: ExecutionLiveness = ExecutionLiveness.UNKNOWN,
  val planning: GoalPlanningStatusSnapshot? = null,
  val latestLivenessSignal: String? = null,
  val latestObservabilityEvent: GoalObservabilityEvent? = null,
  val requestedDiffStat: GoalObservabilityDiffStat? = null,
  val selectedDiffHunks: GoalObservabilitySelectedDiffHunks? = null,
  val blockedAttemptCount: Int = 0,
  val supervisorKillCount: Int = 0,
  val phaseAttemptCounts: Map<String, Int> = emptyMap(),
  val cumulativeFixIterations: Map<String, Int> = emptyMap(),
  val reAttemptCauseCounts: Map<String, Int> = emptyMap(),
  val findingsInScope: Int? = null,
  val outOfBandAcceptances: List<GoalRunnerAcceptedSubtask> = emptyList(),
  val completedSubtaskValidation: List<GoalRunnerSubtaskValidationEvidence> = emptyList(),
  val paused: Boolean = false,
  val pauseRequested: Boolean = false,
  val pauseReason: String? = null,
  val pausedAt: String? = null,
  val stopAfterSubtaskId: Int? = null,
  val activeDurationMs: Long = 0,
  val activeDurationAsOf: String? = null,
  val subtaskActiveDurationMs: Long = 0,
  val subtaskActiveDurationAsOf: String? = null,
  val degradedDurableRead: Boolean = false,
  val latestWorktreeEdit: WorktreeEditSummary? = null,
  val auditAcRetryCount: Int? = null,
)

data class GoalRunnerSubtaskValidationEvidence(
  val subtaskId: Int,
  val validationPassed: Boolean? = null,
  val evidence: FeatureTaskRuntimeValidationEvidence? = null,
  val gateExecutionEvidence: FeatureTaskRuntimeValidationGateExecutionEvidence? = null,
  val integrityProblem: String? = null,
) {
  fun toStatusWire(): FeatureTaskRuntimeWorkflowArtifactMap = FeatureTaskRuntimeWorkflowArtifactMap.from(toStatusMap())

  internal fun toStatusMap(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.SUBTASK_ID to subtaskId,
      ValidationEvidencePayloadKeys.VALIDATION_PASSED to validationPassed,
      ValidationEvidencePayloadKeys.VALIDATION_EVIDENCE to
        evidence?.toArtifactMap(),
      ValidationEvidencePayloadKeys.VALIDATION_RESULT to
        gateExecutionEvidence?.let { gate ->
          linkedMapOf(
            ValidationEvidencePayloadKeys.VALIDATION_STATUS to gate.validationStatus,
            ValidationEvidencePayloadKeys.CHECKS to gate.checks,
            ValidationEvidencePayloadKeys.GATE_RUN_COUNT to gate.gateRunCount,
            ValidationEvidencePayloadKeys.GATE_RUNS to gate.gateRuns.map { it.toArtifactMap() },
          )
        },
      ValidationEvidencePayloadKeys.INTEGRITY_PROBLEM to integrityProblem,
    )
}

data class GoalRunnerAcceptedSubtask(
  val subtaskId: Int,
  val commitSha: String,
  val reason: String,
  val acceptedAt: String,
)

data class GoalRunnerStatusProjectionRuntimeInputs(
  val executionLiveness: ExecutionLiveness = ExecutionLiveness.UNKNOWN,
  val planning: GoalPlanningStatusSnapshot? = null,
  val currentStepOverride: String? = null,
  val currentWorkflowStatus: WorkflowStatus? = null,
  val latestLivenessSignal: String? = null,
  val latestObservabilityEvent: GoalObservabilityEvent? = null,
  val requestedDiffStat: GoalObservabilityDiffStat? = null,
  val selectedDiffHunks: GoalObservabilitySelectedDiffHunks? = null,
  val blockedAttemptCount: Int = 0,
  val supervisorKillCount: Int = 0,
  val phaseAttemptCounts: Map<String, Int> = emptyMap(),
  val cumulativeFixIterations: Map<String, Int> = emptyMap(),
  val reAttemptCauseCounts: Map<String, Int> = emptyMap(),
  val findingsInScope: Int? = null,
  val outOfBandAcceptances: List<GoalRunnerAcceptedSubtask> = emptyList(),
  val completedSubtaskValidation: List<GoalRunnerSubtaskValidationEvidence> = emptyList(),
  val paused: Boolean = false,
  val pauseRequested: Boolean = false,
  val pauseReason: String? = null,
  val pausedAt: String? = null,
  val stopAfterSubtaskId: Int? = null,
  val activeDurationMs: Long = 0,
  val activeDurationAsOf: String? = null,
  val subtaskActiveDurationMs: Long = 0,
  val subtaskActiveDurationAsOf: String? = null,
  val degradedDurableRead: Boolean = false,
  val latestWorktreeEdit: WorktreeEditSummary? = null,
  val auditAcRetryCount: Int? = null,
)

object GoalRunnerStatusProjector {
  fun project(
    manifest: DecompositionManifest,
    activeAgent: String? = null,
    extras: GoalRunnerStatusProjectionRuntimeInputs = GoalRunnerStatusProjectionRuntimeInputs(),
  ): GoalRunnerStatusProjection {
    val context = buildGoalRunnerStatusProjectionContext(manifest, extras)
    return assembleGoalRunnerStatusProjection(manifest, activeAgent, extras, context)
  }
}
