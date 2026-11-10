package skillbill.engine.featuretask.runloop.attempt

import skillbill.agent.model.PhaseOutput
import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProducerOutputRead
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityRepairAppliedWrite
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWrite
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWriteResult
import skillbill.engine.featuretask.model.phase.ProducerOutputQueryArgs
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseSafetyPolicy
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibility
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibilitySnapshot
import skillbill.engine.featuretask.phase.planning.PlanBundleSnapshots
import skillbill.engine.featuretask.phase.planning.PlanBundleTarget
import skillbill.engine.featuretask.phase.planning.feasibilityAcceptedLines
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.planning.PlanBundleStagingPromotion
import skillbill.engine.featuretask.runloop.planning.PromotionRequest
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchRuntimeContext
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowPathContentIdentitiesResult
import skillbill.text.sha256HexUtf8
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundleOrigin
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityApprovalCandidate
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityConflict
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityIdentity
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunch
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchKind
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchOutcome
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityRepositoryBaseline
import skillbill.workflow.taskruntime.model.planning.blockPlanBundleFeasibilityLaunch
import skillbill.workflow.taskruntime.model.planning.refusePlanBundleFeasibilityRepair
import skillbill.workflow.taskruntime.model.planning.refusePlanBundleFeasibilityReview
import skillbill.workflow.taskruntime.model.planning.settlePlanBundleFeasibilityRepair
import skillbill.workflow.taskruntime.model.planning.settlePlanBundleFeasibilityReview
import java.nio.file.Path

internal sealed interface FeasibilityLaunchRecovery {
  data class Ready(val state: FeatureTaskRuntimePlanBundleFeasibilityState) : FeasibilityLaunchRecovery

  data class Blocked(val reason: String) : FeasibilityLaunchRecovery
}

internal data class FeasibilityRecoverySnapshot(
  val state: FeatureTaskRuntimePlanBundleFeasibilityState,
  val snapshot: PlanBundleFeasibilitySnapshot,
  val attempt: Int,
  val staged: Path?,
) {
  val launch: PlanBundleFeasibilityLaunch get() = requireNotNull(state.activeLaunch)
}

internal fun recoverActiveFeasibilityLaunch(
  context: PhaseAttemptLaunchRuntimeContext,
  run: PhaseRun,
  currentAttempt: Int,
  state: FeatureTaskRuntimePlanBundleFeasibilityState,
  snapshot: PlanBundleFeasibilitySnapshot,
): FeasibilityLaunchRecovery {
  val launch = state.activeLaunch ?: return FeasibilityLaunchRecovery.Ready(state)
  val stagingRoot =
    context.decompositionPlanner.planStagingRoot(run.request.repoRoot.toAbsolutePath().normalize())
      .resolve(run.request.workflowId)
  val staged =
    launch.repairDirectory?.let(Path::of)?.toAbsolutePath()?.normalize()
      ?.takeIf { it.startsWith(stagingRoot) }
  val recovery = FeasibilityRecoverySnapshot(state, snapshot, currentAttempt, staged)
  val violation =
    feasibilityRepositoryViolation(
      context,
      run.request.repoRoot,
      launch,
      recoveryAllowedPaths(context.decompositionPlanner, run, recovery),
    ) ?: recoveryBundleViolation(context.decompositionPlanner, run, recovery)
  if (violation != null) {
    return blockInterruptedLaunch(
      context,
      run,
      recovery,
      violation,
    )
  }
  return if (launch.kind == PlanBundleFeasibilityLaunchKind.REVIEW || launch.intendedAfterIdentity == null) {
    recoverUnsettledReceipt(context, run, recovery)
  } else {
    recoverRepairIntent(context, run, recovery)
  }
}

private fun recoveryAllowedPaths(
  planner: FeatureTaskRuntimeDecompositionPlanner,
  run: PhaseRun,
  recovery: FeasibilityRecoverySnapshot,
): Set<String> {
  val launch = recovery.launch
  if (launch.kind != PlanBundleFeasibilityLaunchKind.REPAIR ||
    recovery.staged != null && launch.intendedAfterIdentity == null
  ) {
    return emptySet()
  }
  val root = run.request.repoRoot.toAbsolutePath().normalize()
  return recovery.snapshot.manifest.subtasks.filter { it.status.decompositionStatus() == DecompositionStatus.PENDING }
    .mapTo(mutableSetOf()) {
      root.relativize(planner.bundlePath(root, it.specPath).toAbsolutePath().normalize()).toString()
    }
}

