package skillbill.engine.work.changes

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.phase.core.resolvedBranchFromWorkflowArtifacts
import skillbill.error.core.SkillBillRuntimeException
import skillbill.goalrunner.goalWorkBranch
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.GoalWorktreeState
import skillbill.ports.idestatus.model.GoalWorktreeView
import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkChangeBaseline
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import java.nio.file.Path

internal data class WorkChangeBaselineEnvironment(
  val repoRoot: Path,
  val workflowStates: WorkflowStateRepository,
  val diagnostics: RuntimeDiagnostics,
  val goalWorktree: GoalWorktreeView? = null,
)

internal fun workChangeBaseline(
  standalone: StandalonePhaseStatusRecord?,
  goalManifest: DecompositionManifest?,
  item: WorkItem?,
  environment: WorkChangeBaselineEnvironment,
): WorkChangeBaseline? {
  val worktreeRoot =
    environment.goalWorktree?.takeIf { it.state != GoalWorktreeState.REMOVED }?.path
      ?: standalone?.monitorTarget?.worktreePath
      ?: environment.repoRoot.toString()
  return when {
    environment.goalWorktree?.state == GoalWorktreeState.REMOVED && goalManifest != null ->
      committedBranchBaseline(goalManifest, environment.repoRoot)
    environment.goalWorktree != null && item?.workflowKind == WorkItemKind.FEATURE_GOAL ->
      goalManifest?.baseBranch?.takeIf(String::isNotBlank)
        ?.let { WorkChangeBaseline.TargetMergeBase(worktreeRoot, it) }
    standalone != null -> WorkChangeBaseline.TargetMergeBase(worktreeRoot, targetBranch = null)
    item?.workflowKind == WorkItemKind.FEATURE_GOAL ->
      goalManifest?.baseBranch?.takeIf(String::isNotBlank)?.let { WorkChangeBaseline.TargetMergeBase(worktreeRoot, it) }
    item?.workflowKind == WorkItemKind.FEATURE_TASK_RUNTIME -> {
      val resolved = resolvedBranchOrNull(item.workflowId, environment.workflowStates, environment.diagnostics)
      WorkChangeBaseline.TargetMergeBase(worktreeRoot, resolved?.baseBranch?.takeIf(String::isNotBlank))
    }
    else -> WorkChangeBaseline.TargetMergeBase(worktreeRoot, targetBranch = null)
  }
}

private fun committedBranchBaseline(
  manifest: DecompositionManifest,
  root: Path,
): WorkChangeBaseline? {
  val target = manifest.baseBranch.takeIf(String::isNotBlank)
  val head = manifest.goalWorkBranch() ?: manifest.stackBranches.lastOrNull()?.branch
  return if (target != null && head != null) {
    WorkChangeBaseline.CommittedBranchRange(
      root.toString(),
      target,
      head,
    )
  } else {
    null
  }
}

private fun resolvedBranchOrNull(
  workflowId: String,
  workflowStates: WorkflowStateRepository,
  diagnostics: RuntimeDiagnostics,
): FeatureTaskRuntimeResolvedBranch? =
  try {
    workflowStates
      .readFeatureTaskArtifacts(
        workflowId,
        setOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH),
      )
      ?.let(::resolvedBranchFromWorkflowArtifacts)
  } catch (error: SkillBillRuntimeException) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "work_change_baseline workflow=$workflowId value_used=no_resolved_branch " +
        "value_expected=feature_task_runtime_resolved_branch cause=${error.message}",
      error,
    )
    null
  }
