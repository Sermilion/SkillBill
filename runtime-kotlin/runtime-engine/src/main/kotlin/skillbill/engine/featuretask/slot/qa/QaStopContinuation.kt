package skillbill.engine.featuretask.slot.qa

import skillbill.engine.featuretask.model.execution.QaPolicy
import skillbill.engine.featuretask.qa.report.FeatureTaskQaOutcomeRecorder
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeAttemptBudgets
import skillbill.engine.featuretask.slot.PhaseFailureContinuationOutput
import skillbill.engine.featuretask.slot.PhaseStepFailure
import skillbill.engine.featuretask.slot.PhaseStepFailureSource
import skillbill.engine.featuretask.slot.state.PhaseQaStepBinding
import skillbill.ports.featuretask.model.FeatureTaskQaFailureKind
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal class QaStopContinuation(
  private val outcomes: FeatureTaskQaOutcomeRecorder,
) {
  fun outputFor(
    run: PhaseRun,
    failure: PhaseStepFailure,
    state: PhaseQaStepBinding,
  ): PhaseFailureContinuationOutput? {
    if (failure.source != PhaseStepFailureSource.BLOCK || run.qaExecution?.policy == QaPolicy.STANDALONE) return null
    val repairState = state.readRepairState(run.request.qaSubtaskId())
    val noProgress = noProgressFindings(run, failure)
    val reason =
      if (noProgress.isEmpty()) {
        "QA recorded incomplete because the ${run.phaseId} step stopped: ${failure.reason}"
      } else {
        "qa_fix made no progress on QA findings ${noProgress.sorted().joinToString()}; " +
          "the run continues and reports them as open."
      }
    val kind = FeatureTaskQaFailureKind.ENVIRONMENT.takeIf { noProgress.isEmpty() }
    recordInMemoryGoalRun(run, reason, kind)
    val unreadable = repairState.exceptionOrNull()
    val output = QaIncompleteOutput.build(run.phaseId, repairState.getOrNull(), reason, kind)
    return PhaseFailureContinuationOutput(
      output,
      unreadable?.let { "repair_state=unreadable cause=${it.javaClass.simpleName}" },
    )
  }

  private fun noProgressFindings(
    run: PhaseRun,
    failure: PhaseStepFailure,
  ): Set<String> =
    when {
      run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX -> emptySet()
      failure.failureCode == FeatureTaskRuntimeAttemptBudgets.ATTEMPT_BUDGET_FAILURE_CODE -> failure.owedFindingRefs
      failure.failureCode == null -> FeatureTaskRuntimeAttemptBudgets.omittedFindingsIn(failure.reason)
      else -> emptySet()
    }

  private fun recordInMemoryGoalRun(
    run: PhaseRun,
    reason: String,
    kind: FeatureTaskQaFailureKind?,
  ) {
    val inMemoryGoalRun = run.request.admittedExecution == null && run.request.qaSelection != null
    if (!inMemoryGoalRun || run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX) return
    if (kind == null) {
      outcomes.recordWarning(run.request.workflowId, reason)
    } else {
      outcomes.recordContainedFailure(run.request.workflowId, kind, reason)
    }
  }
}
