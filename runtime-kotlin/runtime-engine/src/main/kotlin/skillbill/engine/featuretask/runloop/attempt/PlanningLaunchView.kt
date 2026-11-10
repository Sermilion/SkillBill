package skillbill.engine.featuretask.runloop.attempt

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWrite
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWriteResult
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibility
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibilitySnapshot
import skillbill.engine.featuretask.phase.planning.PlanBundleTarget
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.planning.PlanBundleStagingPromotion
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchRuntimeContext
import skillbill.engine.featuretask.slot.attempt.PhasePlanningLaunchContext
import skillbill.engine.featuretask.slot.attempt.PlanBundleFeasibilityLaunchContext
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityApprovalCandidate
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityIdentity
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunch
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchKind
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchOutcome
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityMode
import skillbill.workflow.taskruntime.model.planning.blockPlanBundleFeasibilityLaunch
import java.nio.file.Path

internal class PlanningLaunchView(
  private val context: PhaseAttemptLaunchRuntimeContext,
  private val run: PhaseRun,
) : PhasePlanningLaunchContext {
  override val request get() = context.request
  override val progress get() = context.progress
  override val session get() = context.session
  override val diagnostics get() = context.diagnostics

  override fun resolvedBranch() = context.recorder.loadResolvedBranch(request.workflowId)

  override fun completeFeasibilityApprovalCandidate(run: PhaseRun): PhaseOutcome? {
    val currentSnapshot =
      PlanBundleFeasibility.capture(
        context.decompositionPlanner,
        request.repoRoot,
        request.issueKey,
      )
    val state =
      context.recorder.loadPlanBundleFeasibility(run.request.workflowId)
        ?.let { recoverApprovalCandidate(it, currentSnapshot) } ?: return null
    val candidate = state.approvalCandidate ?: return null
    val record = context.recorder.loadPhaseRecords(request.workflowId)?.get(run.phaseId) ?: return null
    return if (!candidateMatchesCurrentRecord(candidate, currentSnapshot, record)) {
      null
    } else {
      if (record.status == WorkflowStepStatus.COMPLETED) {
        completedApprovalOutcome(state, currentSnapshot, record)
      } else {
        publishApprovalCandidate(state, candidate)
      }
    }
  }

  private fun completedApprovalOutcome(
    state: FeatureTaskRuntimePlanBundleFeasibilityState,
    snapshot: PlanBundleFeasibilitySnapshot,
    record: FeatureTaskRuntimePhaseRecord,
  ): PhaseOutcome? {
    if (!PlanBundleFeasibility.approved(state, snapshot, record, run.phaseId)) return null
    val envelope = checkNotNull(record.outputArtifact)
    val normalized =
      NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(
        envelope,
        "${request.workflowId}/${run.phaseId} feasibility completion",
      )
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(run.phaseId, record.attemptCount, envelope, normalized),
    )
  }

  private fun candidateMatchesCurrentRecord(
    candidate: PlanBundleFeasibilityApprovalCandidate,
    currentSnapshot: PlanBundleFeasibilitySnapshot,
    record: FeatureTaskRuntimePhaseRecord,
  ): Boolean {
    if (candidate.immutableFingerprint != currentSnapshot.fingerprint) {
      RuntimeDiagnosticsBestEffortWarning.record(
        context.diagnostics,
        "Feasibility approval candidate '${candidate.transitionId}' no longer matches the current immutable " +
          "bundle fingerprint. Preserve its receipt and require a full review under the remaining cycle budget.",
      )
      return false
    }
    if (record.attemptCount != candidate.attempt || record.resolvedAgentId != candidate.reviewerAgentId) {
      RuntimeDiagnosticsBestEffortWarning.record(
        context.diagnostics,
        "Feasibility approval candidate '${candidate.transitionId}' belongs to attempt ${candidate.attempt}; " +
          "the current plan_subtasks record is attempt ${record.attemptCount}. Retain the receipt and require a " +
          "full review under the remaining cycle budget.",
      )
      return false
    }
    return true
  }

  private fun publishApprovalCandidate(
    state: FeatureTaskRuntimePlanBundleFeasibilityState,
    candidate: PlanBundleFeasibilityApprovalCandidate,
  ): PhaseOutcome? {
    val normalized =
      NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(
        candidate.normalizedEnvelope,
        "${request.workflowId}/${run.phaseId} feasibility candidate",
      )
    val phaseState =
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = request.workflowId,
        phaseId = candidate.phaseId,
        status = WorkflowStepStatus.COMPLETED.wireValue,
        attemptCount = candidate.attempt,
        resolvedAgentId = candidate.reviewerAgentId,
        finished = true,
        outputArtifact = candidate.normalizedEnvelope,
        normalizedOutput = normalized,
        feasibilityApprovalRequired = true,
        feasibilityApprovalRevision = candidate.sourceRevision,
        feasibilityApprovalExpectedRevision = state.revision,
        feasibilityApprovalTransitionId = candidate.transitionId,
        fileManifestBefore = candidate.fileManifestBefore,
        fileManifestAfter = candidate.fileManifestAfter,
        fileManifestIntroduced = candidate.fileManifestIntroduced,
      )
    val output =
      FeatureTaskRuntimePhaseOutput(
        candidate.phaseId,
        candidate.attempt,
        candidate.normalizedEnvelope,
        normalized,
      )
    return if (context.coupledRunTransitions.persistAuthoritativePhaseCompletion(
        context.recorder,
        phaseState,
        output,
      ) == RequiredPhaseWrite.Acknowledged
    ) {
      PhaseOutcome.completed(output)
    } else {
      null
    }
  }

  private fun recoverApprovalCandidate(
    state: FeatureTaskRuntimePlanBundleFeasibilityState,
    currentSnapshot: PlanBundleFeasibilitySnapshot,
  ): FeatureTaskRuntimePlanBundleFeasibilityState? {
    if (state.approvalCandidate == null) {
      val activeReview = state.activeLaunch?.takeIf { it.kind == PlanBundleFeasibilityLaunchKind.REVIEW }
      val phaseRecord =
        context.recorder.loadPhaseRecords(request.workflowId)?.get(run.phaseId)
          ?.takeIf { it.attemptCount == activeReview?.attempt }
      if (activeReview != null && phaseRecord != null) {
        when (
          val recovery =
            recoverActiveFeasibilityLaunch(
              context,
              run,
              phaseRecord.attemptCount,
              state,
              currentSnapshot,
            )
        ) {
          is FeasibilityLaunchRecovery.Ready -> return recovery.state
          is FeasibilityLaunchRecovery.Blocked -> {
            RuntimeDiagnosticsBestEffortWarning.record(context.diagnostics, recovery.reason)
            return null
          }
        }
      }
    }
    return state
  }

  override fun admitBundleFeasibility(attempt: Int): PlanBundleFeasibilityLaunchContext {
    val planner = context.decompositionPlanner
    val workflowId = run.request.workflowId
    val transitionId = feasibilityTransitionId(workflowId, run.phaseId, attempt)
    var snapshot = PlanBundleFeasibility.capture(planner, request.repoRoot, request.issueKey)
    var state = context.recorder.loadPlanBundleFeasibility(workflowId)
    val target = PlanBundleStagingPromotion.targetFor(planner, run)
    state = state ?: initializeFeasibility(attempt, snapshot)
    var current = checkNotNull(state)
    current.invalidReason()?.let { refuseFeasibility(workflowId, run.phaseId, attempt, it) }
    current.activeLaunch?.let { active ->
      when (val recovery = recoverActiveFeasibilityLaunch(context, run, attempt, current, snapshot)) {
        is FeasibilityLaunchRecovery.Ready -> {
          current = recovery.state
          snapshot = PlanBundleFeasibility.capture(planner, request.repoRoot, request.issueKey)
          if (!recoveredRepairMatches(active, current, snapshot)) {
            return PlanBundleFeasibilityLaunchContext(
              snapshot,
              false,
              current.conflicts.map { it.line },
              current.revision,
              active.transitionId,
              attempt,
              "Recovered feasibility repair does not match its completed intended identity.",
            )
          }
        }
        is FeasibilityLaunchRecovery.Blocked ->
          return PlanBundleFeasibilityLaunchContext(
            snapshot,
            active.kind == PlanBundleFeasibilityLaunchKind.REPAIR,
            current.conflicts.map { it.line },
            current.revision,
            active.transitionId,
            attempt,
            recovery.reason,
          )
      }
    }
    val stagingFailure = cleanupSettledFeasibilityStaging(context, run, current)
    if (stagingFailure != null) {
      return PlanBundleFeasibilityLaunchContext(
        snapshot,
        false,
        current.conflicts.map { it.line },
        current.revision,
        transitionId,
        attempt,
        stagingFailure,
      )
    }
    return retainedAdmission(attempt, current, snapshot) ?: admitNewFeasibility(attempt, current, snapshot, target)
  }

  private fun initializeFeasibility(
    attempt: Int,
    snapshot: PlanBundleFeasibilitySnapshot,
  ): FeatureTaskRuntimePlanBundleFeasibilityState {
    val workflowId = request.workflowId
    val phases = context.recorder.loadPhaseRecords(workflowId).orEmpty()
    val legacyOrigin =
      phases.values.any {
        it.outputArtifact?.contains(PlanBundleFeasibility.HISTORICAL_PREFIX) == true
      }
    val initial =
      FeatureTaskRuntimePlanBundleFeasibilityState(
        revision = 0,
        mode = PlanBundleFeasibilityMode.REVIEW,
        identity = snapshot.identity,
        acceptedLines = emptyList(),
        conflicts = emptyList(),
        changedSubtaskIds = emptyList(),
        requiredRefs = snapshot.criterionRefs,
        carriedRefs = emptyList(),
        reviewRoundsStarted = 0,
        repairLaunchesStarted = 0,
        activeLaunch = null,
        settledLaunches = emptyList(),
        lastReviewValue = null,
        approval = null,
        legacyOrigin = legacyOrigin,
        currentCycleId = "$workflowId:plan_subtasks:cycle-1",
      )
    val state =
      context.recorder.writePlanBundleFeasibility(
        PlanBundleFeasibilityStateWrite(workflowId, run.phaseId, attempt, null, null, initial),
      ).stateOrRefuse(workflowId, run.phaseId, attempt, "initialize")
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Initialized feasibility state for workflow '$workflowId' (legacy v1 history: $legacyOrigin).",
    )
    return state
  }

  private fun recoveredRepairMatches(
    active: PlanBundleFeasibilityLaunch,
    current: FeatureTaskRuntimePlanBundleFeasibilityState,
    snapshot: PlanBundleFeasibilitySnapshot,
  ): Boolean {
    if (active.kind != PlanBundleFeasibilityLaunchKind.REPAIR || active.intendedAfterIdentity == null) return true
    val completedIntent =
      current.settledLaunches.lastOrNull {
        it.transitionId == active.transitionId && it.kind == PlanBundleFeasibilityLaunchKind.REPAIR &&
          it.outcome == PlanBundleFeasibilityLaunchOutcome.COMPLETED
      }?.intendedAfterIdentity
    val matchesIntent = completedIntent != null && completedIntent == active.intendedAfterIdentity
    return matchesIntent && current.identity == completedIntent && snapshot.identity == completedIntent
  }

  private fun retainedAdmission(
    attempt: Int,
    current: FeatureTaskRuntimePlanBundleFeasibilityState,
    snapshot: PlanBundleFeasibilitySnapshot,
  ): PlanBundleFeasibilityLaunchContext? {
    val transitionId = feasibilityTransitionId(request.workflowId, run.phaseId, attempt)
    val retained = current.activeLaunch
    if (retained != null) {
      val reason = retainedLaunchReason(attempt, retained, snapshot)
      return PlanBundleFeasibilityLaunchContext(
        snapshot,
        retained.kind == PlanBundleFeasibilityLaunchKind.REPAIR,
        current.conflicts.map { it.line },
        current.revision,
        retained.transitionId,
        attempt,
        reason,
      )
    }
    val settled = current.settledLaunches.firstOrNull { it.transitionId == transitionId }
    val reason =
      when {
        settled != null && settled.phaseId == run.phaseId && settled.attempt == attempt ->
          "This feasibility launch is already settled; the current artifact mode is ${current.mode.wireValue}."
        current.mode == PlanBundleFeasibilityMode.BLOCKED -> current.blockedReason()
        current.mode == PlanBundleFeasibilityMode.APPROVED -> "The feasibility cycle is already approved."
        else -> null
      } ?: return null
    return PlanBundleFeasibilityLaunchContext(
      snapshot,
      settled?.kind == PlanBundleFeasibilityLaunchKind.REPAIR,
      current.conflicts.map { it.line },
      current.revision,
      transitionId,
      attempt,
      reason,
    )
  }

  private fun retainedLaunchReason(
    attempt: Int,
    retained: PlanBundleFeasibilityLaunch,
    snapshot: PlanBundleFeasibilitySnapshot,
  ): String {
    if (retained.repositoryBaseline == null) {
      return "The retained feasibility launch has no original repository baseline. " +
        "Restore its original repository evidence before recovery."
    }
    if (retained.phaseId != run.phaseId) {
      refuseFeasibility(request.workflowId, run.phaseId, attempt, "a prior feasibility launch is unresolved")
    }
    if (!retained.baselineIdentity.hasRawBaseline()) {
      return "The retained feasibility launch has no original raw baseline. " +
        "Restore its original snapshot or backup before recovery."
    }
    val violation =
      if (retained.kind == PlanBundleFeasibilityLaunchKind.REPAIR) {
        repairBaselineViolation(retained.baselineIdentity, snapshot)?.let {
          "the retained repair baseline no longer matches protected bundle content"
        }
      } else {
        "the admitted review baseline changed".takeIf { retained.baselineIdentity != snapshot.identity }
      }
    violation?.let { refuseFeasibility(request.workflowId, run.phaseId, attempt, it) }
    return "The admitted feasibility launch is still active and must be reconciled before another agent can start."
  }

  private fun admitNewFeasibility(
    attempt: Int,
    current: FeatureTaskRuntimePlanBundleFeasibilityState,
    snapshot: PlanBundleFeasibilitySnapshot,
    target: PlanBundleTarget?,
  ): PlanBundleFeasibilityLaunchContext {
    val planner = context.decompositionPlanner
    val workflowId = request.workflowId
    val transitionId = feasibilityTransitionId(workflowId, run.phaseId, attempt)
    val kind =
      when (current.mode) {
        PlanBundleFeasibilityMode.REVIEW -> PlanBundleFeasibilityLaunchKind.REVIEW
        PlanBundleFeasibilityMode.REPAIR -> PlanBundleFeasibilityLaunchKind.REPAIR
        else -> error("handled terminal feasibility mode")
      }
    if (!hasAdmissionSlot(current, kind)) {
      refuseFeasibility(workflowId, run.phaseId, attempt, current.blockedReason())
    }
    val directory = if (target is PlanBundleTarget.Staged) target.directory else target?.directory
    val repositoryCapture = captureRepositoryBaseline(context.gitOperations, request.repoRoot)
    val repositoryBaseline =
      (repositoryCapture as? RepositoryBaselineCapture.Captured)?.baseline
        ?: return PlanBundleFeasibilityLaunchContext(
          snapshot,
          kind == PlanBundleFeasibilityLaunchKind.REPAIR,
          current.conflicts.map { it.line },
          current.revision,
          transitionId,
          attempt,
          (repositoryCapture as RepositoryBaselineCapture.Failed).reason,
        )
    val baselineIdentity = admissionBaseline(current, snapshot, kind)
    val cycleId =
      current.currentCycleId ?: current.settledLaunches.lastOrNull()?.cycleId
        ?: "$workflowId:plan_subtasks:cycle-1"
    val sequence = current.reviewRoundsStarted + current.repairLaunchesStarted + 1
    val admitted =
      PlanBundleFeasibilityLaunch(
        transitionId = "$workflowId:$cycleId:$sequence",
        phaseId = run.phaseId,
        attempt = attempt,
        kind = kind,
        baselineIdentity = baselineIdentity,
        repairDirectory = directory?.toString(),
        intendedAfterIdentity = null,
        cycleId = cycleId,
        sequence = sequence,
        repositoryBaseline = repositoryBaseline,
      )
    val next =
      current.copy(
        revision = current.revision + 1,
        reviewRoundsStarted =
          current.reviewRoundsStarted +
            if (kind == PlanBundleFeasibilityLaunchKind.REVIEW) 1 else 0,
        repairLaunchesStarted =
          current.repairLaunchesStarted +
            if (kind == PlanBundleFeasibilityLaunchKind.REPAIR) 1 else 0,
        activeLaunch = admitted,
        currentCycleId = cycleId,
      )
    val state =
      context.recorder.writePlanBundleFeasibility(
        PlanBundleFeasibilityStateWrite(
          workflowId,
          run.phaseId,
          attempt,
          current.revision,
          admitted.transitionId,
          next,
        ),
      ).stateOrRefuse(workflowId, run.phaseId, attempt, "admit review")
    return prepareAdmittedFeasibility(attempt, current, state, snapshot, target)
  }

  private fun hasAdmissionSlot(
    state: FeatureTaskRuntimePlanBundleFeasibilityState,
    kind: PlanBundleFeasibilityLaunchKind,
  ): Boolean =
    state.reviewRoundsStarted < MAX_REVIEW_ROUNDS &&
      (kind != PlanBundleFeasibilityLaunchKind.REPAIR || state.repairLaunchesStarted < MAX_REPAIR_LAUNCHES)

  private fun admissionBaseline(
    current: FeatureTaskRuntimePlanBundleFeasibilityState,
    snapshot: PlanBundleFeasibilitySnapshot,
    kind: PlanBundleFeasibilityLaunchKind,
  ): PlanBundleFeasibilityIdentity {
    return if (kind == PlanBundleFeasibilityLaunchKind.REPAIR) {
      current.settledLaunches.lastOrNull {
        it.cycleId == current.currentCycleId && it.kind == PlanBundleFeasibilityLaunchKind.REPAIR &&
          it.outcome ==
          PlanBundleFeasibilityLaunchOutcome.REFUSED
      }?.baselineIdentity ?: current.settledLaunches.lastOrNull {
        it.cycleId == current.currentCycleId && it.kind == PlanBundleFeasibilityLaunchKind.REVIEW &&
          it.outcome ==
          PlanBundleFeasibilityLaunchOutcome.COMPLETED
      }?.baselineIdentity ?: snapshot.identity
    } else {
      snapshot.identity
    }
  }

  private fun persistAdmissionBlock(
    attempt: Int,
    state: FeatureTaskRuntimePlanBundleFeasibilityState,
    reason: String,
  ): Pair<FeatureTaskRuntimePlanBundleFeasibilityState, String> {
    val launch = checkNotNull(state.activeLaunch)
    val blocked =
      blockPlanBundleFeasibilityLaunch(state, launch.transitionId, reason)
        ?: return state to "$reason The admitted launch could not be blocked in the artifact."
    return when (
      val result =
        context.recorder.writePlanBundleFeasibility(
          PlanBundleFeasibilityStateWrite(
            request.workflowId,
            run.phaseId,
            attempt,
            state.revision,
            launch.transitionId,
            blocked,
          ),
        )
    ) {
      is PlanBundleFeasibilityStateWriteResult.Applied -> result.state to reason
      is PlanBundleFeasibilityStateWriteResult.Replayed -> result.state to reason
      is PlanBundleFeasibilityStateWriteResult.Existing ->
        state to "$reason The retained launch already has conflicting state."
      is PlanBundleFeasibilityStateWriteResult.Refused ->
        state to "$reason The safety block was refused: ${result.reason}"
    }
  }

  private fun hasPriorRepairRefusal(current: FeatureTaskRuntimePlanBundleFeasibilityState): Boolean =
    current.settledLaunches.any {
      it.cycleId == current.currentCycleId && it.kind == PlanBundleFeasibilityLaunchKind.REPAIR &&
        it.outcome == PlanBundleFeasibilityLaunchOutcome.REFUSED
    }

  private fun prepareAdmittedFeasibility(
    attempt: Int,
    current: FeatureTaskRuntimePlanBundleFeasibilityState,
    admittedState: FeatureTaskRuntimePlanBundleFeasibilityState,
    snapshot: PlanBundleFeasibilitySnapshot,
    target: PlanBundleTarget?,
  ): PlanBundleFeasibilityLaunchContext {
    val workflowId = request.workflowId
    val planner = context.decompositionPlanner
    var state = admittedState

    val launch = requireNotNull(state.activeLaunch)
    val repairing = launch.kind == PlanBundleFeasibilityLaunchKind.REPAIR
    var blockedReason: String? = null
    var launchSnapshot = snapshot.copy(repairDirectory = launch.repairDirectory?.let { Path.of(it) })
    if (repairing && target is PlanBundleTarget.Staged) {
      fun blockAdmission(reason: String) {
        val blocked = persistAdmissionBlock(attempt, state, reason)
        state = blocked.first
        blockedReason = blocked.second
      }
      val priorRepairRefusal = hasPriorRepairRefusal(current)
      val stagedFiles = planner.bundleTree(target.directory).filter(planner::isBundleFile)
      if (snapshot.identity != launch.baselineIdentity) {
        blockAdmission("Canonical bundle files no longer match the staged-repair admission baseline.")
      } else if (priorRepairRefusal && stagedFiles.isNotEmpty()) {
        val stagedSnapshot =
          PlanBundleFeasibility.captureInDirectory(
            planner,
            request.repoRoot,
            request.issueKey,
            target.directory,
          )
        if (repairBaselineViolation(launch.baselineIdentity, stagedSnapshot) != null ||
          !matchesFeasibilityBundleFiles(planner, target.directory, stagedSnapshot)
        ) {
          blockAdmission("Retained repair staging differs from the original protected baseline or bundle paths.")
        } else {
          launchSnapshot = stagedSnapshot.copy(repairDirectory = target.directory)
        }
      } else if (!PlanBundleStagingPromotion.seedFeasibilityRepair(
          planner,
          target,
          request.repoRoot,
          diagnostics,
          run.resolvedAgent.resolvedAgentId,
        )
      ) {
        blockAdmission("Could not seed the admitted repair directory from its preserved baseline.")
      } else {
        val seededSnapshot =
          PlanBundleFeasibility.captureInDirectory(
            planner,
            request.repoRoot,
            request.issueKey,
            target.directory,
          )
        if (seededSnapshot.identity != launch.baselineIdentity ||
          !matchesFeasibilityBundleFiles(planner, target.directory, seededSnapshot)
        ) {
          blockAdmission("Seeded repair staging does not match the admitted canonical baseline.")
        } else {
          launchSnapshot = seededSnapshot.copy(repairDirectory = target.directory)
        }
      }
    }
    return PlanBundleFeasibilityLaunchContext(
      launchSnapshot,
      repairing,
      current.conflicts.map { it.line },
      state.revision,
      launch.transitionId,
      attempt,
      blockedReason,
    )
  }
}

private const val MAX_REVIEW_ROUNDS = 3
private const val MAX_REPAIR_LAUNCHES = 2
