package skillbill.engine.featuretask.slot.qa

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.qa.QaReceiptPayloadKeys
import skillbill.engine.featuretask.qa.execution.QaExecutionLifecycle
import skillbill.engine.featuretask.qa.receipt.FeatureTaskQaOutcomeRecorder
import skillbill.engine.featuretask.qa.receipt.QaPhaseOutputKeys
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseQaStepBinding
import skillbill.engine.featuretask.slot.state.PhaseStepBinding
import skillbill.error.featuretask.qa.invalidQaReceipt
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.qa.QaOpenFinding
import skillbill.workflow.taskruntime.model.qa.QaRepairAttempt
import skillbill.workflow.taskruntime.model.qa.QaRepairAttemptOutcome
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal class QaRepairHooks(
  private val execution: QaExecutionLifecycle,
  private val outcomes: FeatureTaskQaOutcomeRecorder,
) : PhaseStepHooks {
  override fun launchPromptSupplement(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
    state: PhaseStepBinding,
  ): String {
    val findings = (state as PhaseQaStepBinding).qaRepairState()?.openFindings.orEmpty()
    if (findings.isEmpty()) return ""
    return QaPromptSections.repairSupplement(findings, receiptFindings(state), run.request.repoRoot.toString())
  }

  override fun checkValidatedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseAcceptedStepExecution,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck {
    val previous = (state as PhaseQaStepBinding).qaRepairState()
    val openIds = previous?.openFindings.orEmpty().map(QaOpenFinding::id)
    if (outputMap[SharedPayloadKeys.STATUS] != COMPLETED || openIds.isEmpty()) return PhaseStepOutputCheck.Accept
    val tested = previous?.lastReceiptTreeIdentity
    val unchanged = tested != null && execution.currentTreeIdentity(run.request.repoRoot) == tested
    val report = NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(outputMap).output.value
    val unfixed = if (unchanged) openIds else openIds.filter { report.leavesUnchanged(it) }
    if (unfixed.isEmpty()) return PhaseStepOutputCheck.Accept
    val reason =
      if (unchanged) {
        "qa_fix changed no file. Every QA finding must be fixed in source; none may be left unchanged."
      } else {
        "qa_fix left QA findings unchanged. Every QA finding must be fixed in source."
      }
    return PhaseStepOutputCheck.OweFindings("$reason Not fixed: ${unfixed.joinToString()}.", unfixed.toSet())
  }

  override fun interpretedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseAcceptedStepExecution,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    if (output.status != COMPLETED) return output
    val previous =
      (state as PhaseQaStepBinding).qaRepairState()
        ?: invalid("qa_fix completed without the QA findings state it repairs.")
    val number = previous.consumedAttempts + 1
    val attempt =
      QaRepairAttempt(
        attemptNumber = number,
        receiptTreeIdentity =
          previous.lastReceiptTreeIdentity ?: invalid("qa_fix repaired findings without a tested tree identity."),
        consumedFindingIds = previous.openFindings.map(QaOpenFinding::id),
        repairOutputRef = QaRepairState.repairOutputRef(run.request.workflowId, number),
        outcome = QaRepairAttemptOutcome.COMPLETED,
      )
    if (run.request.admittedExecution == null && run.request.qaSelection != null) {
      outcomes.recordRepairAttempts(run.request.workflowId, number)
    }
    return output.copy(qaRepairState = previous.recordRepair(attempt))
  }

  private fun receiptFindings(state: PhaseStepBinding): List<Map<String, Any?>> {
    val produced =
      JsonCodec.anyToStringAnyMap(
        state.completedStepEnvelope(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA)
          ?.get(SharedPayloadKeys.PRODUCED_OUTPUTS),
      )
    val receipt = JsonCodec.anyToStringAnyMap(produced?.get(QaPhaseOutputKeys.RECEIPT))
    return JsonCodec.anyToStringAnyMapList(receipt?.get(QaReceiptPayloadKeys.FINDINGS)).orEmpty()
  }

  private fun invalid(reason: String): Nothing = throw invalidQaReceipt(reason)

  companion object {
    private const val COMPLETED = "completed"
    private const val NO_CHANGE_MARKER = "not changed:"

    fun noChangeWarning(
      state: PhaseStepBinding,
      previous: QaRepairState,
      testedTreeIdentity: String,
    ): String? {
      val last = previous.attempts.lastOrNull() ?: return null
      if (last.receiptTreeIdentity != testedTreeIdentity) return null
      val report =
        state.completedStepEnvelope(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA_FIX)
          ?.let(NormalizedFeatureTaskRuntimePhaseOutput::fromRecordMap)
          ?.output
          ?.value
          ?: return null
      val reasons =
        report.lines()
          .map(String::trim)
          .filter { line -> line.contains(NO_CHANGE_MARKER, ignoreCase = true) }
          .joinToString(" ")
          .take(MAX_REASON_LENGTH)
      return "qa_fix attempt ${last.attemptNumber} changed no file for QA findings " +
        "${last.consumedFindingIds.joinToString()}" +
        reasons.takeIf(String::isNotBlank)?.let { "; its reasons: $it" }
          .orEmpty()
    }

    private fun String.leavesUnchanged(findingId: String): Boolean =
      lines().any { line -> line.substringAfter(findingId, "").contains(NO_CHANGE_MARKER, ignoreCase = true) }

    private const val MAX_REASON_LENGTH = 600
  }
}