private fun recoveryBundleViolation(
  planner: FeatureTaskRuntimeDecompositionPlanner,
  run: PhaseRun,
  recovery: FeasibilityRecoverySnapshot,
): String? {
  val launch = recovery.launch
  val snapshot = recovery.snapshot
  if (launch.kind == PlanBundleFeasibilityLaunchKind.REVIEW) {
    return if (snapshot.identity != launch.baselineIdentity) {
      "The interrupted read-only feasibility review changed its admitted bundle."
    } else {
      retainedStagingViolation(planner, run, recovery, launch.baselineIdentity)
    }
  }
  val protectedFailure = repairBaselineViolation(launch.baselineIdentity, snapshot)
  return protectedFailure ?: stagedRepairBaselineViolation(recovery) ?: repairStagingViolation(planner, run, recovery)
}

private fun stagedRepairBaselineViolation(recovery: FeasibilityRecoverySnapshot): String? {
  val launch = recovery.launch
  if (recovery.staged == null) return null
  return when {
    launch.intendedAfterIdentity == null && recovery.snapshot.identity != launch.baselineIdentity ->
      "Canonical bundle files no longer match the staged-repair admission baseline."
    launch.intendedAfterIdentity != null && launch.repairBeforeIdentity != null &&
      launch.repairBeforeIdentity != launch.baselineIdentity ->
      "The staged repair intent replaced its original canonical baseline."
    else -> null
  }
}

private fun retainedStagingViolation(
  planner: FeatureTaskRuntimeDecompositionPlanner,
  run: PhaseRun,
  recovery: FeasibilityRecoverySnapshot,
  expected: PlanBundleFeasibilityIdentity,
): String? {
  val directory = recovery.staged?.takeIf { planner.bundleTree(it).isNotEmpty() } ?: return null
  val staged = PlanBundleFeasibility.captureInDirectory(planner, run.request.repoRoot, run.request.issueKey, directory)
  return if (staged.identity != expected || !matchesFeasibilityBundleFiles(planner, directory, staged)) {
    "Interrupted feasibility staging bytes or paths disagree with its retained identity."
  } else {
    null
  }
}

private fun repairStagingViolation(
  planner: FeatureTaskRuntimeDecompositionPlanner,
  run: PhaseRun,
  recovery: FeasibilityRecoverySnapshot,
): String? {
  val launch = recovery.launch
  launch.intendedAfterIdentity?.let { return retainedStagingViolation(planner, run, recovery, it) }
  val directory = recovery.staged?.takeIf { planner.bundleTree(it).any(planner::isBundleFile) } ?: return null
  val staged = PlanBundleFeasibility.captureInDirectory(planner, run.request.repoRoot, run.request.issueKey, directory)
  return if (repairBaselineViolation(launch.baselineIdentity, staged) != null ||
    !matchesFeasibilityBundleFiles(planner, directory, staged)
  ) {
    "Interrupted repair staging changed protected bundle authority or paths."
  } else {
    null
  }
}

private fun recoverUnsettledReceipt(
  context: PhaseAttemptLaunchRuntimeContext,
  run: PhaseRun,
  recovery: FeasibilityRecoverySnapshot,
): FeasibilityLaunchRecovery {
  val state = recovery.state
  val launch = recovery.launch
  val retained =
    when (launch.kind) {
      PlanBundleFeasibilityLaunchKind.REVIEW -> recoverRetainedReviewReceipt(context, run, recovery)
      PlanBundleFeasibilityLaunchKind.REPAIR -> recoverRetainedRepairOutput(context, run, recovery)
    }
  if (retained != null) return retained
  val retainedReason =
    context.recorder.loadPhaseRecords(run.request.workflowId)?.get(run.phaseId)
      ?.takeIf { it.attemptCount == launch.attempt }?.blockedReason?.takeIf(String::isNotBlank)
  val reason = retainedReason ?: "The admitted process ended without a settleable receipt."
  val refused =
    when (launch.kind) {
      PlanBundleFeasibilityLaunchKind.REVIEW -> refusePlanBundleFeasibilityReview(state, launch.transitionId, reason)
      PlanBundleFeasibilityLaunchKind.REPAIR -> refusePlanBundleFeasibilityRepair(state, launch.transitionId, reason)
    } ?: return FeasibilityLaunchRecovery.Blocked("The interrupted feasibility launch could not be closed.")
  return writeRecoveredFeasibilityState(
    context,
    run,
    recovery,
    refused,
  )
}

private sealed interface RecoveredRepairProjection {
  data class Ready(val snapshot: PlanBundleFeasibilitySnapshot) : RecoveredRepairProjection

  data class Refused(val reason: String) : RecoveredRepairProjection
}

