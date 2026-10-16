package skillbill.engine.featuretask.runloop.finalization

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.branch.baseBranchOrDefault
import skillbill.engine.featuretask.lifecycle.branch.requirePublishableBranch
import skillbill.engine.featuretask.phase.core.MonitorRepairAccounting
import skillbill.engine.featuretask.phase.core.MonitorRepairStepRoles
import skillbill.engine.featuretask.phase.core.RepairPublicationAssociation
import skillbill.engine.featuretask.phase.core.auditProseValue
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.phaseBlockArgs
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimePhaseStartReentry
import skillbill.engine.featuretask.runloop.observability.ciStillRunning
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.slot.attempt.PhaseRuntimeFinalizationContext
import skillbill.engine.featuretask.slot.attempt.blockAndPersistInPhase
import skillbill.engine.featuretask.slot.attempt.finalizationCoupledProgress
import skillbill.engine.featuretask.slot.attempt.persistFinalizationCompleted
import skillbill.engine.featuretask.slot.attempt.persistFinalizationRequiredRunning
import skillbill.engine.featuretask.slot.state.PhaseCiObservation
import skillbill.engine.featuretask.slot.state.PullRequestCiOutcome
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowQueries

internal object FeatureTaskRuntimeRunLoopMonitorCycle {
  internal fun PhaseRuntimeFinalizationContext.runDeclaredMonitorCycle(
    run: PhaseRun,
    observation: PhaseCiObservation,
    repairSteps: MonitorRepairStepRoles,
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
    val resolved = recorder.loadResolvedBranch(request.workflowId)
    val baseBranch = gitOperations.baseBranchOrDefault(request.repoRoot, resolved?.baseBranch)
    val branch = requirePublishableBranch(resolved?.branch, baseBranch)
    val outcome =
      observation.watch(request.repoRoot, branch) { pullRequestUrl, pending ->
        observability.ciStillRunning(run.phaseId, pullRequestUrl, pending)
      }
    return when (outcome) {
      PullRequestCiOutcome.Merged ->
        complete(run, iteration, mergedMonitorOutput(run.phaseId, branch))
      PullRequestCiOutcome.Passed ->
        complete(
          run,
          iteration,
          monitorOutput(
            run.phaseId,
            "CI passed for the pull request.",
            "CI passed on branch '$branch': every check on the pull request passed, was skipped, or was cancelled.",
            verdict = null,
          ),
        )
      PullRequestCiOutcome.NoCiConfigured ->
        complete(
          run,
          iteration,
          monitorOutput(
            run.phaseId,
            "No CI checks configured for the pull request head.",
            "No CI checks were reported for branch '$branch' within the registration grace period, so no CI is " +
              "configured for the pull request head.",
            verdict = null,
          ),
        )
      PullRequestCiOutcome.NoPullRequest -> settleWithoutPullRequest(run, iteration, branch)
      is PullRequestCiOutcome.Failed -> {
        observation.recordFailingChecks(request.issueKey, outcome.failingChecks)
        failedCiOutcome(run, iteration, branch, outcome, repairSteps)
      }
      PullRequestCiOutcome.Conflicted -> {
        observation.recordMergeConflict(request.issueKey, baseBranch)
        routeToRepair(
          run,
          iteration,
          conflictProse(branch, baseBranch),
          FeatureTaskRuntimeVerdict.PROGRESS,
        )
      }
      is PullRequestCiOutcome.Blocked -> block(run, iteration, outcome.reason)
      is PullRequestCiOutcome.Unavailable -> {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Monitor could not observe CI for branch '$branch': ${outcome.reason}",
        )
        block(run, iteration, outcome.reason)
      }
    }
  }

  private fun PhaseRuntimeFinalizationContext.failedCiOutcome(
    run: PhaseRun,
    iteration: Int,
    branch: String,
    outcome: PullRequestCiOutcome.Failed,
    repairSteps: MonitorRepairStepRoles,
  ): PhaseOutcome {
    val repairAssociation =
      MonitorRepairAccounting(
        repairSteps,
        gitOperations,
        recorder::producerOutput,
        phaseSettlementService::findEnvelope,
        diagnostics,
      ).publishedRepairCommitSha(
        request,
        iteration,
        recorder.loadPhaseRecords(request.workflowId).orEmpty(),
        recorder.loadPhaseLedger(request.workflowId).orEmpty(),
        progress.outputs(),
      )
    return when {
      repairAssociation is RepairPublicationAssociation.NotStarted ->
        routeToRepair(
          run,
          iteration,
          failingChecksProse(branch, outcome.failingChecks, outcome.headSha),
          FeatureTaskRuntimeVerdict.PROGRESS,
        )
      repairAssociation is RepairPublicationAssociation.Unavailable ->
        block(run, iteration, "The completed monitor repair lacks evidence to associate a published change.")
      repairAssociation is RepairPublicationAssociation.Unchanged ->
        block(run, iteration, "The completed monitor repair did not publish a source or merge change.")
      outcome.headSha == null ||
        outcome.headSha != (repairAssociation as RepairPublicationAssociation.Published).commitSha ->
        block(
          run,
          iteration,
          "The failing CI result could not be associated with the published repair commit " +
            "'${(repairAssociation as RepairPublicationAssociation.Published).commitSha}'.",
        )
      repairAssociation.commitSha in progress.monitorFailedRepairCommitShas ->
        routeToRepair(
          run,
          iteration,
          failingChecksProse(branch, outcome.failingChecks, outcome.headSha),
          FeatureTaskRuntimeVerdict.PROGRESS,
        )
      else ->
        routeToRepair(
          run,
          iteration,
          failingChecksProse(branch, outcome.failingChecks, outcome.headSha, publishedRepair = true),
          FeatureTaskRuntimeVerdict.CI_FAILED,
          repairAssociation.commitSha,
        )
    }
  }

  private fun PhaseRuntimeFinalizationContext.routeToRepair(
    run: PhaseRun,
    iteration: Int,
    value: String,
    verdict: FeatureTaskRuntimeVerdict,
    qualifiedFailedRepairSha: String? = null,
  ): PhaseOutcome =
    complete(
      run,
      iteration,
      monitorOutput(
        run.phaseId,
        if (verdict == FeatureTaskRuntimeVerdict.CI_FAILED) {
          "CI confirmed that a published repair failed."
        } else {
          "CI or merge repair work is starting."
        },
        value,
        verdict = verdict,
      ),
      qualifiedFailedRepairSha,
    )

  private fun conflictProse(
    branch: String,
    baseBranch: String,
  ): String =
    "The pull request for branch '$branch' conflicts with '$baseBranch'. " +
      "Merge '$baseBranch' into '$branch' and resolve every conflict."

  private fun mergedMonitorOutput(
    phaseId: String,
    branch: String,
  ): String =
    monitorOutput(
      phaseId,
      "The pull request was merged.",
      "The pull request for branch '$branch' was merged, so CI monitoring is complete.",
      verdict = null,
    )

  internal fun monitorCapExhaustionReason(
    loopId: String,
    progress: FeatureTaskRuntimeProgressSnapshotAccess?,
  ): String {
    if (progress?.monitorFailureEvidenceUnavailable == true) {
      return "Monitoring cannot continue because a completed monitor result lacks retained published-failure " +
        "association evidence. Recover the existing monitor output evidence before resuming."
    }
    val edge = FeatureTaskRuntimePhaseWorkflowQueries.backwardEdgeForLoop(loopId)
    val failedCommitShas = progress?.monitorFailedRepairCommitShas.orEmpty()
    val failedRepairReports =
      progress
        ?.outputs()
        ?.filter { output -> output.phaseId == edge?.fromPhaseId }.orEmpty()
        .mapNotNull(::monitorPhaseProseFor)
        .filter { value -> failedCommitShas.any(value::contains) }
        .distinct()
    val capSummary =
      when {
        failedCommitShas.size >= FeatureTaskRuntimePhaseWorkflowDefinition.MAX_MONITOR_FIX_ATTEMPTS ->
          "Monitoring stopped after ${failedCommitShas.size} CI-confirmed failed repair commit(s): " +
            failedCommitShas.sorted().joinToString(", ") { "'$it'" } + "."
        failedRepairReports.isNotEmpty() ->
          "Monitoring stopped at its repair cap with ${failedCommitShas.size} evidenced failed repair commit(s)."
        else ->
          "Monitoring stopped at its repair cap, but no commit-associated failure reports were retained."
      }
    val failingChecks =
      failedRepairReports.takeIf { it.isNotEmpty() }?.joinToString(" ")
        ?: monitorPhaseProse(progress, edge?.fromPhaseId)
    val lastFixSummary = monitorPhaseProse(progress, edge?.destinationPhaseId)
    return "$capSummary The run blocks rather than fixing past the cap. Last observed monitor result: " +
      (failingChecks ?: "The last failing checks were not recorded.") +
      " Last monitor_fix summary: " +
      (lastFixSummary ?: "none recorded.")
  }

  private fun monitorPhaseProse(
    progress: FeatureTaskRuntimeProgressSnapshotAccess?,
    phaseId: String?,
  ): String? =
    phaseId?.let { id -> progress?.phase(id)?.output }
      ?.let(::monitorPhaseProseFor)

  private fun monitorPhaseProseFor(output: FeatureTaskRuntimePhaseOutput): String? =
    output.normalizedOutput?.envelopeWireMap()?.let(::auditProseValue)

  private fun failingChecksProse(
    branch: String,
    checks: List<PullRequestCheck>,
    headSha: String?,
    publishedRepair: Boolean = false,
  ): String =
    (
      if (publishedRepair) {
        "CI confirmed a failed published repair commit '$headSha' on branch '$branch'. "
      } else {
        "CI is failing on branch '$branch' at observed commit '${headSha ?: "unknown"}'. "
      }
    ) + "Failing checks: " +
      checks.joinToString("; ") { check -> "${check.name} (${check.link})" } + "."

  private fun monitorOutput(
    phaseId: String,
    summary: String,
    value: String,
    verdict: FeatureTaskRuntimeVerdict?,
  ): String {
    val envelope =
      linkedMapOf<String, Any?>(
        SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        SharedPayloadKeys.PHASE_ID to phaseId,
        SharedPayloadKeys.STATUS to STATUS_COMPLETED,
        SharedPayloadKeys.SUMMARY to summary,
        SharedPayloadKeys.PRODUCED_OUTPUTS to mapOf(SharedPayloadKeys.VALUE to value),
      )
    if (verdict != null) envelope[SharedPayloadKeys.VERDICT] = verdict.wireValue
    return JsonCodec.mapToJsonString(envelope)
  }

  private fun PhaseRuntimeFinalizationContext.settleWithoutPullRequest(
    run: PhaseRun,
    iteration: Int,
    branch: String,
  ): PhaseOutcome =
    if (
      request.workflowId.isNotBlank() ||
      request.skeletonDefinition?.slots?.contains(PhaseSlot.PULL_REQUEST) != false
    ) {
      block(run, iteration, "No open pull request was found for branch '$branch' to watch.")
    } else {
      complete(
        run,
        iteration,
        monitorOutput(
          run.phaseId,
          "No open pull request for the branch.",
          "No open pull request was found for branch '$branch', so there is no CI to monitor.",
          verdict = null,
        ),
      )
    }

  private fun PhaseRuntimeFinalizationContext.complete(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    qualifiedFailedRepairSha: String? = null,
  ): PhaseOutcome {
    val normalizedOutput =
      runCatching { NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(outputText, run.phaseId) }
        .getOrElse { error ->
          return block(
            run,
            iteration,
            "Runtime-owned monitor settlement did not validate: ${error.message.orEmpty()}",
          )
        }
    if (!persistFinalizationCompleted(run, iteration, outputText, normalizedOutput, qualifiedFailedRepairSha)) {
      return FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
        finalizationCoupledProgress(),
        coupledRunTransitions,
        recorder,
        PhaseBlockRequest(
          run = run,
          attemptCount = iteration,
          reason = "Runtime-owned monitor settlement could not be persisted.",
          observability = observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        ),
      )
    }
    observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(
        run.phaseId,
        iteration,
        normalizedOutput.canonicalJson,
        normalizedOutput,
        null,
      ),
    )
  }

  private fun PhaseRuntimeFinalizationContext.block(
    run: PhaseRun,
    iteration: Int,
    reason: String,
  ): PhaseOutcome =
    blockAndPersistInPhase(
      phaseBlockArgs(run, iteration, reason, observability),
    )
}
