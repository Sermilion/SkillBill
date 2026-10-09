package skillbill.engine.goalrunner.reset

import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.decomposition.repoRelativePath
import skillbill.engine.decomposition.encodeDecompositionManifestYaml
import skillbill.engine.goalrunner.model.GoalRunnerPurgeSpecAction
import skillbill.engine.goalrunner.model.GoalRunnerPurgeSpecActionKind
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.requireAccepted
import skillbill.workflow.decomposition.resetManifest
import java.nio.file.Path

internal class GoalRunnerPurgeSpecBundleReset(
  private val gitOperations: WorkflowGitOperations,
  private val manifestFileStore: DecompositionManifestStore,
  private val manifestValidator: DecompositionManifestValidator,
) {
  fun reset(
    repoRoot: Path,
    source: DecompositionManifest,
    manifestPath: Path,
  ): List<GoalRunnerPurgeSpecAction> =
    when {
      !isInsideRepo(repoRoot, manifestPath) -> emptyList()
      isTracked(repoRoot, manifestPath) -> resetTrackedBundle(repoRoot, source, manifestPath)
      else -> deleteUntrackedBundle(repoRoot, source, manifestPath)
    }

  fun survivors(
    repoRoot: Path,
    source: DecompositionManifest,
    manifestPath: Path,
  ): List<String> =
    if (!isInsideRepo(repoRoot, manifestPath) || isTracked(repoRoot, manifestPath)) {
      emptyList()
    } else {
      (untrackedSubtaskSpecs(repoRoot, source) + listOf(manifestPath).filter(manifestFileStore::isRegularFile))
        .map { path -> repoRelativePath(repoRoot, path) }
    }

  private fun deleteUntrackedBundle(
    repoRoot: Path,
    source: DecompositionManifest,
    manifestPath: Path,
  ): List<GoalRunnerPurgeSpecAction> {
    val deleted =
      untrackedSubtaskSpecs(
        repoRoot,
        source,
      ) + listOf(manifestPath).filter(manifestFileStore::isRegularFile)
    return deleted.map { path ->
      manifestFileStore.deleteIfExists(path)
      GoalRunnerPurgeSpecAction(repoRelativePath(repoRoot, path), GoalRunnerPurgeSpecActionKind.DELETED)
    }
  }

  private fun resetTrackedBundle(
    repoRoot: Path,
    source: DecompositionManifest,
    manifestPath: Path,
  ): List<GoalRunnerPurgeSpecAction> {
    val unlaunched = source.resetManifest(hard = true)
    val manifestYaml =
      encodeDecompositionManifestYaml(
        unlaunched,
        manifestValidator,
        manifestFileStore,
        sourceLabel = manifestPath.toString(),
      )
    val writes = mutableListOf<Pair<Path, String>>()
    val actions = mutableListOf<GoalRunnerPurgeSpecAction>()
    if (!manifestFileStore.isRegularFile(manifestPath) || manifestFileStore.readText(manifestPath) != manifestYaml) {
      writes += manifestPath to manifestYaml
      actions +=
        GoalRunnerPurgeSpecAction(repoRelativePath(repoRoot, manifestPath), GoalRunnerPurgeSpecActionKind.RESET)
    }
    subtaskSpecPaths(repoRoot, unlaunched)
      .filterNot(manifestFileStore::isRegularFile)
      .forEach { specPath ->
        val restored = readTrackedContent(repoRoot, specPath) ?: return@forEach
        check(restored.isNotBlank()) { "Tracked spec '${repoRelativePath(repoRoot, specPath)}' is empty at HEAD." }
        writes += specPath to restored
        actions +=
          GoalRunnerPurgeSpecAction(repoRelativePath(repoRoot, specPath), GoalRunnerPurgeSpecActionKind.RESTORED)
      }
    if (writes.isNotEmpty()) {
      manifestFileStore.writeBundleAtomically(writes) {
        manifestValidator.validateYamlTextResult(manifestYaml, manifestPath.toString())
          .requireAccepted(manifestPath.toString())
      }
    }
    return actions
  }

  private fun untrackedSubtaskSpecs(
    repoRoot: Path,
    manifest: DecompositionManifest,
  ): List<Path> =
    subtaskSpecPaths(repoRoot, manifest).filter { path ->
      manifestFileStore.isRegularFile(path) && !isTracked(repoRoot, path)
    }

  private fun subtaskSpecPaths(
    repoRoot: Path,
    manifest: DecompositionManifest,
  ): List<Path> {
    val parentSpec = repoRoot.resolve(manifest.parentSpecPath).normalize()
    return manifest.subtasks
      .map { subtask -> repoRoot.resolve(subtask.specPath).normalize() }
      .filter { path ->
        path != parentSpec &&
          path.fileName.toString() != DECOMPOSITION_MANIFEST_FILENAME &&
          isInsideRepo(repoRoot, path)
      }
      .distinct()
  }

  private fun isInsideRepo(
    repoRoot: Path,
    path: Path,
  ): Boolean = path.toAbsolutePath().normalize().startsWith(repoRoot.toAbsolutePath().normalize())

  private fun isTracked(
    repoRoot: Path,
    path: Path,
  ): Boolean = readTrackedContent(repoRoot, path) != null

  private fun readTrackedContent(
    repoRoot: Path,
    path: Path,
  ): String? =
    when (val head = gitOperations.readHeadTrackedFile(repoRoot, repoRelativePath(repoRoot, path))) {
      is WorkflowGitOperationResult.Ok -> head.value
      is WorkflowGitOperationResult.Failed -> null
    }
}
