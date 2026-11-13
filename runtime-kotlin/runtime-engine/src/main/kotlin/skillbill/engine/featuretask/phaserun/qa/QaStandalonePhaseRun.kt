package skillbill.engine.featuretask.phaserun.qa

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.model.execution.QaPolicy
import skillbill.engine.featuretask.model.execution.QaSkipReason
import skillbill.engine.featuretask.phaserun.IntakeIssueKeys
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.qa.checklist.QaChecklistRecorder
import skillbill.engine.featuretask.qa.checklist.QaChecklistSeed
import skillbill.engine.featuretask.qa.execution.QaExecutionLifecycle
import skillbill.engine.featuretask.qa.report.QaReport
import skillbill.engine.featuretask.qa.report.QaReportSettlement
import skillbill.engine.featuretask.qa.report.QaReports
import skillbill.engine.featuretask.qa.report.QaRunDetailsMapping
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.qa.isQaFailure
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.workflow.taskruntime.model.qa.QaRunDetails
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause

@Inject
public class QaStandalonePhaseRun(
  private val execution: QaExecutionLifecycle,
  private val settlement: QaReportSettlement,
  private val checklist: QaChecklistRecorder,
) {
  internal fun run(
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
        val named = qa.copy(namedDevice = request.qaDevice)
        when (val target = qa.target) {
          is QaExecutionTarget.Skipped ->
            QaStandaloneSettlement.Recorded(
              attemptOf(named, QaReports.skipped(target.reason, target.detail), emptyList()),
            )
          is QaExecutionTarget.Supported -> {
            seedChecklist(request, named, target)
            when (val result = launch(request.copy(qaExecution = named))) {
              is PhaseRunResult.Blocked ->
                if (FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(result.reason)) {
                  QaStandaloneSettlement.ProviderLimitPause(result)
                } else {
                  QaStandaloneSettlement.Recorded(attemptOf(named, stopped(result.reason), result.completedStepIds))
                }
              is PhaseRunResult.Completed ->
                QaStandaloneSettlement.Recorded(attemptOf(named, settlement.settle(named), result.completedStepIds))
            }
          }
        }
      }
    return when (val outcome = settled.value) {
      is QaStandaloneSettlement.ProviderLimitPause -> outcome.blocked
      is QaStandaloneSettlement.Recorded -> {
        val attempt = outcome.attempt.withUnsettledStepWarning(invocationId)
        settlement.recordDiagnostics(attempt.report)
        val report =
          QaReports.withRetainedResources(attempt.report, settled.retainedResources, RETAINED_GUIDANCE)
            .let { it.copy(warnings = it.warnings + settled.warnings) }
        PhaseRunResult.Completed(
          invocationId = invocationId,
          completedStepIds = attempt.completedStepIds,
          reviewResult = null,
          value = report.summary(),
          targetReport = report.targetReport(),
          qaOutcome = report.qaOutcome(),
          qaReportText = report.text,
          qaDetails =
            QaRunDetailsMapping.standalone(
              attempt.report,
              attempt.directory,
              attempt.reportPath,
              settled.retainedResources,
            ),
        )
      }
    }
  }

  private fun seedChecklist(
    request: PhaseRunRequest,
    qa: QaExecutionContext,
    target: QaExecutionTarget.Supported,
  ) {
    checklist.seed(
      QaChecklistSeed(
        invocationId = qa.workspace.invocationId,
        invocationDirectory = qa.workspace.directory.toAbsolutePath().normalize().toString(),
        reportPath = qa.workspace.reportPath.toAbsolutePath().normalize().toString(),
        testedCommit = qa.expected.headRevision,
        targets = target.targets.flatMap { it.kinds }.map { it.wireValue }.distinct(),
        criteriaSource = IntakeIssueKeys.ofLeadingToken(request.intake),
      ),
    )
  }

  private fun QaAttempt.withUnsettledStepWarning(invocationId: String): QaAttempt {
    val unsettled = checklist.unsettledSteps(invocationId)
    if (unsettled.isEmpty()) return this
    val warning =
      "QA left ${unsettled.size} plan steps without a passed, failed or skipped status: ${unsettled.joinToString()}"
    return copy(report = report.copy(warnings = report.warnings + warning))
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
      qaDetails = QaRunDetails(skipReason = QaSkipReason.ISOLATION_UNAVAILABLE.wireValue),
    )
  }

  private fun attemptOf(
    qa: QaExecutionContext,
    report: QaReport,
    completedStepIds: List<String>,
  ): QaAttempt =
    QaAttempt(
      report,
      completedStepIds,
      qa.workspace.directory.toAbsolutePath().normalize().toString(),
      qa.workspace.reportPath.toAbsolutePath().normalize().toString(),
    )

  private data class QaAttempt(
    val report: QaReport,
    val completedStepIds: List<String>,
    val directory: String,
    val reportPath: String,
  )

  private sealed interface QaStandaloneSettlement {
    data class Recorded(val attempt: QaAttempt) : QaStandaloneSettlement

    data class ProviderLimitPause(val blocked: PhaseRunResult.Blocked) : QaStandaloneSettlement
  }

  private companion object {
    const val RETAINED_GUIDANCE =
      "Run `skill-bill qa lease release --invocation <id> --device <serial>` or delete the lease file under " +
        "<state dir>/qa/<repo-key>/leases/ (state dir: SKILL_BILL_STATE_DIR, else ~/.skill-bill) once no QA run " +
        "uses the device. Remove a retained QA checkout with `git worktree remove --force <path>`."
  }
}