private fun recoverRepairIntent(
  context: PhaseAttemptLaunchRuntimeContext,
  run: PhaseRun,
  recovery: FeasibilityRecoverySnapshot,
): FeasibilityLaunchRecovery {
  val intended = requireNotNull(recovery.launch.intendedAfterIdentity)
  val projection =
    when {
      recovery.snapshot.identity == intended -> RecoveredRepairProjection.Ready(recovery.snapshot)
      recovery.snapshot.identity == (recovery.launch.repairBeforeIdentity ?: recovery.launch.baselineIdentity) ->
        reapplyRetainedRepair(context.decompositionPlanner, run, recovery)
      else ->
        RecoveredRepairProjection.Refused(
          "Stored bundle bytes match neither the admitted baseline nor the retained repair intent.",
        )
    }
  if (projection is RecoveredRepairProjection.Refused) {
    return blockInterruptedLaunch(
      context,
      run,
      recovery,
      projection.reason,
    )
  }
  projection as RecoveredRepairProjection.Ready
  val violation =
    if (projection.snapshot.identity != intended) {
      "Recovered bundle bytes do not match the retained repair intent."
    } else {
      feasibilityRepositoryViolation(
        context,
        run.request.repoRoot,
        recovery.launch,
        recoveryAllowedPaths(context.decompositionPlanner, run, recovery),
      ) ?: retainedStagingTextViolation(context.decompositionPlanner, recovery, intended)
    }
  return if (violation != null) {
    blockInterruptedLaunch(
      context,
      run,
      recovery,
      violation,
    )
  } else {
    recordRecoveredRepair(context, run, recovery)
  }
}

private fun reapplyRetainedRepair(
  planner: FeatureTaskRuntimeDecompositionPlanner,
  run: PhaseRun,
  recovery: FeasibilityRecoverySnapshot,
): RecoveredRepairProjection {
  val intended = requireNotNull(recovery.launch.intendedAfterIdentity)
  val intendedTexts =
    intended.subtasks.associate { subtask ->
      subtask.id to (
        subtask.rawSpecText ?: return RecoveredRepairProjection.Refused(
          "The retained repair intent lacks original replacement text for subtask ${subtask.id}.",
        )
      )
    }
  val proposed = recovery.snapshot.copy(specTexts = intendedTexts)
  if (proposed.identity != intended) {
    return RecoveredRepairProjection.Refused(
      "The retained repair replacement texts do not reproduce the intended identity.",
    )
  }
  retainedStagingTextViolation(planner, recovery, intended)?.let { return RecoveredRepairProjection.Refused(it) }
  val replacements =
    recovery.snapshot.manifest.subtasks
      .filter { it.status.decompositionStatus() == DecompositionStatus.PENDING }.associate { subtask ->
        planner.bundlePath(run.request.repoRoot, subtask.specPath).toAbsolutePath().normalize() to
          intendedTexts.getValue(subtask.id)
      }
  return if (planner.applyFeasibilityRepair(
      run.request.repoRoot,
      run.request.issueKey,
      recovery.snapshot,
      proposed,
      replacements,
    )
  ) {
    RecoveredRepairProjection.Ready(PlanBundleFeasibility.capture(planner, run.request.repoRoot, run.request.issueKey))
  } else {
    RecoveredRepairProjection.Refused(
      "The retained feasibility repair could not be safely projected from its original baseline.",
    )
  }
}

private fun retainedStagingTextViolation(
  planner: FeatureTaskRuntimeDecompositionPlanner,
  recovery: FeasibilityRecoverySnapshot,
  intended: PlanBundleFeasibilityIdentity,
): String? {
  val directory = recovery.staged ?: return null
  return recovery.snapshot.manifest.subtasks.filter { it.status.decompositionStatus() == DecompositionStatus.PENDING }
    .firstNotNullOfOrNull { subtask ->
      val file = directory.resolve(Path.of(subtask.specPath).fileName)
      val intendedText = intended.subtasks.firstOrNull { it.id == subtask.id }?.rawSpecText
      "Staged repair bytes disagree with the retained intent.".takeIf {
        planner.isBundleFile(file) && planner.specText(file) != intendedText
      }
    }
}

