package skillbill.engine.goalrunner.qa

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.phaserun.PhaseRunPlanResolver
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.execution.support.goalRunnerRepositoryContentIdentity
import skillbill.engine.goalrunner.model.GoalProgressEventDraft
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.monitoring.GOAL_FINALIZATION_OPERATION_KIND
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.quality.GoalQualityAdmissionRequest
import skillbill.engine.goalrunner.persist.quality.GoalQualityExecutionRead
import skillbill.engine.goalrunner.persist.quality.GoalQualityValidationSourceProof
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityExecutionPlans
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.time.Clock

internal sealed class ValidationAfterQaStep {
  data class Paused(val pause: GoalQaGateResult.Paused) : ValidationAfterQaStep()

  data class Settled(val result: GoalQaGateResult) : ValidationAfterQaStep()

  data class Advance(
    val warnings: List<String>,
    val quality: GoalQualityExecutionRecord,
  ) : ValidationAfterQaStep()
}

internal sealed class ValidationPhaseAttempt {
  data class Completed(val result: PhaseRunResult) : ValidationPhaseAttempt()

  data class Contained(val result: GoalQaGateResult) : ValidationPhaseAttempt()
}

internal data class GoalQaRun(
  val request: PhaseRunRequest,
  val branch: String,
  val startTree: WorkflowGitOperationResult,
  val result: PhaseRunResult,
)

internal data class GoalQualityPhaseInput(
  val definition: SkeletonDefinition,
  val inputs: GoalQualityExecutionInputs,
  val execution: GoalQualityExecution? = null,
)

internal const val MAX_ACTIVITY_LENGTH = 500
internal const val MAX_OPERATION_NAME_LENGTH = 160

internal data class PreparedQualityContext(
  val diagnostics: RuntimeDiagnostics,
  val phasePlans: PhaseRunPlanResolver,
  val phaseRequests: GoalQualityPhaseRequests,
  val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  val readParentInvariants: (GoalRunnerManifestState, GoalRunnerRunRequest) -> FeatureTaskRuntimeRunInvariantsRead,
)

internal fun admitPreparedQuality(
  context: PreparedQualityContext,
  gitOperations: WorkflowGitOperations,
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
): Pair<GoalQualityExecutionRecord, FeatureTaskRuntimeRunInvariantsRead> {
  val baseline = requireQualityBaseline(gitOperations, state, request)
  val retainedRead = context.outcomeStore.retainedGoalQualityExecution(state.parentWorkflowId)
  val retainedRecord = (retainedRead as? GoalQualityExecutionRead.Current)?.record
  rejectAmbiguousRetainedQuality(context.diagnostics, request, retainedRead, retainedRecord)
  val retention = preparedRetention(retainedRead, retainedRecord)
  recordHistoricalAdoption(context.diagnostics, retention.historical)
  val parentInvariants = parentInvariantsFor(context, state, request, retention)
  val inputs = qualityInputsFor(state, request, parentInvariants, retention)
  val plans = resolveAdmittedPlans(context, state, request, inputs, retention)
  val validationSourceProof = validationSourceProof(context, state, baseline, retention, plans)
  val quality =
    context.outcomeStore.admitGoalQualityExecution(
      state.parentWorkflowId,
      GoalQualityAdmissionRequest(
        baseline,
        inputs,
        plans.executionPlans,
        validationSourceProof = validationSourceProof,
        expectedRetainedRead = retainedRead,
      ),
    )
  return quality to parentInvariants
}

internal fun Clock.goalQaProgressDraft(
  state: GoalRunnerManifestState,
  agentId: String,
  phaseId: String,
  kind: GoalProgressEventKind,
  outcome: GoalProgressOutcome,
): GoalProgressEventDraft =
  GoalProgressEventDraft(
    eventKind = kind,
    workflowId = state.parentWorkflowId,
    workflowPhase = phaseId,
    processAlive = true,
    timestamp = instant(),
    stepId = phaseId,
    operationKind = GOAL_FINALIZATION_OPERATION_KIND,
    outcome = outcome,
    agentId = agentId,
  )

private data class PreparedRetention(
  val retainedExecution: GoalQualityExecution?,
  val historical: GoalQualityExecutionRead.Historical?,
  val retainedRecord: GoalQualityExecutionRecord?,
)

private data class PreparedPlans(
  val executionPlans: GoalQualityExecutionPlans,
  val retainedValidationPlan: ResolvedPhaseExecutionPlan?,
  val historicalValidationPlan: ResolvedPhaseExecutionPlan?,
)

