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

public class FeatureTaskRuntimeRunTransitionOwner internal constructor(
  private val progress: FeatureTaskRuntimeRunState,
  private val session: FeatureTaskRuntimeRunLoopSession,
) {
  private var checkpointBlockOverride: ((String) -> Unit)? = null

  internal fun <T> checkpointBlocksAsWarnings(
    onBlock: (String) -> Unit,
    action: () -> T,
  ): T {
    checkpointBlockOverride = onBlock
    try {
      return action()
    } finally {
      checkpointBlockOverride = null
    }
  }

  internal fun claimResumedInFlightEdge(
    loopId: String,
    edgeIteration: Int,
  ) {
    progress.recordEdgeIteration(loopId, edgeIteration)
  }

  internal fun enterBackwardEdgeReentry(
    reopenedPhaseIds: Collection<String>,
    loopId: String,
    edgeIteration: Int,
    pendingReentry: PendingReentry,
  ) {
    reopenedPhaseIds.forEach(progress::reopenForReentry)
    progress.recordEdgeIteration(loopId, edgeIteration)
    session.transitionReentryPair(pendingReentry, pendingReentry)
  }

  internal fun reenterAcceptanceSteps(phaseIds: Collection<String>) {
    phaseIds.forEach(progress::reopenForReentry)
    session.transitionReentryPair(null, null)
  }

  internal fun invalidateProducerOutputForRegeneration(phaseId: String) {
    progress.invalidateProducerOutput(phaseId)
  }

  internal fun applyPersistedReviewGenerationInvalidation(
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

  internal fun establishResumedReentryPair(pending: PendingReentry?) {
    session.transitionReentryPair(pending, pending)
  }

  internal fun enterExplicitResumeStart(explicitResume: ExplicitResumeStart) {
    if (explicitResume.reopen) {
      progress.reopenFromExplicitResume(explicitResume.phaseId)
    }
    session.transitionReentryPair(null, null)
  }

  internal fun consumeBriefingPendingReentry(phaseId: String): PendingReentry? {
    val briefing = session.pendingReentry?.takeIf { it.phaseId == phaseId }
    if (briefing != null) {
      session.transitionPendingReentry(null)
    }
    return briefing
  }

  internal fun recordForwardPhaseCompletion(
    output: FeatureTaskRuntimePhaseOutput,
    operatorBlockRetryPhaseId: String,
  ) {
    if (!progress.phase(output.phaseId).completed) {
      progress.recordCompleted(output)
    }
    session.consumeOperatorBlockRetryCompletion(operatorBlockRetryPhaseId)
  }

  internal fun applyCarriedForwardInMemoryCompletion(
    output: FeatureTaskRuntimePhaseOutput,
    clearPendingReentry: Boolean,
  ) {
    if (clearPendingReentry) {
      session.transitionPendingReentry(null)
    }
    progress.recordCompleted(output)
  }

  internal fun reserveReviewPassAfterPhaseState(reviewPassNumber: Int?) {
    progress.reserveReviewPass(reviewPassNumber)
  }

  internal fun persistBlockedPhaseState(
    recorder: PhaseRunRecords,
    phaseState: FeatureTaskRuntimePhaseStateRequest,
  ): Boolean {
    val persisted = recorder.recordPhaseState(phaseState)
    if (persisted) {
      reserveReviewPassAfterPhaseState(phaseState.reviewPassNumber)
    }
    return persisted
  }

  internal fun persistPausedPhaseState(
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

  internal fun persistBranchSetupBlockedPhase(
    recorder: PhaseRunRecords,
    phaseState: FeatureTaskRuntimePhaseStateRequest,
  ): Boolean = recorder.recordPhaseState(phaseState)

  internal fun persistGoalContinuationPause(
    goalContinuationRecorder: PhaseRunGoal,
    request: GoalContinuationStateRecordRequest,
  ): Boolean = goalContinuationRecorder.recordGoalContinuationState(request)

  internal fun persistDecomposeTerminal(
    recorder: PhaseRunRecords,
    workflowId: String,
    terminal: FeatureTaskRuntimeDecomposeTerminal,
    planStepId: String,
  ): Boolean = recorder.recordDecomposeTerminal(workflowId, terminal, planStepId)

  internal fun transitionCheckpointRemediationBlock(
    request: FeatureTaskRuntimeRunFacts,
    phaseId: String,
    reason: String,
    resolvedBranch: String?,
  ) {
    checkpointBlockOverride?.let { override ->
      override(reason)
      return
    }
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

  internal fun acknowledgeRequiredPhaseStart(
    recorder: PhaseRunRecords,
    phaseState: FeatureTaskRuntimePhaseStateRequest,
  ): RequiredPhaseWrite {
    val write = recorder.recordRequiredPhaseStart(phaseState)
    if (write is RequiredPhaseWrite.Acknowledged) {
      progress.recordAcknowledgedPhaseStart(phaseState.phaseId, phaseState.attemptCount)
      reserveReviewPassAfterPhaseState(phaseState.reviewPassNumber)
    }
    return write
  }

  internal fun applyPersistedPhaseCompletion(
    output: FeatureTaskRuntimePhaseOutput,
    reviewPassNumber: Int?,
  ) {
    progress.reserveReviewPass(reviewPassNumber)
    progress.recordCompleted(output)
  }

  internal fun persistAuthoritativePhaseCompletion(
    recorder: PhaseRunRecords,
    phaseState: FeatureTaskRuntimePhaseStateRequest,
    inMemoryOutput: FeatureTaskRuntimePhaseOutput,
    qualifiedFailedRepairSha: String? = null,
  ): RequiredPhaseWrite {
    val persisted = recorder.recordCompletedPhase(phaseState)
    if (persisted == RequiredPhaseWrite.Acknowledged) {
      progress.reserveReviewPass(phaseState.reviewPassNumber)
      progress.recordCompleted(inMemoryOutput, qualifiedFailedRepairSha)
    }
    return persisted
  }

  internal fun persistGoalReviewPhaseCompletion(
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

  internal fun persistReviewGenerationInvalidation(
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

  internal fun persistCarriedForwardPhaseCompletion(
    recorder: PhaseRunRecords,
    phaseState: FeatureTaskRuntimePhaseStateRequest,
    inMemoryOutput: FeatureTaskRuntimePhaseOutput,
    clearPendingReentry: Boolean,
  ): Boolean {
    val persisted = recorder.recordCompletedPhase(phaseState)
    if (persisted == RequiredPhaseWrite.Acknowledged) {
      applyCarriedForwardInMemoryCompletion(inMemoryOutput, clearPendingReentry)
    }
    return persisted == RequiredPhaseWrite.Acknowledged
  }

  internal fun recordSyntheticUpstreamCompletion(output: FeatureTaskRuntimePhaseOutput) {
    progress.recordCompleted(output)
  }

  internal fun transitionTerminalBlocked(report: FeatureTaskRuntimeRunReport.Blocked) {
    session.transitionToBlocked(report)
  }

  internal fun transitionTerminalPaused(report: FeatureTaskRuntimeRunReport.Paused) {
    session.transitionToPaused(report)
  }

  internal fun transitionTerminalDecomposed(report: FeatureTaskRuntimeRunReport.Decomposed) {
    session.transitionToDecomposed(report)
  }

  internal fun restartAttemptBudgetForRelaunch(phaseId: String) {
    progress.restartAttemptBudget(phaseId)
  }

  internal fun beginPhaseAttemptLaunchAfterRequiredStart(
    phaseId: String,
    operatorReopened: Boolean,
    preserveAttemptBudget: Boolean = false,
  ) {
    if (operatorReopened && !preserveAttemptBudget) {
      progress.restartAttemptBudget(phaseId)
    }
    progress.recordPhaseLaunched(phaseId)
  }

  internal fun observeResolvedBranchForCheckpoint(branch: String?) {
    session.transitionResolvedBranch(branch)
  }

  internal fun markCheckpointOwnershipDecided() {
    session.markCheckpointOwnershipDecided()
  }

  internal fun markRecordRejectionSettlementPending() {
    session.markRecordRejectionSettlementPending()
  }

  internal fun recordPhaseContentIdentities(
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

  internal fun consumeRecordRejectionSettlementAdvance(): FeatureTaskRuntimeVerdict? {
    if (!session.recordRejectionSettlementPending) return null
    session.clearRecordRejectionSettlementPending()
    return FeatureTaskRuntimeVerdict.RECORD_REJECTED
  }

  internal fun clearPersistedBlockAfterUpstreamRecovery(phaseId: String) {
    progress.clearPersistedBlock(phaseId)
  }

  internal fun discardStaleResumedReentry(loopId: String) {
    progress.discardStaleReentry(loopId)
  }

  internal fun clearRecoveredBranchSetupBlock(phaseId: String) {
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
