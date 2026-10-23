package skillbill.engine.featuretask.phaserun.qa

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.model.execution.QaPolicy
import skillbill.engine.featuretask.model.execution.QaSkipReason
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.qa.execution.QaExecutionLifecycle
import skillbill.engine.featuretask.qa.report.QaReport
import skillbill.engine.featuretask.qa.report.QaReportSettlement
import skillbill.engine.featuretask.qa.report.QaReports
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.qa.isQaFailure
import skillbill.ports.idestatus.model.StandaloneQaOutcome

@Inject
class QaStandalonePhaseRun(
  private val execution: QaExecutionLifecycle,
  private val settlement: QaReportSettlement,
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
          is QaExecutionTarget.Skipped -> QaAttempt(QaReports.skipped(target.reason, target.detail), emptyList())
          is QaExecutionTarget.Supported ->
            when (val result = launch(request.copy(qaExecution = qa))) {
              is PhaseRunResult.Blocked -> QaAttempt(stopped(result.reason), result.completedStepIds)
              is PhaseRunResult.Completed -> QaAttempt(settlement.settle(qa), result.completedStepIds)
            }
        }
      }
    val outcome = settled.value
    settlement.recordDiagnostics(outcome.report)
    val report =
      QaReports.withRetainedResources(outcome.report, settled.retainedResources, RETAINED_GUIDANCE)
        .let { it.copy(warnings = it.warnings + settled.warnings) }
    return PhaseRunResult.Completed(
      invocationId = invocationId,
      completedStepIds = outcome.completedStepIds,
      reviewResult = null,
      value = report.summary(),
      targetReport = report.targetReport(),
      qaOutcome = report.qaOutcome(),
      qaReportText = report.text,
    )
  }

  private fun stopped(reason: String): QaReport =
    QaReports.missing("QA recorded incomplete because the QA agent step stopped: $reason")

  private fun notStarted(
    invocationId: String,
    warning: String,
  ): PhaseRunResult.Completed {
    val report = QaReports.skipped(QaSkipReason.ISOLATION_UNAVAILABLE, warning)
    settlement.recordDiagnostics(report)
    return PhaseRunResult.Completed(
      invocationId = invocationId,
      completedStepIds = emptyList(),
      reviewResult = null,
      value = warning,
      targetReport = listOf("Warning: $warning") + report.targetReport(),
      qaOutcome = StandaloneQaOutcome.SKIPPED,
      qaReportText = report.text,
    )
  }

  private data class QaAttempt(
    val report: QaReport,
    val completedStepIds: List<String>,
  )

  private companion object {
    const val RETAINED_GUIDANCE =
      "Run `skill-bill qa lease release --invocation <id> --device <serial>` or delete the lease file under " +
        "<state dir>/qa/<repo-key>/leases/ (state dir: SKILL_BILL_STATE_DIR, else ~/.skill-bill) once no QA run " +
        "uses the device. Remove a retained QA checkout with `git worktree remove --force <path>`."
  }
}
