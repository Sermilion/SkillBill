package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.lifecycle.continuation.GoalContinuationStateRecordRequest
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.phase.GoalReviewPhaseCompletionRequest
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.PendingReentry
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunState
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowPathContentIdentitiesResult
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput

internal class FeatureTaskRuntimeRunTransitionOwner(
  private val progress: FeatureTaskRuntimeRunState,
  private val session: FeatureTaskRuntimeRunLoopSession,
) {
  fun resumeInFlightReentry(
    fromPhaseId: String,
    loopId: String,
    edgeIteration: Int,
    pendingReentry: PendingReentry,
  ) {
    progress.reopenForReentry(fromPhaseId)
    progress.recordEdgeIteration(loopId, edgeIteration)
    session.transitionReentryPair(pendingReentry, pendingReentry)
  }

  fun claimResumedInFlightEdge(
    loopId: String,
    edgeIteration: Int,
  ) {
    progress.recordEdgeIteration(loopId, edgeIteration)
  }

  fun enterBackwardEdgeReentry(
    reopenedPhaseIds: Collection<String>,
    loopId: String,
    edgeIteration: Int,
    pendingReentry: PendingReentry,
  ) {
    reopenedPhaseIds.forEach(progress::reopenForReentry)
    progress.recordEdgeIteration(loopId, edgeIteration)
    session.transitionReentryPair(pendingReentry, pendingReentry)
  }

  fun invalidateProducerOutputForRegeneration(phaseId: String) {
    progress.invalidateProducerOutput(phaseId)
  }

  fun applyPersistedReviewGenerationInvalidation(
    generation: Int,
    reviewStepId: String,
    reentryLoopId: String,
  ) {
    progress.advanceReviewGeneration(generation)
    progress.resetInvalidatedReviewGeneration(reviewStepId)
    if (session.pendingReentry?.loopId == reentryLoopId) {
      session.transitionReentryPair(null, null)
    }
  }

  fun establishResumedReentryPair(pending: PendingReentry?) {
    session.transitionReentryPair(pending, pending)
  }

  fun enterExplicitResumeStart(explicitResume: ExplicitResumeStart) {
    if (explicitResume.reopen) {
      progress.reopenFromExplicitResume(explicitResume.phaseId)
    }
    session.transitionReentryPair(null, null)
  }

  fun consumeBriefingPendingReentry(phaseId: String): PendingReentry? {
    val briefing = session.pendingReentry?.takeIf { it.phaseId == phaseId }
    if (briefing != null) {
      session.transitionPendingReentry(null)
    }
    return briefing
  }

  fun recordForwardPhaseCompletion(
    output: FeatureTaskRuntimePhaseOutput,
    operatorBlockRetryPhaseId: String,
  ) {
    if (!progress.phase(output.phaseId).completed) {
      progress.recordCompleted(output)
    }
    session.consumeOperatorBlockRetryCompletion(operatorBlockRetryPhaseId)
  }

  fun applyCarriedForwardInMemoryCompletion(
    output: FeatureTaskRuntimePhaseOutput,
    clearPendingReentry: Boolean,
  ) {
    if (clearPendingReentry) {
      session.transitionPendingReentry(null)
    }
    progress.recordCompleted(output)
  }

  fun reserveReviewPassAfterPhaseState(reviewPassNumber: Int?) {
    progress.reserveReviewPass(reviewPassNumber)
  }

  fun persistBlockedPhaseState(
    recorder: PhaseRunRecords,
    phaseState: FeatureTaskRuntimePhaseStateRequest,
  ): Boolean {
    val persisted = recorder.recordPhaseState(phaseState)
    if (persisted) {
      reserveReviewPassAfterPhaseState(phaseState.reviewPassNumber)
    }
    return persisted
  }

  fun persistPausedPhaseState(
    recorder: PhaseRunRecords,
    phaseState: FeatureTaskRuntimePhaseStateRequest,
    pausedReport: FeatureTaskRuntimeRunReport.Paused,
  ): Boolean {
    val persisted = recorder.recordPhaseState(phaseState)
    if (persisted) {
      reserveReviewPassAfterPhaseState(phaseState.reviewPassNumber)
      transitionTerminalPaused(pausedReport)
    }
    return persisted
  }

  fun persistBranchSetupBlockedPhase(
    recorder: PhaseRunRecords,
    phaseState: FeatureTaskRuntimePhaseStateRequest,
  ): Boolean = recorder.recordPhaseState(phaseState)

  fun persistGoalContinuationPause(
    goalContinuationRecorder: PhaseRunGoal,
    request: GoalContinuationStateRecordRequest,
  ): Boolean = goalContinuationRecorder.recordGoalContinuationState(request)

  fun persistDecomposeTerminal(
    recorder: PhaseRunRecords,
    workflowId: String,
    terminal: FeatureTaskRuntimeDecomposeTerminal,
    planStepId: String,
  ): Boolean = recorder.recordDecomposeTerminal(workflowId, terminal, planStepId)

  fun transitionCheckpointRemediationBlock(
    request: FeatureTaskRuntimeRunFacts,
    phaseId: String,
    reason: String,
    resolvedBranch: String?,
  ) {
    transitionTerminalBlocked(
      FeatureTaskRuntimeRunReport.Blocked(
        issueKey = request.issueKey,
        workflowId = request.workflowId,
        featureSize = request.runInvariants.featureSize.name,
        lastIncompletePhase = phaseId,
        blockedReason = reason,
        completedPhaseIds = progress.completedPhaseIds,
        resolvedBranch = resolvedBranch,
      ),
    )
  }

  fun acknowledgeRequiredPhaseStart(
    recorder: PhaseRunRecords,
    phaseState: FeatureTaskRuntimePhaseStateRequest,
  ): RequiredPhaseWrite {
    val write = recorder.recordRequiredPhaseStart(phaseState)
    if (write is RequiredPhaseWrite.Acknowledged) {
      reserveReviewPassAfterPhaseState(phaseState.reviewPassNumber)
    }
    return write
  }

  fun applyPersistedPhaseCompletion(
    output: FeatureTaskRuntimePhaseOutput,
    reviewPassNumber: Int?,
  ) {
    progress.reserveReviewPass(reviewPassNumber)
    progress.recordCompleted(output)
  }

  fun persistAuthoritativePhaseCompletion(
    recorder: PhaseRunRecords,
    phaseState: FeatureTaskRuntimePhaseStateRequest,
    inMemoryOutput: FeatureTaskRuntimePhaseOutput,
  ): Boolean {
    val persisted = recorder.recordCompletedPhase(phaseState)
    if (persisted) {
      applyPersistedPhaseCompletion(inMemoryOutput, phaseState.reviewPassNumber)
    }
    return persisted
  }

  fun persistGoalReviewPhaseCompletion(
    recorder: PhaseRunRecords,
    completion: GoalReviewPhaseCompletionRequest,
  ): Boolean {
    val phaseState = completion.phaseState
    val persisted = recorder.completeGoalReviewPhase(completion)
    if (persisted) {
      val normalized = phaseState.normalizedOutput
      applyPersistedPhaseCompletion(
        FeatureTaskRuntimePhaseOutput(
          phaseState.phaseId,
          phaseState.attemptCount,
          normalized?.canonicalJson ?: completion.rawReviewResult,
          normalized,
          phaseState.repairEvidence,
        ),
        phaseState.reviewPassNumber,
      )
    }
    return persisted
  }

  fun persistReviewGenerationInvalidation(
    recorder: PhaseRunRecords,
    workflowId: String,
    reviewStepId: String,
    reentryLoopId: String,
  ): Int? {
    val generation = recorder.persistReviewGenerationInvalidation(workflowId, reviewStepId) ?: return null
    applyPersistedReviewGenerationInvalidation(
      generation = generation,
      reviewStepId = reviewStepId,
      reentryLoopId = reentryLoopId,
    )
    return generation
  }

  fun persistCarriedForwardPhaseCompletion(
    recorder: PhaseRunRecords,
    phaseState: FeatureTaskRuntimePhaseStateRequest,
    inMemoryOutput: FeatureTaskRuntimePhaseOutput,
    clearPendingReentry: Boolean,
  ): Boolean {
    val persisted = recorder.recordCompletedPhase(phaseState)
    if (persisted) {
      applyCarriedForwardInMemoryCompletion(inMemoryOutput, clearPendingReentry)
    }
    return persisted
  }

  fun recordSyntheticUpstreamCompletion(output: FeatureTaskRuntimePhaseOutput) {
    progress.recordCompleted(output)
  }

  fun transitionTerminalBlocked(report: FeatureTaskRuntimeRunReport.Blocked) {
    session.transitionToBlocked(report)
  }

  fun transitionTerminalPaused(report: FeatureTaskRuntimeRunReport.Paused) {
    session.transitionToPaused(report)
  }

  fun transitionTerminalDecomposed(report: FeatureTaskRuntimeRunReport.Decomposed) {
    session.transitionToDecomposed(report)
  }

  fun restartAttemptBudgetForRelaunch(phaseId: String) {
    progress.restartAttemptBudget(phaseId)
  }

  fun beginPhaseAttemptLaunchAfterRequiredStart(
    phaseId: String,
    operatorReopened: Boolean,
  ) {
    if (operatorReopened) {
      progress.restartAttemptBudget(phaseId)
    }
    progress.recordPhaseLaunched(phaseId)
  }

  fun observeResolvedBranchForCheckpoint(branch: String?) {
    session.transitionResolvedBranch(branch)
  }

  fun markCheckpointOwnershipDecided() {
    session.markCheckpointOwnershipDecided()
  }

  fun markRecordRejectionSettlementPending() {
    session.markRecordRejectionSettlementPending()
  }

  fun recordPhaseContentIdentities(
    request: FeatureTaskRuntimeRunFacts,
    gitOperations: WorkflowGitOperations,
    phaseId: String,
  ) {
    val owned = gitOperations.repositoryOwnedPaths(request.repoRoot)
    if (owned !is WorkflowGitNameListResult.Listed) return
    val paths = owned.names.map(String::trim).filter(String::isNotBlank)
    val identities = gitOperations.pathContentIdentities(request.repoRoot, paths)
    if (identities !is WorkflowPathContentIdentitiesResult.Resolved) return
    session.recordPhaseContentIdentities(phaseId, identities.identities)
  }

  fun consumeRecordRejectionSettlementAdvance(): FeatureTaskRuntimeVerdict? {
    if (!session.recordRejectionSettlementPending) return null
    session.clearRecordRejectionSettlementPending()
    return FeatureTaskRuntimeVerdict.RECORD_REJECTED
  }

  fun clearPersistedBlockAfterUpstreamRecovery(phaseId: String) {
    progress.clearPersistedBlock(phaseId)
  }

  fun discardStaleResumedReentry(loopId: String) {
    progress.discardStaleReentry(loopId)
  }

  fun clearRecoveredBranchSetupBlock(phaseId: String) {
    if (!progress.phase(phaseId).branchSetupBlocked) {
      return
    }
    progress.clearBranchSetupBlock(phaseId)
  }
}

