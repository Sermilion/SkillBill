package skillbill.engine.featuretask.runloop.phase

import skillbill.application.review.service.RuntimeOwnedReviewMode
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.lifecycle.continuation.GoalContinuationStateRecordRequest
import skillbill.engine.featuretask.lifecycle.continuation.isGoalContinuationRun
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.runloop.attempt.remediationCoupling
import skillbill.engine.featuretask.runloop.attempt.settlementCoupling
import skillbill.engine.featuretask.runloop.core.BlockAndPersistArgs
import skillbill.engine.featuretask.runloop.core.BlockAndPersistInPhaseArgs
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunSessionObservations
import skillbill.engine.featuretask.runloop.core.FixLoopBranchContext
import skillbill.engine.featuretask.runloop.core.PauseAndPersistInPhaseArgs
import skillbill.engine.featuretask.runloop.core.PauseAtArgs
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestArgs
import skillbill.engine.featuretask.runloop.core.phaseBlockArgs
import skillbill.engine.featuretask.runloop.core.resolveReviewPassNumber
import skillbill.engine.featuretask.runloop.core.withDisposition
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.observability.blocked
import skillbill.engine.featuretask.runloop.observability.paused
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeNonOutputAttempt
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.engine.featuretask.runloop.state.coupledProgress
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runner.BRANCH_SETUP_AGENT_ID
import skillbill.engine.featuretask.runner.STATUS_BLOCKED
import skillbill.engine.featuretask.runner.STATUS_PAUSED
import skillbill.engine.featuretask.runner.isProcessFailureBlockReason
import skillbill.engine.featuretask.runner.nonRetryingPhaseSchemaBlockReason
import skillbill.engine.featuretask.runner.withSchemaGateDetail
import skillbill.engine.featuretask.slot.attempt.PhaseCheckpointRemediationContext
import skillbill.engine.featuretask.slot.attempt.PhaseOutputSettlementContext
import skillbill.engine.featuretask.slot.attempt.PhaseRunLoopAttemptScope
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.workflow.model.goalreview.GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttemptStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.review.ReviewPassResolution

object FeatureTaskRuntimeRunLoopPhaseBlocking {
  internal fun blockStepInPhase(
    context: PhaseCheckpointRemediationContext,
    block: PhaseBlockRequest,
  ): PhaseOutcome {
    val coupling = context.remediationCoupling()
    return blockInPhase(coupling.progress, coupling.transitions, context.recorder, block)
  }

  internal fun blockInPhase(
    progress: FeatureTaskRuntimeProgressSnapshotAccess,
    transitions: FeatureTaskRuntimeRunTransitionOwner,
    recorder: PhaseRunRecords,
    block: PhaseBlockRequest,
  ): PhaseOutcome {
    val inPhase =
      phaseBlockArgs(
        block.run,
        block.attemptCount,
        block.reason,
        block.observability,
        block.payload,
      ).withDisposition(block.failureDisposition)
    return blockAndPersistCore(
      progress,
      recorder,
      null,
      BlockAndPersistArgs(
        run = inPhase.run,
        attemptCount = inPhase.attemptCount,
        reason = inPhase.reason,
        observability = inPhase.observability,
        loopId = inPhase.run.reentry?.loopId,
        edgeIteration = inPhase.run.reentry?.edgeIteration,
        failureDisposition = inPhase.failureDisposition,
        payload = inPhase.payload,
      ),
      transitions,
    )
  }

  internal fun blockInPhase(
    runState: PhaseRunState,
    recorder: PhaseRunRecords,
    block: PhaseBlockRequest,
  ): PhaseOutcome =
    blockInPhase(
      runState.coupledProgress(),
      runState.coupledRunTransitions,
      recorder,
      block,
    )

  internal fun PhaseRunLoopAttemptScope.blockAndPersist(args: BlockAndPersistArgs): PhaseOutcome {
    val coupling = settlementCoupling()
    return blockAndPersistCore(
      coupling.progress,
      recorder,
      goalContinuationRecorder,
      args,
      coupling.transitions,
    )
  }

  internal fun blockAndPersist(
    runState: PhaseRunState,
    recorder: PhaseRunRecords,
    goalContinuationRecorder: PhaseRunGoal?,
    args: BlockAndPersistArgs,
  ): PhaseOutcome =
    blockAndPersist(
      runState.coupledProgress(),
      runState.coupledRunTransitions,
      recorder,
      goalContinuationRecorder,
      args,
    )

