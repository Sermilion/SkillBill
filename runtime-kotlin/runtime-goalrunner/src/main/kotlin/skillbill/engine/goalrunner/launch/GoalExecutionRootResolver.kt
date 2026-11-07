package skillbill.engine.goalrunner.launch

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.ProjectCheckout
import skillbill.application.workflow.model.GoalExecutionRootAccess
import skillbill.application.workflow.model.GoalExecutionRootOptions
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goal.goalRepositoryIdentity
import skillbill.engine.goal.goalRepositoryRootFromIdentity
import skillbill.engine.goalrunner.execution.core.StoppedReportArgs
import skillbill.engine.goalrunner.execution.support.branchPlanFor
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunPreparation
import skillbill.engine.goalrunner.model.GoalRunnerRunEvent
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.status.stopped
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowIgnoredFilesCopyResult
import skillbill.ports.workflow.gitops.worktree.MAX_IGNORED_FILE_BYTES
import skillbill.ports.workflow.goalworktree.GoalWorktreeResources
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
import skillbill.workflow.decomposition.baseForSubtask
import skillbill.workflow.decomposition.executionRootSubtaskId
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import java.nio.file.Files
import java.nio.file.Path

private const val MAX_LISTED_PATHS = 20
private const val MAX_RECORD_REASON = 200

