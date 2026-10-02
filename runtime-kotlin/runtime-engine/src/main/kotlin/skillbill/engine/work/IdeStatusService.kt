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
import skillbill.error.shellcontent.InvalidWorkListRowError
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.IdeStatusValidator
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
        val candidates = scopeToBranch(collectCandidates(unitOfWork, repositoryIdentity), currentBranch, repoRoot)
        val selected =
          IdeStatusSelectionPolicy.select(candidates, observedAt)
            ?: return@read emit(IdeStatusProblemSnapshots.noMatchingWork(repositoryIdentity, observedAt, currentBranch))
        val snapshot =
          projector.project(
            candidate = selected,
            context =
              IdeStatusProjectionContext(
                unitOfWork = unitOfWork,
                repositoryIdentity = repositoryIdentity,
                observedAt = observedAt,
                repoRoot = repoRoot,
              ),
          )
        emit(snapshot)
      }
    } catch (error: InvalidWorkListRowError) {
      emit(
        IdeStatusProblemSnapshots.incompatibleRecord(
          repositoryIdentity = repositoryIdentity,
          observedAt = observedAt,
          message = error.message ?: "Incompatible work-list record.",
        ),
      )
    } catch (error: InvalidWorkflowStateSchemaError) {
      emit(
        IdeStatusProblemSnapshots.incompatibleRecord(
          repositoryIdentity = repositoryIdentity,
          observedAt = observedAt,
          message = error.message ?: "Incompatible workflow record.",
        ),
      )
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
    return candidates.filter { candidate ->
      candidate.issueKey?.let { IdeStatusBranchScope.branchReferencesIssueKey(branch, it) } == true ||
        isPlanningOnBaseBranch(candidate, branch, repoRoot)
    }
  }

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
  ): List<IdeStatusCandidate> {
    val work = unitOfWork.workList.list(limit = null)
    val issueKeysWithGoals =
      work
        .filter { it.workflowKind == WorkItemKind.FEATURE_GOAL }
        .mapNotNull { it.issueKey?.uppercase() }
        .toSet()
    val repositoryCorrelation = IdeStatusRepositoryCorrelation(unitOfWork, repositoryIdentity)
    val livenessAnchors = IdeStatusLivenessAnchors(unitOfWork, repositoryIdentity)

    return work.mapNotNull { item ->
      toCandidate(item, issueKeysWithGoals, repositoryCorrelation, livenessAnchors, unitOfWork)
    }
  }

  private fun toCandidate(
    item: WorkItem,
    issueKeysWithGoals: Set<String>,
    repositoryCorrelation: IdeStatusRepositoryCorrelation,
    livenessAnchors: IdeStatusLivenessAnchors,
    unitOfWork: UnitOfWork,
  ): IdeStatusCandidate? {
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
    val routeScope = routeScopeFor(item, unitOfWork)
    if (isExcludedGoalChild(routeScope, item.issueKey, issueKeysWithGoals)) return null
    return IdeStatusCandidate(
      workflowId = item.workflowId,
      workflowFamily = family,
      issueKey = item.issueKey,
      currentState = item.currentState,
      lifecycleState = lifecycle,
      selectionTier = IdeStatusSelectionPolicy.selectionTier(lifecycle),
      updatedAt = livenessAnchors.authoritativeUpdatedAt(item, family) ?: item.stateEnteredAt,
      startedAt = item.startedAt,
      routeScope = routeScope,
      isGoalAuthoritative = family == IdeStatusWorkflowFamily.FEATURE_GOAL,
    )
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
