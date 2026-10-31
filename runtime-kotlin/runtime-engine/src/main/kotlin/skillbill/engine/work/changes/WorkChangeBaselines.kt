package skillbill.engine.work.changes

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.goalrunner.persist.taskRuntimeRecordOrNull
import skillbill.engine.work.IdeStatusProjectionContext
import skillbill.error.core.SkillBillRuntimeException
import skillbill.goalrunner.goalSubtaskReviewArtifacts
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.workflow.taskruntime.artifact.resolvedBranch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch

internal fun workChangeBaseline(
  standalone: StandalonePhaseStatusRecord?,
  goalStatus: GoalRunnerStatusProjection?,
  item: WorkItem?,
  context: IdeStatusProjectionContext,
  diagnostics: RuntimeDiagnostics,
): WorkChangeBaseline? {
  val worktreeRoot = standalone?.monitorTarget?.worktreePath ?: context.repoRoot.toString()
  val workflowStates = context.unitOfWork.workflowStates
  return when {
    standalone != null -> WorkChangeBaseline.TargetMergeBase(worktreeRoot, targetBranch = null)
    item?.workflowKind == WorkItemKind.FEATURE_GOAL ->
      goalStatus?.currentChildWorkflowId?.let { childId ->
        subtaskBaseline(worktreeRoot, item.workflowId, childId, workflowStates, diagnostics)
      }
    item?.workflowKind == WorkItemKind.FEATURE_TASK_RUNTIME ->
      runBaseline(worktreeRoot, resolvedBranchOrNull(item.workflowId, workflowStates, diagnostics))
    else -> WorkChangeBaseline.TargetMergeBase(worktreeRoot, targetBranch = null)
  }
}

private fun subtaskBaseline(
  worktreeRoot: String,
  goalWorkflowId: String,
  childWorkflowId: String,
  workflowStates: WorkflowStateRepository,
  diagnostics: RuntimeDiagnostics,
): WorkChangeBaseline {
  val read =
    try {
      Result.success(
        taskRuntimeRecordOrNull(workflowStates, childWorkflowId)?.artifacts?.goalSubtaskReviewArtifacts()?.state,
      )
    } catch (error: SkillBillRuntimeException) {
      Result.failure(error)
    }
  val state = read.getOrNull()
  val baseSha = state?.reviewBaseSha?.takeIf(String::isNotBlank)
  if (state != null && baseSha != null) {
    return WorkChangeBaseline.SubtaskStart(worktreeRoot, baseSha, state.baselineUntrackedPaths)
  }
  val error = read.exceptionOrNull()
  val cause = error?.message ?: if (state == null) "review state artifact missing" else "review base sha blank"
  RuntimeDiagnosticsBestEffortWarning.record(
    diagnostics,
    "work_change_baseline goal=$goalWorkflowId child=$childWorkflowId " +
      "value_used=target_merge_base value_expected=goal_subtask_review_state cause=$cause",
    error,
  )
  val baseBranch = resolvedBranchOrNull(childWorkflowId, workflowStates, diagnostics)?.baseBranch
  return WorkChangeBaseline.TargetMergeBase(worktreeRoot, baseBranch)
}

private fun runBaseline(
  worktreeRoot: String,
  resolved: FeatureTaskRuntimeResolvedBranch?,
): WorkChangeBaseline =
  resolved?.reviewBaseSha?.takeIf(String::isNotBlank)?.let { baseSha ->
    WorkChangeBaseline.RunStart(worktreeRoot, baseSha, resolved.baselineUntrackedPaths)
  } ?: WorkChangeBaseline.TargetMergeBase(worktreeRoot, resolved?.baseBranch)

private fun resolvedBranchOrNull(
  workflowId: String,
  workflowStates: WorkflowStateRepository,
  diagnostics: RuntimeDiagnostics,
): FeatureTaskRuntimeResolvedBranch? =
  try {
    taskRuntimeRecordOrNull(workflowStates, workflowId)?.artifacts?.resolvedBranch()
  } catch (error: SkillBillRuntimeException) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "work_change_baseline workflow=$workflowId value_used=no_resolved_branch " +
        "value_expected=feature_task_runtime_resolved_branch cause=${error.message}",
      error,
    )
    null
  }
