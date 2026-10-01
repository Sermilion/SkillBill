package skillbill.application.decomposition

import skillbill.application.decomposition.model.LoadedDecompositionManifest
import skillbill.application.decomposition.model.ValidatedDecompositionManifestYaml
import skillbill.error.shellcontent.InvalidDecompositionManifestSchemaError
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestValidationFailureCode
import skillbill.workflow.decomposition.model.DecompositionManifestValidationResult
import skillbill.workflow.decomposition.model.requireAccepted
import java.nio.file.NoSuchFileException
import java.nio.file.Path

fun loadValidatedDecompositionManifest(
  path: Path,
  fileStore: DecompositionManifestStore,
  validator: DecompositionManifestValidator,
  recoverPending: Boolean = true,
): LoadedDecompositionManifest {
  val validated = validateDecompositionManifestYaml(path, fileStore, validator, recoverPending)
  return LoadedDecompositionManifest(
    manifest = validated.manifest,
    yamlText = validated.yamlText,
    repairEvidence = validated.repairEvidence,
  )
}

fun loadValidatedDecompositionManifestPersistingRepair(
  path: Path,
  fileStore: DecompositionManifestStore,
  validator: DecompositionManifestValidator,
): LoadedDecompositionManifest {
  val loaded = loadValidatedDecompositionManifest(path, fileStore, validator)
  val repairEvidence = loaded.repairEvidence ?: return loaded

  fileStore.writeTextAtomically(path, loaded.yamlText)
  val persisted = loadValidatedDecompositionManifest(path, fileStore, validator)
  if (persisted.repairEvidence != null) {
    throw InvalidDecompositionManifestSchemaError(
      sourceLabel = path.toString(),
      reason = "repaired YAML did not validate unchanged after persistence.",
      failureCode = DecompositionManifestValidationFailureCode.REPAIR_LIMIT_EXCEEDED.wireValue,
    )
  }
  return persisted.copy(repairEvidence = repairEvidence)
}

internal fun loadValidatedDecompositionManifestOrNull(
  path: Path,
  fileStore: DecompositionManifestStore,
  validator: DecompositionManifestValidator,
): LoadedDecompositionManifest? =
  try {
    loadValidatedDecompositionManifest(path, fileStore, validator)
  } catch (_: NoSuchFileException) {
    null
  }

private fun validateDecompositionManifestYaml(
  path: Path,
  fileStore: DecompositionManifestStore,
  validator: DecompositionManifestValidator,
  recoverPending: Boolean = true,
): ValidatedDecompositionManifestYaml {
  val yamlText = if (recoverPending) fileStore.readText(path) else fileStore.readTextWithoutRecovery(path)
  return when (val result = validator.validateYamlTextResult(yamlText, path.toString())) {
    is DecompositionManifestValidationResult.AcceptedUnchanged ->
      ValidatedDecompositionManifestYaml(
        manifest = result.manifest,
        yamlText = result.yamlText,
        repairEvidence = null,
      )
    is DecompositionManifestValidationResult.AcceptedAfterRepair ->
      ValidatedDecompositionManifestYaml(
        manifest = result.manifest,
        yamlText = result.yamlText,
        repairEvidence = result.evidence,
      )
    is DecompositionManifestValidationResult.Rejected -> {
      result.requireAccepted(path.toString())
      error("Unreachable rejected decomposition manifest result.")
    }
  }
}

fun encodeValidatedDecompositionManifestYaml(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
  fileStore: DecompositionManifestStore,
  sourceLabel: String = "<in-memory>",
): ValidatedDecompositionManifestYaml {
  val wireMap = validator.encodeManifestWireMap(manifest, sourceLabel)
  val yamlText = fileStore.encodeManifestYaml(wireMap)
  return when (val result = validator.validateYamlTextResult(yamlText, sourceLabel)) {
    is DecompositionManifestValidationResult.AcceptedUnchanged ->
      ValidatedDecompositionManifestYaml(
        manifest = result.manifest,
        yamlText = result.yamlText,
        repairEvidence = null,
      )
    is DecompositionManifestValidationResult.AcceptedAfterRepair ->
      ValidatedDecompositionManifestYaml(
        manifest = result.manifest,
        yamlText = result.yamlText,
        repairEvidence = result.evidence,
      )
    is DecompositionManifestValidationResult.Rejected -> {
      result.requireAccepted(sourceLabel)
      error("Unreachable rejected decomposition manifest result.")
    }
  }
}

fun writeDecompositionManifestText(
  target: Path,
  content: String,
  fileStore: DecompositionManifestStore,
) {
  fileStore.writeTextAtomically(target, content)
}
