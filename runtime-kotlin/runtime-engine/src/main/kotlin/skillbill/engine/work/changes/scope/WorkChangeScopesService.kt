package skillbill.engine.work.changes.scope

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.workflow.identity.status.WorkPaginationLimits
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.goal.GoalRunnerStatusPort
import skillbill.engine.goal.GoalRunnerStatusRequest
import skillbill.engine.work.WorkBoardItemProjection
import skillbill.error.core.SkillBillRuntimeException
import skillbill.goalrunner.commitPushResultArtifact
import skillbill.goalrunner.goalSubtaskReviewArtifacts
import skillbill.goalrunner.goalWorkBranch
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.GoalWorktreeState
import skillbill.ports.idestatus.model.GoalWorktreeView
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.work.model.WorkGoalSubtaskPage
import skillbill.ports.work.model.WorkItemKind
import skillbill.ports.workchanges.model.WorkGoalScopesResult
import skillbill.ports.workchanges.model.WorkScopeSubtask
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.decompositionStatus

@Inject
public class WorkChangeScopesService(
  private val database: DatabaseSessionFactory,
  private val workBoardItemProjection: WorkBoardItemProjection,
  private val goalRunnerStatusService: GoalRunnerStatusPort,
  private val diagnostics: RuntimeDiagnostics,
) {
  public fun goalSubtaskScopes(goalWorkflowId: String): WorkGoalScopesResult {
    if (!database.databaseExists()) return WorkGoalScopesResult.Unavailable("Skill Bill database is not present.")
    return try {
      database.read { unit -> readScopes(goalWorkflowId, unit) }
    } catch (error: SkillBillRuntimeException) {
      val reason = error.message ?: "goal subtask read failed"
      unavailable(goalWorkflowId, reason, error)
    }
  }

  private fun readScopes(
    goalWorkflowId: String,
    unit: UnitOfWork,
  ): WorkGoalScopesResult {
    val goal =
      unit.workList.findByWorkflowId(goalWorkflowId).singleOrNull { it.workflowKind == WorkItemKind.FEATURE_GOAL }
        ?: return WorkGoalScopesResult.NotAGoal(goalWorkflowId)
    val root =
      workBoardItemProjection.resolvedWorkItems(listOf(goal), unit).single().repositoryIdentity
        ?.let(workBoardItemProjection::decodeRepositoryRoot)
        ?: return unavailable(goalWorkflowId, "goal $goalWorkflowId has no usable repository identity")
    val goalWorktree = workBoardItemProjection.goalWorktreeView(goalWorkflowId, unit)
    val worktreeRoot =
      goalWorktree?.takeIf { it.state != GoalWorktreeState.REMOVED }?.path ?: root.toString()
    return listScopes(goalWorkflowId, goal.issueKey, worktreeRoot, goalWorktree, unit)
  }

  private fun listScopes(
    goalWorkflowId: String,
    issueKey: String?,
    worktreeRoot: String,
    goalWorktree: GoalWorktreeView?,
    unit: UnitOfWork,
  ): WorkGoalScopesResult {
    val key = issueKey ?: return unavailable(goalWorkflowId, "goal $goalWorkflowId has no issue key")
    val pages = readPages(goalWorkflowId, key, unit) ?: return WorkGoalScopesResult.NotAGoal(goalWorkflowId)
    val targetBranch = pages.manifest.baseBranch.takeIf(String::isNotBlank)
    val committedHeadBranch =
      if (goalWorktree?.state == GoalWorktreeState.REMOVED) {
        pages.manifest.goalWorkBranch() ?: pages.manifest.stackBranches.lastOrNull()?.branch
      } else {
        null
      }
    if (goalWorktree?.state == GoalWorktreeState.REMOVED && (targetBranch == null || committedHeadBranch == null)) {
      return unavailable(goalWorkflowId, "removed goal has no resolvable target and head branch")
    }
    return WorkGoalScopesResult.Listed(
      worktreeRoot = worktreeRoot,
      targetBranch = targetBranch,
      committedHeadBranch = committedHeadBranch,
      rangesSupported = pages.manifest.executionModel == DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
      subtasks = scopeSubtasks(goalWorkflowId, pages, unit),
    )
  }

  private fun readPages(
    goalWorkflowId: String,
    issueKey: String,
    unit: UnitOfWork,
  ): ScopePages? {
    val first = unit.workList.goalSubtaskPage(goalWorkflowId, 0, WorkPaginationLimits.MAX_PAGE_SIZE) ?: return null
    val subtasks = mutableListOf<DecompositionSubtask>()
    var page: WorkGoalSubtaskPage? = first
    while (page != null) {
      val raw = page.manifest.subtasks
      subtasks += goalRunnerStatusService.reconcileSubtaskPage(goalWorkflowId, GoalRunnerStatusRequest(issueKey), raw)
      page = if (raw.size < WorkPaginationLimits.MAX_PAGE_SIZE) null else nextPage(goalWorkflowId, raw.last().id, unit)
    }
    return ScopePages(first.manifest, first.currentWorkflowId, subtasks)
  }

  private fun nextPage(
    goalWorkflowId: String,
    afterSubtaskId: Int,
    unit: UnitOfWork,
  ): WorkGoalSubtaskPage? =
    unit.workList.goalSubtaskPage(
      goalWorkflowId,
      0,
      WorkPaginationLimits.MAX_PAGE_SIZE,
      completedOnly = false,
      afterSubtaskId = afterSubtaskId,
    )

  private fun scopeSubtasks(
    goalWorkflowId: String,
    pages: ScopePages,
    unit: UnitOfWork,
  ): List<WorkScopeSubtask> {
    var previousEnd: String? = null
    return pages.subtasks.map { subtask ->
      val status = statusOf(goalWorkflowId, subtask)
      val child = subtask.workflowId?.takeIf(String::isNotBlank)?.let { childRead(it, unit) }
      val reviewBase = child?.reviewBaseSha?.takeIf(String::isNotBlank)
      val startSha =
        when {
          child == null -> null
          reviewBase != null -> reviewBase
          else -> previousEnd.also { warnStartFallback(goalWorkflowId, subtask.id, it, child) }
        }
      val endSha =
        subtask.commitSha?.takeIf(String::isNotBlank)
          ?: child?.commitSha?.takeIf(String::isNotBlank)
          ?: startSha.takeIf { status == DecompositionStatus.COMPLETED_NO_CHANGE }
      previousEnd = endSha
      val current = subtask.workflowId != null && subtask.workflowId == pages.currentWorkflowId
      WorkScopeSubtask(
        id = subtask.id,
        name = subtask.name,
        status = status,
        running = current && status == DecompositionStatus.IN_PROGRESS,
        startSha = startSha,
        endSha = endSha,
        baselineUntrackedPaths = child?.baselineUntrackedPaths.orEmpty(),
      )
    }
  }

  private fun statusOf(
    goalWorkflowId: String,
    subtask: DecompositionSubtask,
  ): DecompositionStatus =
    subtask.status.decompositionStatus() ?: DecompositionStatus.PENDING.also {
      warn(
        "work_change_scopes goal=$goalWorkflowId subtask=${subtask.id} value_used=pending " +
          "value_expected=decomposition_status cause=unknown status '${subtask.status}'",
        null,
      )
    }

  private fun childRead(
    childWorkflowId: String,
    unit: UnitOfWork,
  ): ChildRead =
    try {
      val artifacts = taskRuntimeRecordOrNull(unit.workflowStates, childWorkflowId)?.artifacts
      reviewRead(artifacts, artifacts?.commitPushResultArtifact()?.commitSha)
    } catch (error: SkillBillRuntimeException) {
      ChildRead(null, null, null, error.message ?: "child record read failed", error)
    }

  private fun taskRuntimeRecordOrNull(
    workflowStates: WorkflowStateRepository,
    workflowId: String,
  ): WorkflowStateSnapshot? =
    if (workflowStates.getFeatureTaskWorkflow(workflowId)?.mode == FeatureTaskWorkflowMode.RUNTIME) {
      workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
    } else {
      null
    }

  private fun reviewRead(
    artifacts: DurableWorkflowArtifacts?,
    commitSha: String?,
  ): ChildRead =
    try {
      val state = artifacts?.goalSubtaskReviewArtifacts()?.state
      ChildRead(
        reviewBaseSha = state?.reviewBaseSha,
        baselineUntrackedPaths = state?.baselineUntrackedPaths,
        commitSha = commitSha,
        cause = if (state == null) "review state artifact missing" else "review base sha blank",
        error = null,
      )
    } catch (error: SkillBillRuntimeException) {
      ChildRead(null, null, commitSha, error.message ?: "review state read failed", error)
    }

  private fun warnStartFallback(
    goalWorkflowId: String,
    subtaskId: Int,
    used: String?,
    child: ChildRead,
  ) = warn(
    "work_change_scopes goal=$goalWorkflowId subtask=$subtaskId value_used=${used ?: "none"} " +
      "value_expected=goal_subtask_review_state cause=${child.cause}",
    child.error,
  )

  private fun unavailable(
    goalWorkflowId: String,
    reason: String,
    error: Throwable? = null,
  ): WorkGoalScopesResult {
    warn(
      "work_change_scopes goal=$goalWorkflowId value_used=unavailable value_expected=goal_subtask_list cause=$reason",
      error,
    )
    return WorkGoalScopesResult.Unavailable(reason)
  }

  private fun warn(
    message: String,
    cause: Throwable?,
  ) = RuntimeDiagnosticsBestEffortWarning.record(diagnostics, message, cause)

  private data class ScopePages(
    val manifest: DecompositionManifest,
    val currentWorkflowId: String?,
    val subtasks: List<DecompositionSubtask>,
  )

  private data class ChildRead(
    val reviewBaseSha: String?,
    val baselineUntrackedPaths: List<String>?,
    val commitSha: String?,
    val cause: String,
    val error: Throwable?,
  )
}
