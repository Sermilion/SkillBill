package skillbill.engine.goalrunner.history

import skillbill.engine.goalrunner.model.GoalChildHistoryAcceptanceRestoreResult
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.decomposition.withParentStatus
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus

internal fun restoreGoalChildHistoryAcceptance(
  unit: UnitOfWork,
  state: GoalRunnerManifestState,
  acceptance: GoalRunnerOutOfBandAcceptance,
  evidence: GoalChildHistoryEvidenceStore,
): GoalChildHistoryAcceptanceRestoreResult {
  val manifest =
    unit.workflowStates.get(
      WorkflowFamily.TASK_RUNTIME,
      state.parentWorkflowId,
    )?.decompositionRuntime()
      ?: return GoalChildHistoryAcceptanceRestoreResult.Refused("The durable goal manifest is missing.")
  val subtask =
    manifest.subtasks.singleOrNull { it.id == acceptance.subtaskId }
      ?: return GoalChildHistoryAcceptanceRestoreResult.Refused("The selected subtask is absent.")
  val existingAcceptance = unit.goalRunnerControls.outOfBandAcceptances(state.parentWorkflowId)[subtask.id]
  val restoredChild = evidence.restoredChildId(unit, state.parentWorkflowId, subtask.id)
  if (!dependenciesSatisfied(manifest, subtask)) {
    return GoalChildHistoryAcceptanceRestoreResult.Refused("The subtask has unfinished dependencies.")
  }
  val replayAcceptance =
    existingAcceptance?.takeIf {
      it.commitSha == acceptance.commitSha && it.reason == acceptance.reason
    }
  val replay =
    subtask.status.decompositionStatus() == DecompositionStatus.COMPLETE &&
      restoredChild != null && replayAcceptance != null
  return if (replay && evidence.hasRequired(unit, state.parentWorkflowId, subtask.id, requireNotNull(restoredChild))) {
    GoalChildHistoryAcceptanceRestoreResult.Restored(state.copy(manifest = manifest), requireNotNull(replayAcceptance))
  } else {
    restoreClearedChild(unit, state.copy(manifest = manifest), acceptance, evidence)
  }
}

private fun restoreClearedChild(
  unit: UnitOfWork,
  state: GoalRunnerManifestState,
  acceptance: GoalRunnerOutOfBandAcceptance,
  evidence: GoalChildHistoryEvidenceStore,
): GoalChildHistoryAcceptanceRestoreResult {
  val subtask = state.manifest.subtasks.single { it.id == acceptance.subtaskId }
  restorationRejection(state.manifest, subtask)?.let { return GoalChildHistoryAcceptanceRestoreResult.Refused(it) }
  val preserved =
    evidence.read(unit, state.parentWorkflowId).singleOrNull {
      it.subtaskId == subtask.id && it.restoreEligible &&
        it.acceptance?.let { recorded ->
          recorded.commitSha == acceptance.commitSha && recorded.reason == acceptance.reason
        } == true
    } ?: return GoalChildHistoryAcceptanceRestoreResult.Refused(
      "No preserved child and discarded acceptance match this commit. " +
        "A commit alone cannot restore history disposition.",
    )
  if (!evidence.historyReady(unit, preserved)) {
    return GoalChildHistoryAcceptanceRestoreResult.Refused(
      "The preserved child has no accepted history draft, skip, or settled legacy history disposition.",
    )
  }
  val preservedAcceptance = requireNotNull(preserved.acceptance)
  unit.goalRunnerControls.persistOutOfBandAcceptance(state.parentWorkflowId, preservedAcceptance)
  evidence.markRestored(unit, preserved)
  return GoalChildHistoryAcceptanceRestoreResult.Restored(
    state.copy(
      manifest =
        state.manifest.copy(
          subtasks =
            state.manifest.subtasks.map {
              if (it.id == subtask.id) {
                it.copy(status = DecompositionStatus.COMPLETE.wireValue, commitSha = preservedAcceptance.commitSha)
              } else {
                it
              }
            },
        ).withParentStatus(),
    ),
    preservedAcceptance,
  )
}

private fun restorationRejection(
  manifest: DecompositionManifest,
  subtask: DecompositionSubtask,
): String? {
  val clearedIdentity = subtask.workflowId == null && subtask.branch == null && subtask.commitSha == null
  val clearedProgress = subtask.blockedReason == null && subtask.lastResumableStep == null
  if (subtask.status.decompositionStatus() != DecompositionStatus.PENDING ||
    !clearedIdentity ||
    !clearedProgress
  ) {
    return "The subtask is no longer in its cleared hard-reset state."
  }
  val subtasks = manifest.subtasks.associateBy { it.id }
  return if (dependenciesSatisfied(subtasks, subtask)) null else "The subtask has unfinished dependencies."
}

private fun dependenciesSatisfied(
  manifest: DecompositionManifest,
  subtask: DecompositionSubtask,
): Boolean = dependenciesSatisfied(manifest.subtasks.associateBy { it.id }, subtask)

private fun dependenciesSatisfied(
  subtasks: Map<Int, DecompositionSubtask>,
  subtask: DecompositionSubtask,
): Boolean =
  subtask.dependencies.all {
    (it.optional && it.skipped) || subtasks[it.subtaskId]?.status.decompositionStatus() in
      setOf(DecompositionStatus.COMPLETE, DecompositionStatus.COMPLETED_NO_CHANGE, DecompositionStatus.SKIPPED)
  }