@Inject
public class GoalExecutionRootResolver(
  private val manifestStore: GoalRunnerManifestStore,
  private val worktrees: GoalWorktreeResources,
  private val gitOperations: WorkflowGitOperations,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val diagnostics: RuntimeDiagnostics,
  private val checkout: ProjectCheckout,
) {
  public fun ensureForControlVerb(
    state: GoalRunnerManifestState,
    invokedRoot: Path,
    revisionSubtaskId: Int? = state.manifest.executionRootSubtaskId(),
    requireExistingBranch: Boolean = false,
  ): Path? {
    val control = manifestStore.controlState(state.parentWorkflowId)
    return when (
      val access =
        checkout.ensureGoalExecutionRoot(
          control,
          state.manifest,
          invokedRoot,
          options =
            GoalExecutionRootOptions(
              recreateCompleted = true,
              revisionSubtaskId = revisionSubtaskId,
              requireExistingBranch = requireExistingBranch,
            ),
        )
    ) {
      is GoalExecutionRootAccess.Failed -> {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "goal worktree recreation failed: seam=goal_execution_root " +
            "value_expected=worktree value_used=refused reason=${access.reason}",
        )
        null
      }
      is GoalExecutionRootAccess.Ready -> {
        if (access.recreated) {
          manifestStore.updateControlState(state.parentWorkflowId) { current ->
            current.copy(
              executionWorktreePath = access.path.toString(),
              executionWorktreeRemovedAt = null,
              executionWorktreeRetentionReason = null,
              executionWorktreeRetentionDetail = null,
              executionMainCheckoutOutcome = null,
              executionMainCheckoutDetail = null,
            )
          }
        }
        access.path
      }
    }
  }

  internal fun resolve(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalRunPreparation {
    val control = manifestStore.controlState(state.parentWorkflowId)
    val recordedPath = control.executionWorktreePath?.let(Path::of)
    val launchedChild = state.manifest.subtasks.any { !it.workflowId.isNullOrBlank() }
    return if (recordedPath == null && launchedChild) {
      val root = inFlightRoot(control, request)
      GoalRunPreparation.Prepared(state.copy(repoRoot = root), request.copy(repoRoot = root))
    } else {
      resolveWorktree(state, request, recordedPath)
    }
  }

  private fun inFlightRoot(
    control: GoalRunnerControlState,
    request: GoalRunnerRunRequest,
  ): Path {
    val identity = control.repositoryIdentity ?: return request.repoRoot
    val root = goalRepositoryRootFromIdentity(identity)?.let(repositoryEnclosingRootPort::optionalRealPath)
    if (root == null) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "goal bound root missing: seam=goal_execution_root value_expected=$identity " +
          "value_used=${request.repoRoot}",
      )
    }
    return root ?: request.repoRoot
  }

  private fun resolveWorktree(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    recordedPath: Path?,
  ): GoalRunPreparation {
    val launchingRoot = repositoryEnclosingRootPort.enclosingRepositoryRoot(request.repoRoot)
    val revision = worktreeRevision(state, launchingRoot, recordedPath)
    if (revision !is WorkflowGitOperationResult.Ok) {
      return blocked(state, request, "could not read the revision for its worktree: ${revision.error}")
    }
    val startPoint = revision.value.trim()
    return when (val worktree = worktrees.createOrAdopt(launchingRoot, request.issueKey, recordedPath, startPoint)) {
      is GoalWorktreeResult.Failed -> blocked(state, request, worktree.reason)
      is GoalWorktreeResult.Adopted -> prepared(state, request, launchingRoot, worktree.path, null)
      is GoalWorktreeResult.Created -> {
        copyIgnoredFiles(launchingRoot, worktree.path)
        val dirty = launchingCheckoutDirty(request.issueKey, launchingRoot, startPoint)
        prepared(state, request, launchingRoot, worktree.path, dirty)
      }
    }
  }

  private fun worktreeRevision(
    state: GoalRunnerManifestState,
    launchingRoot: Path,
    recordedPath: Path?,
  ): WorkflowGitOperationResult {
    if (recordedPath != null && Files.isDirectory(recordedPath)) {
      return gitOperations.headCommitSha(recordedPath)
    }
    val selectedSubtaskId = state.manifest.executionRootSubtaskId()
    val subtask = selectedSubtaskId?.let { id -> state.manifest.subtasks.firstOrNull { it.id == id } }
    val goalBranch = selectedSubtaskId?.let { state.manifest.branchPlanFor(it).branch.trim() }.orEmpty()
    if (goalBranch.isEmpty() && subtask?.hasStarted() == true) {
      return WorkflowGitOperationResult.Failed("goal branch for subtask ${subtask.id} is not recorded")
    }
    val branchRevision = branchRevision(launchingRoot, goalBranch, subtask)
    if (branchRevision != null) return branchRevision
    val base =
      selectedSubtaskId?.let(state.manifest::baseForSubtask)?.trim().orEmpty()
        .ifBlank { state.manifest.baseBranch.trim() }
    return if (base.isEmpty()) {
      gitOperations.headCommitSha(launchingRoot)
    } else {
      WorkflowGitOperationResult.Ok(value = base)
    }
  }

  private fun branchRevision(
    launchingRoot: Path,
    goalBranch: String,
    subtask: DecompositionSubtask?,
  ): WorkflowGitOperationResult? {
    if (goalBranch.isNotEmpty()) {
      val exists = gitOperations.branchExists(launchingRoot, goalBranch)
      if (exists is WorkflowGitOperationResult.Ok && exists.value.trim() == "true") {
        return WorkflowGitOperationResult.Ok(value = goalBranch)
      }
      if (exists is WorkflowGitOperationResult.Ok && subtask?.hasStarted() == true) {
        return WorkflowGitOperationResult.Failed("goal branch '$goalBranch' for subtask ${subtask.id} is missing")
      }
      if (exists !is WorkflowGitOperationResult.Ok) {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "goal branch unknown: seam=goal_worktree value_expected=$goalBranch value_used=refused " +
            "reason=${exists.error.take(MAX_RECORD_REASON)}",
        )
        return exists
      }
    }
    return null
  }

  private fun copyIgnoredFiles(
    launchingRoot: Path,
    worktree: Path,
  ) {
    when (val copied = gitOperations.copyIgnoredFiles(launchingRoot, worktree, MAX_IGNORED_FILE_BYTES)) {
      is WorkflowIgnoredFilesCopyResult.Copied ->
        if (copied.skippedPaths.isNotEmpty()) {
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "goal worktree skipped ignored files: seam=goal_worktree value_expected=copied value_used=skipped " +
              "paths=${copied.skippedPaths.take(MAX_LISTED_PATHS).joinToString(",")}",
          )
        }
      is WorkflowIgnoredFilesCopyResult.Failed ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "goal worktree could not copy ignored files: seam=goal_worktree value_expected=copied " +
            "value_used=skipped reason=${copied.error.take(MAX_RECORD_REASON)}",
        )
    }
  }

  private fun launchingCheckoutDirty(
    issueKey: String,
    launchingRoot: Path,
    revision: String,
  ): GoalRunnerRunEvent.LaunchingCheckoutDirty? {
    val status = gitOperations.worktreeStatus(launchingRoot)
    if (status !is WorkflowGitOperationResult.Ok) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "goal launch status unknown: seam=goal_launch_status value_expected=status value_used=absent " +
          "reason=${status.error.take(MAX_RECORD_REASON)}",
      )
      return null
    }
    val changedPathCount = status.value.lines().count(String::isNotBlank)
    return if (changedPathCount == 0) {
      null
    } else {
      GoalRunnerRunEvent.LaunchingCheckoutDirty(issueKey, launchingRoot.toString(), changedPathCount, revision)
    }
  }

  private fun prepared(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    launchingRoot: Path,
    worktree: Path,
    dirty: GoalRunnerRunEvent.LaunchingCheckoutDirty?,
  ): GoalRunPreparation {
    val current = manifestStore.controlState(state.parentWorkflowId)
    val path = worktree.toString()
    val control =
      if (current.executionWorktreePath == path && dirty == null) {
        current
      } else {
        manifestStore.updateControlState(state.parentWorkflowId) { latest ->
          latest.copy(
            executionWorktreePath = path,
            repositoryIdentity =
              if (latest.executionWorktreePath == null) {
                goalRepositoryIdentity(launchingRoot, repositoryEnclosingRootPort)
              } else {
                latest.repositoryIdentity
              },
            launchWarning = dirty?.warning ?: latest.launchWarning,
          )
        }
      }
    dirty?.let(request.eventSink::emit)
    return GoalRunPreparation.Prepared(
      state.copy(repoRoot = worktree, controlState = control),
      request.copy(repoRoot = worktree),
    )
  }

  private fun blocked(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    reason: String,
  ): GoalRunPreparation =
    GoalRunPreparation.PreparationBlocked(
      stopped(
        StoppedReportArgs(
          issueKey = request.issueKey,
          attempted = emptyList(),
          subtaskId = 0,
          reason = GoalRunnerStopReason.BLOCKED,
          blockedReason = "Goal '${request.issueKey}' cannot start in its own worktree: $reason",
          workflowId = state.parentWorkflowId,
          lastResumableStep = "goal_assessment",
        ),
      ),
    )
}

internal fun resolveGoalExecutionRoot(
  state: GoalRunnerManifestState,
  invokedRoot: Path,
): Path =
  Path.of(
    FeatureTaskExecutionIdentityPolicy.goalExecutionRootCandidate(
      state.controlState.executionWorktreePath,
      state.manifest.subtasks.any { !it.workflowId.isNullOrBlank() },
      state.controlState.repositoryIdentity,
      invokedRoot.toString(),
    ),
  )

internal fun existingGoalExecutionRoot(
  state: GoalRunnerManifestState,
  invokedRoot: Path,
): Path? = resolveGoalExecutionRoot(state, invokedRoot).takeIf(Files::isDirectory)
