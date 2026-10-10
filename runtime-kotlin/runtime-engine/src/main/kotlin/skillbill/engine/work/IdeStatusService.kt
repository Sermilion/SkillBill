package skillbill.engine.work

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.baseBranch
import skillbill.engine.featuretask.lifecycle.branch.protectedBranchName
import skillbill.engine.goalrunner.goalRepositoryIdentity
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.work.model.IdeStatusCandidate
import skillbill.engine.work.model.IdeStatusRepositoryResolution
import skillbill.engine.work.model.IdeStatusRequest
import skillbill.engine.work.model.IdeStatusResult
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.error.shellcontent.isInvalidWorkflowStateFailure
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.IdeStatusValidator
import skillbill.ports.idestatus.model.IdeStatusExecutionIdentity
import skillbill.ports.idestatus.model.IdeStatusExecutionScope
import skillbill.ports.idestatus.model.IdeStatusLifecycleState
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusWorkflowFamily
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.work.model.WorkItem
import skillbill.ports.work.model.WorkItemKind
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.decompositionStatus
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

@Inject
class IdeStatusService(
  private val database: DatabaseSessionFactory,
  private val projector: IdeStatusProjector,
  private val ideStatusValidator: IdeStatusValidator,
  private val branchSource: CheckedOutBranchSource,
  private val clock: Clock,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  private val manifestStore: GoalRunnerManifestStore,
) {
  fun status(request: IdeStatusRequest): IdeStatusResult {
    val observedAt = request.observedAt ?: clock.instant()
    val identityResult = resolveRepositoryIdentity(request.repoRoot, repositoryEnclosingRootPort)
    if (identityResult is IdeStatusRepositoryResolution.Invalid) {
      return emit(IdeStatusProblemSnapshots.invalidRepositoryInput(observedAt, identityResult.message))
    }
    if (identityResult is IdeStatusRepositoryResolution.Missing) {
      return emit(IdeStatusProblemSnapshots.missingRepositoryIdentity(observedAt, identityResult.message))
    }
    val repositoryIdentity = (identityResult as IdeStatusRepositoryResolution.Ok).identity
    val repoRoot = identityResult.repoRoot

    if (!database.databaseExists()) {
      return emit(IdeStatusProblemSnapshots.absentDatabase(repositoryIdentity, observedAt))
    }

    val currentBranch = branchSource.checkedOutBranch(repoRoot)
    return try {
      database.read { unitOfWork ->
        unitOfWork.standalonePhaseStatuses.reconcileExpiredLeases(observedAt)
        val candidates =
          scopeToBranch(
            collectCandidates(unitOfWork, repositoryIdentity, currentBranch, observedAt, repoRoot),
            currentBranch,
            repoRoot,
          )
        val context =
          IdeStatusProjectionContext(
            unitOfWork = unitOfWork,
            repositoryIdentity = repositoryIdentity,
            branchCorrelation = currentBranch,
            observedAt = observedAt,
            repoRoot = repoRoot,
          )
        val selectable = candidates.filter { projector.readableForSelection(it, context) }
        val selected =
          IdeStatusSelectionPolicy.select(selectable, observedAt)
            ?: IdeStatusSelectionPolicy.select(candidates, observedAt)
            ?: return@read emit(
              IdeStatusProblemSnapshots.noMatchingWork(repositoryIdentity, observedAt, currentBranch),
            )
        emit(projector.project(candidate = selected, context = context))
      }
    } catch (error: SkillBillRuntimeException) {
      when {
        error.code == WorkflowFailureCode.INVALID_WORK_LIST_ROW ->
          emit(
            IdeStatusProblemSnapshots.incompatibleRecord(
              repositoryIdentity = repositoryIdentity,
              observedAt = observedAt,
              message = error.message ?: "Incompatible work-list record.",
            ),
          )
        error.isInvalidWorkflowStateFailure() ->
          emit(
            IdeStatusProblemSnapshots.incompatibleRecord(
              repositoryIdentity = repositoryIdentity,
              observedAt = observedAt,
              message = error.message ?: "Incompatible workflow record.",
            ),
          )
        else -> throw error
      }
    }
  }

  fun toWireMap(snapshot: IdeStatusSnapshot): Map<String, Any?> = ideStatusValidator.toWirePayload(snapshot).toPayload()

  private fun scopeToBranch(
    candidates: List<IdeStatusCandidate>,
    branch: String?,
    repoRoot: Path,
  ): List<IdeStatusCandidate> {
    if (branch == null) return candidates
    if (protectedBranchName(branch) != null) return candidates
    return candidates.filter { candidate -> matchesBranch(candidate, branch, repoRoot) }
  }

  private fun matchesBranch(
    candidate: IdeStatusCandidate,
    branch: String,
    repoRoot: Path,
  ): Boolean =
    (
      candidate.branchCorrelation == null ||
        candidate.branchCorrelation == "HEAD" ||
        candidate.branchCorrelation == branch
    ) &&
      (
        candidate.standaloneStatus != null ||
          candidate.issueKey?.let { IdeStatusBranchScope.branchReferencesIssueKey(branch, it) } == true ||
          isPlanningOnBaseBranch(candidate, branch, repoRoot)
      )

  private fun isPlanningOnBaseBranch(
    candidate: IdeStatusCandidate,
    branch: String,
    repoRoot: Path,
  ): Boolean {
    val issueKey = candidate.issueKey
    if (issueKey == null ||
      candidate.workflowFamily != IdeStatusWorkflowFamily.FEATURE_GOAL ||
      candidate.lifecycleState == IdeStatusLifecycleState.TERMINAL
    ) {
      return false
    }
    val state = manifestStore.readByIssueKey(issueKey, repoRoot)
    if (state == null || state.parentWorkflowId != candidate.workflowId || state.manifest.baseBranch != branch) {
      return false
    }
    val planning =
      manifestStore.planningStatus(
        state.parentWorkflowId,
        state.manifest.subtasks.filter { it.status.decompositionStatus() != DecompositionStatus.SKIPPED }.map { it.id },
      ) ?: return false
    return planning.state != GoalPlanningStatusState.PREPARED
  }

  private fun collectCandidates(
    unitOfWork: UnitOfWork,
    repositoryIdentity: String,
    branch: String?,
    observedAt: Instant,
    repoRoot: Path,
  ): List<IdeStatusCandidate> {
    val work = unitOfWork.workList.list(limit = null)
    val issueKeysWithGoals =
      work
        .filter { it.workflowKind == WorkItemKind.FEATURE_GOAL }
        .mapNotNull { it.issueKey?.uppercase() }
        .toSet()
    val workflowCandidates =
      work.mapNotNull { item ->
        if (isExcludedGoalChild(routeScopeFor(item, unitOfWork), item.issueKey, issueKeysWithGoals)) {
          null
        } else {
          toCandidate(item, unitOfWork, repositoryIdentity, branch, repoRoot)
        }
      }
    val standaloneCandidates =
      branch?.let { branchName ->
        unitOfWork.standalonePhaseStatuses.readEligible(repositoryIdentity, branchName, observedAt).map { record ->
          val lifecycle = standaloneLifecycle(record.lifecycleState)
          IdeStatusCandidate(
            workflowId = record.workflowId ?: "standalone:${record.executionId}",
            workflowFamily = IdeStatusWorkflowFamily.FEATURE_TASK_RUNTIME,
            issueKey = record.issueKey,
            currentState = record.lifecycleState,
            lifecycleState = lifecycle,
            selectionTier = IdeStatusSelectionPolicy.selectionTier(lifecycle),
            updatedAt = record.updatedAt,
            startedAt = record.startedAt,
            isGoalAuthoritative = false,
            execution =
              IdeStatusExecutionIdentity(
                scope = IdeStatusExecutionScope.STANDALONE_PHASE,
                executionId = record.executionId,
                statusStoreId = record.statusStoreId,
                runSequence = record.runSequence,
                statusRevision = record.statusRevision,
                invocationId = record.invocationId,
                phaseId = record.phaseId,
              ),
            branchCorrelation = record.branchCorrelation,
            standaloneStatus = record,
          )
        }
      }.orEmpty()
    val standaloneWorkflowIds = standaloneCandidates.mapNotNull { it.standaloneStatus?.workflowId }.toSet()
    return workflowCandidates.filterNot { it.workflowId in standaloneWorkflowIds } + standaloneCandidates
  }

  private fun standaloneLifecycle(state: String): IdeStatusLifecycleState =
    when (state) {
      "active", "running" -> IdeStatusLifecycleState.ACTIVE
      "paused", "runner_interrupted" -> IdeStatusLifecycleState.PAUSED
      "blocked" -> IdeStatusLifecycleState.BLOCKED
      "failed" -> IdeStatusLifecycleState.FAILED
      "terminal", "completed", "success" -> IdeStatusLifecycleState.TERMINAL
      else -> IdeStatusLifecycleState.FAILED
    }

  private fun toCandidate(
    item: WorkItem,
    unitOfWork: UnitOfWork,
    repositoryIdentity: String,
    branch: String?,
    repoRoot: Path,
  ): IdeStatusCandidate? {
    val repositoryCorrelation = IdeStatusRepositoryCorrelation(unitOfWork, repositoryIdentity)
    val livenessAnchors = IdeStatusLivenessAnchors(unitOfWork, repositoryIdentity)
    val family = item.workflowKind.toIdeFamily()
    val lifecycle =
      family?.let { candidateFamily ->
        if (repositoryCorrelation.matches(item, candidateFamily) != true) {
          null
        } else {
          IdeStatusSelectionPolicy.lifecycleFromDurableStateWire(item.currentState)
        }
      }
    if (family == null || lifecycle == null) return null
    val workflowExecution = unitOfWork.standalonePhaseStatuses.latestWorkflowExecution(item.workflowId)
    val branchCorrelation =
      goalBranchCorrelation(item, family, workflowExecution?.branchCorrelation, branch, repoRoot)
    val execution =
      workflowExecution
        ?.takeIf { branch == null || branchCorrelation == branch || branchCorrelation == "HEAD" }
        ?.let { record ->
          IdeStatusExecutionIdentity(
            scope = IdeStatusExecutionScope.WORKFLOW,
            executionId = record.executionId,
            statusStoreId = record.statusStoreId,
            runSequence = record.runSequence,
            statusRevision = record.statusRevision,
          )
        }
    return IdeStatusCandidate(
      workflowId = item.workflowId,
      workflowFamily = family,
      issueKey = item.issueKey,
      currentState = item.currentState,
      lifecycleState = lifecycle,
      selectionTier = IdeStatusSelectionPolicy.selectionTier(lifecycle),
      updatedAt = livenessAnchors.authoritativeUpdatedAt(item, family) ?: item.stateEnteredAt,
      startedAt = item.startedAt,
      isGoalAuthoritative = family == IdeStatusWorkflowFamily.FEATURE_GOAL,
      execution = execution,
      branchCorrelation = branchCorrelation,
    )
  }

  private fun goalBranchCorrelation(
    item: WorkItem,
    family: IdeStatusWorkflowFamily,
    registeredBranch: String?,
    currentBranch: String?,
    repoRoot: Path,
  ): String? {
    if (family != IdeStatusWorkflowFamily.FEATURE_GOAL || registeredBranch == null || currentBranch == null) {
      return registeredBranch
    }
    if (registeredBranch == currentBranch || registeredBranch == "HEAD") return registeredBranch
    val state = item.issueKey?.let { manifestStore.readByIssueKey(it, repoRoot) }
    val manifest =
      state?.manifest?.takeIf {
        state.parentWorkflowId == item.workflowId
      }
    val ownsBranch =
      manifest != null &&
        (manifest.featureBranch == currentBranch || manifest.stackBranches.any { it.branch == currentBranch })
    return if (ownsBranch) currentBranch else registeredBranch
  }

  private fun routeScopeFor(
    item: WorkItem,
    unitOfWork: UnitOfWork,
  ): FeatureTaskRouteScope? =
    when (item.workflowKind) {
      WorkItemKind.FEATURE_TASK_PROSE, WorkItemKind.FEATURE_TASK_RUNTIME ->
        unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(item.workflowId)?.routeScope
      WorkItemKind.FEATURE_VERIFY,
      WorkItemKind.FEATURE_GOAL,
      -> null
    }

  private fun isExcludedGoalChild(
    routeScope: FeatureTaskRouteScope?,
    issueKey: String?,
    issueKeysWithGoals: Set<String>,
  ): Boolean = routeScope == FeatureTaskRouteScope.GOAL_CHILD && issueKey?.uppercase() in issueKeysWithGoals

  private fun emit(snapshot: IdeStatusSnapshot): IdeStatusResult {
    ideStatusValidator.validate(snapshot, sourceLabel = "ide-status")
    return IdeStatusResult(snapshot = snapshot, exitCode = snapshot.exitCode())
  }
}

