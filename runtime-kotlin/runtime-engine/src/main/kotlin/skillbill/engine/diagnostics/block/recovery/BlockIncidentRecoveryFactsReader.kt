package skillbill.engine.diagnostics.block.recovery

import skillbill.engine.diagnostics.block.BlockIncidentIdentity
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeAttemptBudgets
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.goalreview.GoalSubtaskOperatorDecision
import skillbill.workflow.model.goalreview.GoalSubtaskReviewDisposition
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.artifact.decodeNoChangePauseFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeRunInvariantsFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeValidationGateProgressFromArtifact
import skillbill.workflow.taskruntime.artifact.isPreContractAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.artifact.toAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeNoChangePause
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentPrerequisiteKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryFacts
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRepairWindowPhase
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome

/**
 * Reads recovery policy facts from typed runtime owners inside the admission transaction.
 * Returns [BlockIncidentRecoveryFactsRead.Unavailable] when an owner record is missing, malformed, or holds only
 * uninterpreted text, so the caller refuses instead of assuming a permissive value.
 */
internal object BlockIncidentRecoveryFactsReader {
  fun read(
    unit: UnitOfWork,
    incident: BlockIncident,
    target: BlockIncidentRecoveryTarget,
    confirmedPrerequisites: Set<BlockIncidentPrerequisiteKind>,
  ): BlockIncidentRecoveryFactsRead {
    val workflow = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, target.mutationWorkflowId)
      ?: return BlockIncidentRecoveryFactsRead.Unavailable("the target workflow is missing")
    val step = workflow.steps.firstOrNull { it.stepId == target.phaseId }
      ?: return BlockIncidentRecoveryFactsRead.Unavailable("the blocked phase step is missing")
    val generation = unit.workflowStates.getLatestFeatureTaskRuntimeWorkerGeneration(target.mutationWorkflowId)
      ?.takeIf { it.phaseId == target.phaseId && it.phaseAttempt == step.attemptCount }
      ?: return BlockIncidentRecoveryFactsRead.Unavailable("no worker generation matches the blocked phase attempt")
    val artifacts = workflow.artifacts
    val owners = runCatching { TypedOwners.decode(artifacts) }.getOrElse {
      return BlockIncidentRecoveryFactsRead.Unavailable("a typed policy owner record is malformed")
    }
    if (owners.mandates.isNotEmpty()) {
      // Mandates persist only as authored text. Nothing typed says whether they allow an automatic retry,
      // so their authority is unknown and recovery refuses rather than reading permission into the text.
      return BlockIncidentRecoveryFactsRead.Unavailable(
        "the run carries ${owners.mandates.size} text-only mandate(s) with unknown retry authority",
      )
    }
    if (owners.repairStatePreContract) {
      return BlockIncidentRecoveryFactsRead.Unavailable("the acceptance repair state predates its typed contract")
    }
    val failureCode = incident.occurrence.failureCode
    val identityFailureCode = runCatching { BlockIncidentIdentity.fromArtifacts(artifacts)?.failureCode }.getOrElse {
      return BlockIncidentRecoveryFactsRead.Unavailable("the accepted block identity record is malformed")
    }
    val noChangeDecision = owners.noChangePause?.operatorDecision
    val operatorDecisionForbidsRetry =
      failureCode in POLICY_FAILURE_CODES ||
        owners.noChangePause != null && noChangeDecision != NO_CHANGE_RETRY_DECISION ||
        owners.reviewState?.forbidsRetry() == true ||
        incident.occurrence.parentWorkflowId?.let { parentWorkflowId ->
          val control = unit.goalRunnerControls.controlState(parentWorkflowId)
          control.paused || control.pauseRequested && !control.pauseConsumed
        } == true
    val budgetBlocked =
      failureCode == AcceptanceCriterionRepairStage.BUDGET_BLOCKED.wireValue ||
        failureCode in FeatureTaskRuntimeAttemptBudgets.ATTEMPT_LIMIT_FAILURE_CODES ||
        identityFailureCode in FeatureTaskRuntimeAttemptBudgets.ATTEMPT_LIMIT_FAILURE_CODES ||
        owners.repairState?.stage == AcceptanceCriterionRepairStage.BUDGET_BLOCKED ||
        owners.repairState?.exhaustedUnresolvedCriterion() == true ||
        owners.reviewState?.disposition == GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED
    val gateFailureCausedBlock =
      failureCode == QUALITY_GATE_FAILURE_CODE ||
        owners.gateProgress.any { it.leftGateFailing() } ||
        owners.repairState?.leftAcceptanceUnsatisfied() == true
    return BlockIncidentRecoveryFactsRead.Read(
      BlockIncidentRecoveryFacts(
        workflowStatus = workflow.workflowStatus,
        currentAttempt = step.attemptCount,
        currentGeneration = generation.generation,
        attemptLimitRemaining = !budgetBlocked,
        operatorDecisionForbidsRetry = operatorDecisionForbidsRetry,
        gateFailureCausedBlock = gateFailureCausedBlock,
        confirmedPrerequisites = confirmedPrerequisites,
        targetWorkflowId = target.launchWorkflowId,
        targetInvocationId = target.invocationId,
        phaseId = target.phaseId,
      ),
    )
  }

  private class TypedOwners(
    val mandates: List<String>,
    val noChangePause: FeatureTaskRuntimeNoChangePause?,
    val repairState: AcceptanceCriterionRepairState?,
    val repairStatePreContract: Boolean,
    val reviewState: GoalSubtaskReviewState?,
    val gateProgress: List<FeatureTaskRuntimeValidationGateProgress>,
  ) {
    companion object {
      fun decode(artifacts: Map<String, Any?>): TypedOwners {
        val repairFamily = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR
        val repairWire = repairFamily.value(artifacts)?.let(FeatureTaskRuntimeWorkflowArtifactMap::from)
        val preContract = repairWire?.isPreContractAcceptanceCriterionRepairState() == true
        return TypedOwners(
          mandates = decodeRunInvariantsFromArtifact(
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RUN_INVARIANTS.value(artifacts),
          )?.mandatesAndOverrides.orEmpty().filter(String::isNotBlank),
          noChangePause = decodeNoChangePauseFromArtifact(
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_NO_CHANGE_PAUSE.value(artifacts),
          ),
          repairState = repairWire
            ?.takeUnless { preContract }
            ?.toAcceptanceCriterionRepairState(repairFamily.label()),
          repairStatePreContract = preContract,
          reviewState = GoalSubtaskReviewArtifactDecoder.decodeReviewStateOnly(artifacts),
          gateProgress = listOf(
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_BUILD_GATE_PROGRESS,
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS,
          ).mapNotNull { family -> decodeValidationGateProgressFromArtifact(family.value(artifacts)) },
        )
      }
    }
  }

  private const val NO_CHANGE_RETRY_DECISION = "retry_fix"

  /** An operator decision other than retry_fix, or a review pause the operator has not answered, forbids retry. */
  private fun GoalSubtaskReviewState.forbidsRetry(): Boolean =
    operatorDecision?.let { it != GoalSubtaskOperatorDecision.RETRY_FIX }
      ?: (disposition == GoalSubtaskReviewDisposition.PAUSED)

  /** The gate's latest run did not pass, or its findings repair window is still open. */
  private fun FeatureTaskRuntimeValidationGateProgress.leftGateFailing(): Boolean =
    repairWindowPhase == FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN ||
      gateRuns.lastOrNull()?.let { it.outcome != ValidationGateRunOutcome.PASSED } == true

  /**
   * The acceptance audit still owes a verdict: a targeted verification or read-only audit is pending, or the
   * latest repair outcome failed verification or blocked. A retry would skip that verdict, so it counts as a gate.
   */
  private fun AcceptanceCriterionRepairState.leftAcceptanceUnsatisfied(): Boolean =
    stage in PENDING_VERDICT_STAGES ||
      pendingAuditRefs.isNotEmpty() ||
      outcomes.lastOrNull()?.kind in UNSATISFIED_OUTCOME_KINDS

  /** Mirrors the repair state's own budget rule: an unresolved criterion spent its unsuccessful outcomes. */
  private fun AcceptanceCriterionRepairState.exhaustedUnresolvedCriterion(): Boolean =
    unresolvedQueue.any {
      unsuccessfulTotals.getOrDefault(it, 0) >= AcceptanceCriterionRepairState.MAX_UNSUCCESSFUL_REPAIR_OUTCOMES
    }

  private val PENDING_VERDICT_STAGES = setOf(
    AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED,
    AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION,
  )

  private val UNSATISFIED_OUTCOME_KINDS = setOf(
    AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE,
    AcceptanceCriterionRepairOutcomeKind.BLOCKED,
  )

  private val QUALITY_GATE_FAILURE_CODE = FeatureTaskRuntimeFailureCode.PROJECT_QUALITY_GATE_FAILED.classifierLabel()

  private val POLICY_FAILURE_CODES =
    setOf(GoalRunnerStopReason.POLICY_BLOCKED.name, GoalRunnerStopReason.DEPENDENCIES_BLOCKED.name)
}

internal sealed interface BlockIncidentRecoveryFactsRead {
  data class Read(val facts: BlockIncidentRecoveryFacts) : BlockIncidentRecoveryFactsRead

  /** [reason] names the missing or unknown owner for the refusal diagnostic. */
  data class Unavailable(val reason: String) : BlockIncidentRecoveryFactsRead
}

/** Matches the failure-code label format the block classifier stores on the occurrence. */
private fun Enum<*>.classifierLabel(): String = "${declaringJavaClass.simpleName}.$name"