private fun preparedRetention(
  retainedRead: GoalQualityExecutionRead?,
  retainedRecord: GoalQualityExecutionRecord?,
): PreparedRetention =
  PreparedRetention(
    retainedRecord?.execution?.takeUnless { it.status == GoalQualityExecutionStatus.RETIRED },
    adoptableHistorical(retainedRead),
    retainedRecord,
  )

private fun adoptableHistorical(read: GoalQualityExecutionRead?): GoalQualityExecutionRead.Historical? =
  (read as? GoalQualityExecutionRead.Historical)?.takeUnless(::retiredHistorical)

private fun retiredHistorical(retained: GoalQualityExecutionRead.Historical): Boolean =
  retained.status == GoalQualityExecutionStatus.RETIRED || retained.invocationId == null

private fun recordHistoricalAdoption(
  diagnostics: RuntimeDiagnostics,
  historical: GoalQualityExecutionRead.Historical?,
) {
  historical ?: return
  RuntimeDiagnosticsBestEffortWarning.record(
    diagnostics,
    "goal_qa_gate: adopting supported retained quality execution ${historical.sourceContractVersion} before dispatch.",
  )
}

private fun parentInvariantsFor(
  context: PreparedQualityContext,
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
  retention: PreparedRetention,
): FeatureTaskRuntimeRunInvariantsRead {
  val retainedInputs = retention.retainedExecution?.governingInputs ?: retention.historical?.governingInputs
  return retainedInputs?.let(::retainedParentInvariants) ?: context.readParentInvariants(state, request)
}

private fun qualityInputsFor(
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
  parentInvariants: FeatureTaskRuntimeRunInvariantsRead,
  retention: PreparedRetention,
): GoalQualityExecutionInputs =
  retention.retainedExecution?.governingInputs
    ?: retention.historical?.governingInputs
    ?: governingInputs(state, request, parentInvariants)

private fun resolveAdmittedPlans(
  context: PreparedQualityContext,
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
  inputs: GoalQualityExecutionInputs,
  retention: PreparedRetention,
): PreparedPlans {
  val acceptedAgentId = inputs.effectiveAgentId
  val retainedPlans = retention.retainedExecution?.executionPlans
  val retainedValidationPlan =
    retainedPlans?.let { plans ->
      context.phasePlans.validateGoalQualityExecutionPlan(plans.qa, SkeletonDefinition.GOAL_QA.id, acceptedAgentId)
      context.phasePlans.validateGoalQualityExecutionPlan(
        plans.build,
        SkeletonDefinition.GOAL_BUILD.id,
        acceptedAgentId,
      )
      context.phasePlans.validateGoalQualityExecutionPlan(
        plans.validation,
        SkeletonDefinition.VALIDATION.id,
        acceptedAgentId,
      )
    }
  val historicalPlans = historicalPlansFor(context, state, request, inputs, retention.historical)
  val executionPlans =
    retainedPlans ?: historicalPlans?.plans ?: resolveQualityPlans(
      context.phasePlans,
      context.phaseRequests,
      state,
      request,
      inputs,
    )
  return PreparedPlans(executionPlans, retainedValidationPlan, historicalPlans?.validationPlan)
}

private data class HistoricalPlans(
  val plans: GoalQualityExecutionPlans,
  val validationPlan: ResolvedPhaseExecutionPlan,
)

private fun historicalPlansFor(
  context: PreparedQualityContext,
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
  inputs: GoalQualityExecutionInputs,
  historical: GoalQualityExecutionRead.Historical?,
): HistoricalPlans? {
  val retained = historical ?: return null
  val acceptedAgentId = inputs.effectiveAgentId
  val qa = requireNotNull(retained.qaPlan)
  val validation = requireNotNull(retained.validationPlan)
  context.phasePlans.validateGoalQualityExecutionPlan(qa, SkeletonDefinition.GOAL_QA.id, acceptedAgentId)
  val validationPlan =
    context.phasePlans.validateGoalQualityExecutionPlan(validation, SkeletonDefinition.VALIDATION.id, acceptedAgentId)
  return HistoricalPlans(
    GoalQualityExecutionPlans(
      resolveLegacyBuildPlan(context.phasePlans, context.phaseRequests, state, request, inputs),
      qa,
      validation,
    ),
    validationPlan,
  )
}