internal fun resolveRepositoryIdentity(
  repoRootArg: String,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): IdeStatusRepositoryResolution {
  val resolvedStart =
    runCatching {
      repositoryEnclosingRootPort.canonicalPath(Path.of(repoRootArg))
    }.getOrNull()
      ?: return IdeStatusRepositoryResolution.Invalid("Repository root cannot be resolved: $repoRootArg")
  val gitRoot =
    findGitRoot(resolvedStart, repositoryEnclosingRootPort)
      ?: return IdeStatusRepositoryResolution.Invalid("Path is not inside a Git repository: $repoRootArg")
  val canonicalGitRoot = repositoryEnclosingRootPort.canonicalPath(gitRoot)
  val identity = goalRepositoryIdentity(canonicalGitRoot, repositoryEnclosingRootPort)
  return if (
    identity.isBlank() ||
    !identity.startsWith(FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX)
  ) {
    IdeStatusRepositoryResolution.Missing(
      "Could not form canonical repository identity for: $repoRootArg",
    )
  } else {
    IdeStatusRepositoryResolution.Ok(identity = identity, repoRoot = canonicalGitRoot)
  }
}

private fun findGitRoot(
  start: Path,
  repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
): Path? {
  var candidate: Path? = start
  while (candidate != null) {
    if (repositoryEnclosingRootPort.optionalRealPath(candidate.resolve(".git")) != null) return candidate
    candidate = candidate.parent
  }
  return null
}
