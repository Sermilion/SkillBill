package skillbill.engine.featuretask.runloop.finalization

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.phase.core.RepairPublicationAssociation
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.state.PhaseCiObservation
import skillbill.engine.featuretask.slot.state.PullRequestCiOutcome
import skillbill.engine.featuretask.slot.state.PullRequestCiSnapshot
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.idestatus.model.MonitorCiCheckBucket
import skillbill.ports.idestatus.model.MonitorCiCycleCheck
import skillbill.ports.idestatus.model.MonitorCiCycleOutcome
import skillbill.ports.idestatus.model.MonitorCiCycleRecord
import java.time.Instant

internal fun monitorCycleStartRecord(
  run: PhaseRun,
  iteration: Int,
  repairAssociation: RepairPublicationAssociation,
  now: Instant,
): MonitorCiCycleRecord =
  MonitorCiCycleRecord(
    cycle = iteration,
    phaseId = run.phaseId,
    startedAt = now,
    observedAt = now,
    repairCommitSha = (repairAssociation as? RepairPublicationAssociation.Published)?.commitSha,
  )

internal fun MonitorCiCycleRecord.observed(
  snapshot: PullRequestCiSnapshot,
  now: Instant,
): MonitorCiCycleRecord =
  copy(
    observedAt = now,
    checks = snapshot.checks.map(PullRequestCheck::toCycleCheck),
    headSha = snapshot.headSha,
    pullRequestNumber = snapshot.pullRequestNumber,
    pullRequestUrl = snapshot.pullRequestUrl,
  )

internal fun MonitorCiCycleRecord.finished(
  outcome: PullRequestCiOutcome,
  baseBranch: String,
  now: Instant,
): MonitorCiCycleRecord {
  val finished = copy(finishedAt = now)
  return when (outcome) {
    is PullRequestCiOutcome.Passed ->
      finished.copy(outcome = MonitorCiCycleOutcome.PASSED, headSha = headSha ?: outcome.headSha)
    is PullRequestCiOutcome.Failed ->
      finished.copy(outcome = MonitorCiCycleOutcome.FAILED, headSha = headSha ?: outcome.headSha)
    PullRequestCiOutcome.Conflicted ->
      finished.copy(outcome = MonitorCiCycleOutcome.CONFLICTED, conflictBaseBranch = baseBranch)
    PullRequestCiOutcome.Merged -> finished.copy(outcome = MonitorCiCycleOutcome.MERGED)
    PullRequestCiOutcome.NoChecksReported -> finished.copy(outcome = MonitorCiCycleOutcome.NO_CHECKS)
    PullRequestCiOutcome.NoPullRequest -> finished.copy(outcome = MonitorCiCycleOutcome.NO_PULL_REQUEST)
    is PullRequestCiOutcome.Blocked -> finished.copy(outcome = MonitorCiCycleOutcome.BLOCKED)
    is PullRequestCiOutcome.Unavailable -> finished.copy(outcome = MonitorCiCycleOutcome.UNAVAILABLE)
  }
}

internal fun recordMonitorCycleBestEffort(
  observation: PhaseCiObservation,
  workflowId: String,
  record: MonitorCiCycleRecord,
  diagnostics: RuntimeDiagnostics,
  what: String,
) {
  catchingNonCancellation { observation.recordCycle(workflowId, record) }.onFailure { error ->
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Monitor could not record CI cycle $what: ${error.message.orEmpty()}",
      error,
    )
  }
}

private fun PullRequestCheck.toCycleCheck(): MonitorCiCycleCheck =
  MonitorCiCycleCheck(
    name = name,
    bucket =
      when (bucket) {
        CheckBucket.PASS -> MonitorCiCheckBucket.PASS
        CheckBucket.SKIPPING -> MonitorCiCheckBucket.SKIPPING
        CheckBucket.FAIL -> MonitorCiCheckBucket.FAIL
        CheckBucket.CANCEL -> MonitorCiCheckBucket.CANCEL
        CheckBucket.PENDING -> MonitorCiCheckBucket.PENDING
      },
    link = link,
    startedAt = startedAt,
    completedAt = completedAt,
  )
