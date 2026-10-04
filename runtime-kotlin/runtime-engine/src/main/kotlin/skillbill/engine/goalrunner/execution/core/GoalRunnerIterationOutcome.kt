package skillbill.engine.goalrunner.execution.core

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseQuery
import skillbill.engine.goalrunner.execution.support.CHILD_WORKFLOW_BLOCK_REASONS
import skillbill.engine.goalrunner.execution.support.CompletedIterationArgs
import skillbill.engine.goalrunner.execution.support.GoalRunnerIterationPendingState
import skillbill.engine.goalrunner.execution.support.GoalRunnerIterationResult
import skillbill.engine.goalrunner.execution.support.MAX_VALIDATION_QUALITY_RETRIES
import skillbill.engine.goalrunner.execution.support.StoppedIterationArgs
import skillbill.engine.goalrunner.execution.support.StoppedIterationResultArgs
import skillbill.engine.goalrunner.execution.support.causingLoopEntryFor
import skillbill.engine.goalrunner.execution.support.confirmedAliveKillDiagnosticClass
import skillbill.engine.goalrunner.execution.support.emitStoppedSubtaskEvent
import skillbill.engine.goalrunner.execution.support.reAttemptCauseFor
import skillbill.engine.goalrunner.execution.support.recoverySafeAction
import skillbill.engine.goalrunner.findings.UnaddressedFindingsLedgerService
import skillbill.engine.goalrunner.findings.resolveUnaddressedFindingsLedger
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerObservabilityLivenessClass
import skillbill.engine.goalrunner.model.GoalRunnerRunEvent
import skillbill.engine.goalrunner.model.GoalRunnerWorkflowProgress
import skillbill.engine.goalrunner.persist.GoalRunnerBackwardEdge
import skillbill.engine.goalrunner.persist.GoalRunnerLedgerContext
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.StoppedLedgerContextValues
import skillbill.engine.goalrunner.status.completed
import skillbill.engine.goalrunner.status.isRecoverableValidationBlock
import skillbill.engine.goalrunner.status.stopped
import skillbill.engine.goalrunner.status.supervisionEvent
import skillbill.engine.goalrunner.status.withStopDiagnostics
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilityEmitter
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilitySignal
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilitySubject
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.goalrunner.model.nextSafeAction
import skillbill.goalrunner.model.toDiagnosticClass
import skillbill.goalrunner.model.toLedgerAction
import skillbill.workflow.decomposition.knownWorkflowId
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.withCompletedSubtask
import skillbill.workflow.decomposition.withResumableSubtask
import skillbill.workflow.decomposition.withStoppedSubtask
import skillbill.workflow.decomposition.withValidationQualityRetrySubtask
import skillbill.workflow.engine.blockedStepId
import skillbill.workflow.model.decompositionStatus
import java.time.Clock

