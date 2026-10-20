package skillbill.engine.operation.verify

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.qa.QaReceiptPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.model.execution.QaExpectedIdentity
import skillbill.engine.featuretask.model.execution.QaPolicy
import skillbill.engine.featuretask.model.execution.QaSkipReason
import skillbill.engine.featuretask.qa.execution.QaExecutionLifecycle
import skillbill.engine.featuretask.qa.receipt.QaIncompleteReason
import skillbill.engine.featuretask.qa.receipt.QaReceipt
import skillbill.engine.featuretask.qa.receipt.QaReceiptSettlement
import skillbill.engine.featuretask.qa.receipt.QaReceiptValidator
import skillbill.engine.featuretask.qa.receipt.QaRetainedResource
import skillbill.engine.featuretask.qa.receipt.QaVerdict
import skillbill.engine.featuretask.slot.qa.QaPromptSections
import skillbill.engine.operation.core.OperationStepResult
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.qa.isQaFailure
import skillbill.error.featuretask.qa.qaRepositoryUnreadable
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
) {
  val line: String get() = "QA outcome: ${outcome.wireValue}${reason?.let { " ($it)" }.orEmpty()}"

  val patch: Map<String, Any?>
    get() =
      mapOf(
        VerifyWorkflow.QA_OUTCOME to
          mapOf(
            VerifyWorkflow.CONTRACT_VERSION to VerifyWorkflow.ARTIFACT_CONTRACT_VERSION,
            QaReceiptPayloadKeys.OUTCOME to outcome.wireValue,
            QaReceiptPayloadKeys.REASON to reason,
          ),
      )

  companion object {
    fun at(
      run: VerifyRun,
      stepId: String,
      artifacts: Map<String, Any?>,
    ): VerifyQaReport {
      val qaSettled = run.steps.indexOf(stepId) > run.steps.indexOf(VerifyWorkflow.QA)
      return of(run.qa, artifacts[VerifyWorkflow.QA_RECEIPT].takeIf { qaSettled })
    }

    fun of(
      selection: VerifyQaSelection?,
      receipt: Any?,
    ): VerifyQaReport {
      if (selection == null) return VerifyQaReport(VerifyQaOutcome.NOT_SELECTED, null)
      val outcome = VerifyQaOutcome.fromWire(VerifyWorkflow.string(receipt, QaReceiptPayloadKeys.OUTCOME))
      return when {
        outcome != null -> VerifyQaReport(outcome, VerifyWorkflow.string(receipt, QaReceiptPayloadKeys.REASON))
        selection == VerifyQaSelection.ON -> VerifyQaReport(VerifyQaOutcome.ATTEMPTED, null)
        else -> VerifyQaReport(VerifyQaOutcome.SKIPPED, QaSkipReason.DISABLED_POLICY.wireValue)
      }
    }
  }
}

