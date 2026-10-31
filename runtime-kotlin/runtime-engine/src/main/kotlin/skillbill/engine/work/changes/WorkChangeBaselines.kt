package skillbill.engine.work.changes

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.goalrunner.persist.taskRuntimeRecordOrNull
import skillbill.engine.work.IdeStatusProjectionContext
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.taskruntime.artifact.resolvedBranch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch

/**
 * The baseline a work item's changes are diffed against: the merge base of HEAD and the work's own base branch.
 * A goal uses its manifest's base branch, so a goal stacked on another branch shows only its own changes.
 * A feature task run uses its resolved branch's base. A standalone phase, or a run with no recorded base,
 * falls back to the repository's default branch.
 */
internal fun workChangeBaseline(
  standalone: StandalonePhaseStatusRecord?,
  goalManifest: DecompositionManifest?,
  item: WorkItem?,
  context: IdeStatusProjectionContext,
  diagnostics: RuntimeDiagnostics,
): WorkChangeBaseline? {
  val worktreeRoot = standalone?.monitorTarget?.worktreePath ?: context.repoRoot.toString()
  return when {
    standalone != null -> WorkChangeBaseline.TargetMergeBase(worktreeRoot, targetBranch = null)
    item?.workflowKind == WorkItemKind.FEATURE_GOAL ->
      goalManifest?.baseBranch?.takeIf(String::isNotBlank)?.let { WorkChangeBaseline.TargetMergeBase(worktreeRoot, it) }
    item?.workflowKind == WorkItemKind.FEATURE_TASK_RUNTIME -> {
      val resolved = resolvedBranchOrNull(item.workflowId, context.unitOfWork.workflowStates, diagnostics)
      WorkChangeBaseline.TargetMergeBase(worktreeRoot, resolved?.baseBranch?.takeIf(String::isNotBlank))
    }
    else -> WorkChangeBaseline.TargetMergeBase(worktreeRoot, targetBranch = null)
  }
}

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
