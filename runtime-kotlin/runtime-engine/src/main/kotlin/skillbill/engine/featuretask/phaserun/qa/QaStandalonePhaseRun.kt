package skillbill.engine.featuretask.phaserun.qa

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.model.execution.QaPolicy
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.qa.execution.QaExecutionLifecycle
import skillbill.engine.featuretask.qa.receipt.QaReceipt
import skillbill.engine.featuretask.qa.receipt.QaReceiptSettlement
import skillbill.engine.featuretask.qa.receipt.QaReceiptValidator
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.qa.isQaFailure
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

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
      blocked(request, invocationId, failure.message.orEmpty())
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
            QaAttempt.Settled(
              validator.skipped(target.reason, target.detail, qa.expected, qa.policy),
              emptyList(),
            )
          is QaExecutionTarget.Supported ->
            when (val result = launch(request.copy(qaExecution = qa))) {
              is PhaseRunResult.Blocked -> QaAttempt.Stopped(result)
              is PhaseRunResult.Completed -> QaAttempt.Settled(settlement.settle(qa), result.completedStepIds)
            }
        }
      }
    return when (val outcome = settled.value) {
      is QaAttempt.Stopped -> outcome.result
      is QaAttempt.Settled -> {
        val receipt =
          validator.withRetainedResources(
            outcome.receipt,
            settled.retainedResources,
            RETAINED_GUIDANCE,
            settled.expected,
          )
        settlement.recordDiagnostics(outcome.receipt)
        PhaseRunResult.Completed(
          invocationId = invocationId,
          completedStepIds = outcome.completedStepIds,
          reviewResult = null,
          value = receipt.reasonDetail ?: "QA verdict: ${receipt.verdict.wireValue}.",
          targetReport = receipt.targetReport(),
          qaOutcome = receipt.qaOutcome(),
        )
      }
    }
  }

  private fun blocked(
    request: PhaseRunRequest,
    invocationId: String,
    reason: String,
  ): PhaseRunResult.Blocked {
    val stepId = SkeletonDefinition.byId(request.definitionId).stepIds.first()
    return PhaseRunResult.Blocked(invocationId, emptyList(), null, stepId, reason)
  }

  private sealed interface QaAttempt {
    data class Settled(
      val receipt: QaReceipt,
      val completedStepIds: List<String>,
    ) : QaAttempt

    data class Stopped(val result: PhaseRunResult.Blocked) : QaAttempt
  }

  private companion object {
    const val RETAINED_GUIDANCE =
      "Run `skill-bill qa lease release --invocation <id> --device <serial>` or delete the lease file under " +
        ".git/skill-bill/qa/leases/ once no QA run uses the device."
  }
}