  internal fun blockAndPersist(
    progress: FeatureTaskRuntimeProgressSnapshotAccess,
    transitions: FeatureTaskRuntimeRunTransitionOwner,
    recorder: PhaseRunRecords,
    goalContinuationRecorder: PhaseRunGoal?,
    args: BlockAndPersistArgs,
  ): PhaseOutcome =
    blockAndPersistCore(
      progress,
      recorder,
      goalContinuationRecorder,
      args,
      transitions,
    )

  internal fun blockAndPersistInPhase(
    progress: FeatureTaskRuntimeProgressSnapshotAccess,
    transitions: FeatureTaskRuntimeRunTransitionOwner,
    recorder: PhaseRunRecords,
    goalContinuationRecorder: PhaseRunGoal?,
    args: BlockAndPersistInPhaseArgs,
  ): PhaseOutcome =
    blockAndPersistCore(
      progress,
      recorder,
      goalContinuationRecorder,
      BlockAndPersistArgs(
        run = args.run,
        attemptCount = args.attemptCount,
        reason = args.reason,
        observability = args.observability,
        loopId = args.run.reentry?.loopId,
        edgeIteration = args.run.reentry?.edgeIteration,
        failureDisposition = args.failureDisposition,
        payload = args.payload,
      ),
      transitions,
    )

