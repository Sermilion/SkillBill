package skillbill.engine.goalrunner.intake

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.issuekey.issueAndFeature
import skillbill.contracts.issuekey.normalizeIssueKey
import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.contracts.projectstore.ProjectStoreLayout
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import java.io.IOException
import java.nio.file.Path

data class LegacySpecImportResult(
  val sourceRoot: Path,
  val specsRoot: Path,
  val imported: List<String>,
  val alreadyInStore: List<String>,
  val inFlight: List<String>,
  val failed: List<String>,
)

@Inject
class LegacySpecImport(
  private val database: DatabaseSessionFactory,
  private val projectStore: ProjectStorePort,
  private val fileStore: DecompositionManifestStore,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun import(
    repoRoot: Path,
    sourceRoot: Path? = null,
  ): LegacySpecImportResult {
    val root = repoRoot.toAbsolutePath().normalize()
    val source =
      (sourceRoot ?: root.resolve(LegacyProjectStatePaths.FEATURE_SPECS_DIRECTORY)).toAbsolutePath().normalize()
    val specsRoot = projectStore.resolve(root).specsRoot
    val outcomes = mutableMapOf<Outcome, MutableList<String>>()
    fileStore.listDirectChildDirectories(source).sorted().forEach { bundle ->
      val name = bundle.fileName.toString()
      if (name == DONE_DIRECTORY) {
        fileStore.listDirectChildDirectories(bundle).sorted().forEach { archived ->
          val label = "$DONE_DIRECTORY/${archived.fileName}"
          val target = specsRoot.resolve(archived.fileName.toString())
          outcomes.getOrPut(copy(archived, target, label)) { mutableListOf() } += label
        }
      } else {
        val outcome =
          when {
            isInFlight(name) -> Outcome.IN_FLIGHT
            storeHasIssue(specsRoot, name) -> Outcome.ALREADY_IN_STORE
            else -> copy(bundle, specsRoot.resolve(name), name)
          }
        outcomes.getOrPut(outcome) { mutableListOf() } += name
      }
    }
    return LegacySpecImportResult(
      sourceRoot = source,
      specsRoot = specsRoot,
      imported = outcomes[Outcome.IMPORTED].orEmpty(),
      alreadyInStore = outcomes[Outcome.ALREADY_IN_STORE].orEmpty(),
      inFlight = outcomes[Outcome.IN_FLIGHT].orEmpty(),
      failed = outcomes[Outcome.FAILED].orEmpty(),
    )
  }

  private fun isInFlight(bundleName: String): Boolean {
    val issueKey = normalizeIssueKey(issueAndFeature(bundleName).first) ?: return false
    return try {
      database.readIfPresent { unitOfWork ->
        unitOfWork.workflowStates
          .findFeatureTaskWorkflowsForIssue(FeatureTaskWorkflowMode.RUNTIME, issueKey, null)
          .any { workflow -> isActive(workflow.workflowStatus) }
      } == true
    } catch (error: SkillBillRuntimeException) {
      degrade(bundleName, "workflow_lookup_failed", error)
      true
    } catch (error: IOException) {
      degrade(bundleName, "workflow_lookup_failed", error)
      true
    }
  }

  private fun storeHasIssue(
    specsRoot: Path,
    bundleName: String,
  ): Boolean {
    val issueKey = issueAndFeature(bundleName).first
    return fileStore.listDirectChildDirectories(specsRoot).any { existing ->
      val name = existing.fileName.toString()
      issueAndFeature(name).first == issueKey && bundleFiles(existing).isNotEmpty()
    }
  }

  private fun copy(
    bundle: Path,
    target: Path,
    label: String,
  ): Outcome {
    return try {
      if (bundleFiles(target).isNotEmpty()) return Outcome.ALREADY_IN_STORE
      val writes =
        bundleFiles(bundle).map { file ->
          val text = fileStore.readTextWithoutRecovery(file)
          val content = if (file.fileName.toString() == MANIFEST_FILE) storeManifestPaths(text) else text
          target.resolve(bundle.relativize(file).toString()) to content
        }
      if (writes.isNotEmpty()) fileStore.writeBundleAtomically(writes) {}
      Outcome.IMPORTED
    } catch (error: SkillBillRuntimeException) {
      degrade(label, "copy_failed", error)
      Outcome.FAILED
    } catch (error: IOException) {
      degrade(label, "copy_failed", error)
      Outcome.FAILED
    }
  }

  private fun bundleFiles(directory: Path): List<Path> =
    if (fileStore.listDirectChildDirectories(directory.parent).none { it.fileName == directory.fileName }) {
      emptyList()
    } else {
      fileStore.listTree(directory)
        .filter { path -> !path.fileName.toString().startsWith(BUNDLE_JOURNAL_PREFIX) }
        .filter(fileStore::isRegularFileWithoutRecovery)
        .sorted()
    }

  private fun storeManifestPaths(text: String): String =
    LEGACY_MANIFEST_PATH.replace(text) { match -> match.groupValues[1] + ProjectStoreLayout.SPECS_PREFIX }

  private fun isActive(workflowStatus: String): Boolean =
    WorkflowStatus.fromWire(workflowStatus)?.let { it !in WorkflowStatus.terminalStatuses } == true

  private fun degrade(
    bundle: String,
    cause: String,
    error: Throwable,
  ) {
    diagnostics.warning(
      "seam=legacy_spec_import value_expected=store_bundle value_used=$bundle cause=$cause",
      error,
    )
  }

  private enum class Outcome { IMPORTED, ALREADY_IN_STORE, IN_FLIGHT, FAILED }

  private companion object {
    const val DONE_DIRECTORY = LegacyProjectStatePaths.FEATURE_SPECS_ARCHIVE_DIRECTORY
    const val MANIFEST_FILE = "decomposition-manifest.yaml"
    const val BUNDLE_JOURNAL_PREFIX = ".decomposition-manifest-bundle-"
    val LEGACY_MANIFEST_PATH =
      Regex(
        """(^|[\s"':\[,])${Regex.escape(LegacyProjectStatePaths.FEATURE_SPECS_PREFIX)}(?:$DONE_DIRECTORY/)?""",
        RegexOption.MULTILINE,
      )
  }
}
