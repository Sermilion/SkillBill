package skillbill.engine.featuretask.phaserun.qa

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.model.execution.QaPolicy
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.qa.execution.QaExecutionLifecycle
import skillbill.engine.featuretask.qa.receipt.QaIncompleteReason
import skillbill.engine.featuretask.qa.receipt.QaReceipt
import skillbill.engine.featuretask.qa.receipt.QaReceiptSettlement
import skillbill.engine.featuretask.qa.receipt.QaReceiptValidator
import skillbill.engine.featuretask.qa.receipt.QaVerdict
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.qa.isQaFailure
import skillbill.ports.idestatus.model.StandaloneQaOutcome

@Inject
class QaStandalonePhaseRun(
  private val execution: QaExecutionLifecycle,
  private val settlement: QaReceiptSettlement,
  private val validator: QaReceiptValidator,
) {
  fun run(
    request: PhaseRunRequest,
    invocationId: String,
    launch: (PhaseRunRequest) -> PhaseRunResult,
  ): PhaseRunResult =
    try {
      execute(request, invocationId, launch)
    } catch (failure: SkillBillRuntimeException) {
      failure.rethrowUnless(failure.isQaFailure)
      notStarted(invocationId, "QA could not start and was skipped: ${failure.message.orEmpty()}")
    }

  private fun execute(
    request: PhaseRunRequest,
    invocationId: String,
    launch: (PhaseRunRequest) -> PhaseRunResult,
  ): PhaseRunResult {
    val settled =
      execution.withExecution(request.repoRoot, QaPolicy.STANDALONE, invocationId) { qa ->
        when (val target = qa.target) {
          is QaExecutionTarget.Skipped ->
            QaAttempt(validator.skipped(target.reason, target.detail, qa.expected, qa.policy), emptyList())
          is QaExecutionTarget.Supported ->
            when (val result = launch(request.copy(qaExecution = qa))) {
              is PhaseRunResult.Blocked -> QaAttempt(stopped(qa, result.reason), result.completedStepIds)
              is PhaseRunResult.Completed -> QaAttempt(settlement.settle(qa), result.completedStepIds)
            }
        }
      }
    val outcome = settled.value
    val receipt =
      validator.withRetainedResources(outcome.receipt, settled.retainedResources, RETAINED_GUIDANCE, settled.expected)
    settlement.recordDiagnostics(outcome.receipt)
    val warnings = receipt.warnings + settled.warnings
    return PhaseRunResult.Completed(
      invocationId = invocationId,
      completedStepIds = outcome.completedStepIds,
      reviewResult = null,
      value = receipt.reasonDetail ?: "QA verdict: ${receipt.verdict.wireValue}.",
      targetReport = warnings.map(::warningLine) + receipt.targetReport(),
      qaOutcome = receipt.qaOutcome(),
      qaReport = receipt.qaReport(),
    )
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

  private fun notStarted(
    invocationId: String,
    warning: String,
  ): PhaseRunResult.Completed =
    PhaseRunResult.Completed(
      invocationId = invocationId,
      completedStepIds = emptyList(),
      reviewResult = null,
      value = warning,
      targetReport = listOf(warningLine(warning), "Verdict: ${QaVerdict.SKIPPED.wireValue}"),
      qaOutcome = StandaloneQaOutcome.SKIPPED,
    )

  private fun warningLine(warning: String): String = "Warning: $warning"

  private data class QaAttempt(
    val receipt: QaReceipt,
    val completedStepIds: List<String>,
  )

  private companion object {
    const val RETAINED_GUIDANCE =
      "Run `skill-bill qa lease release --invocation <id> --device <serial>` or delete the lease file under " +
        "<state dir>/qa/<repo-key>/leases/ (state dir: SKILL_BILL_STATE_DIR, else ~/.skill-bill) once no QA run " +
        "uses the device."
  }
}