  private fun blockAndPersistCore(
    state: FeatureTaskRuntimeProgressSnapshotAccess,
    recorder: PhaseRunRecords,
    goalContinuationRecorder: PhaseRunGoal?,
    args: BlockAndPersistArgs,
    transitions: FeatureTaskRuntimeRunTransitionOwner,
  ): PhaseOutcome {
    val request = args.run.request
    val run = args.run
    val attemptCount = args.attemptCount
    val reason = args.reason
    val observability = args.observability
    val loopId = args.loopId
    val edgeIteration = args.edgeIteration
    val failureDisposition = args.failureDisposition
    val fileManifest = args.payload.fileManifest
    val outputArtifact = args.payload.outputArtifact
    val normalizedOutput = args.payload.normalizedOutput
    val repairEvidence = args.payload.repairEvidence
    val rejectedOutput = args.payload.rejectedOutput
    val childNeverLaunched = args.payload.childNeverLaunched
    val phaseState =
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = run.request.workflowId,
        phaseId = run.phaseId,
        status = STATUS_BLOCKED,
        attemptCount = attemptCount.coerceAtLeast(1),
        resolvedAgentId = run.resolvedAgent.resolvedAgentId,
        finished = false,
        outputArtifact =
          normalizedOutput?.canonicalJson
            ?: outputArtifact
            ?: state.phase(run.phaseId).output?.payload,
        rejectedOutput = rejectedOutput,
        normalizedOutput = normalizedOutput,
        repairEvidence = repairEvidence,
        blockedReason = reason,
        failureDisposition = failureDisposition,
        fileManifestBefore = fileManifest?.before.orEmpty(),
        fileManifestAfter = fileManifest?.after.orEmpty(),
        fileManifestIntroduced = fileManifest?.introduced.orEmpty(),
        loopId = loopId,
        edgeIteration = edgeIteration,
        reviewPassNumber =
          goalContinuationRecorder?.let { continuationRecorder ->
            reviewPassNumber(request, continuationRecorder, run, state)
          },
        launchOutcomeKnown = childNeverLaunched,
        mutating = run.policy.mutating,
      )
    transitions.persistBlockedPhaseState(recorder, phaseState)
    observability.blocked(run.phaseId, run.resolvedAgent.resolvedAgentId, attemptCount.coerceAtLeast(1), reason)
    return PhaseOutcome.blocked(reason)
  }

  internal fun PhaseRunLoopAttemptScope.pauseAndPersistInPhase(args: PauseAndPersistInPhaseArgs): PhaseOutcome {
    val run = args.run
    val attemptCount = args.attemptCount
    val reason = args.reason
    val observability = args.observability
    val fileManifest = args.fileManifest
    val attempt = attemptCount.coerceAtLeast(1)
    if (isGoalContinuationRun(request)) {
      coupledRunTransitions.persistGoalContinuationPause(
        goalContinuationRecorder,
        GoalContinuationStateRecordRequest(
          workflowId = request.workflowId,
          workflowStatus = STATUS_PAUSED,
        ),
      )
    }
    val phaseState =
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = request.workflowId,
        phaseId = run.phaseId,
        status = STATUS_PAUSED,
        attemptCount = attempt,
        resolvedAgentId = run.resolvedAgent.resolvedAgentId,
        finished = false,
        outputArtifact = progress.phase(run.phaseId).output?.payload,
        blockedReason = reason,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.RETRYABLE,
        fileManifestBefore = fileManifest?.before.orEmpty(),
        fileManifestAfter = fileManifest?.after.orEmpty(),
        fileManifestIntroduced = fileManifest?.introduced.orEmpty(),
        loopId = run.reentry?.loopId,
        edgeIteration = run.reentry?.edgeIteration,
        launchOutcomeKnown = false,
        mutating = run.policy.mutating,
      )
    coupledRunTransitions.persistPausedPhaseState(
      recorder,
      phaseState,
      FeatureTaskRuntimeRunReport.Paused(
        issueKey = request.issueKey,
        workflowId = request.workflowId,
        featureSize = request.runInvariants.featureSize.name,
        pausedPhase = run.phaseId,
        pauseReason = reason,
        resumableStep = run.phaseId,
        completedPhaseIds = progress.completedPhaseIds,
        resolvedBranch = session.resolvedBranch,
      ),
    )
    observability.paused(run.phaseId, run.resolvedAgent.resolvedAgentId, attempt, reason)
    return PhaseOutcome.paused(reason)
  }

  internal fun PhaseRunLoopAttemptScope.blockAndPersistInPhase(args: BlockAndPersistInPhaseArgs): PhaseOutcome =
    blockAndPersist(
      BlockAndPersistArgs(
        run = args.run,
        attemptCount = args.attemptCount,
        reason = args.reason,
        observability = args.observability,
        loopId = args.run.reentry?.loopId,
        edgeIteration = args.run.reentry?.edgeIteration,
        failureDisposition = args.failureDisposition,
        payload = args.payload,
      ),
    )

  internal fun PhaseOutputSettlementContext.pauseAndPersistInPhase(args: PauseAndPersistInPhaseArgs): PhaseOutcome {
    val coupling = settlementCoupling()
    val run = args.run
    val attemptCount = args.attemptCount
    val reason = args.reason
    val observability = args.observability
    val fileManifest = args.fileManifest
    val attempt = attemptCount.coerceAtLeast(1)
    if (isGoalContinuationRun(request)) {
      coupledRunTransitions.persistGoalContinuationPause(
        goalContinuationRecorder,
        GoalContinuationStateRecordRequest(
          workflowId = request.workflowId,
          workflowStatus = STATUS_PAUSED,
        ),
      )
    }
    val phaseState =
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = request.workflowId,
        phaseId = run.phaseId,
        status = STATUS_PAUSED,
        attemptCount = attempt,
        resolvedAgentId = run.resolvedAgent.resolvedAgentId,
        finished = false,
        outputArtifact = progress.phase(run.phaseId).output?.payload,
        blockedReason = reason,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.RETRYABLE,
        fileManifestBefore = fileManifest?.before.orEmpty(),
        fileManifestAfter = fileManifest?.after.orEmpty(),
        fileManifestIntroduced = fileManifest?.introduced.orEmpty(),
        loopId = run.reentry?.loopId,
        edgeIteration = run.reentry?.edgeIteration,
        launchOutcomeKnown = false,
        mutating = run.policy.mutating,
      )
    coupledRunTransitions.persistPausedPhaseState(
      recorder,
      phaseState,
      FeatureTaskRuntimeRunReport.Paused(
        issueKey = request.issueKey,
        workflowId = request.workflowId,
        featureSize = request.runInvariants.featureSize.name,
        pausedPhase = run.phaseId,
        pauseReason = reason,
        resumableStep = run.phaseId,
        completedPhaseIds = progress.completedPhaseIds,
        resolvedBranch = coupling.sessionObservations.resolvedBranch,
      ),
    )
    observability.paused(run.phaseId, run.resolvedAgent.resolvedAgentId, attempt, reason)
    return PhaseOutcome.paused(reason)
  }

  internal fun PhaseOutputSettlementContext.blockAndPersistInPhase(args: BlockAndPersistInPhaseArgs): PhaseOutcome {
    val coupling = settlementCoupling()
    return blockAndPersistCore(
      coupling.progress,
      recorder,
      goalContinuationRecorder,
      BlockAndPersistArgs(
        run = args.run,
        attemptCount = args.attemptCount,
        reason = args.reason,
        observability = args.observability,
        loopId = args.run.reentry?.loopId,
        edgeIteration = args.run.reentry?.edgeIteration,
        failureDisposition = args.failureDisposition,
        payload = args.payload,
      ),
      coupling.transitions,
    )
  }

  internal fun operatorReopenedPhase(
    session: FeatureTaskRuntimeRunSessionObservations,
    phaseId: String,
  ): Boolean = session.operatorBlockRetry?.phaseId == phaseId && !session.operatorBlockRetryCompleted

  internal fun blockAt(
    request: FeatureTaskRuntimeRunFacts,
    state: FeatureTaskRuntimeRunState,
    session: FeatureTaskRuntimeRunLoopSession,
    phaseId: String,
    reason: String,
  ) {
    coupledRunTransitions(state, session).transitionTerminalBlocked(
      FeatureTaskRuntimeRunReport.Blocked(
        issueKey = request.issueKey,
        workflowId = request.workflowId,
        featureSize = request.runInvariants.featureSize.name,
        lastIncompletePhase = phaseId,
        blockedReason = reason,
        completedPhaseIds = state.completedPhaseIds,
        resolvedBranch = session.resolvedBranch,
      ),
    )
  }

  internal fun FeatureTaskRuntimeRunTransitionOwner.persistBranchSetupBlock(
    request: FeatureTaskRuntimeRunFacts,
    recorder: PhaseRunRecords,
    observability: FeatureTaskRuntimeRunObservability,
    phaseId: String,
    reason: String,
  ) {
    persistBranchSetupBlockedPhase(
      recorder,
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = request.workflowId,
        phaseId = phaseId,
        status = STATUS_BLOCKED,
        attemptCount = 1,
        resolvedAgentId = BRANCH_SETUP_AGENT_ID,
        finished = false,
        outputArtifact = null,
        blockedReason = reason,
      ),
    )
    observability.branchSetupBlocked(phaseId, BRANCH_SETUP_AGENT_ID, reason)
  }

  internal fun clearRecoveredBranchSetupBlock(
    transitions: FeatureTaskRuntimeRunTransitionOwner,
    phaseId: String,
  ) {
    transitions.clearRecoveredBranchSetupBlock(phaseId)
  }

  internal fun pauseAt(
    args: PauseAtArgs,
    transitions: FeatureTaskRuntimeRunTransitionOwner,
  ) {
    val request = args.request
    val state = args.state
    val session = args.session
    val phaseId = args.phaseId
    val reason = args.reason
    val resumableStep = args.resumableStep
    transitions.transitionTerminalPaused(
      FeatureTaskRuntimeRunReport.Paused(
        issueKey = request.issueKey,
        workflowId = request.workflowId,
        featureSize = request.runInvariants.featureSize.name,
        pausedPhase = phaseId,
        pauseReason = reason,
        resumableStep = resumableStep,
        completedPhaseIds = state.completedPhaseIds,
        resolvedBranch = session.resolvedBranch,
      ),
    )
  }

  internal fun goalReviewStateOrNull(
    request: FeatureTaskRuntimeRunFacts,
    goalContinuationRecorder: PhaseRunGoal,
  ): GoalSubtaskReviewState? =
    if (!isGoalContinuationRun(request)) {
      null
    } else {
      goalContinuationRecorder.reviewState(request.workflowId)
    }

  internal fun priorBlockerFindingIds(
    request: FeatureTaskRuntimeRunFacts,
    goalContinuationRecorder: PhaseRunGoal,
  ): List<String> {
    val priorPass =
      goalReviewStateOrNull(request, goalContinuationRecorder)?.passResults?.lastOrNull()
        ?: return emptyList()
    return priorPass.findings
      .filter { it.severity == GOAL_SUBTASK_REVIEW_BLOCKER_SEVERITY }
      .mapIndexed { index, finding -> finding.findingId ?: "pass${priorPass.passNumber}-blocker-${index + 1}" }
  }

  internal fun persistResolvedReviewTier(
    request: FeatureTaskRuntimeRunFacts,
    goalContinuationRecorder: PhaseRunGoal,
    run: PhaseRun,
    state: FeatureTaskRuntimeProgressSnapshotAccess,
    resolution: ReviewPassResolution,
  ) {
    if (!state.resumeRules(run.phaseId).tracksReviewPasses || !isGoalContinuationRun(request)) {
      return
    }
    goalContinuationRecorder.updateReviewState(request.workflowId) { state ->
      state.copy(
        resolvedTier = RuntimeOwnedReviewMode.execute(resolution.resolvedTier),
        decidingRule = resolution.decidingRule,
      )
    }
  }

  internal fun reviewPassNumber(
    request: FeatureTaskRuntimeRunFacts,
    goalContinuationRecorder: PhaseRunGoal?,
    run: PhaseRun,
    state: FeatureTaskRuntimeProgressSnapshotAccess,
  ): Int? {
    if (!state.resumeRules(run.phaseId).tracksReviewPasses) return null
    if (goalContinuationRecorder == null) return state.currentReviewPassNumber ?: 1
    val durable = goalReviewStateOrNull(request, goalContinuationRecorder) ?: return 1
    return resolveReviewPassNumber(
      reservedPassNumber = durable.reservedPassNumber ?: state.currentReviewPassNumber,
      completedReviewPassCount = durable.completedPassCount,
    )
  }

  internal fun phaseStateRequest(
    request: FeatureTaskRuntimeRunFacts,
    state: FeatureTaskRuntimeProgressSnapshotAccess,
    goalContinuationRecorder: PhaseRunGoal,
    args: PhaseStateRequestArgs,
  ): FeatureTaskRuntimePhaseStateRequest {
    val write = args.write
    val run = write.run
    val extras = args.extras
    val fileManifest = extras.fileManifest
    return FeatureTaskRuntimePhaseStateRequest(
      workflowId = run.request.workflowId,
      phaseId = run.phaseId,
      status = write.status,
      attemptCount = write.iteration,
      resolvedAgentId = run.resolvedAgent.resolvedAgentId,
      finished = write.finished,
      outputArtifact = write.outputArtifact,
      normalizedOutput = extras.normalizedOutput,
      repairEvidence = extras.repairEvidence,
      repositoryFingerprint = extras.repositoryFingerprint,
      fileManifestBefore = fileManifest?.before.orEmpty(),
      fileManifestAfter = fileManifest?.after.orEmpty(),
      fileManifestIntroduced = fileManifest?.introduced.orEmpty(),
      loopId = run.reentry?.loopId,
      edgeIteration = run.reentry?.edgeIteration,
      reviewPassNumber = reviewPassNumber(request, goalContinuationRecorder, run, state),
      launchedModel = extras.launched?.modelOverride,
      launchedEffort = extras.launched?.persistedEffort,
      launchOutcomeKnown = extras.launched != null,
      reviewRunId = extras.reviewRunId,
      mutating = run.policy.mutating,
    )
  }

  internal fun reviewedCheckpointFingerprint(
    request: FeatureTaskRuntimeRunFacts,
    recorder: PhaseRunRecords,
    reviewStepId: String,
  ): String? =
    recorder
      .loadDeliveredProjections(request.workflowId)
      ?.get(reviewStepId)
      ?.repositoryCheckpointFingerprint

  internal fun settleSemanticFailure(
    recorder: PhaseRunRecords,
    context: FixLoopBranchContext,
  ): PhaseOutcome {
    val run = context.run
    val attempt = context.attempt
    return blockInPhase(
      context.progress,
      context.loopTransitions,
      recorder,
      PhaseBlockRequest(
        run = run,
        attemptCount = context.loop.iteration,
        reason =
          withSchemaGateDetail(
            nonRetryingPhaseSchemaBlockReason(run.phaseId),
            requireNotNull(attempt.retryableOperatorReason),
          ),
        observability = context.observability,
        payload =
          BlockAndPersistPayload(
            fileManifest = attempt.fileManifest,
            rejectedOutput = attempt.rejectedOutput,
          ),
        failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
      ),
    )
  }

  internal fun durableNonOutputAttempts(
    state: FeatureTaskRuntimeProgressSnapshotAccess,
    run: PhaseRun,
  ): List<FeatureTaskRuntimeNonOutputAttempt> =
    state.trailingNonOutputAttempts(run.phaseId) { reason -> isProcessFailureBlockReason(run.phaseId, reason) }

  internal fun durableContinuationSegmentCount(
    recorder: PhaseRunRecords,
    run: PhaseRun,
  ): Int {
    if (!run.policy.mutating) return 0
    val attempts =
      recorder.loadImplementationAttempts(run.request.workflowId)
        ?: return 0
    return attempts.count {
      it.phaseId == run.phaseId &&
        it.loopId == run.reentry?.loopId &&
        it.edgeIteration == run.reentry?.edgeIteration &&
        it.status == FeatureTaskRuntimeImplementationAttemptStatus.INCOMPLETE &&
        it.failureDisposition?.retryOnResume != true
    }
  }
}