private fun recordRecoveredRepair(
  context: PhaseAttemptLaunchRuntimeContext,
  run: PhaseRun,
  recovery: FeasibilityRecoverySnapshot,
): FeasibilityLaunchRecovery {
  val launch = recovery.launch
  val intended = requireNotNull(launch.intendedAfterIdentity)
  val value =
    launch.intendedRepairValue ?: return blockInterruptedLaunch(
      context,
      run,
      recovery,
      "The retained repair value is missing.",
    )
  val proposed =
    settlePlanBundleFeasibilityRepair(recovery.state, launch.transitionId, intended, value)
      ?: return blockInterruptedLaunch(
        context,
        run,
        recovery,
        "The retained repair transition is invalid.",
      )
  val bundle = recoveredRepairBundle(context, run)
  return when (
    val result =
      context.recorder.recordFeasibilityRepairApplied(
        PlanBundleFeasibilityRepairAppliedWrite(
          run.request.workflowId,
          run.phaseId,
          recovery.attempt,
          recovery.state.revision,
          launch.transitionId,
          intended,
          bundle,
          proposed,
        ),
      )
  ) {
    is PlanBundleFeasibilityStateWriteResult.Applied -> {
      cleanupRecoveredStaging(context, run, recovery.staged)
      FeasibilityLaunchRecovery.Ready(result.state)
    }
    is PlanBundleFeasibilityStateWriteResult.Replayed -> {
      cleanupRecoveredStaging(context, run, recovery.staged)
      FeasibilityLaunchRecovery.Ready(result.state)
    }
    is PlanBundleFeasibilityStateWriteResult.Existing ->
      blockInterruptedLaunch(
        context,
        run,
        recovery,
        "The repaired bundle record already exists without an applied intent.",
      )
    is PlanBundleFeasibilityStateWriteResult.Refused ->
      blockInterruptedLaunch(
        context,
        run,
        recovery,
        "The retained repair could not be recorded: ${result.reason}",
      )
  }
}

private fun recoveredRepairBundle(
  context: PhaseAttemptLaunchRuntimeContext,
  run: PhaseRun,
): FeatureTaskRuntimePlanBundle {
  val planner = context.decompositionPlanner
  val written =
    planner.verifyAuthoredBundle(
      run.request.repoRoot,
      run.request.issueKey,
      run.request.runInvariants,
      run.request.protectedSpecSha256 == null,
      context.diagnostics,
    )
  return PlanBundleSnapshots(planner).capture(
    run.request.repoRoot,
    written,
    FeatureTaskRuntimePlanBundleOrigin.EXISTING,
  )
}

internal fun cleanupSettledFeasibilityStaging(
  context: PhaseAttemptLaunchRuntimeContext,
  run: PhaseRun,
  state: FeatureTaskRuntimePlanBundleFeasibilityState,
): String? {
  val settled =
    state.settledLaunches.lastOrNull { it.kind == PlanBundleFeasibilityLaunchKind.REPAIR }
      ?.takeIf {
        it.outcome == PlanBundleFeasibilityLaunchOutcome.COMPLETED &&
          it.repairDirectory != null && it.intendedAfterIdentity != null
      } ?: return null
  val directory = Path.of(checkNotNull(settled.repairDirectory)).toAbsolutePath().normalize()
  val planner = context.decompositionPlanner
  val target = PlanBundleStagingPromotion.targetFor(planner, run) as? PlanBundleTarget.Staged
  if (target == null || target.directory.toAbsolutePath().normalize() != directory ||
    planner.bundleTree(directory).none(planner::isBundleFile)
  ) {
    return null
  }
  val canonical = PlanBundleFeasibility.capture(planner, run.request.repoRoot, run.request.issueKey)
  val staged = PlanBundleFeasibility.captureInDirectory(planner, run.request.repoRoot, run.request.issueKey, directory)
  if (canonical.identity != settled.intendedAfterIdentity || staged.identity != settled.intendedAfterIdentity ||
    !matchesFeasibilityBundleFiles(planner, directory, staged)
  ) {
    return "Retained feasibility repair staging differs from its applied intent and was preserved."
  }
  PlanBundleStagingPromotion.finishStaging(
    planner,
    context.diagnostics,
    PromotionRequest(target, run.request.repoRoot, run.resolvedAgent.resolvedAgentId, parentSpecProtected = true),
  )
  return null
}

private fun recoverRetainedReviewReceipt(
  context: PhaseAttemptLaunchRuntimeContext,
  run: PhaseRun,
  recovery: FeasibilityRecoverySnapshot,
): FeasibilityLaunchRecovery? {
  val retained = retainedFeasibilityOutput(context, run, recovery.launch) ?: return null
  if (retained.normalized.status != WorkflowStepStatus.COMPLETED.wireValue) {
    return refuseRetainedLaunch(
      context,
      run,
      recovery,
      "The admitted review ended with ${retained.normalized.status} output. " +
        "Original phase envelope: ${retained.normalized.canonicalJson}",
    )
  }
  return when (val parsed = PlanBundleFeasibility.parseReviewReceipt(recovery.snapshot, retained.value)) {
    is PlanBundleFeasibility.ReviewParseResult.Rejected -> refuseRetainedLaunch(context, run, recovery, parsed.reason)
    is PlanBundleFeasibility.ReviewParseResult.Accepted ->
      settleRecoveredReview(context, run, recovery, retained, parsed.receipt)
  }
}

