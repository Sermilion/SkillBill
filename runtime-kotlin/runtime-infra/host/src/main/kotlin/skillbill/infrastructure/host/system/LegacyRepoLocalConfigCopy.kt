package skillbill.infrastructure.host.system

import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal fun readableRepoLocalConfigPath(
  diagnostics: RuntimeDiagnostics,
  repoRoot: Path,
  storeConfig: Path,
): Path? {
  if (Files.exists(storeConfig)) return storeConfig
  val legacy =
    repoRoot
      .resolve(LegacyProjectStatePaths.RUNTIME_STATE_DIRECTORY)
      .resolve(REPO_LOCAL_CONFIG_FILE_NAME)
      .toAbsolutePath()
      .normalize()
  if (!Files.isRegularFile(legacy)) return null
  return try {
    copyLegacyConfig(diagnostics, legacy, storeConfig)
    storeConfig
  } catch (failure: IOException) {
    diagnostics.warning(
      "seam=repo_local_config value_expected=$storeConfig value_used=$legacy cause=legacy_config_copy_failed",
      failure,
    )
    legacy
  }
}

private fun copyLegacyConfig(
  diagnostics: RuntimeDiagnostics,
  legacy: Path,
  storeConfig: Path,
) {
  val directory = storeConfig.parent
  Files.createDirectories(directory)
  val temporary = Files.createTempFile(directory, "config-", ".tmp")
  try {
    Files.copy(legacy, temporary, StandardCopyOption.REPLACE_EXISTING)
    if (Files.exists(storeConfig)) return
    Files.move(temporary, storeConfig, StandardCopyOption.ATOMIC_MOVE)
    diagnostics.warning(
      "seam=repo_local_config value_expected=$storeConfig value_used=$legacy cause=legacy_config_copied",
    )
  } catch (concurrentCopy: FileAlreadyExistsException) {
    if (!Files.exists(storeConfig)) throw concurrentCopy
  } finally {
    Files.deleteIfExists(temporary)
  }
}
