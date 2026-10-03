package skillbill.engine.goalrunner.reset

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.decomposition.findMatchingDecompositionManifests
import skillbill.application.decomposition.parentSpecPath
import skillbill.engine.decomposition.encodeDecompositionManifestYaml
import skillbill.engine.featuretask.lifecycle.checkpoint.pruneGoalPurgeCheckpointRefs
import skillbill.engine.goalrunner.goalRepositoryIdentity
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerPurgeRequest
import skillbill.engine.goalrunner.model.GoalRunnerPurgeResult
import skillbill.engine.goalrunner.status.GoalRunnerStatusProjectionAssembler
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.requireAccepted
import skillbill.workflow.decomposition.resetManifest
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import java.nio.file.Path

@Inject
class GoalRunnerPurgeCoordinator(
  private val manifestStore: GoalRunnerManifestStore,
  private val gitOperations: WorkflowGitOperations,
  private val projectionAssembler: GoalRunnerStatusProjectionAssembler,
  private val manifestFileStore: DecompositionManifestStore,
  private val manifestValidator: DecompositionManifestValidator,
  private val database: DatabaseSessionFactory,
  private val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
) {
  fun purge(request: GoalRunnerPurgeRequest): GoalRunnerPurgeResult {
    val state =
      loadPurgeState(request)
        ?: return missingPurgeResult(FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(request.issueKey))
    state.refusal?.let { return it }
    val unlaunched = requireNotNull(state.sourceManifest).resetManifest(hard = true)
    val bundle = buildPurgeSpecBundle(state.repoRoot, unlaunched, requireNotNull(state.manifestPath))
    val specRestored = writePurgeSpecBundle(bundle)
    state.parentWorkflowId?.let { parentWorkflowId ->
      purgeDatabaseAndRestoreOnFailure(parentWorkflowId, bundle)
    }
    pruneGoalPurgeCheckpointRefs(
      gitOperations = gitOperations,
      repoRoot = state.repoRoot,
      issueKey = state.issueKey,
      skipStandaloneNamespace = hasStandaloneSibling(state.issueKey, state.repoRoot),
      record = {},
    )
    return GoalRunnerPurgeResult(
      issueKey = state.issueKey,
      parentWorkflowId = state.parentWorkflowId,
      deletedChildWorkflowIds = state.childWorkflowIds,
      specRestored = specRestored,
    )
  }

  private fun loadPurgeState(request: GoalRunnerPurgeRequest): PurgeState? {
    val repoRoot = request.repoRoot ?: error("repoRoot is required to purge goal '${request.issueKey}'.")
    val issueKey = FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(request.issueKey)
    val loaded = manifestStore.loadDurableByIssueKey(issueKey)
    val parentWorkflowId = loaded?.parentWorkflowId
    val childWorkflowIds = parentWorkflowId?.let(manifestStore::listOwnedGoalChildWorkflowIds).orEmpty()
    if (parentWorkflowId != null) {
      refuseIfLive(parentWorkflowId, childWorkflowIds, issueKey)?.let {
        return PurgeState(repoRoot, issueKey, parentWorkflowId, childWorkflowIds, null, null, it)
      }
    }
    val diskCandidates =
      findMatchingDecompositionManifests(
        repoRoot = repoRoot,
        issueKey = issueKey,
        fileStore = manifestFileStore,
        validator = manifestValidator,
        recoverPending = false,
      )
    if (parentWorkflowId == null && diskCandidates.isEmpty()) return null
    return PurgeState(
      repoRoot = repoRoot,
      issueKey = issueKey,
      parentWorkflowId = parentWorkflowId,
      childWorkflowIds = childWorkflowIds,
      sourceManifest = loaded?.manifest ?: diskCandidates.first().manifest,
      manifestPath =
        diskCandidates.firstOrNull()?.path
          ?: error("A decomposition manifest path is required to restore goal '$issueKey'."),
      refusal = null,
    )
  }

  private fun missingPurgeResult(issueKey: String) =
    GoalRunnerPurgeResult(
      issueKey = issueKey,
      parentWorkflowId = null,
      deletedChildWorkflowIds = emptyList(),
      specRestored = false,
      refusalReason = "No decomposed goal or feature-spec directory exists for '$issueKey'.",
    )

  private fun purgeDatabaseAndRestoreOnFailure(
    parentWorkflowId: String,
    bundle: PurgeSpecBundle,
  ) {
    runCatching {
      manifestStore.purgeDecomposedGoal(parentWorkflowId)
    }.onFailure { failure ->
      restorePurgeSpecBundle(bundle.snapshots)
      throw failure
    }
  }

  private fun refuseIfLive(
    parentWorkflowId: String,
    childWorkflowIds: List<String>,
    issueKey: String,
  ): GoalRunnerPurgeResult? {
    val blockingLiveness = projectionAssembler.resolvePurgeBlockingLiveness(parentWorkflowId, childWorkflowIds)
    return blockingLiveness?.let { liveness -> refused(issueKey, parentWorkflowId, childWorkflowIds, liveness) }
  }

  private fun refused(
    issueKey: String,
    parentWorkflowId: String,
    childWorkflowIds: List<String>,
    liveness: ExecutionLiveness,
  ): GoalRunnerPurgeResult {
    val reason =
      when (liveness) {
        ExecutionLiveness.LIVE -> "Goal '$issueKey' is live; refuse purge while a parent or child worker is active."
        ExecutionLiveness.UNKNOWN ->
          "Goal '$issueKey' has unknown execution liveness; refuse purge until liveness is known."
        ExecutionLiveness.IDLE -> "Goal '$issueKey' is idle."
      }
    return GoalRunnerPurgeResult(
      issueKey = issueKey,
      parentWorkflowId = parentWorkflowId,
      deletedChildWorkflowIds = childWorkflowIds,
      specRestored = false,
      refusalReason = reason,
    )
  }

  private fun hasStandaloneSibling(
    issueKey: String,
    repoRoot: Path,
  ): Boolean {
    val repositoryIdentity = goalRepositoryIdentity(repoRoot, repositoryEnclosingRootPort)
    return database.read { unitOfWork ->
      unitOfWork.workflowStates.findStandaloneFeatureTaskCandidates(issueKey, repositoryIdentity).isNotEmpty()
    }
  }

  private fun buildPurgeSpecBundle(
    repoRoot: Path,
    unlaunched: DecompositionManifest,
    manifestPath: Path,
  ): PurgeSpecBundle {
    val manifestYaml =
      encodeDecompositionManifestYaml(
        unlaunched,
        manifestValidator,
        manifestFileStore,
        sourceLabel = manifestPath.toString(),
      )
    val writes = mutableListOf<Pair<Path, String>>()
    writes += manifestPath to manifestYaml
    (listOf(unlaunched.parentSpecPath) + unlaunched.subtasks.map { it.specPath }).forEach { relativeSpecPath ->
      val specPath = repoRoot.resolve(relativeSpecPath).normalize()
      if (!manifestFileStore.isRegularFile(specPath)) {
        val relative = repoRoot.relativize(specPath).toString().replace('\\', '/')
        val restored = gitOperations.readHeadTrackedFile(repoRoot, relative)
        val content =
          when (restored) {
            is WorkflowGitOperationResult.Ok -> restored.value.orEmpty()
            is WorkflowGitOperationResult.Failed ->
              error("Missing spec '$relative' is not tracked at HEAD: ${restored.error}")
          }
        if (content.isBlank()) {
          error("Tracked spec '$relative' is empty at HEAD.")
        }
        writes += specPath to content
      }
    }
    val distinctWrites = writes.distinctBy { it.first }
    val snapshots =
      distinctWrites.map { (path, _) ->
        val existed = manifestFileStore.isRegularFile(path)
        PurgeSpecSnapshot(
          path = path,
          existed = existed,
          content = if (existed) manifestFileStore.readText(path) else null,
        )
      }
    return PurgeSpecBundle(distinctWrites, snapshots)
  }

  private fun writePurgeSpecBundle(bundle: PurgeSpecBundle): Boolean {
    if (bundle.writes.isEmpty()) return false
    manifestFileStore.writeBundleAtomically(bundle.writes) {
      val manifestWrite =
        bundle.writes.single { (path, _) ->
          path.fileName.toString() == DECOMPOSITION_MANIFEST_FILENAME
        }
      manifestValidator.validateYamlTextResult(manifestWrite.second, manifestWrite.first.toString())
        .requireAccepted(manifestWrite.first.toString())
    }
    return true
  }

  private fun restorePurgeSpecBundle(snapshots: List<PurgeSpecSnapshot>) {
    snapshots.asReversed().forEach { snapshot ->
      if (snapshot.existed) {
        manifestFileStore.writeTextAtomically(snapshot.path, requireNotNull(snapshot.content))
      } else {
        manifestFileStore.deleteIfExists(snapshot.path)
      }
    }
  }
}

private data class PurgeSpecSnapshot(
  val path: Path,
  val existed: Boolean,
  val content: String?,
)

private data class PurgeSpecBundle(
  val writes: List<Pair<Path, String>>,
  val snapshots: List<PurgeSpecSnapshot>,
)

private data class PurgeState(
  val repoRoot: Path,
  val issueKey: String,
  val parentWorkflowId: String?,
  val childWorkflowIds: List<String>,
  val sourceManifest: DecompositionManifest?,
  val manifestPath: Path?,
  val refusal: GoalRunnerPurgeResult?,
)
