package skillbill.engine.operation.verify

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.qa.QaOutcomePayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.model.execution.QaPolicy
import skillbill.engine.featuretask.model.execution.QaSkipReason
import skillbill.engine.featuretask.qa.execution.QaExecutionLifecycle
import skillbill.engine.featuretask.qa.report.QaReport
import skillbill.engine.featuretask.qa.report.QaReportSettlement
import skillbill.engine.featuretask.qa.report.QaReports
import skillbill.engine.featuretask.qa.report.QaVerdict
import skillbill.engine.featuretask.slot.qa.QaPromptSections
import skillbill.engine.operation.core.OperationStepResult
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.qa.isQaFailure
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.verify.model.VerifyQaSelection
import java.nio.file.Path

internal enum class VerifyQaOutcome(val wireValue: String) {
  NOT_SELECTED("not_selected"),
  ATTEMPTED("attempted"),
  PASSED("passed"),
  FINDINGS("findings"),
  SKIPPED("skipped"),
  INCOMPLETE("incomplete"),
  ;

  companion object {
    fun of(verdict: QaVerdict): VerifyQaOutcome =
      when (verdict) {
        QaVerdict.PASSED -> PASSED
        QaVerdict.FINDINGS_OPEN -> FINDINGS
        QaVerdict.SKIPPED -> SKIPPED
        QaVerdict.INCOMPLETE -> INCOMPLETE
      }

    fun fromWire(value: String?): VerifyQaOutcome? = entries.firstOrNull { it.wireValue == value }
  }
}

internal data class VerifyQaReport(
  val outcome: VerifyQaOutcome,
  val reason: String?,
  val reportText: String? = null,
  val warnings: List<String> = emptyList(),
  val cleanup: String? = null,
) {
  val line: String get() = "QA outcome: ${outcome.wireValue}${reason?.let { " ($it)" }.orEmpty()}"

  val presentation: List<String>
    get() = warnings.map { warning -> "Warning: $warning" } + listOfNotNull(line, reportText, cleanup)

  val patch: Map<String, Any?>
    get() =
      mapOf(
        VerifyWorkflow.QA_OUTCOME to
          mapOf(
            VerifyWorkflow.CONTRACT_VERSION to VerifyWorkflow.ARTIFACT_CONTRACT_VERSION,
            QaOutcomePayloadKeys.OUTCOME to outcome.wireValue,
            QaOutcomePayloadKeys.REASON to reason,
          ),
      )

  companion object {
    fun at(
      run: VerifyRun,
      stepId: String,
      artifacts: Map<String, Any?>,
    ): VerifyQaReport {
      val qaSettled = run.steps.indexOf(stepId) > run.steps.indexOf(VerifyWorkflow.QA)
      return of(run.qa, VerifyWorkflow.qaArtifact(artifacts).takeIf { qaSettled })
    }

    fun of(
      selection: VerifyQaSelection?,
      artifact: Any?,
    ): VerifyQaReport {
      if (selection == null) return VerifyQaReport(VerifyQaOutcome.NOT_SELECTED, null)
      val outcome =
        VerifyQaOutcome.fromWire(VerifyWorkflow.string(artifact, QaOutcomePayloadKeys.OUTCOME))
          ?: QaVerdict.fromWire(VerifyWorkflow.string(artifact, VerifyWorkflow.VERDICT_FIELD))
            ?.let(VerifyQaOutcome::of)
      val reason = VerifyWorkflow.string(artifact, QaOutcomePayloadKeys.REASON)
      val reportText = VerifyWorkflow.string(artifact, QaOutcomePayloadKeys.REPORT_TEXT)
      val warnings = VerifyWorkflow.strings(artifact, VerifyWorkflow.WARNINGS)
      val cleanup = VerifyWorkflow.string(artifact, QaOutcomePayloadKeys.CLEANUP)
      return when {
        outcome != null -> VerifyQaReport(outcome, reason, reportText, warnings, cleanup)
        selection == VerifyQaSelection.ON -> VerifyQaReport(VerifyQaOutcome.ATTEMPTED, null)
        else -> VerifyQaReport(VerifyQaOutcome.SKIPPED, QaSkipReason.DISABLED_POLICY.wireValue)
      }
    }
  }
}

