package skillbill.engine.work.recovery

import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION
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
import skillbill.workflow.taskruntime.artifact.isPreviousVersionAcceptanceCriterionRepairState
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

internal object BlockIncidentRecoveryFactsReader {
  fun read(
    unit: UnitOfWork,
    incident: BlockIncident,
    target: BlockIncidentRecoveryTarget,
    confirmedPrerequisites: Set<BlockIncidentPrerequisiteKind>,
  ): BlockIncidentRecoveryFactsRead {
    val workflow =
      unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, target.mutationWorkflowId)
        ?: return BlockIncidentRecoveryFactsRead.Unavailable("the target workflow is missing")
    val step =
      workflow.step(target.phaseId)
        ?: return BlockIncidentRecoveryFactsRead.Unavailable("the blocked phase step is missing")
    val generation =
      unit.workflowStates.getLatestFeatureTaskRuntimeWorkerGeneration(target.mutationWorkflowId)
        ?.takeIf { it.phaseId == target.phaseId && it.phaseAttempt == step.attemptCount }
        ?: return BlockIncidentRecoveryFactsRead.Unavailable("no worker generation matches the blocked phase attempt")
    val base =
      BlockIncidentRecoveryFacts(
        workflowStatus = workflow.workflowStatus,
        currentAttempt = step.attemptCount,
        currentGeneration = generation.generation,
        attemptLimitRemaining = false,
        operatorDecisionForbidsRetry = false,
        gateFailureCausedBlock = false,
        confirmedPrerequisites = confirmedPrerequisites,
        targetWorkflowId = target.launchWorkflowId,
        targetInvocationId = target.invocationId,
        phaseId = target.phaseId,
      )
    return readPolicy(unit, incident, base, workflow.artifacts)
  }

  private fun readPolicy(
    unit: UnitOfWork,
    incident: BlockIncident,
    base: BlockIncidentRecoveryFacts,
    artifacts: Map<String, Any?>,
  ): BlockIncidentRecoveryFactsRead {
    val owners =
      runCatching { TypedOwners.decode(artifacts) }.getOrElse {
        return BlockIncidentRecoveryFactsRead.Unavailable("a typed policy owner record is malformed")
      }
    val unavailable =
      when {
        owners.mandates.isNotEmpty() ->
          "the run carries ${owners.mandates.size} text-only mandate(s) with unknown retry authority"
        owners.repairStatePreContract -> "the acceptance repair state predates its typed contract"
        owners.repairStateSuperseded ->
          "the acceptance repair state predates contract " +
            "$ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION and awaits in-band regeneration"
        else -> null
      }
    if (unavailable != null) return BlockIncidentRecoveryFactsRead.Unavailable(unavailable)
    val identityFailureCode =
      runCatching { BlockIncidentIdentity.fromArtifacts(artifacts)?.failureCode }.getOrElse {
        return BlockIncidentRecoveryFactsRead.Unavailable("the accepted block identity record is malformed")
      }
    val failureCode = incident.occurrence.failureCode
    val parentPaused =
      incident.occurrence.parentWorkflowId?.let { id ->
        val control = unit.goalRunnerControls.controlState(id)
        control.paused || control.pauseRequested && !control.pauseConsumed
      } == true
    return BlockIncidentRecoveryFactsRead.Read(
      base.copy(
        attemptLimitRemaining = !owners.budgetBlocked(failureCode, identityFailureCode),
        operatorDecisionForbidsRetry = owners.forbidsRetry(failureCode) || parentPaused,
        gateFailureCausedBlock = failureCode == QUALITY_GATE_FAILURE_CODE || owners.gateFailed(),
      ),
    )
  }

  private fun TypedOwners.forbidsRetry(failureCode: String?): Boolean =
    failureCode in POLICY_FAILURE_CODES ||
      noChangePause != null && noChangePause.operatorDecision != NO_CHANGE_RETRY_DECISION ||
      reviewState?.forbidsRetry() == true

  private fun TypedOwners.budgetBlocked(
    failureCode: String?,
    identityFailureCode: String?,
  ): Boolean =
    failureCode == AcceptanceCriterionRepairStage.BUDGET_BLOCKED.wireValue ||
      failureCode in FeatureTaskRuntimeAttemptBudgets.ATTEMPT_LIMIT_FAILURE_CODES ||
      identityFailureCode in FeatureTaskRuntimeAttemptBudgets.ATTEMPT_LIMIT_FAILURE_CODES ||
      repairState?.stage == AcceptanceCriterionRepairStage.BUDGET_BLOCKED ||
      repairState?.exhaustedUnresolvedCriterion() == true ||
      reviewState?.disposition == GoalSubtaskReviewDisposition.REVIEW_CAP_REACHED

  private fun TypedOwners.gateFailed(): Boolean =
    gateProgress.any { it.leftGateFailing() } || repairState?.leftAcceptanceUnsatisfied() == true

  private class TypedOwners(
    val mandates: List<String>,
    val noChangePause: FeatureTaskRuntimeNoChangePause?,
    val repairState: AcceptanceCriterionRepairState?,
    val repairStatePreContract: Boolean,
    val repairStateSuperseded: Boolean,
    val reviewState: GoalSubtaskReviewState?,
    val gateProgress: List<FeatureTaskRuntimeValidationGateProgress>,
  ) {
    companion object {
      fun decode(artifacts: Map<String, Any?>): TypedOwners {
        val repairFamily = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR
        val repairWire = repairFamily.value(artifacts)?.let(FeatureTaskRuntimeWorkflowArtifactMap::from)
        val preContract = repairWire?.isPreContractAcceptanceCriterionRepairState() == true
        val superseded = repairWire?.isPreviousVersionAcceptanceCriterionRepairState() == true
        return TypedOwners(
          mandates =
            decodeRunInvariantsFromArtifact(
              DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RUN_INVARIANTS.value(artifacts),
            )?.mandatesAndOverrides.orEmpty().filter(String::isNotBlank),
          noChangePause =
            decodeNoChangePauseFromArtifact(
              DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_NO_CHANGE_PAUSE.value(artifacts),
            ),
          repairState =
            repairWire
              ?.takeUnless { preContract || superseded }
              ?.toAcceptanceCriterionRepairState(repairFamily.label()),
          repairStatePreContract = preContract,
          repairStateSuperseded = superseded,
          reviewState = GoalSubtaskReviewArtifactDecoder.decodeReviewStateOnly(artifacts),
          gateProgress =
            listOf(
              DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_BUILD_GATE_PROGRESS,
              DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_VALIDATION_GATE_PROGRESS,
            ).mapNotNull { family -> decodeValidationGateProgressFromArtifact(family.value(artifacts)) },
        )
      }
    }
  }

  private const val NO_CHANGE_RETRY_DECISION = "retry_fix"

  private fun GoalSubtaskReviewState.forbidsRetry(): Boolean =
    operatorDecision?.let { it != GoalSubtaskOperatorDecision.RETRY_FIX }
      ?: (disposition == GoalSubtaskReviewDisposition.PAUSED)

  private fun FeatureTaskRuntimeValidationGateProgress.leftGateFailing(): Boolean =
    repairWindowPhase == FeatureTaskRuntimeValidationGateRepairWindowPhase.FINDINGS_OPEN ||
      gateRuns.lastOrNull()?.let { it.outcome != ValidationGateRunOutcome.PASSED } == true

  private fun AcceptanceCriterionRepairState.leftAcceptanceUnsatisfied(): Boolean =
    stage in PENDING_VERDICT_STAGES ||
      pendingAuditRefs.isNotEmpty() ||
      outcomes.lastOrNull()?.kind in UNSATISFIED_OUTCOME_KINDS

  private fun AcceptanceCriterionRepairState.exhaustedUnresolvedCriterion(): Boolean =
    unresolvedQueue.any {
      unsuccessfulTotals.getOrDefault(it, 0) >= AcceptanceCriterionRepairState.MAX_UNSUCCESSFUL_REPAIR_OUTCOMES
    }

  private val PENDING_VERDICT_STAGES =
    setOf(
      AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED,
      AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION,
      AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED,
    )

  private val UNSATISFIED_OUTCOME_KINDS =
    setOf(
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

private fun Enum<*>.classifierLabel(): String = "${declaringJavaClass.simpleName}.$name"