private fun refuseRetainedLaunch(
  context: PhaseAttemptLaunchRuntimeContext,
  run: PhaseRun,
  recovery: FeasibilityRecoverySnapshot,
  reason: String,
): FeasibilityLaunchRecovery {
  val refused =
    when (recovery.launch.kind) {
      PlanBundleFeasibilityLaunchKind.REVIEW ->
        refusePlanBundleFeasibilityReview(recovery.state, recovery.launch.transitionId, reason)
      PlanBundleFeasibilityLaunchKind.REPAIR ->
        refusePlanBundleFeasibilityRepair(recovery.state, recovery.launch.transitionId, reason)
    } ?: return FeasibilityLaunchRecovery.Blocked("The retained output refusal did not match its admission.")
  return writeRecoveredFeasibilityState(context, run, recovery, refused)
}

private fun settleRecoveredReview(
  context: PhaseAttemptLaunchRuntimeContext,
  run: PhaseRun,
  recovery: FeasibilityRecoverySnapshot,
  retained: RetainedFeasibilityOutput,
  receipt: PlanBundleFeasibility.ReviewReceipt,
): FeasibilityLaunchRecovery {
  val launch = recovery.launch
  if (recovery.snapshot.identity != launch.baselineIdentity) {
    return blockInterruptedLaunch(
      context,
      run,
      recovery,
      "The retained review receipt no longer matches its admitted baseline.",
    )
  }
  val candidate = if (receipt.approved) recoveredApprovalCandidate(context, run, recovery, retained) else null
  if (receipt.approved && candidate == null) {
    return FeasibilityLaunchRecovery.Blocked(
      "The retained approval receipt belongs to an earlier phase attempt; preserve it and require a full review.",
    )
  }
  val proposed =
    settlePlanBundleFeasibilityReview(
      recovery.state,
      launch.transitionId,
      feasibilityAcceptedLines(launch.baselineIdentity, receipt),
      receipt.lines.filter { it.conflict }.map { PlanBundleFeasibilityConflict(it.ref, it.originalLine) },
      receipt.originalValue,
    )?.copy(approvalCandidate = candidate)
      ?: return FeasibilityLaunchRecovery.Blocked("The retained review receipt did not match its admission.")
  return writeRecoveredFeasibilityState(context, run, recovery, proposed)
}

private fun recoveredApprovalCandidate(
  context: PhaseAttemptLaunchRuntimeContext,
  run: PhaseRun,
  recovery: FeasibilityRecoverySnapshot,
  retained: RetainedFeasibilityOutput,
): PlanBundleFeasibilityApprovalCandidate? {
  val launch = recovery.launch
  val phaseRecord =
    context.recorder.loadPhaseRecords(run.request.workflowId)?.get(run.phaseId)
      ?.takeIf { it.attemptCount == launch.attempt && it.attemptCount == recovery.attempt } ?: return null
  return PlanBundleFeasibilityApprovalCandidate(
    sourceRevision = recovery.state.revision + 1,
    immutableFingerprint = recovery.snapshot.fingerprint,
    transitionId = launch.transitionId,
    phaseId = launch.phaseId,
    attempt = launch.attempt,
    reviewerAgentId = phaseRecord.resolvedAgentId,
    normalizedEnvelope = retained.normalized.canonicalJson,
    fileManifestBefore = phaseRecord.fileManifestBefore,
    fileManifestAfter = phaseRecord.fileManifestAfter,
    fileManifestIntroduced = phaseRecord.fileManifestIntroduced,
  )
}

private fun recoverRetainedRepairOutput(
  context: PhaseAttemptLaunchRuntimeContext,
  run: PhaseRun,
  recovery: FeasibilityRecoverySnapshot,
): FeasibilityLaunchRecovery? {
  val retained = retainedFeasibilityOutput(context, run, recovery.launch) ?: return null
  if (retained.normalized.status != WorkflowStepStatus.COMPLETED.wireValue) {
    return refuseRetainedLaunch(
      context,
      run,
      recovery,
      "The admitted repair ended with ${retained.normalized.status} output. " +
        "Original phase envelope: ${retained.normalized.canonicalJson}",
    )
  }
  val staged = recovery.staged
  if (staged != null &&
    context.decompositionPlanner.bundleTree(staged).none(context.decompositionPlanner::isBundleFile)
  ) {
    return blockInterruptedLaunch(
      context,
      run,
      recovery,
      "The retained repair output has no staged spec files or persisted intended replacement text.",
    )
  }
  val candidate =
    staged?.let {
      PlanBundleFeasibility.captureInDirectory(
        context.decompositionPlanner,
        run.request.repoRoot,
        run.request.issueKey,
        it,
      )
    } ?: recovery.snapshot
  return recoverRetainedRepairCandidate(context, run, recovery, retained, candidate)
}

