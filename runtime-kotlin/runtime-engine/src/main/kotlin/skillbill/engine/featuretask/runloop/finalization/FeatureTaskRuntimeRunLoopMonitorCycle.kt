package skillbill.engine.featuretask.runloop.finalization

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.branch.baseBranchOrDefault
import skillbill.engine.featuretask.lifecycle.branch.requirePublishableBranch
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
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowQueries

internal object FeatureTaskRuntimeRunLoopMonitorCycle {
  internal fun PhaseRuntimeFinalizationContext.runDeclaredMonitorCycle(
    run: PhaseRun,
    observation: PhaseCiObservation,
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
      observation.watch(request.repoRoot, branch) { pending ->
        observability.ciStillRunning(run.phaseId, pending.map(PullRequestCheck::name))
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
        routeToRepair(
          run,
          iteration,
          "CI is failing for the pull request.",
          failingChecksProse(branch, outcome.failingChecks),
        )
      }
      PullRequestCiOutcome.Conflicted -> {
        observation.recordMergeConflict(request.issueKey, baseBranch)
        routeToRepair(run, iteration, "The pull request has merge conflicts.", conflictProse(branch, baseBranch))
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

  private fun PhaseRuntimeFinalizationContext.routeToRepair(
    run: PhaseRun,
    iteration: Int,
    summary: String,
    value: String,
  ): PhaseOutcome =
    complete(
      run,
      iteration,
      monitorOutput(run.phaseId, summary, value, verdict = FeatureTaskRuntimeVerdict.CI_FAILED),
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
    edgeIteration: Int,
    progress: FeatureTaskRuntimeProgressSnapshotAccess?,
  ): String {
    val edge = FeatureTaskRuntimePhaseWorkflowQueries.backwardEdgeForLoop(loopId)
    val failingChecks = monitorPhaseProse(progress, edge?.fromPhaseId)
    val lastFixSummary = monitorPhaseProse(progress, edge?.destinationPhaseId)
    return "The pull request still needs repair after $edgeIteration fix attempt(s); " +
      "the run blocks rather than fixing past the cap. " +
      (failingChecks ?: "The last failing checks were not recorded.") +
      " Last monitor_fix summary: " +
      (lastFixSummary ?: "none recorded.")
  }

  private fun monitorPhaseProse(
    progress: FeatureTaskRuntimeProgressSnapshotAccess?,
    phaseId: String?,
  ): String? =
    phaseId?.let { id -> progress?.phase(id)?.output }
      ?.normalizedOutput
      ?.envelopeWireMap()
      ?.let(::auditProseValue)

  private fun failingChecksProse(
    branch: String,
    checks: List<PullRequestCheck>,
  ): String =
    "CI is failing on branch '$branch'. Failing checks: " +
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
    if (!persistFinalizationCompleted(run, iteration, outputText, normalizedOutput)) {
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
