package skillbill.application.decomposition

import skillbill.application.decomposition.model.DecompositionManifestFileCandidate
import skillbill.application.decomposition.model.SpecBundleScope
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.error.shellcontent.invalidDecompositionManifestSchema
import skillbill.ports.projectstore.model.ProjectStoreLocation
import skillbill.ports.projectstore.model.SpecPathLocation
import skillbill.ports.projectstore.model.classifySpecPath
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestValidationResult
import skillbill.workflow.decomposition.model.requireAccepted
import skillbill.workflow.decomposition.runtime.isActiveGoalRuntime
import java.nio.file.NoSuchFileException
import java.nio.file.Path

fun loadDecompositionManifest(
  path: Path,
  fileStore: DecompositionManifestStore,
  validator: DecompositionManifestValidator,
  recoverPending: Boolean = true,
): DecompositionManifest {
  val yamlText = if (recoverPending) fileStore.readText(path) else fileStore.readTextWithoutRecovery(path)
  return when (val result = validator.validateYamlTextResult(yamlText, path.toString())) {
    is DecompositionManifestValidationResult.AcceptedUnchanged -> result.manifest
    is DecompositionManifestValidationResult.AcceptedAfterRepair -> result.manifest
    is DecompositionManifestValidationResult.Rejected -> {
      result.requireAccepted(path.toString())
      error("Unreachable rejected decomposition manifest result.")
    }
  }
}

fun findMatchingDecompositionManifests(
  scope: SpecBundleScope,
  issueKey: String,
  fileStore: DecompositionManifestStore,
  validator: DecompositionManifestValidator,
  recoverPending: Boolean = true,
): List<DecompositionManifestFileCandidate> {
  val (repoRoot, store) = scope
  val normalizedIssueKey = issueKey.trim().uppercase()
  val issueKeyInPath = Regex("(?<![A-Za-z0-9])${Regex.escape(normalizedIssueKey)}(?![A-Za-z0-9])")
  val manifestFiles =
    if (recoverPending) {
      fileStore.findDecompositionManifestFiles(repoRoot)
    } else {
      fileStore.findDecompositionManifestFilesWithoutRecovery(repoRoot)
    }
  return manifestFiles
    .asSequence()
    .sortedBy(Path::toString)
    .filter { path -> issueKeyInPath.containsMatchIn(manifestLabel(repoRoot, store, path).uppercase()) }
    .map { path ->
      val manifest =
        try {
          loadDecompositionManifest(path, fileStore, validator, recoverPending)
        } catch (error: NoSuchFileException) {
          throw invalidDecompositionManifestSchema(
            sourceLabel = path.toString(),
            reason = "manifest disappeared during read; the decomposition bundle is incomplete.",
            code = WorkflowFailureCode.DECOMPOSITION_MANIFEST_INCOMPLETE_BUNDLE,
            cause = error,
          )
        }
      if (manifest.issueKey != normalizedIssueKey) {
        throw invalidDecompositionManifestSchema(
          sourceLabel = path.toString(),
          reason =
            "manifest issue_key '${manifest.issueKey}' does not match the requested issue key " +
              "'$normalizedIssueKey'.",
          code = WorkflowFailureCode.DECOMPOSITION_MANIFEST_ISSUE_KEY_MISMATCH,
        )
      }
      DecompositionManifestFileCandidate(path, manifest)
    }
    .toList()
}

private fun manifestLabel(
  repoRoot: Path,
  store: ProjectStoreLocation,
  path: Path,
): String =
  store.classifySpecPath(path.toAbsolutePath().normalize(), repoRoot.toAbsolutePath().normalize())?.relative
    ?: runCatching { repoRoot.relativize(path).toString() }.getOrElse { path.toString() }

fun resolveDecompositionManifest(
  scope: SpecBundleScope,
  issueKey: String,
  fileStore: DecompositionManifestStore,
  validator: DecompositionManifestValidator,
  recoverPending: Boolean = true,
): DecompositionManifest? {
  val (repoRoot, store) = scope
  val candidates =
    findMatchingDecompositionManifests(scope, issueKey, fileStore, validator, recoverPending)
      .preferStore(repoRoot, store)
  val activeCandidates = candidates.filter { candidate -> candidate.manifest.isActiveGoalRuntime() }
  if (activeCandidates.size > 1) {
    throw invalidDecompositionManifestSchema(
      sourceLabel = issueKey,
      reason =
        "multiple active decomposition manifests match the requested issue key: " +
          activeCandidates.joinToString { candidate -> manifestLabel(repoRoot, store, candidate.path) } + ".",
      code = WorkflowFailureCode.DECOMPOSITION_MANIFEST_DUPLICATE_ACTIVE,
    )
  }
  return activeCandidates.firstOrNull()?.manifest ?: candidates.firstOrNull()?.manifest
}

private fun List<DecompositionManifestFileCandidate>.preferStore(
  repoRoot: Path,
  store: ProjectStoreLocation,
): List<DecompositionManifestFileCandidate> {
  val root = repoRoot.toAbsolutePath().normalize()
  val inStore =
    filter { candidate ->
      store.classifySpecPath(candidate.path.toAbsolutePath().normalize(), root) is SpecPathLocation.Store
    }
  return inStore.ifEmpty { this }
}