private fun recoverRetainedRepairCandidate(
  context: PhaseAttemptLaunchRuntimeContext,
  run: PhaseRun,
  recovery: FeasibilityRecoverySnapshot,
  retained: RetainedFeasibilityOutput,
  candidate: PlanBundleFeasibilitySnapshot,
): FeasibilityLaunchRecovery {
  val violation = retainedRepairCandidateViolation(context, recovery, candidate)
  if (violation != null) return blockInterruptedLaunch(context, run, recovery, violation)
  if (retained.value.lineSequence().firstOrNull() != PlanBundleFeasibility.REPAIRED) {
    return refuseRetainedLaunch(
      context,
      run,
      recovery,
      "The retained repair output has no valid repaired receipt header.",
    )
  }
  val currentAttempt = recovery.attempt
  val state = recovery.state
  val launch = recovery.launch
  val canonical = recovery.snapshot
  val staged = recovery.staged
  val prepared =
    state.copy(
      revision = state.revision + 1,
      activeLaunch =
        launch.copy(
          intendedAfterIdentity = candidate.identity,
          intendedRepairValue = retained.value,
          repairBeforeIdentity = if (staged != null) launch.baselineIdentity else canonical.identity,
        ),
    )
  return when (
    val result =
      context.recorder.writePlanBundleFeasibility(
        PlanBundleFeasibilityStateWrite(
          run.request.workflowId,
          run.phaseId,
          currentAttempt,
          state.revision,
          launch.transitionId,
          prepared,
        ),
      )
  ) {
    is PlanBundleFeasibilityStateWriteResult.Applied ->
      recoverActiveFeasibilityLaunch(context, run, currentAttempt, result.state, canonical)
    is PlanBundleFeasibilityStateWriteResult.Replayed ->
      recoverActiveFeasibilityLaunch(context, run, currentAttempt, result.state, canonical)
    is PlanBundleFeasibilityStateWriteResult.Existing ->
      FeasibilityLaunchRecovery.Blocked("The retained repair intent found conflicting state.")
    is PlanBundleFeasibilityStateWriteResult.Refused ->
      FeasibilityLaunchRecovery.Blocked("The retained repair intent was refused: ${result.reason}")
  }
}

private fun retainedRepairCandidateViolation(
  context: PhaseAttemptLaunchRuntimeContext,
  recovery: FeasibilityRecoverySnapshot,
  candidate: PlanBundleFeasibilitySnapshot,
): String? {
  val violation = repairBaselineViolation(recovery.launch.baselineIdentity, candidate)
  val staged = recovery.staged
  return when {
    violation != null -> "The retained repair output changed protected bundle authority: $violation."
    staged != null && !matchesFeasibilityBundleFiles(context.decompositionPlanner, staged, candidate) ->
      "The retained repair output changed staged bundle paths."
    staged != null && recovery.snapshot.identity != recovery.launch.baselineIdentity ->
      "Canonical bundle files no longer match the staged-repair admission baseline."
    else -> null
  }
}

private data class RetainedFeasibilityOutput(
  val value: String,
  val normalized: NormalizedFeatureTaskRuntimePhaseOutput,
)

private fun retainedFeasibilityOutput(
  context: PhaseAttemptLaunchRuntimeContext,
  run: PhaseRun,
  launch: PlanBundleFeasibilityLaunch,
): RetainedFeasibilityOutput? {
  context.phaseSettlementService.findEnvelope(run.request.workflowId, launch.phaseId, launch.attempt)?.let { settled ->
    val normalized = NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(settled.envelope)
    if (normalized.phaseId == launch.phaseId) {
      return RetainedFeasibilityOutput(normalized.output.value, normalized)
    }
  }
  retainedProducerFeasibilityOutput(context, run, launch)?.let { return it }
  return retainedCompletedFeasibilityOutput(context, run, launch)
}

private fun cleanupRecoveredStaging(
  context: PhaseAttemptLaunchRuntimeContext,
  run: PhaseRun,
  directory: Path?,
) {
  if (directory == null) return
  val target =
    (PlanBundleStagingPromotion.targetFor(context.decompositionPlanner, run) as? PlanBundleTarget.Staged)
      ?.takeIf { it.directory.toAbsolutePath().normalize() == directory }
      ?: return
  PlanBundleStagingPromotion.finishStaging(
    context.decompositionPlanner,
    context.diagnostics,
    PromotionRequest(target, run.request.repoRoot, run.resolvedAgent.resolvedAgentId, parentSpecProtected = true),
  )
}

