package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.model.execution.MonitorExecutionContext
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorRunFailureCode
import skillbill.engine.featuretask.phaserun.monitor.monitorOutcomeDetail
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.idestatus.model.StandaloneMonitorCleanup
import skillbill.ports.monitor.model.MonitorOutcomeKind
import skillbill.ports.monitor.model.MonitorPrimaryOutcome
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorRetentionReason
import skillbill.ports.monitor.model.MonitorSettlement

internal fun earlyReport(
  selector: String,
  pullRequest: MonitorPullRequestIdentity?,
): List<String> = listOfNotNull("Selector: $selector", pullRequest?.let { "Pull request: ${it.url}" })

internal fun earlySetupReport(
  selector: String,
  pullRequest: MonitorPullRequestIdentity?,
  settlement: MonitorSettlement?,
): List<String> = earlyReport(selector, pullRequest) + listOfNotNull(settlement?.let { "Cleanup: ${cleanupLine(it)}" })

internal fun primaryOutcomeOf(outcome: Result<PhaseRunResult>): MonitorPrimaryOutcome =
  outcome.fold(
    onSuccess = { result ->
      when (result) {
        is PhaseRunResult.Completed -> MonitorPrimaryOutcome(MonitorOutcomeKind.COMPLETED, "completed")
        is PhaseRunResult.Blocked -> MonitorPrimaryOutcome(MonitorOutcomeKind.BLOCKED, result.reason)
      }
    },
    onFailure = { failure ->
      MonitorPrimaryOutcome(
        if (failure is InterruptedException) MonitorOutcomeKind.CANCELLED else MonitorOutcomeKind.RUN_FAILED,
        monitorOutcomeDetail(failure.message, failure::class.java.simpleName),
      )
    },
  )

internal fun monitorRunFailure(
  failure: Throwable,
  context: MonitorExecutionContext,
  settlement: MonitorSettlement?,
): Throwable {
  if (settlement == null || failure !is Exception || failure is InterruptedException) return failure
  return SkillBillRuntimeException(
    code = (failure as? SkillBillRuntimeException)?.code ?: StandaloneMonitorRunFailureCode.RUN_FAILED,
    message =
      (
        listOf(failure.message?.takeIf(String::isNotBlank) ?: failure::class.java.simpleName) +
          monitorTargetReport(context, settlement)
      ).joinToString("\n"),
    cause = failure,
  )
}

internal fun MonitorSettlement.toStatusCleanup(): StandaloneMonitorCleanup =
  when (this) {
    MonitorSettlement.Released -> StandaloneMonitorCleanup.Released
    is MonitorSettlement.Retained ->
      StandaloneMonitorCleanup.Retained(
        reason,
        listOfNotNull(detail.takeIf(String::isNotBlank), path?.let { "Path: $it." })
          .joinToString(" ")
          .ifBlank { reason.wireValue },
      )
    is MonitorSettlement.FenceLost ->
      StandaloneMonitorCleanup.Retained(
        MonitorRetentionReason.UNOWNED_RESOURCE,
        detail.ifBlank { "The ownership fence was lost." },
      )
  }

internal fun PhaseRunResult.withTargetReport(
  context: MonitorExecutionContext,
  settlement: MonitorSettlement?,
): PhaseRunResult {
  val report = monitorTargetReport(context, settlement)
  return when (this) {
    is PhaseRunResult.Completed -> copy(targetReport = report)
    is PhaseRunResult.Blocked -> copy(targetReport = report)
  }
}

private fun monitorTargetReport(
  context: MonitorExecutionContext,
  settlement: MonitorSettlement?,
): List<String> {
  val pullRequest = context.target.pullRequest
  val head = context.target.head.tip
  return listOf(
    "Pull request: ${pullRequest.url}",
    "Repository: ${pullRequest.repository.host}/${pullRequest.repository.slug}",
    "Branch: ${head.ref} (${head.repository.host}/${head.repository.slug})",
    "Worktree: ${context.worktree.path}",
    "Cleanup: ${cleanupLine(settlement)}",
  )
}

private fun cleanupLine(settlement: MonitorSettlement?): String =
  when (settlement) {
    null -> "not settled"
    MonitorSettlement.Released -> "released (owned worktree, branch and refs removed)"
    is MonitorSettlement.Retained ->
      "retained (${settlement.reason.wireValue})" +
        (settlement.path?.let { " at $it" } ?: "") + ": ${settlement.detail}"
    is MonitorSettlement.FenceLost -> "not settled, ownership fence lost: ${settlement.detail}"
  }
