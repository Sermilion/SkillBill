package skillbill.engine.goalrunner.qa

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.phaserun.GoalQualityValidationSourceRequest
import skillbill.engine.featuretask.phaserun.PhaseRunEntry
import skillbill.engine.featuretask.phaserun.PhaseRunPlanResolver
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.quality.GoalQualityBuildSourceProof
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.goalrunner.model.quality.GoalQualityPreQaBuildState
import skillbill.goalrunner.model.quality.acceptedQaSourceWitness
import skillbill.goalrunner.model.quality.hasAcceptedQaSource
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import java.util.concurrent.atomic.AtomicReference

@Inject
public class GoalQualityBuildBoundary public constructor(
  private val phaseRunEntry: PhaseRunEntry,
  private val phasePlans: PhaseRunPlanResolver,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val gitOperations: WorkflowGitOperations,
  private val phaseRequests: GoalQualityPhaseRequests,
  private val diagnostics: RuntimeDiagnostics,
) {
  private val repairOutputs = GoalQualityRepairOutputs(outcomeStore)

  internal fun currentBaseline(result: WorkflowGitOperationResult): String =
    (result as? WorkflowGitOperationResult.Ok)?.value?.takeIf(String::isNotBlank)
      ?: throw SkillBillRuntimeException(
        WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
        "Could not capture a repository baseline after the initial build.",
      )

  internal fun run(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
  ): GoalQaGateResult.Paused? {
    val admitted = admitInitialBuild(state, request, quality)
    val execution = requireNotNull(admitted.execution)
    if (execution.preQaBuild in setOf(GoalQualityPreQaBuildState.PASSED, GoalQualityPreQaBuildState.WARNED)) {
      return null
    }
    return dispatchInitialBuild(state, request, admitted, execution)
  }

  private fun admitInitialBuild(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    quality: GoalQualityExecutionRecord,
  ): GoalQualityExecutionRecord {
    var current = quality
    var execution = requireNotNull(current.execution)
    validateRetainedQualityWork(execution, state, request)
    if (needsCurrentSourceBuild(execution)) {
      reenterInitialBuild(state, request, execution)?.let { current = it }
      execution = requireNotNull(current.execution)
    }
    if (execution.preQaBuild == GoalQualityPreQaBuildState.PENDING) {
      current = transitionQuality(outcomeStore, state, current, current.startPreQaBuild())
    }
    repairOutputs.reconcileInitialBuild(state, requireNotNull(current.execution).invocationId)
    return current
  }

  private fun needsCurrentSourceBuild(execution: GoalQualityExecution): Boolean {
    if (execution.status != GoalQualityExecutionStatus.QA_PENDING) return false
    return execution.preQaBuild in setOf(GoalQualityPreQaBuildState.PASSED, GoalQualityPreQaBuildState.WARNED)
  }

  private fun reenterInitialBuild(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    execution: GoalQualityExecution,
  ): GoalQualityExecutionRecord? {
    val currentSource = currentContentFingerprint(gitOperations, state, request)
    val acceptedMutation =
      hasAcceptedQaMutationAt(execution, currentSource, currentRepositoryCheckpoint(gitOperations, request))
    if (currentSource == execution.preQaBuildContentFingerprint || acceptedMutation) return null
    val plan = requireNotNull(execution.executionPlans).build
    requireResolvedNestedGateOutcome(outcomeStore, execution, SkeletonDefinition.GOAL_BUILD, plan)
    val progress = requireNotNull(execution.progress).build
    val observation =
      requireBuildObservation(
        progress,
        "Initial build '${execution.invocationId}' has no retained source observation for re-entry.",
      )
    val proof =
      GoalQualityBuildSourceProof(
        state.parentWorkflowId,
        execution.invocationId,
        plan,
        progress.revision,
        observation.phaseId,
        observation.attempt,
        observation.completionSequenceNumber,
        observation.contentFingerprint,
        currentSource,
      )
    return outcomeStore.reenterGoalQualityBuild(proof) ?: invalidBuild(
      "Initial build source changed while its retained boundary was being admitted.",
    )
  }

  private fun dispatchInitialBuild(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    current: GoalQualityExecutionRecord,
    execution: GoalQualityExecution,
  ): GoalQaGateResult.Paused? {
    if (execution.preQaBuild != GoalQualityPreQaBuildState.RUNNING) {
      invalidBuild("Initial build state for '${execution.invocationId}' cannot continue.")
    }
    val inputs = requireNotNull(execution.governingInputs)
    val failure = AtomicReference<SkillBillRuntimeException?>()
    val buildRequest =
      phaseRequests.create(
        GoalQualityPhaseInput(SkeletonDefinition.GOAL_BUILD, inputs, execution),
        state,
        request,
        repairOutputs.sink(state, SkeletonDefinition.GOAL_BUILD, failure),
      )
    val plan = requireNotNull(execution.executionPlans).build
    val result =
      phaseRunEntry.runForGoalQuality(
        buildRequest,
        state.parentWorkflowId,
        execution.invocationId,
        inputs.branch,
        plan,
      )
    requireResolvedNestedGateOutcome(outcomeStore, execution, SkeletonDefinition.GOAL_BUILD, plan)
    rethrowPersisted(failure)
    repairOutputs.reconcile(state, SkeletonDefinition.GOAL_BUILD, result)
    val paused = goalQaProviderLimitPause(result, nestedQaRepairBuild = false)
    if (paused != null) {
      val blocked = result as PhaseRunResult.Blocked
      return GoalQaGateResult.Paused(blocked.reason, blocked.stepId)
    }
    settle(InitialBuildSettlement(state, request, current, execution, plan, result))
    return null
  }

  private fun hasAcceptedQaMutationAt(
    execution: GoalQualityExecution,
    currentSource: String,
    repositoryCheckpoint: String,
  ): Boolean =
    execution.acceptedQaSourceWitness(currentSource, repositoryCheckpoint)?.let {
      execution.hasAcceptedQaSource(currentSource, it)
    } == true

  private fun validateRetainedQualityWork(
    execution: GoalQualityExecution,
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ) {
    val plans = requireNotNull(execution.executionPlans)
    val progress = requireNotNull(execution.progress)
    val inputs = requireNotNull(execution.governingInputs)
    val assignments = qualityWorkflowAgentAssignments(inputs, request, state.parentWorkflowId)
    listOf(
      SkeletonDefinition.GOAL_QA.id to (plans.qa to progress.qa),
      SkeletonDefinition.VALIDATION.id to (plans.validation to progress.validation),
    ).forEach { (definitionId, retained) ->
      val plan = phasePlans.validateGoalQualityExecutionPlan(
        retained.first,
        definitionId,
        inputs.effectiveAgentId,
        assignments,
      )
      phasePlans.validateNestedProgressKeys(retained.second)
      phasePlans.validateRetainedRunningPhases(
        retained.second,
        plan,
        "${execution.parentWorkflowId}:${execution.invocationId}",
      )
    }
  }

  private fun settle(settlement: InitialBuildSettlement) {
    val state = settlement.state
    val request = settlement.request
    val execution = settlement.execution
    val latest = requireLatestQuality(outcomeStore, state, settlement.current)
    val latestExecution = requireNotNull(latest.execution)
    val latestInputs = requireNotNull(latestExecution.governingInputs)
    val assignments = qualityWorkflowAgentAssignments(latestInputs, request, state.parentWorkflowId)
    val progress = requireNotNull(latestExecution.progress).build
    val observation =
      requireBuildObservation(
        progress,
        "Initial build '${execution.invocationId}' has no accepted terminal source observation.",
      )
    val acceptedPlan =
      phasePlans.validateGoalQualityExecutionPlan(
        settlement.plan,
        SkeletonDefinition.GOAL_BUILD.id,
        latestInputs.effectiveAgentId,
        assignments,
      )
    val sourceCurrent =
      phasePlans.acceptedValidationSourceIsCurrent(
        GoalQualityValidationSourceRequest(
          progress,
          acceptedPlan,
          "${state.parentWorkflowId}:${execution.invocationId}",
          request.repoRoot,
          currentContentFingerprint(gitOperations, state, request),
          validationOccurrence = observation.validationOccurrence,
        ),
      )
    if (!sourceCurrent) {
      invalidBuild("Initial build '${execution.invocationId}' terminal source evidence is not current.")
    }
    val retainedWarning = persistBuildWarning(state, execution.invocationId, buildWarning(progress, settlement.result))
    val passed = buildPassed(progress, settlement.result)
    transitionQuality(
      outcomeStore,
      state,
      latest,
      latest.settlePreQaBuild(passed, observation.contentFingerprint, retainedWarning),
    )
  }

  private fun requireBuildObservation(
    progress: GoalQualityPhaseProgress,
    message: String,
  ) = progress.acceptedSourceObservations.lastOrNull() ?: invalidBuild(message)

  private fun persistBuildWarning(
    state: GoalRunnerManifestState,
    invocationId: String,
    warning: String?,
  ): String? {
    if (warning == null) return null
    if (outcomeStore.recordGoalFinalRepairWarning(state.parentWorkflowId, invocationId, warning)) {
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "goal_qa_gate: $warning")
      return warning
    }
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
      "Could not persist initial build warning for '${state.parentWorkflowId}'.",
    )
  }

  private fun rethrowPersisted(failure: AtomicReference<SkillBillRuntimeException?>) {
    val persisted = failure.get() ?: return
    throw persisted
  }

  private fun invalidBuild(message: String): Nothing =
    throw SkillBillRuntimeException(WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION, message)
}

private data class InitialBuildSettlement(
  val state: GoalRunnerManifestState,
  val request: GoalRunnerRunRequest,
  val current: GoalQualityExecutionRecord,
  val execution: GoalQualityExecution,
  val plan: String,
  val result: PhaseRunResult,
)

private fun buildPassed(
  progress: GoalQualityPhaseProgress,
  result: PhaseRunResult,
): Boolean {
  val gate = progress.buildGateProgress
  val lastRun = gate?.gateRuns?.lastOrNull()
  val pending = gate?.pendingExternalOperation != null || gate?.pendingExternalCheckpoint != null
  val receipt = lastRun?.outcome == ValidationGateRunOutcome.PASSED && lastRun.exitCode == 0
  return result is PhaseRunResult.Completed && !pending && receipt
}

private fun buildWarning(
  progress: GoalQualityPhaseProgress,
  result: PhaseRunResult,
): String? {
  if (buildPassed(progress, result)) return null
  return when (result) {
    is PhaseRunResult.Blocked -> result.reason
    is PhaseRunResult.Completed -> "The initial build did not retain an accepted passing pack build receipt."
  }
}
