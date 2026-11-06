package skillbill.engine.goalrunner.qa

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeModelAssignment
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalProgressEventDraft
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.monitoring.GOAL_FINALIZATION_OPERATION_KIND
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
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

internal fun Clock.goalQaProgressDraft(
  state: GoalRunnerManifestState,
  request: GoalRunnerRunRequest,
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
    agentId = request.configuredAgentOverrideId ?: request.invokedAgentId,
  )

internal fun resumeModelAssignment(
  inputs: GoalQualityExecutionInputs,
  request: GoalRunnerRunRequest,
): FeatureTaskRuntimeModelAssignment =
  if ((request.configuredAgentOverrideId ?: request.invokedAgentId) == inputs.effectiveAgentId) {
    FeatureTaskRuntimeModelAssignment(inputs.modelDirectives, inputs.modelMatrix)
  } else {
    request.modelAssignment
  }
