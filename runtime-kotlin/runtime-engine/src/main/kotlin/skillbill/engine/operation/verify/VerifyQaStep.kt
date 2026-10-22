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
import skillbill.engine.featuretask.qa.report.QaRetainedResource
import skillbill.engine.featuretask.qa.report.QaVerdict
import skillbill.engine.featuretask.slot.qa.QaPromptSections
import skillbill.engine.operation.core.OperationStepResult
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.qa.isQaFailure
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
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
  private val gitOperations: WorkflowGitOperations,
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

  private fun notStarted(detail: String): VerifyStepDone {
    RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "verify qa warning: ${detail.take(MAX_RECORD_REASON)}")
    val report = QaReports.skipped(QaSkipReason.ISOLATION_UNAVAILABLE, detail)
    return settled(report.copy(warnings = listOf(detail)))
  }

  private fun opted(
    run: VerifyRun,
    prior: Map<String, String>,
  ): VerifyStepDone {
    val repoRoot = run.context.repoRoot
    val head = run.target.headRevision
    val relativePath = "$WORKTREE_ROOT/${run.workflowId}"
    val worktree =
      when (val resolved = gitOperations.linkedWorktreePath(repoRoot, relativePath)) {
        is WorkflowGitOperationResult.Ok -> Path.of(resolved.value.trim())
        else -> return notStarted("QA was skipped: could not resolve the QA worktree path: ${resolved.error}")
      }
    val added = gitOperations.addDetachedWorktree(repoRoot, relativePath, head)
    if (added !is WorkflowGitOperationResult.Ok) {
      val failure = "QA was skipped: could not create the QA worktree at $head: ${added.error}"
      val stale = removeWorktree(repoRoot, worktree)
      return notStarted(if (stale.isEmpty()) failure else "$failure ${guidance(worktree)}")
    }
    var retained = emptyList<QaRetainedResource>()
    val attempt =
      try {
        attempt(run, worktree, prior)
      } finally {
        retained = removeWorktree(repoRoot, worktree)
      }
    val report = QaReports.withRetainedResources(attempt, retained, guidance(worktree))
    settlement.recordDiagnostics(report)
    return settled(report)
  }

  private fun attempt(
    run: VerifyRun,
    worktree: Path,
    prior: Map<String, String>,
  ): QaReport {
    val settled =
      execution.withExecution(worktree, QaPolicy.ON) { qa ->
        when (val target = qa.target) {
          is QaExecutionTarget.Skipped -> QaReports.skipped(target.reason, target.detail)
          is QaExecutionTarget.Supported -> launch(run, worktree, qa, prior)
        }
      }
    val report = QaReports.withRetainedResources(settled.value, settled.retainedResources, LEASE_GUIDANCE)
    return report.copy(warnings = report.warnings + settled.warnings)
  }

  private fun launch(
    run: VerifyRun,
    worktree: Path,
    qa: QaExecutionContext,
    prior: Map<String, String>,
  ): QaReport {
    val directive =
      VerifyPromptSections.qaDirective(worktree, run.target.headRevision) + QaPromptSections.supplement(qa)
    val context = run.context.copy(repoRoot = worktree, additionalWritableDirectories = listOf(qa.workspace.directory))
    return when (val step = run.context.steps.runReportOnly(context, VerifyPromptSections.QA_STEP, directive, prior)) {
      is OperationStepResult.Failed -> stopped(step.reason)
      is OperationStepResult.Refused -> stopped(step.refusal.reason)
      is OperationStepResult.Settled -> settlement.settle(qa)
    }
  }

  private fun stopped(reason: String): QaReport =
    QaReports.missing("QA recorded incomplete because the QA agent step stopped: $reason")

  private fun removeWorktree(
    repoRoot: Path,
    worktree: Path,
  ): List<QaRetainedResource> {
    val removed = gitOperations.removeWorktree(repoRoot, worktree)
    if (removed is WorkflowGitOperationResult.Ok) return emptyList()
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "verify qa worktree retained: seam=verify_qa_cleanup value_expected=removed value_used=retained " +
        "worktree=$worktree reason=${removed.error.take(MAX_RECORD_REASON)}",
    )
    return listOf(QaRetainedResource(WORKTREE_KIND, worktree.toString()))
  }

  private fun guidance(worktree: Path): String =
    "Run `git worktree remove --force $worktree` and `git worktree prune` once no QA run uses it."

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
    const val WORKTREE_ROOT = "skill-bill/verify-qa"
    const val WORKTREE_KIND = "worktree"
    const val DISABLED_DETAIL = "QA is off for this verify run."
    const val MAX_RECORD_REASON = 200
    const val LEASE_GUIDANCE =
      "Run `skill-bill qa lease release --invocation <id> --device <serial>` or delete the lease file under " +
        "<state dir>/qa/<repo-key>/leases/ (state dir: SKILL_BILL_STATE_DIR, else ~/.skill-bill) once no QA run " +
        "uses the device."
  }
}
