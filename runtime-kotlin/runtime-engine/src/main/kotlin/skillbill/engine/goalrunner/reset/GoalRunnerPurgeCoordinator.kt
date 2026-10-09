package skillbill.engine.goalrunner.reset

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.findMatchingDecompositionManifests
import skillbill.application.decomposition.repoRelativePath
import skillbill.engine.featuretask.lifecycle.checkpoint.pruneGoalPurgeCheckpointRefs
import skillbill.engine.goalrunner.goalRepositoryIdentity
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerPurgeRequest
import skillbill.engine.goalrunner.model.GoalRunnerPurgeResult
import skillbill.engine.goalrunner.model.GoalRunnerPurgeSpecAction
import skillbill.engine.goalrunner.status.GoalRunnerStatusProjectionAssembler
import skillbill.goalrunner.model.ExecutionLiveness
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.model.GoalPurgeTarget
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.taskruntime.model.implementationChecklistDirectory
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.goalstate.GoalRuntimeStateFileStore
import skillbill.ports.workflow.goalstate.model.GoalRuntimeDirectoryDeletion
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.taskruntime.artifact.FeatureTaskRuntimeRunEvidenceAddress
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
  private val goalStateFiles: GoalRuntimeStateFileStore,
) {
  private val specBundleReset = GoalRunnerPurgeSpecBundleReset(gitOperations, manifestFileStore, manifestValidator)

  fun purge(request: GoalRunnerPurgeRequest): GoalRunnerPurgeResult {
    val repoRoot = request.repoRoot ?: error("repoRoot is required to purge goal '${request.issueKey}'.")
    val issueKey = FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(request.issueKey)
    val plan = discover(repoRoot, issueKey)
    if (plan.refusalReason != null) return plan.toResult()
    val directories = ownedDirectories(repoRoot, plan.target)
    val removedPaths = mutableListOf<String>()
    val failedDirectories = mutableListOf<String>()
    directories.forEach { directory ->
      when (val deletion = goalStateFiles.deleteDirectoryTree(directory)) {
        GoalRuntimeDirectoryDeletion.Deleted -> removedPaths += repoRelativePath(repoRoot, directory)
        GoalRuntimeDirectoryDeletion.Absent -> Unit
        is GoalRuntimeDirectoryDeletion.Failed ->
          failedDirectories += "${repoRelativePath(repoRoot, directory)}: ${deletion.reason}"
      }
    }
    if (failedDirectories.isNotEmpty()) {
      return plan.toResult(
        removedPaths = removedPaths,
        leftovers = failedDirectories + "database and spec-bundle steps deferred until the directories can be removed",
      )
    }
    val leftovers = mutableListOf<String>()
    val removedRowCounts =
      if (plan.target.isEmpty) {
        emptyMap()
      } else {
        manifestStore.purgeDecomposedGoal(
          plan.target,
        ).byTable
      }
    val checkpointRefsPruned =
      pruneGoalPurgeCheckpointRefs(
        gitOperations = gitOperations,
        repoRoot = repoRoot,
        issueKey = issueKey,
        skipStandaloneNamespace = hasStandaloneSibling(issueKey, repoRoot),
        record = { diagnostic -> leftovers += diagnostic },
      )
    val specActions =
      plan.sourceManifest?.let { source -> specBundleReset.reset(repoRoot, source, requireNotNull(plan.manifestPath)) }
        .orEmpty()
    leftovers += survivors(repoRoot, issueKey, plan, directories)
    return plan.toResult(
      removedRowCounts = removedRowCounts,
      removedPaths = removedPaths,
      specBundleActions = specActions,
      checkpointRefsPruned = checkpointRefsPruned,
      leftovers = leftovers,
    )
  }

  private fun discover(
    repoRoot: Path,
    issueKey: String,
  ): PurgePlan {
    val ownership = manifestStore.discoverPurgeOwnership(issueKey, repoRoot)
    val diskCandidate =
      findMatchingDecompositionManifests(
        repoRoot = repoRoot,
        issueKey = issueKey,
        fileStore = manifestFileStore,
        validator = manifestValidator,
        recoverPending = false,
      ).firstOrNull()
    val childrenByParent = ownership.parentWorkflowIds.associateWith(manifestStore::listOwnedGoalChildWorkflowIds)
    val manifests = listOfNotNull(diskCandidate?.manifest) + ownership.manifests.map { it.manifest }
    val manifestRecordedIds =
      manifests.flatMapTo(linkedSetOf()) { manifest ->
        manifest.subtasks.mapNotNull { subtask -> subtask.workflowId?.takeIf(String::isNotBlank) }
      }
    val verifiedIds =
      manifestStore.verifyOwnedWorkflowIds(manifestRecordedIds, ownership.parentWorkflowIds, issueKey, repoRoot)
    val target =
      GoalPurgeTarget(
        parentWorkflowIds = ownership.parentWorkflowIds,
        workflowIds = ownership.parentWorkflowIds + childrenByParent.values.flatten() + verifiedIds,
      )
    val sourceManifest = manifests.firstOrNull()
    val orphanIds = verifiedIds - childrenByParent.values.flatten().toSet()
    return PurgePlan(
      issueKey = issueKey,
      target = target,
      sourceManifest = sourceManifest,
      manifestPath = diskCandidate?.path ?: sourceManifest?.let { derivedManifestPath(repoRoot, it) },
      refusalReason =
        childrenByParent.firstNotNullOfOrNull { (parentId, childIds) ->
          refusal(issueKey, parentId, childIds + orphanIds)
        },
      unclassifiedLeftovers = ownership.unclassifiedWorkflows,
    )
  }

  private fun derivedManifestPath(
    repoRoot: Path,
    manifest: DecompositionManifest,
  ): Path = decompositionManifestPath(repoRoot, Path.of(manifest.parentSpecPath), manifest.subtasks.map { it.specPath })

  private fun refusal(
    issueKey: String,
    parentWorkflowId: String,
    childWorkflowIds: List<String>,
  ): String? =
    when (projectionAssembler.resolvePurgeBlockingLiveness(parentWorkflowId, childWorkflowIds)) {
      ExecutionLiveness.LIVE -> "Goal '$issueKey' is live; refuse purge while a parent or child worker is active."
      ExecutionLiveness.UNKNOWN ->
        "Goal '$issueKey' has unknown execution liveness; refuse purge until liveness is known."
      ExecutionLiveness.IDLE, null -> null
    }

  private fun ownedDirectories(
    repoRoot: Path,
    target: GoalPurgeTarget,
  ): List<Path> {
    val root = repoRoot.normalize()
    return target.workflowIds.sorted().flatMap { workflowId ->
      listOf(
        implementationChecklistDirectory(workflowId),
        FeatureTaskRuntimeRunEvidenceAddress.workflowStoreRoot(workflowId),
      ).map { relative ->
        repoRoot.resolve(relative).normalize().also { resolved ->
          require(resolved.startsWith(root)) { "Owned runtime directory '$relative' escapes the repository root." }
        }
      }
    }
  }

  private fun survivors(
    repoRoot: Path,
    issueKey: String,
    plan: PurgePlan,
    directories: List<Path>,
  ): List<String> =
    buildList {
      if (!plan.target.isEmpty) {
        manifestStore.countDecomposedGoalState(plan.target).byTable
          .filterValues { count -> count > 0 }
          .forEach { (table, count) -> add("$table: $count rows") }
      }
      directories.filter(goalStateFiles::directoryExists).forEach { add(repoRelativePath(repoRoot, it)) }
      manifestStore.discoverPurgeOwnership(issueKey, repoRoot).parentWorkflowIds
        .forEach { parentId -> add("parent workflow $parentId still present") }
      plan.sourceManifest?.let { source ->
        addAll(specBundleReset.survivors(repoRoot, source, requireNotNull(plan.manifestPath)))
      }
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
}

private data class PurgePlan(
  val issueKey: String,
  val target: GoalPurgeTarget,
  val sourceManifest: DecompositionManifest?,
  val manifestPath: Path?,
  val refusalReason: String?,
  val unclassifiedLeftovers: List<String>,
) {
  fun toResult(
    removedRowCounts: Map<String, Int> = emptyMap(),
    removedPaths: List<String> = emptyList(),
    specBundleActions: List<GoalRunnerPurgeSpecAction> = emptyList(),
    checkpointRefsPruned: Int = 0,
    leftovers: List<String> = emptyList(),
  ): GoalRunnerPurgeResult =
    GoalRunnerPurgeResult(
      issueKey = issueKey,
      parentWorkflowIds = target.parentWorkflowIds.sorted(),
      deletedChildWorkflowIds = (target.workflowIds - target.parentWorkflowIds).sorted(),
      removedRowCounts = removedRowCounts,
      removedPaths = removedPaths,
      specBundleActions = specBundleActions,
      checkpointRefsPruned = checkpointRefsPruned,
      leftovers = unclassifiedLeftovers + leftovers,
      refusalReason = refusalReason,
    )
}
