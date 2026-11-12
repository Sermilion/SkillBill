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
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import skillbill.ports.goalrunner.model.GoalPlanningOwnershipWriteResult
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowIgnoredFilesCopyResult
import skillbill.ports.workflow.gitops.worktree.MAX_IGNORED_FILE_BYTES
import skillbill.ports.workflow.goalworktree.GoalWorktreeResources
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.baseForSubtask
import skillbill.workflow.decomposition.executionRootSubtaskId
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
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
  private val database: DatabaseSessionFactory,
) {
  private fun findOwnership(
    parentWorkflowId: String,
    repositoryIdentity: String,
    issueKey: String,
  ): Pair<String?, GoalPlanningOwnership?> {
    val selected =
      database.read { unit ->
        unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId)
          ?.step(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN)
          ?.planWorkflowId
      }
    val owner =
      database.read { unit ->
        selected?.let(unit.goalPlanningOwnership::findByWorkflow)
          ?: unit.goalPlanningOwnership.findByIssue(
            repositoryIdentity,
            FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(issueKey),
          )
      }
    return selected to owner
  }

  public fun ensureForControlVerb(
    state: GoalRunnerManifestState,
    invokedRoot: Path,
    revisionSubtaskId: Int? = state.manifest.executionRootSubtaskId(),
    requireExistingBranch: Boolean = false,
  ): Path? {
    val control = manifestStore.controlState(state.parentWorkflowId)
    val recordedPath = control.executionWorktreePath?.let(Path::of)
    if (recordedPath != null && !Files.isDirectory(recordedPath)) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "goal worktree recovery refused: seam=goal_execution_root value_expected=recorded_checkout " +
          "value_used=retained path=$recordedPath recovery=restore_or_inspect",
      )
      return null
    }
    val repositoryIdentity =
      control.repositoryIdentity ?: goalRepositoryIdentity(invokedRoot, repositoryEnclosingRootPort)
    val (selectedPlanWorkflowId, rawOwnership) =
      findOwnership(state.parentWorkflowId, repositoryIdentity, state.manifest.issueKey)
    if (recordedPath == null && rawOwnership == null) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "legacy goal control uses its original root: seam=goal_execution_root " +
          "value_expected=recorded_checkout value_used=$invokedRoot",
      )
      return invokedRoot
    }
    val ownership =
      rawOwnership?.takeIf { owner ->
        recordedPath != null && owner.state == GoalPlanningOwnership.State.TRANSFERRED &&
          ownerMatchesPlan(owner, repositoryIdentity, state.manifest.issueKey, selectedPlanWorkflowId) &&
          ownerMatchesParent(owner, state.parentWorkflowId, recordedPath)
      }
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
              ownership = ownership,
              parentWorkflowId = state.parentWorkflowId,
              allowLegacyRecordedPath = recordedPath != null && rawOwnership == null,
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
      is GoalExecutionRootAccess.Ready -> recordReadyRoot(state, recordedPath, rawOwnership, access)
    }
  }

  private fun recordReadyRoot(
    state: GoalRunnerManifestState,
    recordedPath: Path?,
    rawOwnership: GoalPlanningOwnership?,
    access: GoalExecutionRootAccess.Ready,
  ): Path {
    if (recordedPath != null && rawOwnership == null) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "legacy goal checkout retained: seam=goal_execution_root value_expected=recorded_path " +
          "value_used=legacy path=$recordedPath",
      )
    }
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
    return access.path
  }

  internal fun resolve(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalRunPreparation {
    val control = manifestStore.controlState(state.parentWorkflowId)
    val recordedPath = control.executionWorktreePath?.let(Path::of)
    val repositoryIdentity =
      control.repositoryIdentity ?: goalRepositoryIdentity(request.repoRoot, repositoryEnclosingRootPort)
    val (selectedPlanWorkflowId, rawOwnership) =
      findOwnership(state.parentWorkflowId, repositoryIdentity, request.issueKey)
    val ownership =
      rawOwnership?.takeIf { owner ->
        ownerMatchesPlan(owner, repositoryIdentity, request.issueKey, selectedPlanWorkflowId) &&
          ownerMatchesParent(owner, state.parentWorkflowId, recordedPath)
      }
    if (recordedPath != null && rawOwnership != null && ownership == null) {
      return blocked(
        state,
        request,
        "the recorded execution checkout $recordedPath has no matching ownership record. " +
          "Preserve the path and reconcile its repository, workflow and ownership before retrying.",
      )
    }
    if (recordedPath != null && rawOwnership == null) {
      return resolveLegacyPath(state, request, control, recordedPath)
    }
    val effectiveRecordedPath = recordedPath ?: ownership?.ownedPath
    val launchedChild = state.manifest.subtasks.any { !it.workflowId.isNullOrBlank() }
    val pathProblem = executionPathProblem(request, effectiveRecordedPath, launchedChild)
    if (pathProblem != null) return blocked(state, request, pathProblem)
    return if (effectiveRecordedPath == null && launchedChild) {
      val root = inFlightRoot(control, request)
      val sourceRoot = control.planningSourceRoot?.let(Path::of) ?: request.sourceRepoRoot
      GoalRunPreparation.Prepared(
        state.copy(repoRoot = root),
        request.copy(repoRoot = root, sourceRepoRoot = sourceRoot),
      )
    } else {
      resolveWorktree(state, request, effectiveRecordedPath, ownership)
    }
  }

  private fun executionPathProblem(
    request: GoalRunnerRunRequest,
    path: Path?,
    launchedChild: Boolean,
  ): String? =
    when {
      path == null && !launchedChild -> {
        val ambiguous = worktrees.plannedPath(request.repoRoot, request.issueKey)
        if (ambiguous.toAbsolutePath().normalize() == request.repoRoot.toAbsolutePath().normalize()) {
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "goal execution using caller checkout: seam=goal_execution_root value_expected=owned_checkout " +
              "value_used=in_place path=$ambiguous",
          )
          null
        } else {
          "no durable ownership record identifies this goal checkout. Preserve any existing path at $ambiguous " +
            "and establish ownership before retrying; a Git worktree listing alone is not proof."
        }
      }
      path != null && !Files.isDirectory(path) ->
        "the recorded execution checkout $path is missing. Restore or inspect that path and its " +
          "unpublished work before retrying; the runtime will not recreate it from a revision alone."
      else -> null
    }

  private fun ownerMatchesPlan(
    owner: GoalPlanningOwnership,
    identity: String,
    issueKey: String,
    selectedPlanWorkflowId: String?,
  ): Boolean =
    owner.repositoryIdentity == identity &&
      owner.normalizedIssueKey == FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(issueKey) &&
      (
        selectedPlanWorkflowId == null || owner.workflowId == selectedPlanWorkflowId ||
          ownerHasNotLaunched(
            owner,
            selectedPlanWorkflowId,
          )
      ) &&
      owner.state != GoalPlanningOwnership.State.SUPERSEDED

  private fun ownerHasNotLaunched(
    owner: GoalPlanningOwnership,
    selectedPlan: String?,
  ): Boolean =
    owner.state != GoalPlanningOwnership.State.SUPERSEDED &&
      database.read { unit ->
        unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, owner.workflowId) == null &&
          (
            selectedPlan == null ||
              unit.workflowStates.get(
                WorkflowFamily.TASK_RUNTIME,
                selectedPlan,
              )?.workflowStatus == WorkflowStatus.COMPLETED
          )
      }

  private fun ownerMatchesParent(
    owner: GoalPlanningOwnership,
    parentWorkflowId: String,
    path: Path?,
  ): Boolean {
    val matchingPath = path == null || owner.ownedPath.toAbsolutePath().normalize() == path.toAbsolutePath().normalize()
    if (path != null && (!matchingPath || owner.state != GoalPlanningOwnership.State.TRANSFERRED)) return false
    return owner.state != GoalPlanningOwnership.State.TRANSFERRED || owner.parentWorkflowId == parentWorkflowId
  }

  private fun resolveLegacyPath(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    control: GoalRunnerControlState,
    recordedPath: Path,
  ): GoalRunPreparation {
    if (!Files.isDirectory(recordedPath)) {
      return blocked(
        state,
        request,
        "the recorded execution checkout $recordedPath is missing. Restore or inspect that path and its " +
          "unpublished work before retrying; the runtime will not recreate it from a revision alone.",
      )
    }
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "legacy goal checkout retained: seam=goal_execution_root value_expected=recorded_path " +
        "value_used=legacy path=$recordedPath",
    )
    val sourceRoot = control.planningSourceRoot?.let(Path::of) ?: request.sourceRepoRoot
    return GoalRunPreparation.Prepared(
      state.copy(repoRoot = recordedPath),
      request.copy(repoRoot = recordedPath, sourceRepoRoot = sourceRoot),
    )
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
    ownership: GoalPlanningOwnership?,
  ): GoalRunPreparation {
    val launchingRoot = repositoryEnclosingRootPort.enclosingRepositoryRoot(request.repoRoot)
    val revision =
      if (ownership?.state == GoalPlanningOwnership.State.RESERVED) {
        WorkflowGitOperationResult.Ok(ownership.initialCommit)
      } else if (ownership == null &&
        worktrees.plannedPath(launchingRoot, request.issueKey).toAbsolutePath().normalize() ==
        launchingRoot.toAbsolutePath().normalize()
      ) {
        gitOperations.headCommitSha(launchingRoot)
      } else {
        worktreeRevision(state, launchingRoot, recordedPath)
      }
    if (revision !is WorkflowGitOperationResult.Ok) {
      return blocked(state, request, "could not read the revision for its worktree: ${revision.error}")
    }
    val startPoint = revision.value.trim()
    return when (
      val worktree = worktrees.createOrAdopt(launchingRoot, request.issueKey, recordedPath, startPoint, ownership)
    ) {
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
    val path = worktree.toString()
    val identity = goalRepositoryIdentity(launchingRoot, repositoryEnclosingRootPort)
    val control =
      database.transaction { unit ->
        val current = unit.goalRunnerControls.controlState(state.parentWorkflowId)
        val selectedPlanWorkflowId =
          unit.workflowStates
            .get(WorkflowFamily.TASK_RUNTIME, state.parentWorkflowId)
            ?.step(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN)
            ?.planWorkflowId
        val owned =
          selectedPlanWorkflowId?.let(unit.goalPlanningOwnership::findByWorkflow)
            ?: unit.goalPlanningOwnership.findByIssue(
              identity,
              FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(request.issueKey),
            )
        if (!unit.transferPlanningOwnership(owned, selectedPlanWorkflowId, state.parentWorkflowId, worktree, current)) {
          return@transaction null
        }
        unit.goalRunnerControls.persistControlState(
          state.parentWorkflowId,
          current.copy(
            executionWorktreePath = path,
            repositoryIdentity = current.repositoryIdentity ?: identity,
            launchWarning = dirty?.warning ?: current.launchWarning,
            planningSourceRoot =
              current.planningSourceRoot ?: request.sourceRepoRoot.toAbsolutePath().normalize().toString(),
          ),
        )
      } ?: return blocked(state, request, "the planning checkout ownership record did not match $path")
    dirty?.let(request.eventSink::emit)
    return GoalRunPreparation.Prepared(
      state.copy(repoRoot = worktree, controlState = control),
      request.copy(
        repoRoot = worktree,
        sourceRepoRoot = control.planningSourceRoot?.let(Path::of) ?: request.sourceRepoRoot,
      ),
    )
  }

  private fun UnitOfWork.transferPlanningOwnership(
    owner: GoalPlanningOwnership?,
    selectedPlan: String?,
    parentId: String,
    worktree: Path,
    control: GoalRunnerControlState,
  ): Boolean {
    if (owner == null) return true
    val selected =
      selectedPlan == null || owner.workflowId == selectedPlan ||
        (
          owner.state != GoalPlanningOwnership.State.SUPERSEDED &&
            workflowStates.get(WorkflowFamily.TASK_RUNTIME, owner.workflowId) == null &&
            workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedPlan)?.workflowStatus == WorkflowStatus.COMPLETED
        )
    if (owner.state == GoalPlanningOwnership.State.TRANSFERRED) {
      return selected && owner.parentWorkflowId == parentId
    }
    val currentPath = control.executionWorktreePath?.let(Path::of)?.toAbsolutePath()?.normalize()
    val expectedPath = worktree.toAbsolutePath().normalize()
    val ownerPathMatches = owner.ownedPath.toAbsolutePath().normalize() == expectedPath
    val controlPathMatches = currentPath == null || currentPath == expectedPath
    if (!selected || !ownerPathMatches || !controlPathMatches) return false
    return goalPlanningOwnership.transfer(owner.reservationId, parentId, worktree) is
      GoalPlanningOwnershipWriteResult.Stored
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
