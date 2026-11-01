package skillbill.engine.featuretask.slot.qa

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.execution.QaPolicy
import skillbill.engine.featuretask.qa.report.FeatureTaskQaOutcomeRecorder
import skillbill.engine.featuretask.runloop.core.containableOrThrow
import skillbill.engine.featuretask.slot.PhaseFailureContinuationOutput
import skillbill.engine.featuretask.slot.PhaseLoopContext
import skillbill.engine.featuretask.slot.state.PhaseQaStepBinding
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.error.core.failureCodeLabel
import skillbill.error.featuretask.qa.isQaFailure
import skillbill.ports.featuretask.model.FeatureTaskQaFailureKind
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal class QaFailureContainment(
  private val outcomes: FeatureTaskQaOutcomeRecorder,
) {
  fun contain(
    stepId: String,
    error: Exception,
    context: PhaseLoopContext,
    state: PhaseQaStepBinding,
  ): PhaseFailureContinuationOutput? {
    if (!claims(stepId, context)) return null
    val environment = (error as? SkillBillRuntimeException)?.isQaFailure == true
    val kind = if (environment) FeatureTaskQaFailureKind.ENVIRONMENT else FeatureTaskQaFailureKind.DEFECT
    val message = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
    val reason =
      if (environment) {
        "QA could not complete: $message"
      } else {
        "QA could not complete because of a runtime defect: ${error.javaClass.simpleName}: $message"
      }
    val code = error.failureCodeLabel() ?: error.javaClass.simpleName
    RuntimeDiagnosticsBestEffortWarning.record(
      context.diagnostics,
      "QA failure contained [seam=qa_containment, phase=$stepId, kind=${kind.wireValue}, code=$code].",
      error,
    )
    catchingNonCancellation { outcomes.recordContainedFailure(context.request.workflowId, kind, reason) }
      .onFailure { recordingFailure ->
        RuntimeDiagnosticsBestEffortWarning.record(
          context.diagnostics,
          "QA failure contained [seam=qa_containment, phase=$stepId]: could not record the QA outcome.",
          recordingFailure.containableOrThrow(),
        )
      }
    val repairState = repairStateOf(stepId, state, context)
    return PhaseFailureContinuationOutput(QaIncompleteOutput.build(stepId, repairState, reason, kind))
  }

  private fun repairStateOf(
    stepId: String,
    state: PhaseQaStepBinding,
    context: PhaseLoopContext,
  ): QaRepairState? =
    state
      .readRepairState(context.request.qaSubtaskId())
      .onFailure { unreadable ->
        RuntimeDiagnosticsBestEffortWarning.record(
          context.diagnostics,
          "QA repair state could not be read [seam=qa_containment, phase=$stepId]: open findings are not carried.",
          unreadable,
        )
      }.getOrNull()

  companion object {
    private val QA_STEPS =
      setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA, FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX)

    fun claims(
      stepId: String,
      context: PhaseLoopContext,
    ): Boolean = stepId in QA_STEPS && context.request.qaExecution?.policy != QaPolicy.STANDALONE
  }
}
