package skillbill.engine.featuretask.runloop.finalization.standalone

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorLedger
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorRunRecords
import skillbill.engine.featuretask.phaserun.monitor.StandalonePendingRepair
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.finalization.FeatureTaskRuntimeRunLoopMonitorCycle
import skillbill.engine.featuretask.runloop.finalization.FeatureTaskRuntimeRunLoopMonitorCycle.block
import skillbill.engine.featuretask.runloop.finalization.FeatureTaskRuntimeRunLoopMonitorCycle.complete
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimePhaseStartReentry
import skillbill.engine.featuretask.runloop.observability.ciStillRunning
import skillbill.engine.featuretask.slot.attempt.PhaseRuntimeFinalizationContext
import skillbill.engine.featuretask.slot.attempt.persistFinalizationRequiredRunning
import skillbill.engine.featuretask.slot.state.PhaseStandaloneMonitorOperations
import skillbill.engine.featuretask.slot.state.PullRequestCiOutcome
import skillbill.engine.featuretask.slot.state.StandalonePublication
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict

internal object FeatureTaskRuntimeRunLoopStandaloneMonitorCycle {
  internal fun PhaseRuntimeFinalizationContext.runStandaloneMonitor(
    run: PhaseRun,
    operations: PhaseStandaloneMonitorOperations,
  ): PhaseOutcome {
    val iteration = progress.phase(run.phaseId).nextIteration
    persistFinalizationRequiredRunning(run, iteration)?.let { return it }
    observability.started(
      run.phaseId,
      run.resolvedAgent.resolvedAgentId,
      iteration,
      run.modelDirective,
      FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
    )
    val ledger = standaloneLedger() ?: return block(run, iteration, MISSING_LEDGER)
    val target = ledger.execution.target.pullRequest
    return when (
      val outcome =
        operations.observe(
          ledger,
        ) { url, pending -> observability.ciStillRunning(run.phaseId, url, pending) }
    ) {
      PullRequestCiOutcome.Merged ->
        complete(
          run,
          iteration,
          monitorOutput(
            run,
            "The pull request was merged.",
            "${target.targetKey} was merged, so monitoring is complete.",
          ),
        )
      PullRequestCiOutcome.Passed ->
        complete(
          run,
          iteration,
          monitorOutput(
            run,
            "CI passed for the pull request.",
            "CI passed on ${target.targetKey} at ${ledger.acknowledgedHeadSha}: every check passed, was skipped, " +
              "or was cancelled.",
          ),
        )
      PullRequestCiOutcome.NoCiConfigured ->
        complete(
          run,
          iteration,
          monitorOutput(run, "No CI checks configured.", "No CI checks were reported for ${target.targetKey}."),
        )
      PullRequestCiOutcome.NoPullRequest -> block(run, iteration, "Pull request ${target.url} was not found.")
      is PullRequestCiOutcome.Failed -> failed(run, iteration, operations, ledger, outcome)
      PullRequestCiOutcome.Conflicted ->
        routeToRepair(
          run,
          iteration,
          ledger,
          RepairRoute(
            operations.conflictEvidence(ledger),
            "${target.targetKey} conflicts with its base ${ledger.execution.target.base.ref}. Merge the base and " +
              "resolve every conflict.",
            FeatureTaskRuntimeVerdict.PROGRESS,
          ),
        )
      is PullRequestCiOutcome.Blocked -> block(run, iteration, outcome.reason)
      is PullRequestCiOutcome.Unavailable -> {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Standalone monitor could not observe ${target.targetKey}: ${outcome.reason}",
        )
        block(run, iteration, outcome.reason)
      }
    }
  }

  internal fun PhaseRuntimeFinalizationContext.runStandalonePublication(
    run: PhaseRun,
    operations: PhaseStandaloneMonitorOperations,
  ): PhaseOutcome {
    val iteration = progress.phase(run.phaseId).nextIteration
    persistFinalizationRequiredRunning(run, iteration)?.let { return it }
    observability.started(
      run.phaseId,
      run.resolvedAgent.resolvedAgentId,
      iteration,
      run.modelDirective,
      FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
    )
    val ledger = standaloneLedger() ?: return block(run, iteration, MISSING_LEDGER)
    val (summary, value) =
      when (val publication = operations.publish(ledger, repairWorktree(ledger))) {
        StandalonePublication.NothingPending ->
          "No repair is pending; nothing was staged, committed, fetched, or pushed." to
            "No repair was published for ${ledger.execution.target.pullRequest.targetKey}."
        is StandalonePublication.Published ->
          "The repair was published." to
            "The remote acknowledged repair commit ${publication.commitSha} on ${ledger.execution.target.head.tip.ref}."
        is StandalonePublication.Blocked -> return block(run, iteration, publication.reason)
      }
    return complete(run, iteration, monitorOutput(run, summary, value))
  }

  internal fun PhaseRuntimeFinalizationContext.publishStandaloneRepair(
    operations: PhaseStandaloneMonitorOperations,
    reportedFixed: Boolean,
  ): String? {
    val ledger = standaloneLedger() ?: return MISSING_LEDGER
    if (!reportedFixed) {
      val worktree = ledger.execution.worktree
      return "The repair agent did not report a fixed repair, so nothing was committed or published. Work is " +
        "preserved in ${worktree.path} on branch ${worktree.branch}."
    }
    ledger.completePendingRepair()
    val publication = operations.publish(ledger, repairWorktree(ledger))
    return (publication as? StandalonePublication.Blocked)?.reason
  }

  private fun PhaseRuntimeFinalizationContext.repairWorktree(ledger: StandaloneMonitorLedger) =
    StandaloneRepairCommit(gitOperations, ledger.execution.worktree.path)

  private fun PhaseRuntimeFinalizationContext.failed(
    run: PhaseRun,
    iteration: Int,
    operations: PhaseStandaloneMonitorOperations,
    ledger: StandaloneMonitorLedger,
    outcome: PullRequestCiOutcome.Failed,
  ): PhaseOutcome {
    val target = ledger.execution.target.pullRequest
    val head = ledger.acknowledgedHeadSha
    if (outcome.headSha != head) {
      return block(
        run,
        iteration,
        "The failing checks belong to ${outcome.headSha ?: "an unknown commit"}, not the head $head this " +
          "invocation last published.",
      )
    }
    val checks = failingChecksProse(outcome.failingChecks)
    val confirmsPublishedRepair =
      ledger.publishedRepairs.isNotEmpty() && head !in progress.monitorFailedRepairCommitShas
    return routeToRepair(
      run,
      iteration,
      ledger,
      RepairRoute(
        operations.failureEvidence(ledger, outcome.failingChecks),
        if (confirmsPublishedRepair) {
          "CI confirmed a failed published repair commit '$head' on ${target.targetKey}. $checks"
        } else {
          "CI is failing on ${target.targetKey} at observed commit '$head'. $checks"
        },
        if (confirmsPublishedRepair) FeatureTaskRuntimeVerdict.CI_FAILED else FeatureTaskRuntimeVerdict.PROGRESS,
      ),
    )
  }

  private fun PhaseRuntimeFinalizationContext.routeToRepair(
    run: PhaseRun,
    iteration: Int,
    ledger: StandaloneMonitorLedger,
    route: RepairRoute,
  ): PhaseOutcome {
    val head = ledger.acknowledgedHeadSha
    val verdict = route.verdict
    ledger.beginRepair(StandalonePendingRepair(route.evidence, head))
    val summary =
      if (verdict == FeatureTaskRuntimeVerdict.CI_FAILED) {
        "CI confirmed that a published repair failed."
      } else {
        "CI or merge repair work is starting."
      }
    val qualifiedFailedRepairSha = head.takeIf { verdict == FeatureTaskRuntimeVerdict.CI_FAILED }
    return complete(run, iteration, monitorOutput(run, summary, route.prose, verdict), qualifiedFailedRepairSha)
  }

  private data class RepairRoute(
    val evidence: String,
    val prose: String,
    val verdict: FeatureTaskRuntimeVerdict,
  )

  private fun failingChecksProse(checks: List<PullRequestCheck>): String =
    "Failing checks: " + checks.joinToString("; ") { check -> "${check.name} (${check.link})" } + "."

  private fun monitorOutput(
    run: PhaseRun,
    summary: String,
    value: String,
    verdict: FeatureTaskRuntimeVerdict? = null,
  ): String = FeatureTaskRuntimeRunLoopMonitorCycle.monitorOutput(run.phaseId, summary, value, verdict)

  private fun PhaseRuntimeFinalizationContext.standaloneLedger(): StandaloneMonitorLedger? =
    (recorder as? StandaloneMonitorRunRecords)?.standaloneMonitorLedger

  private const val MISSING_LEDGER = "Standalone monitoring requires its prepared execution ledger."
}