private fun retainedCompletedFeasibilityOutput(
  context: PhaseAttemptLaunchRuntimeContext,
  run: PhaseRun,
  launch: PlanBundleFeasibilityLaunch,
): RetainedFeasibilityOutput? {
  val record =
    context.recorder.loadPhaseRecords(run.request.workflowId)?.get(run.phaseId)
      ?.takeIf { it.attemptCount == launch.attempt && it.status == WorkflowStepStatus.COMPLETED }
      ?: return null
  val artifact = record.outputArtifact ?: return null
  val normalized =
    NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(
      artifact,
      "${run.phaseId} feasibility recovery",
    )
  if (normalized.phaseId != run.phaseId) return null
  return RetainedFeasibilityOutput(normalized.output.value, normalized)
}

private fun retainedProducerFeasibilityOutput(
  context: PhaseAttemptLaunchRuntimeContext,
  run: PhaseRun,
  launch: PlanBundleFeasibilityLaunch,
): RetainedFeasibilityOutput? {
  val generation = if (run.policy.generationScoped) context.progress.reviewEvidenceGeneration else 0
  val producerEvidence =
    context.recorder.producerOutput(
      ProducerOutputQueryArgs(
        run.request.workflowId,
        run.phaseId,
        launch.attempt,
        run.resolvedAgent.resolvedAgentId,
        generation,
      ),
    ) as? FeatureTaskRuntimeProducerOutputRead.Found
  producerEvidence?.evidence?.payload?.let { bytes ->
    val value = bytes.toString(Charsets.UTF_8)
    if (producerEvidence.evidence.byteSize == bytes.size.toLong() &&
      producerEvidence.evidence.sha256 == sha256HexUtf8(value)
    ) {
      val normalized =
        NormalizedFeatureTaskRuntimePhaseOutput(
          phaseId = run.phaseId,
          status = WorkflowStepStatus.COMPLETED.wireValue,
          summary = "",
          output = PhaseOutput(value),
        )
      return RetainedFeasibilityOutput(value, normalized)
    }
  }
  return null
}

private fun blockInterruptedLaunch(
  context: PhaseAttemptLaunchRuntimeContext,
  run: PhaseRun,
  recovery: FeasibilityRecoverySnapshot,
  reason: String,
): FeasibilityLaunchRecovery {
  val attempt = recovery.attempt
  val state = recovery.state
  val launch = recovery.launch

  val blocked =
    blockPlanBundleFeasibilityLaunch(state, launch.transitionId, reason)
      ?: return FeasibilityLaunchRecovery.Blocked(reason)
  return when (
    val result =
      context.recorder.writePlanBundleFeasibility(
        PlanBundleFeasibilityStateWrite(
          run.request.workflowId,
          run.phaseId,
          attempt,
          state.revision,
          launch.transitionId,
          blocked,
        ),
      )
  ) {
    is PlanBundleFeasibilityStateWriteResult.Applied -> FeasibilityLaunchRecovery.Blocked(reason)
    is PlanBundleFeasibilityStateWriteResult.Replayed -> FeasibilityLaunchRecovery.Blocked(reason)
    is PlanBundleFeasibilityStateWriteResult.Existing -> FeasibilityLaunchRecovery.Blocked(reason)
    is PlanBundleFeasibilityStateWriteResult.Refused ->
      FeasibilityLaunchRecovery.Blocked("$reason The safety block could not be recorded: ${result.reason}")
  }
}

private fun writeRecoveredFeasibilityState(
  context: PhaseAttemptLaunchRuntimeContext,
  run: PhaseRun,
  recovery: FeasibilityRecoverySnapshot,
  proposed: FeatureTaskRuntimePlanBundleFeasibilityState,
): FeasibilityLaunchRecovery {
  val attempt = recovery.attempt
  val launch = recovery.launch
  val expectedRevision = recovery.state.revision
  return when (
    val result =
      context.recorder.writePlanBundleFeasibility(
        PlanBundleFeasibilityStateWrite(
          run.request.workflowId,
          run.phaseId,
          attempt,
          expectedRevision,
          launch.transitionId,
          proposed,
        ),
      )
  ) {
    is PlanBundleFeasibilityStateWriteResult.Applied -> FeasibilityLaunchRecovery.Ready(result.state)
    is PlanBundleFeasibilityStateWriteResult.Replayed -> FeasibilityLaunchRecovery.Ready(result.state)
    is PlanBundleFeasibilityStateWriteResult.Existing ->
      FeasibilityLaunchRecovery.Blocked("Feasibility recovery found conflicting existing state.")
    is PlanBundleFeasibilityStateWriteResult.Refused ->
      FeasibilityLaunchRecovery.Blocked("Feasibility recovery write was refused: ${result.reason}")
  }
}