private fun validationSourceProof(
  context: PreparedQualityContext,
  state: GoalRunnerManifestState,
  baseline: String,
  retention: PreparedRetention,
  plans: PreparedPlans,
): GoalQualityValidationSourceProof? {
  val retainedRecord = retention.retainedRecord
  if (retainedRecord != null) return retainedValidationProof(context, state, baseline, retainedRecord, plans)
  val historical = retention.historical ?: return null
  val plan = plans.historicalValidationPlan ?: return null
  return acceptedHistoricalValidationSourceProof(context.phasePlans, historical, plan, baseline)
}

private fun retainedValidationProof(
  context: PreparedQualityContext,
  state: GoalRunnerManifestState,
  baseline: String,
  retainedRecord: GoalQualityExecutionRecord,
  plans: PreparedPlans,
): GoalQualityValidationSourceProof? {
  val execution =
    retainedRecord.execution?.takeUnless { it.status == GoalQualityExecutionStatus.RETIRED } ?: return null
  val plan = plans.retainedValidationPlan ?: return null
  return acceptedValidationSourceProof(
    context.phasePlans,
    retainedRecord,
    plan,
    baseline,
    historyWriterOwnsCurrentContents(context.outcomeStore, context.phasePlans, state, execution, baseline),
  )
}

private fun requireQualityBaseline(
  gitOperations: WorkflowGitOperations,
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
): String {
  val currentTree = goalRunnerRepositoryContentIdentity(gitOperations, request.repoRoot)
  return (currentTree as? WorkflowGitOperationResult.Ok)?.value?.takeIf(String::isNotBlank)
    ?: throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      "Could not capture a repository baseline for final quality execution '${state.parentWorkflowId}'.",
    )
}

internal fun rejectAmbiguousRetainedQuality(
  diagnostics: RuntimeDiagnostics,
  request: GoalRunnerRunRequest,
  retainedRead: GoalQualityExecutionRead?,
  retainedRecord: GoalQualityExecutionRecord?,
) {
  val ambiguous =
    retainedRecord?.readiness == GoalQualityReadiness.HISTORICAL_AMBIGUITY ||
      retainedRead is GoalQualityExecutionRead.Ambiguous
  val historical = retainedRead is GoalQualityExecutionRead.Historical && historicalNeedsEvidence(retainedRead)
  if (ambiguous || historical) {
    val sourceVersion =
      when (retainedRead) {
        is GoalQualityExecutionRead.Ambiguous -> retainedRead.sourceContractVersion
        is GoalQualityExecutionRead.Historical -> retainedRead.sourceContractVersion
        else -> "current"
      }
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "goal_qa_gate: historical quality execution $sourceVersion requires authoritative evidence or an operator reset.",
    )
    throw SkillBillRuntimeException(
      WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
      "This parent has ambiguous historical quality state. Provide authoritative original evidence or explicitly " +
        "choose a fresh quality execution with new cycles: " +
        "skill-bill goal fresh-quality ${request.issueKey} --reason \"<why>\".",
    )
  }
}

private fun historicalNeedsEvidence(retained: GoalQualityExecutionRead.Historical): Boolean =
  !retained.hasAuthoritativePlansAndProgress &&
    retained.status != GoalQualityExecutionStatus.RETIRED &&
    retained.invocationId != null

internal fun resolveLegacyBuildPlan(
  phasePlans: PhaseRunPlanResolver,
  phaseRequests: GoalQualityPhaseRequests,
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
  inputs: GoalQualityExecutionInputs,
): String =
  phasePlans.resolveGoalQualityExecutionPlan(
    phaseRequests.create(
      GoalQualityPhaseInput(SkeletonDefinition.GOAL_BUILD, inputs),
      state,
      request,
    ) { _, _, _, _ -> },
    state.parentWorkflowId,
  )

internal fun resolveQualityPlans(
  phasePlans: PhaseRunPlanResolver,
  phaseRequests: GoalQualityPhaseRequests,
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
  inputs: GoalQualityExecutionInputs,
): GoalQualityExecutionPlans =
  GoalQualityExecutionPlans(
    resolveLegacyBuildPlan(phasePlans, phaseRequests, state, request, inputs),
    phasePlans.resolveGoalQualityExecutionPlan(
      phaseRequests.create(
        GoalQualityPhaseInput(SkeletonDefinition.GOAL_QA, inputs),
        state,
        request,
      ) { _, _, _, _ -> },
      state.parentWorkflowId,
    ),
    phasePlans.resolveGoalQualityExecutionPlan(
      phaseRequests.create(
        GoalQualityPhaseInput(SkeletonDefinition.VALIDATION, inputs),
        state,
        request,
      ) { _, _, _, _ -> },
      state.parentWorkflowId,
    ),
  )
