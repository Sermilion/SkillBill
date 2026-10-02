package skillbill.application.decomposition

import skillbill.application.decomposition.model.DecompositionManifestRuntimeUpdate
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningResult
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.intentFor
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.runtime.invalidManifest
import skillbill.workflow.decomposition.withParentStatus
import java.nio.file.NoSuchFileException
import java.nio.file.Path

fun archivedDecompositionManifest(
  repoRoot: Path,
  manifestPath: Path,
): Boolean {
  val relative =
    runCatching { repoRoot.normalize().relativize(manifestPath.normalize()).toString() }
      .getOrDefault(manifestPath.toString())
      .replace('\\', '/')
  return relative.startsWith(".feature-specs/done/")
}

fun loadManifestOrNull(
  path: Path,
  validator: DecompositionManifestValidator,
  fileStore: DecompositionManifestStore,
): DecompositionManifest? =
  try {
    loadDecompositionManifest(path, fileStore, validator)
  } catch (_: NoSuchFileException) {
    null
  }

internal fun manifestPathFromArtifacts(
  repoRoot: Path,
  artifactsPatch: Map<String, Any?>?,
  existingArtifacts: Map<String, Any?>,
  planningResult: DecompositionPlanningResult? = null,
): Path? {
  val merged = LinkedHashMap(existingArtifacts)
  artifactsPatch?.let(merged::putAll)
  val specPath =
    (merged["assessment"] as? Map<*, *>)?.get(
      DecompositionPlanningPayloadKeys.SPEC_PATH,
    )?.toString()?.takeIf(String::isNotBlank)
      ?: planningResult?.takeIf { it.isDecomposeMode() }?.parentSpecPath?.takeIf(String::isNotBlank)
  planningResult?.takeIf { it.isDecomposeMode() }?.let { plan ->
    return decompositionManifestPath(
      repoRoot,
      Path.of(parentSpecPath(plan)),
      plan.subtasks.map { it.specPath },
    )
  }
  return specPath?.let { resolvedParentSpecPath(repoRoot, Path.of(it)).parent.resolve(DECOMPOSITION_MANIFEST_FILENAME) }
}

internal fun DecompositionManifest.assertExecutionModelCanReplace(
  existing: DecompositionManifest?,
  manifestPath: Path,
): DecompositionManifest {
  if (existing != null && executionModel != existing.executionModel && existing.subtasks.any { it.hasStarted() }) {
    invalidManifest(
      manifestPath.toString(),
      "execution_model cannot change after decomposition execution has begun; manually migrate or reset the " +
        "decomposition manifest before changing execution_model.",
    )
  }
  return this
}

internal fun DecompositionManifest.withRuntimeUpdate(
  repoRoot: Path,
  update: DecompositionManifestRuntimeUpdate,
): DecompositionManifest {
  val subtaskId = currentSubtaskIdForUpdate(repoRoot, update) ?: return this
  val status = statusFromUpdate(update)
  val updatedSubtasks =
    subtasks.map { subtask ->
      if (subtask.id == subtaskId) subtask.withRuntimeFields(this, update, status) else subtask
    }
  return copy(
    subtasks = updatedSubtasks,
    currentSubtaskIntent = intentFor(subtaskId, status),
  ).withParentStatus()
}
