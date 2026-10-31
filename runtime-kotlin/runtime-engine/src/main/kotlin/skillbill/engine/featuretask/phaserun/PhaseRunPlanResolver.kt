package skillbill.engine.featuretask.phaserun

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCodec
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCompatibility
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanResolver
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeStepLaunchAssignmentFactory
import skillbill.engine.featuretask.lifecycle.execution.StepLaunchAssignmentInputs
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.runloop.core.strategySelectionFacts
import skillbill.engine.featuretask.runloop.qualitygate.packBuildGateProgressKey
import skillbill.engine.featuretask.slot.PhaseQualityGateOperation
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanAdmissionError
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import kotlin.coroutines.cancellation.CancellationException

@Inject
public class PhaseRunPlanResolver(
  private val strategies: PhaseStrategyLookup,
  private val gitOperations: WorkflowGitOperations,
  private val agentRunLauncher: AgentRunLauncher,
  private val intakeResolver: PhaseRunIntakeResolver,
  private val executionPlanCodec: FeatureTaskRuntimeExecutionPlanCodec,
  private val executionPlanCompatibility: FeatureTaskRuntimeExecutionPlanCompatibility,
  private val executionPlanResolver: FeatureTaskRuntimeExecutionPlanResolver,
) {
  internal fun resolveGoalQualityExecutionPlan(
    request: PhaseRunRequest,
    workflowId: String,
  ): String =
    try {
      val plan = resolveExecutionPlan(request, workflowId)
      val inputs =
        requireNotNull(executionPlanResolver).resolveInputs(
          request.repoRoot,
          plan.qualityGateSelection,
          ValidationDepth.DEFAULT,
          request.timeout,
        )
      requireNotNull(executionPlanCodec).encodeExecution(plan, inputs).decodeToString()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: FeatureTaskRuntimeExecutionPlanAdmissionError) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Could not resolve the goal-quality execution plan for '${request.definitionId}'.",
        failure,
      )
    }

  internal fun validateGoalQualityExecutionPlan(
    encoded: String,
    expectedDefinitionId: String,
    expectedAgentId: String,
  ): ResolvedPhaseExecutionPlan {
    val definition = SkeletonDefinition.byId(expectedDefinitionId)
    try {
      val plan =
        requireNotNull(executionPlanCompatibility)
          .requireSupportedComposition(encoded.toByteArray(Charsets.UTF_8))
      if (plan.definitionId != definition.id || plan.definitionSemanticRevision != definition.semanticRevision) {
        invalidQualityPlan()
      }
      if (strategies.qualityGateSelection(plan) != plan.qualityGateSelection) invalidQualityPlan()
      plan.selectedStrategies.forEach { identity ->
        identity.steps.forEach { step ->
          if (strategies.selectedOwnerOf(step, plan) == null) invalidQualityPlan()
        }
      }
      val requiredAssignments =
        plan.selectedStepIds.filter { step ->
          strategies.selectedOwnerOf(step, plan)?.slot !in setOf(PhaseSlot.INTAKE, PhaseSlot.COMMIT_PUSH)
        }.toSet()
      if (plan.stepLaunchAssignments.keys != requiredAssignments ||
        plan.stepLaunchAssignments.any { (step, assignment) ->
          assignment.stepId != step || assignment.agentId != expectedAgentId
        }
      ) {
        invalidQualityPlan()
      }
      return plan
    } catch (failure: SkillBillRuntimeException) {
      throw failure
    } catch (failure: FeatureTaskRuntimeExecutionPlanAdmissionError) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "The admitted '${definition.id}' execution plan is unsupported or corrupt.",
        failure,
      )
    }
  }

  internal fun validateRetainedRunningPhases(
    progress: GoalQualityPhaseProgress,
    plan: ResolvedPhaseExecutionPlan,
    outcomeNamespace: String,
  ) {
    progress.phaseRecords.values.filter { it.status == WorkflowStepStatus.RUNNING }.forEach { record ->
      val envelopeAccepted =
        progress.acceptedEnvelopes.any {
          it.phaseId == record.phaseId && it.attempt == record.attemptCount
        }
      when (val operation = strategies.qualityGateOperation(plan, record.phaseId)) {
        is PhaseQualityGateOperation.PackGate -> {
          validateRunningGate(progress, plan, outcomeNamespace, record, operation.commandFamily)
        }
        else -> if (!envelopeAccepted) unknownQualityStep(record)
      }
    }
  }

  private fun validateRunningGate(
    progress: GoalQualityPhaseProgress,
    plan: ResolvedPhaseExecutionPlan,
    outcomeNamespace: String,
    record: FeatureTaskRuntimePhaseRecord,
    commandFamily: ValidationGateCommandFamily,
  ) {
    val envelopeAccepted =
      progress.acceptedEnvelopes.any {
        it.phaseId == record.phaseId && it.attempt == record.attemptCount
      }
    val starts = matchingAttemptStarts(progress.phaseLedger, record.phaseId, record.attemptCount)
    val terminalActions = hasTerminalAction(progress.phaseLedger, record, starts)
    if (terminalActions) unknownQualityStep(record)
    val expectedKey =
      packBuildGateProgressKey(
        outcomeNamespace,
        plan.definitionId,
        record.phaseId,
        record.attemptCount,
        commandFamily,
      )
    val (storedKey, nested) =
      when (commandFamily) {
        ValidationGateCommandFamily.BUILD -> progress.buildGateProgressKey to progress.buildGateProgress
        ValidationGateCommandFamily.VALIDATION ->
          progress.validationGateProgressKey to progress.validationGateProgress
      }
    if (storedKey != expectedKey || nested == null) unknownQualityStep(record)
    if (nested.pendingExternalOperation != null || nested.pendingExternalCheckpoint != null) {
      unknownQualityStep(
        record,
      )
    }
    if (!envelopeAccepted && nested.gateRunCount == 0 && nested.terminalOutcome == null) {
      unknownQualityStep(record)
    }
  }

  private fun hasTerminalAction(
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    record: FeatureTaskRuntimePhaseRecord,
    start: FeatureTaskRuntimePhaseLedgerEntry,
  ): Boolean {
    return ledger.any {
      it.phaseId == record.phaseId && it.attemptCount == record.attemptCount &&
        it.sequenceNumber > start.sequenceNumber &&
        it.action in
        setOf(
          FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
          FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
          FeatureTaskRuntimePhaseLedgerAction.PAUSED,
        )
    }
  }

  internal fun validateNestedProgressKeys(progress: GoalQualityPhaseProgress) {
    val invalidBuild =
      (progress.buildGateProgress == null) != (progress.buildGateProgressKey == null) ||
        progress.buildGateProgressKey?.isBlank() == true
    val invalidValidation =
      (progress.validationGateProgress == null) != (progress.validationGateProgressKey == null) ||
        progress.validationGateProgressKey?.isBlank() == true
    if (invalidBuild || invalidValidation) {
      throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Goal-quality nested gate progress is missing its exact progress key.",
      )
    }
  }

  private fun matchingAttemptStarts(
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    phaseId: String,
    attempt: Int,
  ): FeatureTaskRuntimePhaseLedgerEntry {
    val starts =
      ledger.filter {
        it.phaseId == phaseId && it.attemptCount == attempt && it.action == FeatureTaskRuntimePhaseLedgerAction.START
      }
    return starts.singleOrNull() ?: throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      "Goal-quality step '$phaseId#$attempt' has no unique accepted start for nested gate recovery.",
    )
  }

  private fun unknownQualityStep(record: FeatureTaskRuntimePhaseRecord): Nothing =
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      "The outcome of goal-quality step '${record.phaseId}#${record.attemptCount}' is unknown. " +
        "Inspect authoritative process or phase evidence, or explicitly retire " +
        "this quality execution and accept new cycles.",
    )

  internal fun invalidQualityPlan(): Nothing =
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      "The admitted goal-quality execution plan is unsupported or corrupt.",
    )

  internal fun resolveExecutionPlan(
    request: PhaseRunRequest,
    workflowId: String,
    branch: FeatureTaskRuntimeResolvedBranch? = null,
    preparedFacts: InMemoryPhaseRunFacts? = null,
  ): ResolvedPhaseExecutionPlan {
    val definition = SkeletonDefinition.byId(request.definitionId)
    val resolvedBranch = branch ?: currentBranch(request)
    val facts =
      preparedFacts ?: InMemoryPhaseRunFacts(
        request,
        definition,
        intakeResolver.resolve(definition, request, resolvedBranch?.branch),
        workflowId,
        null,
      )
    val selection = strategySelectionFacts(facts)
    return strategies.executionPlan(
      selection.copy(
        stepAssignments =
          FeatureTaskRuntimeStepLaunchAssignmentFactory.resolve(
            launcher = agentRunLauncher,
            lookup = strategies,
            facts = selection,
            inputs =
              StepLaunchAssignmentInputs(
                invokedAgentId = request.invokedAgentId,
                agentAssignment = facts.agentAssignment,
                modelAssignment = facts.modelAssignment,
              ),
          ),
      ),
    )
  }

  internal fun currentBranch(request: PhaseRunRequest): FeatureTaskRuntimeResolvedBranch? =
    (gitOperations.currentBranch(request.repoRoot) as? WorkflowGitOperationResult.Ok)
      ?.value
      ?.trim()
      ?.takeIf { branch -> branch.isNotBlank() && branch != "HEAD" }
      ?.let(::FeatureTaskRuntimeResolvedBranch)
}
