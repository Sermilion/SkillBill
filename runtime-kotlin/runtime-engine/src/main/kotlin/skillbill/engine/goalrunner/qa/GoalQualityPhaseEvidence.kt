package skillbill.engine.goalrunner.qa

import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal fun sameTree(
  before: WorkflowGitOperationResult,
  after: WorkflowGitOperationResult,
): Boolean =
  before is WorkflowGitOperationResult.Ok && after is WorkflowGitOperationResult.Ok &&
    before.value.trim() == after.value.trim()

internal fun hasAcceptedPhaseProgress(progress: GoalQualityPhaseProgress): Boolean =
  progress.phaseRecords.isNotEmpty() || progress.phaseLedger.isNotEmpty() ||
    progress.acceptedOutputs.isNotEmpty() || progress.acceptedEnvelopes.isNotEmpty() ||
    progress.buildGateProgress != null || progress.validationGateProgress != null ||
    progress.verificationCheckpoint != null || progress.verificationBoundary != null

internal fun retainedParentInvariants(inputs: GoalQualityExecutionInputs): FeatureTaskRuntimeRunInvariantsRead =
  inputs.acceptanceCriteria?.let { criteria ->
    FeatureTaskRuntimeRunInvariantsRead.Read(
      FeatureTaskRuntimeRunInvariants(
        specReference = inputs.parentSpecPath,
        acceptanceCriteria = criteria,
        mandatesAndOverrides = emptyList(),
      ),
    )
  } ?: FeatureTaskRuntimeRunInvariantsRead.Rejected(
    inputs.acceptanceCriteriaReadFailure ?: "The admitted execution did not retain parent acceptance criteria.",
  )

internal fun qaOutcomeWorkflowId(
  state: GoalRunnerManifestState,
  invocationId: String?,
): String =
  invocationId?.takeIf(String::isNotBlank)?.let { "${state.parentWorkflowId}:$it" }
    ?: state.parentWorkflowId

internal fun finalQaWarning(
  qaWarning: String?,
  qaOutcome: FeatureTaskQaOutcome?,
  testedTreeIdentity: String?,
): String? {
  val reportedQaWarning =
    qaOutcome
      ?.takeIf { it.outcome != StandaloneQaOutcome.PASSED }
      ?.let { it.reason ?: "QA completed with ${it.outcome.wireValue} status." }
  val untestedWarning =
    if (testedTreeIdentity == null) {
      qaOutcome?.reason ?: "QA ended without a tested-tree receipt."
    } else {
      null
    }
  return listOfNotNull(qaWarning, reportedQaWarning, untestedWarning)
    .distinct()
    .joinToString(" ")
    .takeIf(String::isNotBlank)
}

internal fun buildAfterQaFixWarning(result: PhaseRunResult): String? {
  val blocked = result as? PhaseRunResult.Blocked ?: return null
  if (blocked.stepId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD) return null
  return "qa_fix left the app without a passing build, so QA did not re-test the fix. The build gate allows " +
    "${FeatureTaskRuntimeBuildGateCoordinator.MAX_REPAIR_TURNS} build repairs and stopped: ${blocked.reason}"
}