@Inject
class GoalRunnerIterationOutcome(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val finalization: GoalRunnerFinalization,
  private val unaddressedFindingsLedgerService: UnaddressedFindingsLedgerService?,
  private val progressReader: GoalRunnerProgressReader,
  private val clock: Clock,
  private val phaseQuery: FeatureTaskRuntimePhaseQuery?,
) {
  internal fun stoppedIteration(
    args: StoppedIterationArgs,
    pendingState: GoalRunnerIterationPendingState,
  ): GoalRunnerIterationResult {
    val validationQualityState = pendingState.validationQualityState
    val state = args.state
    val subtaskId = args.subtaskId
    val reconciled = args.reconciled
    val session = args.session
    val request = session.request
    val attempted = session.attempted
    val observability = session.observability
    val ledger = session.ledger
    val launchDiagnostics = args.launchDiagnostics
    val attemptStartMillis = session.attemptStartMillis
    val knownWorkflowId = state.manifest.knownWorkflowId(subtaskId, reconciled)
    val stoppedOutcome = markChildWorkflowBlockedIfNeeded(reconciled, knownWorkflowId)
    val attemptDurationMillis = attemptStartMillis?.let { clock.millis() - it }
    knownWorkflowId?.let { workflowId ->
      recordStoppedLedgerEntries(
        RecordStoppedLedgerEntriesArgs(
          workflowId = workflowId,
          state = state,
          subtaskId = subtaskId,
          stoppedOutcome = stoppedOutcome,
          reconciled = reconciled,
          launchDiagnostics = launchDiagnostics,
          attemptDurationMillis = attemptDurationMillis,
          ledger = ledger,
          request = request,
        ),
        pendingState,
      )
    }
    val blocked =
      if (stoppedOutcome.reason in GoalRunnerStopReason.RESUMABLE_STOP_REASONS) {
        state.manifest.withResumableSubtask(subtaskId, stoppedOutcome, knownWorkflowId)
      } else {
        state.manifest.withStoppedSubtask(subtaskId, stoppedOutcome, knownWorkflowId)
      }
    val blockedState = state.copy(manifest = blocked)
    val control = manifestStore.controlState(state.parentWorkflowId)
    if (!control.pauseRequested && !control.paused) {
      validationRetryIteration(blocked, stoppedOutcome, subtaskId, state, pendingState)
        ?.let { retry -> return retry }
    }
    val saved = persistStoppedBoundary(blockedState, control)
    emitStoppedObservability(saved, knownWorkflowId, subtaskId, stoppedOutcome, observability)
    request.emitStoppedSubtaskEvent(saved.manifest.issueKey, subtaskId, stoppedOutcome)
    return stoppedIterationResult(
      StoppedIterationResultArgs(
        saved = saved,
        attempted = attempted,
        subtaskId = subtaskId,
        stoppedOutcome = stoppedOutcome,
        knownWorkflowId = knownWorkflowId,
        request = request,
      ),
    )
  }

  private fun persistStoppedBoundary(
    blockedState: GoalRunnerManifestState,
    control: GoalRunnerControlState,
  ): GoalRunnerManifestState =
    if (control.pauseRequested || control.paused) {
      manifestStore.pauseAtBoundary(blockedState.copy(controlState = control))
    } else {
      manifestStore.save(blockedState)
    }

  private fun emitStoppedObservability(
    saved: GoalRunnerManifestState,
    knownWorkflowId: String?,
    subtaskId: Int,
    stoppedOutcome: GoalRunnerReconciledOutcome.Stop,
    observability: GoalRunnerObservabilityEmitter,
  ) {
    knownWorkflowId?.let { workflowId ->
      observability.record(
        subject = GoalRunnerObservabilitySubject(workflowId, saved.manifest.issueKey, subtaskId),
        signal =
          GoalRunnerObservabilitySignal(
            workflowPhase = stoppedOutcome.lastResumableStep,
            livenessClass =
              if (stoppedOutcome.reason == GoalRunnerStopReason.FAILED) {
                GoalRunnerObservabilityLivenessClass.FAILURE
              } else {
                GoalRunnerObservabilityLivenessClass.BLOCK
              },
            activitySummary = stoppedOutcome.blockedReason,
          ),
      )
    }
  }

  private fun stoppedIterationResult(args: StoppedIterationResultArgs): GoalRunnerIterationResult {
    val saved = args.saved
    val stoppedOutcome = args.stoppedOutcome
    val knownWorkflowId = args.knownWorkflowId
    val request = args.request
    val parentPaused = saved.controlState.paused
    return GoalRunnerIterationResult(
      state = saved,
      report =
        stopped(
          StoppedReportArgs(
            issueKey = saved.manifest.issueKey,
            attempted = args.attempted,
            subtaskId = args.subtaskId,
            reason = if (parentPaused) GoalRunnerStopReason.PAUSED else stoppedOutcome.reason,
            blockedReason =
              if (parentPaused) {
                "Goal paused at a durable boundary: ${saved.controlState.pauseReason}"
              } else {
                stoppedOutcome.blockedReason.withStopDiagnostics(
                  knownWorkflowId = knownWorkflowId,
                  progress =
                    knownWorkflowId?.let { workflowId ->
                      progressReader.safeProgress(workflowId)
                    },
                  liveness = stoppedOutcome.liveness,
                )
              },
            workflowId = knownWorkflowId,
            lastResumableStep = stoppedOutcome.lastResumableStep,
          ),
        ),
    )
  }

  internal fun completedIteration(args: CompletedIterationArgs): GoalRunnerIterationResult {
    val state = args.state
    val subtaskId = args.subtaskId
    val reconciled = args.reconciled
    val session = args.session
    val request = session.request
    val observability = session.observability
    val ledger = session.ledger
    val attemptStartMillis = session.attemptStartMillis
    val completedTransition =
      manifestStore.saveCompletedSubtaskAtBoundary(
        state.copy(manifest = state.manifest.withCompletedSubtask(subtaskId, reconciled)),
        subtaskId,
      )
    val completed = completedTransition.state
    finalization.pruneCompletedCheckpointRefs(completed, subtaskId, reconciled, request, observability)
    finalization.deleteCompletedSubtaskSpecScratch(completed.manifest, subtaskId, request)
    recordCompletedSubtask(
      RecordCompletedSubtaskArgs(
        completed = completed,
        subtaskId = subtaskId,
        reconciled = reconciled,
        request = request,
        observability = observability,
        ledger = ledger,
        attemptStartMillis = attemptStartMillis,
      ),
    )
    return if (!completedTransition.paused) {
      GoalRunnerIterationResult(state = completed)
    } else {
      GoalRunnerIterationResult(
        state = completed,
        report =
          stopped(
            StoppedReportArgs(
              issueKey = completed.manifest.issueKey,
              attempted = emptyList(),
              subtaskId = subtaskId,
              reason = GoalRunnerStopReason.PAUSED,
              blockedReason = "Goal paused at a durable boundary: ${completed.controlState.pauseReason}",
              workflowId = reconciled.workflowId,
              lastResumableStep = reconciled.lastResumableStep,
            ),
          ),
      )
    }
  }

  fun safeProgress(workflowId: String): GoalRunnerWorkflowProgress? = progressReader.safeProgress(workflowId)

  private fun recordStoppedLedgerEntries(
    args: RecordStoppedLedgerEntriesArgs,
    pendingState: GoalRunnerIterationPendingState,
  ) {
    val validationQualityState = pendingState.validationQualityState
    val workflowId = args.workflowId
    val state = args.state
    val subtaskId = args.subtaskId
    val stoppedOutcome = args.stoppedOutcome
    val reconciled = args.reconciled
    val launchDiagnostics = args.launchDiagnostics
    val attemptDurationMillis = args.attemptDurationMillis
    val ledger = args.ledger
    val request = args.request
    val progress = progressReader.safeProgress(workflowId)
    val childLoopIterations = outcomeStore.childWorkflowLoopIterations(workflowId)
    val reAttemptCause = reAttemptCauseFor(stoppedOutcome.reason, childLoopIterations)
    val causingLoopEntry = causingLoopEntryFor(childLoopIterations)
    val nextSafeAction =
      launchDiagnostics?.nextSafeAction ?: recoverySafeAction(
        issueKey = state.manifest.issueKey,
        subtaskId = subtaskId,
        progress = progress,
        fallback = stoppedOutcome.reason.nextSafeAction(),
        subtaskStatus = state.manifest.subtasks.firstOrNull { it.id == subtaskId }?.status?.decompositionStatus(),
      )
    val findingsInScope =
      resolveUnaddressedFindingsLedger(
        unaddressedFindingsLedgerService,
        state.manifest.issueKey,
      )?.findings?.count { it.subtaskId == subtaskId }
    ledger.recordLedgerEntry(
      progress.stoppedLedgerContext(
        args,
        reAttemptCause,
        causingLoopEntry,
        nextSafeAction,
        findingsInScope,
      ),
    )
    childLoopIterations.forEach { (loopId, edgeIteration) ->
      ledger.recordBackwardEdgeEntry(
        GoalRunnerBackwardEdge(
          workflowId = workflowId,
          issueKey = state.manifest.issueKey,
          subtaskId = subtaskId,
          loopId = loopId,
          edgeIteration = edgeIteration,
          progress = progress,
        ),
      )
    }
    if (reAttemptCause != null) validationQualityState.storePendingReAttemptCause(subtaskId, reAttemptCause)
    causingLoopEntry?.let { validationQualityState.storePendingCausingLoopEntry(subtaskId, it) }
  }

  private fun GoalRunnerWorkflowProgress?.stoppedLedgerContext(
    args: RecordStoppedLedgerEntriesArgs,
    reAttemptCause: String?,
    causingLoopEntry: String?,
    nextSafeAction: String,
    findingsInScope: Int?,
  ): GoalRunnerLedgerContext {
    val action = args.stoppedOutcome.reason.toLedgerAction()
    val common =
      StoppedLedgerContextValues(
        workflowId = args.workflowId,
        issueKey = args.state.manifest.issueKey,
        subtaskId = args.subtaskId,
        progress = this,
        blockedReason = args.stoppedOutcome.blockedReason,
        finalReconciledResult = args.stoppedOutcome.reason.name.lowercase(),
        stopReason = args.stoppedOutcome.reason.name.lowercase(),
        diagnosticClass =
          args.launchDiagnostics?.diagnosticClass
            ?: confirmedAliveKillDiagnosticClass(args.reconciled.liveness)
            ?: args.stoppedOutcome.reason.toDiagnosticClass(),
        recoverableJsonPresent = args.launchDiagnostics?.recoverableJsonPresent ?: false,
        nextSafeAction = nextSafeAction,
        attemptDurationMillis = args.attemptDurationMillis,
        reAttemptCause = reAttemptCause,
        causingLoopEntry = causingLoopEntry,
        findingsInScope = findingsInScope,
      )
    return when (action) {
      GoalAttemptLedgerAction.RETRY -> GoalRunnerLedgerContext.Retry(common)
      GoalAttemptLedgerAction.TIMEOUT -> GoalRunnerLedgerContext.Timeout(common)
      GoalAttemptLedgerAction.INTERRUPTION -> GoalRunnerLedgerContext.Interruption(common)
      else -> GoalRunnerLedgerContext.FinalReconciledOutcome(common)
    }
  }

  private fun validationRetryIteration(
    blocked: DecompositionManifest,
    stoppedOutcome: GoalRunnerReconciledOutcome.Stop,
    subtaskId: Int,
    state: GoalRunnerManifestState,
    pendingState: GoalRunnerIterationPendingState,
  ): GoalRunnerIterationResult? {
    val validationQualityState = pendingState.validationQualityState
    if (!stoppedOutcome.isRecoverableValidationBlock(phaseQuery)) {
      return null
    }
    val priorRetries = validationQualityState.validationQualityRetryCount(subtaskId)
    if (priorRetries >= MAX_VALIDATION_QUALITY_RETRIES) {
      return null
    }
    validationQualityState.incrementValidationQualityRetry(subtaskId)
    return GoalRunnerIterationResult(
      state =
        manifestStore.save(
          state.copy(manifest = blocked.withValidationQualityRetrySubtask(subtaskId)),
        ),
    )
  }

  private fun markChildWorkflowBlockedIfNeeded(
    reconciled: GoalRunnerReconciledOutcome.Stop,
    knownWorkflowId: String?,
  ): GoalRunnerReconciledOutcome.Stop {
    if (knownWorkflowId == null || reconciled.reason !in CHILD_WORKFLOW_BLOCK_REASONS) {
      return reconciled
    }
    val progress = progressReader.safeProgress(knownWorkflowId)
    val blockedStepId =
      outcomeStore.markBlocked(
        workflowId = knownWorkflowId,
        blockedReason = reconciled.blockedReason.withStopDiagnostics(knownWorkflowId, progress, reconciled.liveness),
        lastResumableStep = reconciled.lastResumableStep,
        supervisionEvent =
          supervisionEvent(
            reason = reconciled.reason,
            knownWorkflowId = knownWorkflowId,
            progress = progress,
            liveness = reconciled.liveness,
          ),
      )
    return blockedStepId?.takeIf(String::isNotBlank)?.let { stepId ->
      reconciled.copy(lastResumableStep = stepId)
    } ?: reconciled
  }

  private fun recordCompletedSubtask(args: RecordCompletedSubtaskArgs) {
    val completed = args.completed
    val subtaskId = args.subtaskId
    val reconciled = args.reconciled
    val request = args.request
    val observability = args.observability
    val ledger = args.ledger
    val attemptStartMillis = args.attemptStartMillis
    request.eventSink.emit(
      GoalRunnerRunEvent.SubtaskCompleted(
        issueKey = completed.manifest.issueKey,
        subtaskId = subtaskId,
        currentStepId = reconciled.lastResumableStep.takeIf(String::isNotBlank),
      ),
    )
    observability.record(
      subject = GoalRunnerObservabilitySubject(reconciled.workflowId, completed.manifest.issueKey, subtaskId),
      signal =
        GoalRunnerObservabilitySignal(
          workflowPhase = reconciled.lastResumableStep,
          livenessClass = GoalRunnerObservabilityLivenessClass.COMPLETION,
          activitySummary = "Subtask $subtaskId completed with commit ${reconciled.commitSha}.",
        ),
    )
    ledger.recordLedgerEntry(
      GoalRunnerLedgerContext.TerminalDoneCheck(
        workflowId = reconciled.workflowId,
        issueKey = completed.manifest.issueKey,
        subtaskId = subtaskId,
        progress = progressReader.safeProgress(reconciled.workflowId),
        finalReconciledResult = "complete commit=${reconciled.commitSha}",
        attemptDurationMillis = attemptStartMillis?.let { clock.millis() - it },
      ),
    )
  }
}
