package skillbill.engine.goalrunner.execution.core

import skillbill.application.decomposition.executionModel
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.lifecycle.branch.protectedBranchName
import skillbill.engine.goalrunner.execution.support.GoalRunnerAttemptState
import skillbill.engine.goalrunner.execution.support.GoalRunnerIterationResult
import skillbill.engine.goalrunner.execution.support.branchPlanFor
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerObservabilityLivenessClass
import skillbill.engine.goalrunner.model.GoalRunnerRunEvent
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerLedgerContext
import skillbill.engine.goalrunner.persist.GoalRunnerLedgerRecorder
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.sweep.GoalPlanningSweep
import skillbill.engine.goalrunner.status.stopped
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilitySignal
import skillbill.engine.goalrunner.telemetry.GoalRunnerObservabilitySubject
import skillbill.goalrunner.GoalRunnerPlanner
import skillbill.goalrunner.model.GoalRunnerRunReport
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.withBlockedSelection
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.DecompositionSubtaskAction

internal class GoalRunnerGoalLoop(
  private val manifestStore: GoalRunnerManifestStore,
  private val goalPlanningSweep: GoalPlanningSweep,
  private val finalization: GoalRunnerFinalization,
  private val selectedSubtaskLoop: GoalRunnerSelectedSubtaskLoop,
  private val pauseBoundary: GoalRunnerPauseBoundary,
  private val progressReader: GoalRunnerProgressReader,
) {
  private val attemptState = GoalRunnerAttemptState()

  internal fun driveGoalLoop(args: DriveGoalLoopArgs): GoalRunnerIterationResult {
    var state = args.initialState
    var currentPlanning = args.planning
    var terminalReport: GoalRunnerRunReport? = preflightPolicyBlockedReport(state, args.request, args.ledger)
    while (terminalReport == null) {
      val pause = pauseBoundary.pauseBeforeLaunch(state)
      if (pause != null) {
        state = pause.state
        terminalReport = pause.report
      } else {
        val selection = GoalRunnerPlanner.selectNext(state.manifest)
        when (selection) {
          is GoalRunnerSelection.Done ->
            terminalReport =
              finalization.finalizeGoal(
                state,
                args.request,
                attemptState.attempted,
                args.ledger,
              )
          is GoalRunnerSelection.Blocked ->
            blockedSelectionIteration(
              BlockedSelectionIterationArgs(
                state = state,
                selection = selection,
                request = args.request,
                attempted = attemptState.attempted,
                observability = args.observability,
                ledger = args.ledger,
              ),
            ).also { result ->
              state = result.state
              terminalReport = result.report
            }
          is GoalRunnerSelection.Run -> {
            val advanced = advanceRunSelection(state, selection, currentPlanning, args)
            state = advanced.state
            currentPlanning = advanced.planning
            terminalReport = advanced.report
          }
        }
      }
      args.telemetryEmitter.emitNewlyTerminalSubtasks(state.manifest, attemptState.attempted)
    }
    return GoalRunnerIterationResult(state, requireNotNull(terminalReport), attemptState.attempted)
  }

  private data class RunSelectionAdvance(
    val state: GoalRunnerManifestState,
    val planning: GoalPlanningSweepOutcome.PreparedAll,
    val report: GoalRunnerRunReport?,
  )

  private fun advanceRunSelection(
    state: GoalRunnerManifestState,
    selection: GoalRunnerSelection.Run,
    currentPlanning: GoalPlanningSweepOutcome.PreparedAll,
    args: DriveGoalLoopArgs,
  ): RunSelectionAdvance {
    var planning = currentPlanning
    val planningHydrationMissing =
      planning.identity != null &&
        planning.hydrationFor(selection.decision.subtask.id) == null
    if (planningHydrationMissing) {
      when (val refreshedPlanning = goalPlanningSweep.prepare(state, args.request)) {
        is GoalPlanningSweepOutcome.PreparedAll -> planning = refreshedPlanning
        is GoalPlanningSweepOutcome.Stopped -> {
          return RunSelectionAdvance(
            state = state,
            planning = planning,
            report =
              stopped(
                StoppedReportArgs(
                  issueKey = refreshedPlanning.issueKey,
                  attempted = attemptState.attempted,
                  subtaskId = refreshedPlanning.currentSubtaskId,
                  reason = refreshedPlanning.reason,
                  blockedReason = refreshedPlanning.blockedReason,
                  workflowId = state.manifest.workflowIdFor(refreshedPlanning.currentSubtaskId),
                  lastResumableStep = refreshedPlanning.lastResumableStep,
                ),
              ),
          )
        }
      }
    }
    val result =
      selectedSubtaskLoop.runSelectedSubtask(
        RunSelectedSubtaskArgs(
          state = state,
          selection = selection,
          request = args.request,
          attemptedSnapshot = attemptState::attempted,
          recordAttempt = attemptState::record,
          observability = args.observability,
          ledger = args.ledger,
          telemetryEmitter = args.telemetryEmitter,
          planning = planning,
        ),
        args.pendingState,
      )
    return RunSelectionAdvance(result.state, planning, result.report)
  }

  private fun blockedSelectionIteration(args: BlockedSelectionIterationArgs): GoalRunnerIterationResult {
    val state = args.state
    val selection = args.selection
    val request = args.request
    val observability = args.observability
    val ledger = args.ledger
    val attempted = attemptState.attempted
    val saved =
      manifestStore.save(
        state.copy(manifest = state.manifest.withBlockedSelection(selection.subtask.id, selection.reason)),
      )
    selection.subtask.workflowId?.takeIf(String::isNotBlank)?.let { workflowId ->
      observability.record(
        subject = GoalRunnerObservabilitySubject(workflowId, saved.manifest.issueKey, selection.subtask.id),
        signal =
          GoalRunnerObservabilitySignal(
            workflowPhase = selection.subtask.lastResumableStep.orEmpty().ifBlank { "preplan" },
            livenessClass = GoalRunnerObservabilityLivenessClass.BLOCK,
            activitySummary = selection.reason,
          ),
      )
      ledger.recordLedgerEntry(
        GoalRunnerLedgerContext.PolicyBlock(
          workflowId = workflowId,
          issueKey = saved.manifest.issueKey,
          subtaskId = selection.subtask.id,
          progress = progressReader.safeProgress(workflowId),
          blockedReason = selection.reason,
          stopReason = GoalRunnerStopReason.DEPENDENCIES_BLOCKED.name.lowercase(),
        ),
      )
    }
    request.eventSink.emit(
      GoalRunnerRunEvent.SubtaskStopped(
        issueKey = saved.manifest.issueKey,
        subtaskId = selection.subtask.id,
        reason = GoalRunnerStopReason.DEPENDENCIES_BLOCKED.name.lowercase(),
        blockedReason = selection.reason,
        currentStepId = selection.subtask.lastResumableStep?.takeIf(String::isNotBlank),
      ),
    )
    return GoalRunnerIterationResult(
      state = saved,
      report =
        stopped(
          StoppedReportArgs(
            issueKey = saved.manifest.issueKey,
            attempted = attempted,
            subtaskId = selection.subtask.id,
            reason = GoalRunnerStopReason.DEPENDENCIES_BLOCKED,
            blockedReason = selection.reason,
            workflowId = selection.subtask.workflowId,
            lastResumableStep = selection.subtask.lastResumableStep.orEmpty().ifBlank { "preplan" },
          ),
        ),
    )
  }

  private fun preflightPolicyBlockedReport(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    ledger: GoalRunnerLedgerRecorder,
  ): GoalRunnerRunReport.Stopped? {
    val violation = protectedBranchViolation(state.manifest)
    val selection = GoalRunnerPlanner.selectNext(state.manifest)
    return if (violation == null || selection is GoalRunnerSelection.Done) {
      null
    } else {
      blockedByPreflightPolicy(state, request, violation, selection, ledger)
    }
  }

  private fun blockedByPreflightPolicy(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    violation: String,
    selection: GoalRunnerSelection,
    ledger: GoalRunnerLedgerRecorder,
  ): GoalRunnerRunReport.Stopped {
    val subtaskId =
      when (selection) {
        is GoalRunnerSelection.Run -> selection.decision.subtask.id
        is GoalRunnerSelection.Blocked -> selection.subtask.id
        is GoalRunnerSelection.Done -> 0
      }
    val blockedManifest =
      if (subtaskId > 0) {
        state.manifest.withBlockedSelection(subtaskId, violation)
      } else {
        state.manifest.copy(
          status = DecompositionStatus.BLOCKED.wireValue,
          currentSubtaskIntent =
            CurrentSubtaskIntent(subtaskId = 0, action = DecompositionSubtaskAction.BLOCKED.wireValue),
        )
      }
    val saved = manifestStore.save(state.copy(manifest = blockedManifest))
    ledger.recordLedgerEntry(
      GoalRunnerLedgerContext.PolicyBlock(
        workflowId = saved.parentWorkflowId,
        issueKey = saved.manifest.issueKey,
        subtaskId = subtaskId,
        progress = progressReader.safeProgress(saved.parentWorkflowId),
        blockedReason = violation,
        stopReason = GoalRunnerStopReason.POLICY_BLOCKED.name.lowercase(),
      ),
    )
    if (subtaskId > 0) {
      request.eventSink.emit(
        GoalRunnerRunEvent.SubtaskStopped(
          issueKey = saved.manifest.issueKey,
          subtaskId = subtaskId,
          reason = GoalRunnerStopReason.POLICY_BLOCKED.name.lowercase(),
          blockedReason = violation,
          currentStepId = "create_branch",
        ),
      )
    }
    return stopped(
      StoppedReportArgs(
        issueKey = saved.manifest.issueKey,
        attempted = emptyList(),
        subtaskId = subtaskId,
        reason = GoalRunnerStopReason.POLICY_BLOCKED,
        blockedReason = violation,
        workflowId = null,
        lastResumableStep = "create_branch",
      ),
    )
  }

  private fun protectedBranchViolation(manifest: DecompositionManifest): String? =
    if (manifest.executionModel == DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK) {
      protectedBranchViolationMessage(manifest)
    } else {
      null
    }

  private fun protectedBranchViolationMessage(manifest: DecompositionManifest): String? {
    val selection = GoalRunnerPlanner.selectNext(manifest) as? GoalRunnerSelection.Run
    val selectedBranch =
      selection
        ?.decision
        ?.subtask
        ?.id
        ?.let { subtaskId -> manifest.branchPlanFor(subtaskId).branch }
        ?.takeIf(String::isNotBlank)
        ?: manifest.featureBranch
    val protectedBranch =
      protectedBranchName(selectedBranch)
        ?: return null
    return "Goal runner policy blocked execution because same-branch mode resolved to protected branch " +
      "'$protectedBranch'. Set decomposition feature/subtask branches to a non-protected branch " +
      "(for example `feat/${manifest.issueKey}-${manifest.featureName}`) before resuming."
  }
}
