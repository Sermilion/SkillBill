package skillbill.engine.goalrunner.plan

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.issuekey.issueAndFeature
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandalonePlanReport
import skillbill.ports.idestatus.model.StandalonePlanSubtask
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.model.resolveSpecPath
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.decomposition.model.DecompositionManifest
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Path

@Inject
class StandalonePlanReportBuilder(
  private val manifestStore: GoalRunnerManifestStore,
  private val fileStore: DecompositionManifestStore,
  private val projectStore: ProjectStorePort,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun manifestExists(
    issueKey: String,
    repoRoot: Path,
  ): Boolean = manifestStore.readByIssueKey(issueKey, repoRoot) != null

  fun build(
    issueKey: String,
    repoRoot: Path,
  ): StandalonePlanReport? =
    manifestStore.readByIssueKeyIfPresent(issueKey, repoRoot)?.manifest?.let { report(it, repoRoot) }

  fun seededFeatureName(
    issueKey: String,
    repoRoot: Path,
  ): String? {
    val specsRoot = projectStore.resolve(repoRoot).specsRoot
    val bundles =
      try {
        fileStore.listDirectChildDirectories(specsRoot)
      } catch (error: IOException) {
        return unlisted(specsRoot, error)
      } catch (error: UncheckedIOException) {
        return unlisted(specsRoot, error)
      }
    return bundles
      .map { issueAndFeature(it.fileName.toString()) }
      .singleOrNull { (key, _) -> key.equals(issueKey, ignoreCase = true) }
      ?.second
  }

  fun report(
    manifest: DecompositionManifest,
    repoRoot: Path,
  ): StandalonePlanReport {
    val store = projectStore.resolve(repoRoot)
    val checkoutRoot = repoRoot.toAbsolutePath().normalize()
    val absoluteSpecPath = { specPath: String ->
      store.resolveSpecPath(specPath, checkoutRoot).toAbsolutePath().normalize()
    }
    val parentSpec = absoluteSpecPath(manifest.parentSpecPath)
    return StandalonePlanReport(
      featureName = manifest.featureName,
      parentSpecPath = parentSpec.toString(),
      subtasks =
        manifest.subtasks.map { subtask ->
          StandalonePlanSubtask(
            subtask.id,
            subtask.name,
            absoluteSpecPath(subtask.specPath).toString(),
            subtask.dependencies.map { it.subtaskId },
          )
        },
      bundleFiles = planBundleFiles(requireNotNull(parentSpec.parent)),
    )
  }

  private fun planBundleFiles(bundleDirectory: Path): List<String> =
    try {
      fileStore
        .listTree(bundleDirectory)
        .filter(fileStore::isRegularFile)
        .map { it.toAbsolutePath().normalize().toString() }
        .sorted()
    } catch (error: IOException) {
      unreadable(bundleDirectory, error)
    } catch (error: UncheckedIOException) {
      unreadable(bundleDirectory, error)
    }

  private fun unlisted(
    specsRoot: Path,
    error: Exception,
  ): String? {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=plan_seeded_feature_name value_expected=$specsRoot listable value_used=null " +
        "cause=${error::class.simpleName}",
      error,
    )
    return null
  }

  private fun unreadable(
    bundleDirectory: Path,
    error: Exception,
  ): List<String> {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=plan_bundle_files value_expected=$bundleDirectory readable value_used=empty " +
        "cause=${error::class.simpleName}",
      error,
    )
    return emptyList()
  }
}