internal val PhaseRunState.coupledRunTransitions: FeatureTaskRuntimeRunTransitionOwner
  get() = runLoopCoupledTransitions

internal fun PhaseRunState.coupledProgress(): FeatureTaskRuntimeRunState = runLoopCoupledProgress()

internal fun PhaseRunState.coupledSession(): FeatureTaskRuntimeRunLoopSession = runLoopCoupledSession()

internal fun coupledRunTransitions(
  progress: FeatureTaskRuntimeRunState,
  session: FeatureTaskRuntimeRunLoopSession,
): FeatureTaskRuntimeRunTransitionOwner = runLoopCoupledTransitions(progress, session)

internal fun PhaseRunState.runLoopCoupledProgress(): FeatureTaskRuntimeRunState = progress as FeatureTaskRuntimeRunState

internal fun PhaseRunState.runLoopCoupledSession(): FeatureTaskRuntimeRunLoopSession =
  session as FeatureTaskRuntimeRunLoopSession

internal fun coupledRunTransitionOwner(
  progress: FeatureTaskRuntimeRunState,
  session: FeatureTaskRuntimeRunLoopSession,
): FeatureTaskRuntimeRunTransitionOwner = progress.transitionOwnerFor(session)

internal val PhaseRunState.runLoopCoupledTransitions: FeatureTaskRuntimeRunTransitionOwner
  get() = coupledRunTransitionOwner(runLoopCoupledProgress(), runLoopCoupledSession())

internal fun runLoopCoupledTransitions(
  progress: FeatureTaskRuntimeRunState,
  session: FeatureTaskRuntimeRunLoopSession,
): FeatureTaskRuntimeRunTransitionOwner = coupledRunTransitionOwner(progress, session)
