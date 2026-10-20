package skillbill.engine.goalrunner.execution.core

import me.tatarka.inject.annotations.Inject
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerReconcileGate
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerLedgerContext
import skillbill.engine.goalrunner.persist.GoalRunnerLedgerRecorder
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore

@Inject
class GoalRunnerFinalReconciliation(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val progressReader: GoalRunnerProgressReader,
) {
  fun reconcile(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    ledger: GoalRunnerLedgerRecorder,
  ): GoalRunnerManifestState {
    outcomeStore.reconcileAuthoritativeOutcomes(
      issueKey = state.manifest.issueKey,
      activeWorkflowIds = emptySet(),
      gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
      repoRoot = request.repoRoot,
    )
    state.manifest.subtasks
      .lastOrNull { subtask -> !subtask.workflowId.isNullOrBlank() }
      ?.let { subtask ->
        ledger.recordLedgerEntry(
          GoalRunnerLedgerContext.FinalReconciledOutcome(
            workflowId = subtask.workflowId,
            issueKey = state.manifest.issueKey,
            subtaskId = subtask.id,
            progress = subtask.workflowId?.let { progressReader.safeProgress(it) },
            blockedReason = null,
            finalReconciledResult = "goal_finalize status=${state.manifest.status}",
            stopReason = null,
            diagnosticClass = null,
            recoverableJsonPresent = null,
            nextSafeAction = null,
            attemptDurationMillis = null,
            causingLoopEntry = null,
            reAttemptCause = null,
            findingsInScope = null,
          ),
        )
      }
    return manifestStore.save(state)
  }
}
