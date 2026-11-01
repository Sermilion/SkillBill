package skillbill.engine.featuretask.runloop.finalization

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.branch.baseBranchOrDefault
import skillbill.engine.featuretask.lifecycle.branch.requirePublishableBranch
import skillbill.engine.featuretask.phase.core.MonitorRepairAccounting
import skillbill.engine.featuretask.phase.core.MonitorRepairPayloadKeys
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
import skillbill.engine.featuretask.slot.state.ExpectedCiHead
import skillbill.engine.featuretask.slot.state.PhaseCiObservation
import skillbill.engine.featuretask.slot.state.PullRequestCiOutcome
import skillbill.error.core.catchingNonCancellation
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
    val (repairAssociation, expectedHead) = publishedHeadExpectation(repairSteps, iteration)
    val outcome = observePublishedHead(run, observation, branch, expectedHead)
    return when (outcome) {
      PullRequestCiOutcome.Merged ->
        complete(run, iteration, mergedMonitorOutput(run.phaseId, branch))
      is PullRequestCiOutcome.Passed ->
        complete(
          run,
          iteration,
          monitorOutput(
            run.phaseId,
            "CI passed for published commit '${outcome.headSha}'.",
            "CI passed for published commit '${outcome.headSha}' on branch '$branch': every check passed, " +
              "was skipped, or was cancelled.",
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
        failedCiOutcome(run, iteration, branch, outcome, repairAssociation)
      }
      PullRequestCiOutcome.Conflicted -> {
        observation.recordMergeConflict(request.issueKey, baseBranch)
        routeToRepair(
          run,
          iteration,
          RepairRoute(conflictProse(branch, baseBranch), FeatureTaskRuntimeVerdict.PROGRESS),
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

  private fun PhaseRuntimeFinalizationContext.repairAccounting(repairSteps: MonitorRepairStepRoles) =
    MonitorRepairAccounting(
      repairSteps,
      gitOperations,
      recorder::producerOutput,
      phaseSettlementService::findEnvelope,
      diagnostics,
    )

  private fun PhaseRuntimeFinalizationContext.publishedHeadExpectation(
    repairSteps: MonitorRepairStepRoles,
    iteration: Int,
  ): Pair<RepairPublicationAssociation, ExpectedCiHead> {
    val accounting = repairAccounting(repairSteps)
    val ledger = recorder.loadPhaseLedger(request.workflowId).orEmpty()
    val outputs = progress.outputs()
    val repairAssociation =
      accounting.publishedRepairCommitSha(
        request,
        iteration,
        recorder.loadPhaseRecords(request.workflowId).orEmpty(),
        ledger,
        outputs,
      )
    val expectedHead =
      ExpectedCiHead(
        accounting.publishedHeadBeforeMonitor(request, iteration, ledger, outputs),
        (repairAssociation as? RepairPublicationAssociation.Published)?.pullRequestEdit?.repairStartedAt,
      )
    return repairAssociation to expectedHead
  }

  private fun PhaseRuntimeFinalizationContext.observePublishedHead(
    run: PhaseRun,
    observation: PhaseCiObservation,
    branch: String,
    expectedHead: ExpectedCiHead,
  ): PullRequestCiOutcome =
    observation.watch(
      request.repoRoot,
      branch,
      onCiRunning = { pullRequestUrl, pending ->
        observability.ciStillRunning(run.phaseId, pullRequestUrl, pending)
      },
      expectedHead = expectedHead,
      onInconclusive = { reason ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Monitor kept polling after an inconclusive CI observation: $reason",
        )
      },
    )

  private fun PhaseRuntimeFinalizationContext.failedCiOutcome(
    run: PhaseRun,
    iteration: Int,
    branch: String,
    outcome: PullRequestCiOutcome.Failed,
    repairAssociation: RepairPublicationAssociation,
  ): PhaseOutcome {
    if (repairAssociation == RepairPublicationAssociation.Unchanged) {
      return block(
        run,
        iteration,
        "The completed repair published no source, merge, or pull request change, and CI still fails on branch " +
          "'$branch'. ${failingChecksList(outcome.failingChecks)}",
      )
    }
    val published = repairAssociation as? RepairPublicationAssociation.Published
    val qualified =
      published != null && outcome.headSha != null && outcome.headSha == published.commitSha &&
        published.repairKey !in progress.monitorFailedRepairCommitShas
    if (!qualified && repairAssociation !is RepairPublicationAssociation.NotStarted) {
      val reason = repairExclusionReason(repairAssociation, outcome.headSha)
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Monitor excluded a failing CI observation from attempt accounting: $reason.",
      )
    }
    val confirmed = published?.takeIf { qualified }
    return routeToRepair(
      run,
      iteration,
      RepairRoute(
        failingChecksProse(branch, outcome, confirmed),
        if (confirmed != null) FeatureTaskRuntimeVerdict.CI_FAILED else FeatureTaskRuntimeVerdict.PROGRESS,
        outcome.editableFieldsDigest,
      ),
      confirmed?.repairKey,
    )
  }

  private fun repairExclusionReason(
    association: RepairPublicationAssociation,
    observedHeadSha: String?,
  ): String =
    when (association) {
      RepairPublicationAssociation.Unavailable -> "repair publication association is unavailable"
      RepairPublicationAssociation.Unchanged -> "repair publication has no source, merge, or pull request change"
      RepairPublicationAssociation.NotStarted -> "repair failure is not associated with a published commit"
      is RepairPublicationAssociation.Published ->
        when (observedHeadSha) {
          null -> "the observed failing checks have no head identity"
          association.commitSha -> "the published repair failure was already counted"
          else -> "the observed failing head differs from the published repair"
        }
    }

  private data class RepairRoute(
    val value: String,
    val verdict: FeatureTaskRuntimeVerdict,
    val pullRequestFieldsDigest: String? = null,
  )

  private fun PhaseRuntimeFinalizationContext.routeToRepair(
    run: PhaseRun,
    iteration: Int,
    route: RepairRoute,
    qualifiedFailedRepairSha: String? = null,
  ): PhaseOutcome =
    complete(
      run,
      iteration,
      monitorOutput(
        run.phaseId,
        if (route.verdict == FeatureTaskRuntimeVerdict.CI_FAILED) {
          "CI confirmed that a published repair failed."
        } else {
          "CI or merge repair work is starting."
        },
        route.value,
        verdict = route.verdict,
        pullRequestFieldsDigest = route.pullRequestFieldsDigest,
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
    outcome: PullRequestCiOutcome.Failed,
    confirmedRepair: RepairPublicationAssociation.Published?,
  ): String =
    when {
      confirmedRepair?.pullRequestEdit != null ->
        "CI confirmed a failed published pull request edit '${confirmedRepair.repairKey}' on branch '$branch'. "
      confirmedRepair != null ->
        "CI confirmed a failed published repair commit '${outcome.headSha}' on branch '$branch'. "
      else -> "CI is failing on branch '$branch' at observed commit '${outcome.headSha ?: "unknown"}'. "
    } + failingChecksList(outcome.failingChecks)

  private fun failingChecksList(checks: List<PullRequestCheck>): String =
    "Failing checks: " + checks.joinToString("; ") { check -> "${check.name} (${check.link})" } + "."

  internal fun monitorOutput(
    phaseId: String,
    summary: String,
    value: String,
    verdict: FeatureTaskRuntimeVerdict?,
    pullRequestFieldsDigest: String? = null,
  ): String {
    val producedOutputs = linkedMapOf<String, Any?>(SharedPayloadKeys.VALUE to value)
    pullRequestFieldsDigest?.let { producedOutputs[MonitorRepairPayloadKeys.PULL_REQUEST_FIELDS_DIGEST] = it }
    val envelope =
      linkedMapOf<String, Any?>(
        SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
        SharedPayloadKeys.PHASE_ID to phaseId,
        SharedPayloadKeys.STATUS to STATUS_COMPLETED,
        SharedPayloadKeys.SUMMARY to summary,
        SharedPayloadKeys.PRODUCED_OUTPUTS to producedOutputs,
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

  internal fun PhaseRuntimeFinalizationContext.complete(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    qualifiedFailedRepairSha: String? = null,
  ): PhaseOutcome {
    val normalizedOutput =
      catchingNonCancellation { NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(outputText, run.phaseId) }
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

  internal fun PhaseRuntimeFinalizationContext.block(
    run: PhaseRun,
    iteration: Int,
    reason: String,
  ): PhaseOutcome =
    blockAndPersistInPhase(
      phaseBlockArgs(run, iteration, reason, observability),
    )
}