private fun feasibilityRepositoryViolation(
  context: PhaseAttemptLaunchRuntimeContext,
  repoRoot: Path,
  launch: PlanBundleFeasibilityLaunch,
  allowedPaths: Set<String>,
): String? {
  val baseline =
    launch.repositoryBaseline
      ?: return "The retained feasibility launch has no original repository baseline."
  val status = context.gitOperations.worktreeStatus(repoRoot)
  val head = context.gitOperations.runtimePhaseHeadCommit(repoRoot)
  return when {
    status !is WorkflowGitOperationResult.Ok -> "Could not observe repository status: ${status.error}"
    head !is WorkflowGitOperationResult.Ok -> "Could not observe repository HEAD: ${head.error}"
    head.value != baseline.headCommit -> "Repository HEAD changed after feasibility admission."
    else -> retainedRepositoryPathViolation(context, repoRoot, baseline, allowedPaths, status.value.orEmpty())
  }
}

private fun retainedRepositoryPathViolation(
  context: PhaseAttemptLaunchRuntimeContext,
  repoRoot: Path,
  baseline: PlanBundleFeasibilityRepositoryBaseline,
  allowedPaths: Set<String>,
  statusText: String,
): String? {
  val paths =
    (
      FeatureTaskRuntimePhaseSafetyPolicy.changedPaths(statusText) +
        FeatureTaskRuntimePhaseSafetyPolicy.deletedPaths(statusText)
    ).distinct().sorted()
  val identities =
    when (val result = context.gitOperations.pathContentIdentities(repoRoot, paths)) {
      is WorkflowPathContentIdentitiesResult.Resolved -> result.identities
      is WorkflowPathContentIdentitiesResult.Failed ->
        return "Could not observe repository file identities: ${result.error}"
    }
  val changed =
    buildSet {
      addAll(baseline.dirtyPaths.toSet() - paths.toSet())
      addAll(paths.toSet() - baseline.dirtyPaths.toSet())
      addAll(baseline.contentIdentities.keys - identities.keys)
      addAll(identities.keys - baseline.contentIdentities.keys)
      (baseline.contentIdentities.keys intersect identities.keys).forEach { path ->
        if (baseline.contentIdentities[path] != identities[path]) add(path)
      }
    }
  val unauthorized = changed.filterNot(allowedPaths::contains)
  return unauthorized.takeIf { it.isNotEmpty() }?.let {
    "Repository paths changed outside the retained feasibility authority: ${it.sorted().joinToString()}."
  }
}

internal fun repairBaselineViolation(
  baseline: PlanBundleFeasibilityIdentity,
  current: PlanBundleFeasibilitySnapshot,
): String? {
  val identity = current.identity
  if (!matchesProtectedBundleBytes(identity, baseline)) return "protected bundle content changed"
  if (identity.subtasks.map { it.id to it.specPath } != baseline.subtasks.map { it.id to it.specPath }) {
    return "child ids or paths changed"
  }
  val baselineById = baseline.subtasks.associateBy { it.id }
  val pendingIds =
    current.manifest.subtasks.filter {
      it.status.decompositionStatus() == DecompositionStatus.PENDING
    }.mapTo(mutableSetOf()) { it.id }
  return identity.subtasks.firstNotNullOfOrNull { actual ->
    val original = baselineById[actual.id] ?: return@firstNotNullOfOrNull "child identity changed"
    when {
      actual.dependencies != original.dependencies -> "dependencies changed"
      actual.id !in pendingIds && actual != original -> "a protected child changed"
      else -> null
    }
  }
}

internal fun matchesFeasibilityBundleFiles(
  planner: FeatureTaskRuntimeDecompositionPlanner,
  directory: Path,
  snapshot: PlanBundleFeasibilitySnapshot,
): Boolean {
  val expected =
    (
      setOf(
        Path.of(snapshot.manifest.parentSpecPath).fileName.toString(),
        DECOMPOSITION_MANIFEST_FILENAME,
      ) + snapshot.manifest.subtasks.map { Path.of(it.specPath).fileName.toString() }
    ).toSet()
  val actual =
    planner.bundleTree(directory).filter(planner::isBundleFile).map { path ->
      directory.relativize(path).toString().replace('\\', '/')
    }.toSet()
  return actual == expected
}

internal fun matchesProtectedBundleBytes(
  actual: PlanBundleFeasibilityIdentity,
  baseline: PlanBundleFeasibilityIdentity,
): Boolean {
  val matchingHashes =
    actual.parentSha256 == baseline.parentSha256 &&
      actual.manifestSha256 == baseline.manifestSha256 &&
      actual.immutableManifestSha256 == baseline.immutableManifestSha256
  val matchingText =
    actual.rawParentText == baseline.rawParentText &&
      actual.rawManifestText == baseline.rawManifestText
  return matchingHashes && matchingText
}
