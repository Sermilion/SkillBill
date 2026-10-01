package skillbill.application.decomposition

import skillbill.application.decomposition.model.DecompositionManifestFileCandidate
import skillbill.error.shellcontent.InvalidDecompositionManifestSchemaError
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
  repoRoot: Path,
  issueKey: String,
  fileStore: DecompositionManifestStore,
  validator: DecompositionManifestValidator,
  recoverPending: Boolean = true,
): List<DecompositionManifestFileCandidate> {
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
    .filterNot { path -> archivedDecompositionManifest(repoRoot, path) }
    .filter { path ->
      val relativePath =
        runCatching { repoRoot.relativize(path).toString() }
          .getOrElse { path.toString() }
      issueKeyInPath.containsMatchIn(relativePath.uppercase())
    }
    .map { path ->
      val manifest =
        try {
          loadDecompositionManifest(path, fileStore, validator, recoverPending)
        } catch (error: NoSuchFileException) {
          throw InvalidDecompositionManifestSchemaError(
            sourceLabel = path.toString(),
            reason = "manifest disappeared during read; the decomposition bundle is incomplete.",
            failureCode = "incomplete_bundle",
            cause = error,
          )
        }
      if (manifest.issueKey != normalizedIssueKey) {
        throw InvalidDecompositionManifestSchemaError(
          sourceLabel = path.toString(),
          reason =
            "manifest issue_key '${manifest.issueKey}' does not match the requested issue key " +
              "'$normalizedIssueKey'.",
          failureCode = "issue_key_mismatch",
        )
      }
      DecompositionManifestFileCandidate(path, manifest)
    }
    .toList()
}

fun resolveDecompositionManifest(
  repoRoot: Path,
  issueKey: String,
  fileStore: DecompositionManifestStore,
  validator: DecompositionManifestValidator,
  recoverPending: Boolean = true,
): DecompositionManifest? {
  val candidates = findMatchingDecompositionManifests(repoRoot, issueKey, fileStore, validator, recoverPending)
  val activeCandidates = candidates.filter { candidate -> candidate.manifest.isActiveGoalRuntime() }
  if (activeCandidates.size > 1) {
    throw InvalidDecompositionManifestSchemaError(
      sourceLabel = issueKey,
      reason =
        "multiple active decomposition manifests match the requested issue key: " +
          activeCandidates.joinToString { candidate -> repoRoot.relativize(candidate.path).toString() } + ".",
      failureCode = "duplicate_active",
    )
  }
  return activeCandidates.firstOrNull()?.manifest ?: candidates.firstOrNull()?.manifest
}