@Inject
class VerifyQaStep(
  private val execution: QaExecutionLifecycle,
  private val settlement: QaReceiptSettlement,
  private val validator: QaReceiptValidator,
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
        else -> settled(disabled(run))
      }
    } catch (failure: SkillBillRuntimeException) {
      failure.rethrowUnless(failure.isQaFailure)
      unavailable(run, "QA could not finish and was recorded incomplete: ${failure.message.orEmpty()}")
    }

  private fun disabled(run: VerifyRun): QaReceipt {
    val receipt = validator.skipped(QaSkipReason.DISABLED_POLICY, DISABLED_DETAIL, revisionExpected(run), QaPolicy.OFF)
    settlement.recordDiagnostics(receipt)
    return receipt
  }

  private fun revisionExpected(run: VerifyRun): QaExpectedIdentity {
    val tree =
      when (val identity = gitOperations.revisionTreeIdentity(run.context.repoRoot, run.target.headRevision)) {
        is WorkflowGitOperationResult.Ok -> identity.value.trim()
        else -> throw qaRepositoryUnreadable("could not read tree identity: ${identity.error}")
      }
    return QaExpectedIdentity(run.target.headRevision, tree, "")
  }

  private fun unavailable(
    run: VerifyRun,
    warning: String,
  ): VerifyStepDone {
    RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "verify qa warning: ${warning.take(MAX_RECORD_REASON)}")
    val receipt =
      try {
        validator.incomplete(warning, revisionExpected(run), QaPolicy.ON)
      } catch (failure: SkillBillRuntimeException) {
        failure.rethrowUnless(failure.isQaFailure)
        return VerifyStepDone.Settled(
          WorkflowStepStatus.COMPLETED,
          mapOf(VerifyWorkflow.QA_RECEIPT to unreadableArtifact(run, warning)) +
            VerifyQaReport(VerifyQaOutcome.INCOMPLETE, QaIncompleteReason.OTHER.wireValue).patch,
        )
      }
    return settled(receipt)
  }

  private fun notStarted(
    run: VerifyRun,
    detail: String,
  ): VerifyStepDone {
    RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "verify qa warning: ${detail.take(MAX_RECORD_REASON)}")
    val receipt = validator.skipped(QaSkipReason.ISOLATION_UNAVAILABLE, detail, revisionExpected(run), QaPolicy.ON)
    return settled(receipt.copy(warnings = listOf(detail)))
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
        else -> return notStarted(run, "QA was skipped: could not resolve the QA worktree path: ${resolved.error}")
      }
    val added = gitOperations.addDetachedWorktree(repoRoot, relativePath, head)
    if (added !is WorkflowGitOperationResult.Ok) {
      val failure = "QA was skipped: could not create the QA worktree at $head: ${added.error}"
      val stale = removeWorktree(repoRoot, worktree)
      return notStarted(run, if (stale.isEmpty()) failure else "$failure ${guidance(worktree)}")
    }
    var retained = emptyList<QaRetainedResource>()
    val attempt =
      try {
        attempt(run, worktree, prior)
      } finally {
        retained = removeWorktree(repoRoot, worktree)
      }
    val receipt = validator.withRetainedResources(attempt.receipt, retained, guidance(worktree), attempt.expected)
    settlement.recordDiagnostics(receipt)
    return settled(receipt)
  }

  private fun attempt(
    run: VerifyRun,
    worktree: Path,
    prior: Map<String, String>,
  ): VerifyQaAttempt {
    val settled =
      execution.withExecution(worktree, QaPolicy.ON) { qa ->
        when (val target = qa.target) {
          is QaExecutionTarget.Skipped -> {
            val skipped = validator.skipped(target.reason, target.detail, qa.expected, qa.policy)
            VerifyQaAttempt(skipped, qa.expected)
          }
          is QaExecutionTarget.Supported -> launch(run, worktree, qa, prior)
        }
      }
    val attempt = settled.value
    val receipt =
      validator.withRetainedResources(attempt.receipt, settled.retainedResources, LEASE_GUIDANCE, settled.expected)
    return attempt.copy(receipt = receipt.copy(warnings = receipt.warnings + settled.warnings))
  }

  private fun launch(
    run: VerifyRun,
    worktree: Path,
    qa: QaExecutionContext,
    prior: Map<String, String>,
  ): VerifyQaAttempt {
    val directive =
      VerifyPromptSections.qaDirective(worktree, run.target.headRevision) + QaPromptSections.supplement(qa)
    val context = run.context.copy(repoRoot = worktree, additionalWritableDirectories = listOf(qa.workspace.directory))
    val receipt =
      when (val step = run.context.steps.runReportOnly(context, VerifyPromptSections.QA_STEP, directive, prior)) {
        is OperationStepResult.Failed -> stopped(qa, step.reason)
        is OperationStepResult.Refused -> stopped(qa, step.refusal.reason)
        is OperationStepResult.Settled -> settlement.settle(qa)
      }
    return VerifyQaAttempt(receipt, qa.expected)
  }

  private fun stopped(
    qa: QaExecutionContext,
    reason: String,
  ): QaReceipt =
    validator.incomplete(
      "QA recorded incomplete because the QA agent step stopped: $reason",
      qa.expected,
      qa.policy,
      QaIncompleteReason.INTERRUPTED,
    )

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

  private fun settled(receipt: QaReceipt): VerifyStepDone.Settled =
    VerifyStepDone.Settled(
      if (receipt.verdict == QaVerdict.SKIPPED) WorkflowStepStatus.SKIPPED else WorkflowStepStatus.COMPLETED,
      mapOf(VerifyWorkflow.QA_RECEIPT to artifact(receipt)) +
        VerifyQaReport(VerifyQaOutcome.of(receipt.verdict), reason(receipt)).patch,
    )

  private fun artifact(receipt: QaReceipt): Map<String, Any?> =
    mapOf(
      VerifyWorkflow.CONTRACT_VERSION to VerifyWorkflow.ARTIFACT_CONTRACT_VERSION,
      VerifyWorkflow.VERDICT_FIELD to receipt.verdict.wireValue,
      QaReceiptPayloadKeys.OUTCOME to VerifyQaOutcome.of(receipt.verdict).wireValue,
      QaReceiptPayloadKeys.REASON to reason(receipt),
      QaReceiptPayloadKeys.REASON_DETAIL to receipt.reasonDetail,
      QaReceiptPayloadKeys.HEAD_REVISION to receipt.headRevision,
      QaReceiptPayloadKeys.TREE_IDENTITY to receipt.treeIdentity,
      QaReceiptPayloadKeys.CLEANUP to receipt.cleanup.outcome.wireValue,
      QaReceiptPayloadKeys.RETAINED_RESOURCES to receipt.cleanup.retainedResources.map { "${it.kind} ${it.identity}" },
      VerifyWorkflow.FINDINGS to findingLines(receipt),
      VerifyWorkflow.WARNINGS to receipt.warnings,
    )

  private fun unreadableArtifact(
    run: VerifyRun,
    warning: String,
  ): Map<String, Any?> =
    mapOf(
      VerifyWorkflow.CONTRACT_VERSION to VerifyWorkflow.ARTIFACT_CONTRACT_VERSION,
      VerifyWorkflow.VERDICT_FIELD to QaVerdict.INCOMPLETE.wireValue,
      QaReceiptPayloadKeys.OUTCOME to VerifyQaOutcome.INCOMPLETE.wireValue,
      QaReceiptPayloadKeys.REASON to QaIncompleteReason.OTHER.wireValue,
      QaReceiptPayloadKeys.REASON_DETAIL to warning,
      QaReceiptPayloadKeys.HEAD_REVISION to run.target.headRevision,
      VerifyWorkflow.FINDINGS to emptyList<String>(),
      VerifyWorkflow.WARNINGS to listOf(warning),
    )

  private fun reason(receipt: QaReceipt): String? = receipt.skipReason?.wireValue ?: receipt.incompleteReason?.wireValue

  private fun findingLines(receipt: QaReceipt): List<String> {
    val wire = (receipt.wire[QaReceiptPayloadKeys.FINDINGS] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
    return receipt.findings.map { finding ->
      val detail = wire.firstOrNull { it[QaReceiptPayloadKeys.ID] == finding.id }
      val criteria = finding.affectedCriteria.joinToString(",").ifEmpty { NONE }
      val evidence = finding.evidencePaths.joinToString(",").ifEmpty { NONE }
      "- [${finding.id}] criteria=$criteria | expected: ${detail?.get(QaReceiptPayloadKeys.EXPECTED) ?: NONE} | " +
        "observed: ${detail?.get(QaReceiptPayloadKeys.OBSERVED) ?: NONE} | evidence=$evidence"
    }
  }

  private data class VerifyQaAttempt(
    val receipt: QaReceipt,
    val expected: QaExpectedIdentity,
  )

  private companion object {
    const val WORKTREE_ROOT = "skill-bill/verify-qa"
    const val WORKTREE_KIND = "worktree"
    const val DISABLED_DETAIL = "QA is off for this verify run."
    const val NONE = "none"
    const val MAX_RECORD_REASON = 200
    const val LEASE_GUIDANCE =
      "Run `skill-bill qa lease release --invocation <id> --device <serial>` or delete the lease file under " +
        "<state dir>/qa/<repo-key>/leases/ (state dir: SKILL_BILL_STATE_DIR, else ~/.skill-bill) once no QA run " +
        "uses the device."
  }
}
