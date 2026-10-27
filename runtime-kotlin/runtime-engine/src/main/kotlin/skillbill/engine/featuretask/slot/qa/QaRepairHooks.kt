package skillbill.engine.featuretask.slot.qa

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.qa.execution.QaExecutionLifecycle
import skillbill.engine.featuretask.qa.report.FeatureTaskQaOutcomeRecorder
import skillbill.engine.featuretask.qa.report.FeatureTaskQaOutcomeRecords
import skillbill.engine.featuretask.qa.report.QaPhaseOutputKeys
import skillbill.engine.featuretask.qa.report.QaReportSettlement
import skillbill.engine.featuretask.qa.report.QaRetainedFindingRequest
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseFailureContinuationOutput
import skillbill.engine.featuretask.slot.PhaseStepFailure
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.state.PhaseQaStepBinding
import skillbill.engine.featuretask.slot.state.PhaseStepBinding
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.qa.QaOpenFinding
import skillbill.workflow.taskruntime.model.qa.QaRepairAttempt
import skillbill.workflow.taskruntime.model.qa.QaRepairAttemptOutcome
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

internal class QaRepairHooks(
  private val execution: QaExecutionLifecycle,
  private val outcomes: FeatureTaskQaOutcomeRecorder,
  private val reports: QaReportSettlement,
  private val stops: QaStopContinuation,
) : PhaseStepHooks<PhaseQaStepBinding> {
  override fun failureContinuationOutput(
    run: PhaseRun,
    failure: PhaseStepFailure,
    state: PhaseQaStepBinding,
  ): PhaseFailureContinuationOutput? = stops.outputFor(run, failure, state)

  override fun launchPromptSupplement(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
    state: PhaseQaStepBinding,
  ): String {
    val findings = state.qaRepairState()?.openFindings.orEmpty()
    if (findings.isEmpty()) return ""
    return QaPromptSections.repairSupplement(
      findings,
      openFindingSections(run.request.qaOutcomeWorkflowId, run.request.repoRoot, findings, state),
      run.request.repoRoot.toString(),
    )
  }

  override fun checkValidatedOutput(
    run: PhaseRun,
    context: PhaseStepOutputContext,
    state: PhaseQaStepBinding,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): PhaseStepOutputCheck {
    val previous = state.qaRepairState()
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
    state: PhaseQaStepBinding,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    if (output.status != COMPLETED) return output
    val previous =
      state.qaRepairState()
        ?: error("qa_fix completed without the QA findings state it repairs.")
    val number = previous.consumedAttempts + 1
    val attempt =
      QaRepairAttempt(
        attemptNumber = number,
        receiptTreeIdentity =
          previous.lastReceiptTreeIdentity ?: error("qa_fix repaired findings without a tested tree identity."),
        consumedFindingIds = previous.openFindings.map(QaOpenFinding::id),
        repairOutputRef = QaRepairState.repairOutputRef(run.request.workflowId, number),
        outcome = QaRepairAttemptOutcome.COMPLETED,
      )
    if (run.request.admittedExecution == null && run.request.qaSelection != null) {
      outcomes.recordRepairAttempts(run.request.qaOutcomeWorkflowId, number)
    }
    return output.copy(qaRepairState = previous.recordRepair(attempt))
  }

  private fun openFindingSections(
    workflowId: String,
    repoRoot: Path,
    findings: List<QaOpenFinding>,
    state: PhaseStepBinding,
  ): Map<String, QaPromptSections.RepairFinding> {
    val outcome = outcomes.find(workflowId)
    val stored = outcome?.findings.orEmpty().associateBy { finding -> finding.id }
    val currentReport = currentProseReport(state)
    return findings.associate { finding ->
      val persisted = stored[finding.id]
      val reportFinding =
        reports.retainedFinding(
          QaRetainedFindingRequest(
            repoRoot = repoRoot,
            reference = finding.receiptRefs.lastOrNull(),
            findingId = finding.id,
            storedReportText = outcome?.reportText,
            storedEvidencePaths = persisted?.evidencePaths.orEmpty(),
            currentReport = currentReport,
          ),
        )
      finding.id to
        QaPromptSections.RepairFinding(
          id = finding.id,
          title = persisted?.title ?: reportFinding?.title ?: finding.id,
          section = reportFinding?.section,
          evidencePaths = (reportFinding?.evidencePaths.orEmpty() + persisted?.evidencePaths.orEmpty()).distinct(),
        )
    }
  }

  private fun currentProseReport(state: PhaseStepBinding) =
    state.completedStepEnvelope(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_QA)
      ?.let { envelope -> JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]) }
      ?.takeIf { produced -> produced[QaPhaseOutputKeys.REPORT_TEXT] is String }
      ?.let(FeatureTaskQaOutcomeRecords::reportFrom)

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