@Inject
class VerifyQaStep(
  private val execution: QaExecutionLifecycle,
  private val settlement: QaReportSettlement,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun run(
    run: VerifyRun,
    prior: Map<String, String>,
  ): VerifyStepDone =
    try {
      when (run.qa) {
        VerifyQaSelection.ON -> opted(run, prior)
        else -> settled(disabled())
      }
    } catch (failure: SkillBillRuntimeException) {
      failure.rethrowUnless(failure.isQaFailure)
      unavailable("QA could not finish and was recorded incomplete: ${failure.message.orEmpty()}")
    }

  private fun disabled(): QaReport {
    val report = QaReports.skipped(QaSkipReason.DISABLED_POLICY, DISABLED_DETAIL)
    settlement.recordDiagnostics(report)
    return report
  }

  private fun unavailable(warning: String): VerifyStepDone {
    RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "verify qa warning: ${warning.take(MAX_RECORD_REASON)}")
    return settled(QaReports.incomplete(warning))
  }

  private fun opted(
    run: VerifyRun,
    prior: Map<String, String>,
  ): VerifyStepDone {
    val settled =
      execution.withExecution(run.context.repoRoot, QaPolicy.ON, revision = run.target.headRevision) { qa ->
        when (val target = qa.target) {
          is QaExecutionTarget.Skipped -> QaReports.skipped(target.reason, target.detail)
          is QaExecutionTarget.Supported -> launch(run, target.checkout, qa, prior)
        }
      }
    val retained = QaReports.withRetainedResources(settled.value, settled.retainedResources, RETAINED_GUIDANCE)
    val report = retained.copy(warnings = retained.warnings + settled.warnings)
    settlement.recordDiagnostics(report)
    return settled(report)
  }

  private fun launch(
    run: VerifyRun,
    checkout: Path,
    qa: QaExecutionContext,
    prior: Map<String, String>,
  ): QaReport {
    val directive =
      VerifyPromptSections.qaDirective(
        checkout,
        run.target.headRevision,
      ) + QaPromptSections.supplement(qa)
    val context =
      run.context.copy(
        repoRoot = checkout,
        additionalWritableDirectories =
          (run.context.additionalWritableDirectories + listOf(qa.workspace.directory)).distinct(),
      )
    return when (val step = run.context.steps.runReportOnly(context, VerifyPromptSections.QA_STEP, directive, prior)) {
      is OperationStepResult.Failed -> stopped(step.reason)
      is OperationStepResult.Refused -> stopped(step.refusal.reason)
      is OperationStepResult.Settled -> settlement.settle(qa)
    }
  }

  private fun stopped(reason: String): QaReport =
    QaReports.missing("QA recorded incomplete because the QA agent step stopped: $reason")

  private fun settled(report: QaReport): VerifyStepDone.Settled =
    VerifyStepDone.Settled(
      if (report.verdict == QaVerdict.SKIPPED) WorkflowStepStatus.SKIPPED else WorkflowStepStatus.COMPLETED,
      mapOf(VerifyWorkflow.QA_REPORT to artifact(report)) +
        VerifyQaReport(VerifyQaOutcome.of(report.verdict), reason(report)).patch,
    )

  private fun artifact(report: QaReport): Map<String, Any?> =
    mapOf(
      VerifyWorkflow.CONTRACT_VERSION to VerifyWorkflow.ARTIFACT_CONTRACT_VERSION,
      VerifyWorkflow.VERDICT_FIELD to report.verdict.wireValue,
      QaOutcomePayloadKeys.OUTCOME to VerifyQaOutcome.of(report.verdict).wireValue,
      QaOutcomePayloadKeys.REASON to reason(report),
      QaOutcomePayloadKeys.REPORT_TEXT to report.text,
      QaOutcomePayloadKeys.CLEANUP to report.cleanup,
      VerifyWorkflow.WARNINGS to report.warnings,
    )

  private fun reason(report: QaReport): String? = report.reason.takeIf { report.verdict == QaVerdict.SKIPPED }

  private companion object {
    const val DISABLED_DETAIL = "QA is off for this verify run."
    const val MAX_RECORD_REASON = 200
    const val RETAINED_GUIDANCE =
      "Run `skill-bill qa lease release --invocation <id> --device <serial>` or delete the lease file under " +
        "<state dir>/qa/<repo-key>/leases/ (state dir: SKILL_BILL_STATE_DIR, else ~/.skill-bill) once no QA run " +
        "uses the device. Remove a retained QA checkout with `git worktree remove --force <path>`."
  }
}
