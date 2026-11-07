package skillbill.engine.goalrunner.plan

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.issuekey.issueAndFeature
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.goal.StandalonePlanReportPort
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
public class StandalonePlanReportBuilder(
  private val manifestStore: GoalRunnerManifestStore,
  private val fileStore: DecompositionManifestStore,
  private val projectStore: ProjectStorePort,
  private val diagnostics: RuntimeDiagnostics,
) : StandalonePlanReportPort {
  internal fun manifestExists(
    issueKey: String,
    repoRoot: Path,
  ): Boolean = manifestStore.readByIssueKey(issueKey, repoRoot) != null

  public fun build(
    issueKey: String,
    repoRoot: Path,
  ): StandalonePlanReport? =
    manifestStore.readByIssueKeyIfPresent(issueKey, repoRoot)?.manifest?.let { report(it, repoRoot) }

  public override fun detailReport(
    issueKey: String,
    repoRoot: Path,
    savedSubtaskIds: Set<Int>,
  ): StandalonePlanReport? {
    val manifest = manifestStore.readByIssueKeyIfPresent(issueKey, repoRoot)?.manifest
    if (manifest != null) return report(manifest, repoRoot, savedSubtaskIds)
    return seededReport(issueKey, repoRoot, savedSubtaskIds)
  }

  private fun seededReport(
    issueKey: String,
    repoRoot: Path,
    savedSubtaskIds: Set<Int>,
  ): StandalonePlanReport? {
    val featureName = seededFeatureName(issueKey, repoRoot) ?: return null
    val store = projectStore.resolve(repoRoot)
    val bundleDirectory = findBundle(store.specsRoot, issueKey, featureName) ?: return null
    val normalizedBundleDirectory = bundleDirectory.toAbsolutePath().normalize()
    val parentSpec = normalizedBundleDirectory.resolve("spec.md")
    if (!fileStore.isRegularFile(parentSpec)) return null
    return StandalonePlanReport(
      featureName = featureName,
      parentSpecPath = parentSpec.toString(),
      subtasks = emptyList(),
      bundleFiles = planBundleFiles(normalizedBundleDirectory, emptyMap(), savedSubtaskIds),
    )
  }

  private fun findBundle(
    specsRoot: Path,
    issueKey: String,
    featureName: String,
  ): Path? =
    try {
      fileStore.listDirectChildDirectories(specsRoot).singleOrNull {
        issueAndFeature(it.fileName.toString()).let { (key, name) ->
          key.equals(issueKey, ignoreCase = true) && name == featureName
        }
      }
    } catch (error: IOException) {
      unlisted(specsRoot, error)
      null
    } catch (error: UncheckedIOException) {
      unlisted(specsRoot, error)
      null
    }

  public override fun seededFeatureName(
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

  public override fun report(
    manifest: DecompositionManifest,
    repoRoot: Path,
  ): StandalonePlanReport = report(manifest, repoRoot, null)

  private fun report(
    manifest: DecompositionManifest,
    repoRoot: Path,
    savedSubtaskIds: Set<Int>?,
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
      bundleFiles =
        planBundleFiles(
          requireNotNull(parentSpec.parent),
          manifest.subtasks.associate { absoluteSpecPath(it.specPath) to it.id },
          savedSubtaskIds,
        ),
    )
  }

  private fun planBundleFiles(
    bundleDirectory: Path,
    subtaskSpecIds: Map<Path, Int>,
    savedSubtaskIds: Set<Int>?,
  ): List<String> =
    try {
      fileStore
        .listTree(bundleDirectory)
        .filter(fileStore::isRegularFile)
        .filter { path ->
          if (savedSubtaskIds == null) return@filter true
          val normalized = path.toAbsolutePath().normalize()
          val relative = bundleDirectory.relativize(normalized).toString().replace('\\', '/')
          val subtaskId = subtaskSpecIds[normalized]
          when {
            subtaskId != null -> subtaskId in savedSubtaskIds
            Regex("(?:.*/)?spec_subtask_[1-9][0-9]*(?:_.*)?\\.md").matches(relative) -> false
            else -> true
          }
        }
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
