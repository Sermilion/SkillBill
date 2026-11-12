package skillbill.engine.goalrunner.launch

import me.tatarka.inject.annotations.Inject
import skillbill.application.runtime.RuntimeSingleton
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
import skillbill.engine.goalrunner.plan.GoalPlanCheckoutPreparer
import skillbill.engine.goalrunner.planning.source.goalPlanMatches
import skillbill.engine.goalrunner.planning.source.selectGoalPlanWorkflowId
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
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path

private const val MAX_LISTED_PATHS = 20
private const val MAX_RECORD_REASON = 200

@Inject
@RuntimeSingleton
public class GoalExecutionRootResolver(
  private val manifestStore: GoalRunnerManifestStore,
  private val worktrees: GoalWorktreeResources,
  private val gitOperations: WorkflowGitOperations,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val diagnostics: RuntimeDiagnostics,
  private val checkout: ProjectCheckout,
  private val database: DatabaseSessionFactory,
  private val planRun: GoalPlanCheckoutPreparer,
) {
  private fun findOwnership(
    state: GoalRunnerManifestState,
    sourceRoot: Path,
  ): Pair<String?, GoalPlanningOwnership?> =
    database.read { unit ->
      val selected =
        unit.workflowStates.selectGoalPlanWorkflowId(
          state,
          sourceRoot,
          repositoryEnclosingRootPort,
          checkout.store(sourceRoot),
        )
      val owner =
        selected?.let(unit.goalPlanningOwnership::findByWorkflow)
          ?: unit.goalPlanningOwnership.findByIssue(
            goalRepositoryIdentity(sourceRoot, repositoryEnclosingRootPort),
            FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(state.manifest.issueKey),
          )
      selected to owner
    }

  public fun prepareControlCheckout(
    issueKey: String,
    invokedRoot: Path,
  ) {
    val recovery = checkout.recoverGoalCheckout(issueKey, invokedRoot)
    if (recovery is GoalWorktreeResult.Failed) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "goal control checkout recovery refused: seam=goal_execution_root reason=${recovery.reason}",
      )
    }
  }

  public fun ensureForControlVerb(
    state: GoalRunnerManifestState,
    invokedRoot: Path,
    revisionSubtaskId: Int? = state.manifest.executionRootSubtaskId(),
    requireExistingBranch: Boolean = false,
  ): Path? =
    database.transaction { unit ->
      val control = manifestStore.controlState(state.parentWorkflowId)
      val recordedPath = control.executionWorktreePath?.let(Path::of)
      val sourceRoot = control.planningSourceRoot?.let(Path::of) ?: invokedRoot
      val selection = findOwnership(state, sourceRoot)
      val rawOwnership = selection.second
      if (isLegacyUnbound(recordedPath, selection)) {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "legacy goal control uses its original root: seam=goal_execution_root " +
            "value_expected=recorded_checkout value_used=$invokedRoot",
        )
        return@transaction invokedRoot
      }
      val parent = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, state.parentWorkflowId)
      val planStep = parent?.step(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN)
      val boundPlan = planStep?.planWorkflowId
      val boundCheckout =
        rawOwnership == null || recordedPath != null && rawOwnership.state == GoalPlanningOwnership.State.TRANSFERRED
      val boundSelection = controlSelectionBound(selection.first, boundPlan, planStep != null, rawOwnership)
      if (!boundCheckout || !boundSelection ||
        !unit.planningSelectionMatches(state, sourceRoot, selection, recordedPath)
      ) {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "goal control checkout refused: seam=goal_execution_root path=${rawOwnership?.ownedPath ?: recordedPath} " +
            "selected=${selection.first} owner=${rawOwnership?.workflowId} bound=$boundPlan " +
            "recovery=preserve_checkout_reconcile_selection_ownership_and_worker_then_resume_goal",
        )
        return@transaction null
      }
      when (
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
                ownership = rawOwnership,
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

  private fun controlSelectionBound(
    selected: String?,
    bound: String?,
    hasPlanStep: Boolean,
    owner: GoalPlanningOwnership?,
  ): Boolean = selected == null || bound == selected || (bound == null && hasPlanStep && owner?.workflowId == selected)

  private fun isLegacyUnbound(
    recordedPath: Path?,
    selection: Pair<String?, GoalPlanningOwnership?>,
  ): Boolean = recordedPath == null && selection.first == null && selection.second == null

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
    val hasLaunchedChild = state.manifest.subtasks.any { !it.workflowId.isNullOrBlank() }
    val boundRoot =
      if (hasLaunchedChild && control.planningSourceRoot == null) {
        control.repositoryIdentity?.let(::goalRepositoryRootFromIdentity)
          ?.let(repositoryEnclosingRootPort::optionalRealPath)
      } else {
        null
      }
    return resolveSelected(state, if (boundRoot == null) request else request.copy(sourceRepoRoot = boundRoot))
  }

  private fun resolveSelected(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): GoalRunPreparation {
    if (FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(request.issueKey) !=
      FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(state.manifest.issueKey)
    ) {
      return blocked(
        state,
        request,
        "the requested issue does not match the goal at ${request.repoRoot}. " +
          "Preserve the checkout and resume its recorded issue.",
      )
    }
    val control = manifestStore.controlState(state.parentWorkflowId)
    val recordedPath = control.executionWorktreePath?.let(Path::of)
    val initialSelection = findOwnership(state, request.sourceRepoRoot)
    acquireLegacySelectedPlan(state, request, initialSelection, recordedPath)?.let {
      return blocked(state, request, it)
    }
    val selection = findOwnership(state, request.sourceRepoRoot)
    val rawOwnership = selection.second
    if (selection.first != initialSelection.first ||
      !database.read { it.planningSelectionMatches(state, request.sourceRepoRoot, selection, recordedPath) }
    ) {
      return blocked(
        state,
        request,
        "selected plan '${selection.first}' does not match checkout " +
          "${rawOwnership?.ownedPath ?: recordedPath ?: request.repoRoot} " +
          "or owner '${rawOwnership?.workflowId}'. Preserve the checkout and plan evidence, then reconcile " +
          "the repository, issue, selected workflow, ownership and worker before retrying.",
      )
    }
    val ownership = rawOwnership
    val effectiveRecordedPath = recordedPath ?: ownership?.ownedPath
    val launchedChild = state.manifest.subtasks.any { !it.workflowId.isNullOrBlank() }
    val pathProblem = executionPathProblem(request, effectiveRecordedPath, launchedChild, ownership)
    return if (recordedPath != null && rawOwnership == null) {
      resolveLegacyPath(state, request, control, recordedPath)
    } else if (pathProblem != null) {
      blocked(state, request, pathProblem)
    } else if (effectiveRecordedPath == null && launchedChild) {
      val root = inFlightRoot(control, request)
      val sourceRoot = control.planningSourceRoot?.let(Path::of) ?: request.sourceRepoRoot
      GoalRunPreparation.Prepared(
        state.copy(repoRoot = root),
        request.copy(repoRoot = root, sourceRepoRoot = sourceRoot),
      )
    } else {
      resolveWorktree(state, request, effectiveRecordedPath, selection)
    }
  }

  private fun acquireLegacySelectedPlan(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    selection: Pair<String?, GoalPlanningOwnership?>,
    recordedPath: Path?,
  ): String? {
    val selected = selection.first ?: return null
    if (recordedPath != null || selection.second != null) return null
    return database.transaction { unit ->
      val states = unit.workflowStates
      val root = request.sourceRepoRoot
      val current = states.selectGoalPlanWorkflowId(state, root, repositoryEnclosingRootPort, checkout.store(root))
      val identity = unit.goalRunnerControls.controlState(state.parentWorkflowId).repositoryIdentity
      val matchesRepository = identity == null || identity == goalRepositoryIdentity(root, repositoryEnclosingRootPort)
      val validSelection =
        matchesRepository && current == selected &&
          states.goalPlanMatches(selected, state, root, repositoryEnclosingRootPort, checkout.store(root))
      if (!validSelection || states.getFeatureTaskRuntimeWorkerOwnership(selected) != null) {
        "Selected plan '$selected' changed or is occupied at $root. Preserve its evidence and reconcile " +
          "the selected workflow and worker before retrying."
      } else {
        planRun.prepareGoalCheckout(request.issueKey, state.manifest.parentSpecPath, root, selected)?.reason
      }
    }
  }

  private fun executionPathProblem(
    request: GoalRunnerRunRequest,
    path: Path?,
    launchedChild: Boolean,
    ownership: GoalPlanningOwnership?,
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
      path != null && !Files.isDirectory(path) && ownership == null ->
        "the recorded execution checkout $path is missing. Restore or inspect that path and its " +
          "unpublished work before retrying; no matching ownership record authorizes reconciliation."
      else -> null
    }

  private fun UnitOfWork.planningSelectionMatches(
    state: GoalRunnerManifestState,
    sourceRoot: Path,
    selection: Pair<String?, GoalPlanningOwnership?>,
    recordedPath: Path?,
  ): Boolean {
    val (selected, owner) = selection
    val identity = goalRepositoryIdentity(sourceRoot, repositoryEnclosingRootPort)
    val issueKey = FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(state.manifest.issueKey)
    val control = goalRunnerControls.controlState(state.parentWorkflowId)
    val manifest = workflowStates.get(WorkflowFamily.TASK_RUNTIME, state.parentWorkflowId)?.decompositionRuntime()
    val matchesParent =
      manifest?.issueKey == state.manifest.issueKey && manifest.parentSpecPath == state.manifest.parentSpecPath &&
        (control.repositoryIdentity == null || control.repositoryIdentity == identity)
    val current =
      workflowStates.selectGoalPlanWorkflowId(
        state,
        sourceRoot,
        repositoryEnclosingRootPort,
        checkout.store(sourceRoot),
      )
    if (!matchesParent || current != selected) return false
    if (owner == null) return selected == null && goalPlanningOwnership.findByIssue(identity, issueKey) == null
    val matchesWorkflow =
      if (selected == null) {
        workflowStates.get(WorkflowFamily.TASK_RUNTIME, owner.workflowId) == null
      } else {
        selected == owner.workflowId &&
          workflowStates.goalPlanMatches(
            selected,
            state,
            sourceRoot,
            repositoryEnclosingRootPort,
            checkout.store(sourceRoot),
          )
      }
    return matchesWorkflow && ownerMatchesParent(owner, state.parentWorkflowId, recordedPath) &&
      matchingOwnership(owner, identity, issueKey)
  }

  private fun UnitOfWork.matchingOwnership(
    owner: GoalPlanningOwnership,
    identity: String,
    issueKey: String,
  ): Boolean =
    owner.repositoryIdentity == identity && owner.normalizedIssueKey == issueKey &&
      goalPlanningOwnership.findByWorkflow(owner.workflowId) == owner &&
      goalPlanningOwnership.findByIssue(identity, issueKey) == owner &&
      owner.state != GoalPlanningOwnership.State.SUPERSEDED &&
      workflowStates.getFeatureTaskRuntimeWorkerOwnership(owner.workflowId) == null

  private fun ownerMatchesParent(
    owner: GoalPlanningOwnership,
    parentWorkflowId: String,
    path: Path?,
  ): Boolean {
    val matchingPath =
      path == null || owner.ownedPath.toAbsolutePath().normalize() == path.toAbsolutePath().normalize()
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
    selection: Pair<String?, GoalPlanningOwnership?>,
  ): GoalRunPreparation {
    val ownership = selection.second
    val launchingRoot = repositoryEnclosingRootPort.enclosingRepositoryRoot(request.repoRoot)
    val revision =
      if (ownership != null) {
        WorkflowGitOperationResult.Ok(ownership.initialCommit)
      } else if (worktrees.plannedPath(launchingRoot, request.issueKey).toAbsolutePath().normalize() ==
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
    return database.transaction { unit ->
      val currentPath =
        unit.goalRunnerControls.controlState(state.parentWorkflowId).executionWorktreePath?.let(Path::of)
      if (!unit.planningSelectionMatches(state, request.sourceRepoRoot, selection, currentPath)) {
        return@transaction blocked(
          state,
          request,
          "planning ownership changed at ${ownership?.ownedPath ?: recordedPath ?: launchingRoot}. " +
            "Preserve the checkout and reconcile its selection, ownership and worker before retrying.",
        )
      }
      when (
        val worktree = worktrees.createOrAdopt(launchingRoot, request.issueKey, recordedPath, startPoint, ownership)
      ) {
        is GoalWorktreeResult.Failed -> blocked(state, request, worktree.reason)
        is GoalWorktreeResult.Adopted -> prepared(state, request, worktree.path, null, selection)
        is GoalWorktreeResult.Created -> {
          if (ownership != null && ownership.state != GoalPlanningOwnership.State.RESERVED) {
            return@transaction prepared(state, request, worktree.path, null, selection)
          }
          copyIgnoredFiles(launchingRoot, worktree.path)
          val dirty = launchingCheckoutDirty(request.issueKey, launchingRoot, startPoint)
          prepared(state, request, worktree.path, dirty, selection)
        }
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
    worktree: Path,
    dirty: GoalRunnerRunEvent.LaunchingCheckoutDirty?,
    selection: Pair<String?, GoalPlanningOwnership?>,
  ): GoalRunPreparation {
    val path = worktree.toString()
    val control =
      persistPlanningCheckout(state, request, worktree, dirty, selection) ?: run {
        val selectedPlanWorkflowId = selection.first ?: selection.second?.workflowId
        val workerLease =
          selectedPlanWorkflowId?.let { workflowId ->
            database.read { unit -> unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId) }
          }
        val reason =
          if (workerLease != null) {
            "selected plan workflow '${workerLease.workflowId}' still holds a worker lease. Preserve $path, " +
              "stop that worker or let crash recovery reconcile it before retrying goal adoption."
          } else {
            "selected plan '$selectedPlanWorkflowId' or its planning checkout ownership changed at $path. " +
              "Preserve the path and plan evidence, then reconcile the selection and ownership before retrying."
          }
        return blocked(state, request, reason)
      }
    dirty?.let(request.eventSink::emit)
    return GoalRunPreparation.Prepared(
      state.copy(repoRoot = worktree, controlState = control),
      request.copy(
        repoRoot = worktree,
        sourceRepoRoot = control.planningSourceRoot?.let(Path::of) ?: request.sourceRepoRoot,
      ),
    )
  }

  private fun persistPlanningCheckout(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
    worktree: Path,
    dirty: GoalRunnerRunEvent.LaunchingCheckoutDirty?,
    selection: Pair<String?, GoalPlanningOwnership?>,
  ): GoalRunnerControlState? =
    database.transaction { unit ->
      val path = worktree.toString()
      val identity = goalRepositoryIdentity(request.sourceRepoRoot, repositoryEnclosingRootPort)
      val current = unit.goalRunnerControls.controlState(state.parentWorkflowId)
      val parent =
        unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, state.parentWorkflowId) ?: return@transaction null
      val selected = selection.first
      if (selected != null && parent.step(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN) == null) {
        return@transaction null
      }
      val recordedPath = current.executionWorktreePath?.let(Path::of)
      if (!unit.planningSelectionMatches(state, request.sourceRepoRoot, selection, recordedPath) ||
        !unit.transferPlanningOwnership(selection.second, state.parentWorkflowId, worktree, current)
      ) {
        return@transaction null
      }
      val planStep = parent.step(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN)
      if (selected != null && planStep?.planWorkflowId == null) {
        unit.workflowStates.save(
          WorkflowFamily.TASK_RUNTIME,
          parent.copy(
            steps =
              parent.steps.map { step ->
                if (step.stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN) {
                  step.copy(planWorkflowId = selected)
                } else {
                  step
                }
              },
          ),
        )
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
    }

  private fun UnitOfWork.transferPlanningOwnership(
    owner: GoalPlanningOwnership?,
    parentId: String,
    worktree: Path,
    control: GoalRunnerControlState,
  ): Boolean {
    if (owner == null) return true
    val currentPath = control.executionWorktreePath?.let(Path::of)?.toAbsolutePath()?.normalize()
    val expectedPath = worktree.toAbsolutePath().normalize()
    val ownerPathMatches = owner.ownedPath.toAbsolutePath().normalize() == expectedPath
    val controlPathMatches = currentPath == null || currentPath == expectedPath
    if (!ownerPathMatches || !controlPathMatches || goalPlanningOwnership.checkoutRecovery(owner) != null) return false
    if (owner.state == GoalPlanningOwnership.State.TRANSFERRED) return owner.parentWorkflowId == parentId
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
