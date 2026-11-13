package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.model.execution.MonitorExecutionContext
import skillbill.engine.featuretask.phaserun.monitor.MonitorCleanupOwnership
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorRunFailureCode
import skillbill.engine.featuretask.phaserun.monitor.monitorOutcomeDetail
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.isCooperativeCancellation
import skillbill.ports.idestatus.model.StandaloneMonitorCleanup
import skillbill.ports.monitor.model.MonitorBranchIdentity
import skillbill.ports.monitor.model.MonitorOutcomeKind
import skillbill.ports.monitor.model.MonitorPrimaryOutcome
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorRetentionReason
import skillbill.ports.monitor.model.MonitorSettlement
import skillbill.ports.monitor.model.MonitorTargetIdentity
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause

internal fun earlyReport(
  selector: String,
  identity: MonitorTargetIdentity?,
): List<String> = listOfNotNull("Selector: $selector", identity?.let(::targetLine))

internal fun earlySetupReport(
  selector: String,
  identity: MonitorTargetIdentity?,
  settlement: MonitorSettlement?,
): List<String> = earlyReport(selector, identity) + listOfNotNull(settlement?.let { "Cleanup: ${cleanupLine(it)}" })

internal fun settleMonitorCleanup(
  cleanup: MonitorCleanupOwnership,
  outcome: Result<PhaseRunResult>,
): MonitorSettlement {
  val primary = primaryOutcomeOf(outcome)
  return if (outcome.getOrNull() is PhaseRunResult.Completed) {
    cleanup.deferUntilFinalize(primary)
  } else {
    cleanup.settle(primary)
  }
}

public fun primaryOutcomeOf(outcome: Result<PhaseRunResult>): MonitorPrimaryOutcome =
  outcome.fold(
    onSuccess = { result ->
      when (result) {
        is PhaseRunResult.Completed -> MonitorPrimaryOutcome(MonitorOutcomeKind.COMPLETED, "completed")
        is PhaseRunResult.Blocked -> {
          val providerLimitPause = FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(result.reason)
          MonitorPrimaryOutcome(
            if (providerLimitPause) MonitorOutcomeKind.PAUSED else MonitorOutcomeKind.BLOCKED,
            result.reason,
          )
        }
      }
    },
    onFailure = { failure ->
      MonitorPrimaryOutcome(
        if (failure.isCooperativeCancellation()) MonitorOutcomeKind.CANCELLED else MonitorOutcomeKind.RUN_FAILED,
        monitorOutcomeDetail(failure.message, failure::class.java.simpleName),
      )
    },
  )

public fun monitorRunFailure(
  failure: Throwable,
  context: MonitorExecutionContext,
  settlement: MonitorSettlement?,
  followedHeads: List<String> = emptyList(),
): Throwable {
  if (settlement == null || failure !is Exception || failure.isCooperativeCancellation()) return failure
  return SkillBillRuntimeException(
    code = (failure as? SkillBillRuntimeException)?.code ?: StandaloneMonitorRunFailureCode.RUN_FAILED,
    message =
      (
        listOf(failure.message?.takeIf(String::isNotBlank) ?: failure::class.java.simpleName) +
          monitorTargetReport(context, settlement, followedHeads)
      ).joinToString("\n"),
    cause = failure,
  )
}

public fun MonitorSettlement.toStatusCleanup(): StandaloneMonitorCleanup =
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

public fun PhaseRunResult.withTargetReport(
  context: MonitorExecutionContext,
  settlement: MonitorSettlement?,
  followedHeads: List<String>,
): PhaseRunResult {
  val report = monitorTargetReport(context, settlement, followedHeads)
  return when (this) {
    is PhaseRunResult.Completed -> copy(targetReport = report)
    is PhaseRunResult.Blocked -> copy(targetReport = report)
  }
}

private fun monitorTargetReport(
  context: MonitorExecutionContext,
  settlement: MonitorSettlement?,
  followedHeads: List<String>,
): List<String> {
  val identity = context.target.identity
  val head = context.target.head.tip
  return listOfNotNull(
    targetLine(identity),
    "Repository: ${identity.repository.host}/${identity.repository.slug}",
    "Branch: ${head.ref} (${head.repository.host}/${head.repository.slug})",
    followedHeads
      .takeIf { identity is MonitorBranchIdentity && it.isNotEmpty() }
      ?.let { "Followed commits: ${it.joinToString()}" },
    "Worktree: ${context.worktree.path}",
    "Cleanup: ${cleanupLine(settlement)}",
  )
}

private fun targetLine(identity: MonitorTargetIdentity): String =
  when (identity) {
    is MonitorBranchIdentity -> "Branch target: ${identity.url}"
    is MonitorPullRequestIdentity -> "Pull request: ${identity.url}"
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
