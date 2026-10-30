package skillbill.infrastructure.host.system

import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

internal fun readableRepoLocalConfigPath(
  diagnostics: RuntimeDiagnostics,
  repoRoot: Path,
  storeConfig: Path,
  copy: (Path, Path) -> Unit = { legacy, store -> copyLegacyConfig(diagnostics, legacy, store) },
): Path? {
  val canonicalStoreConfig = FileConfigDocumentLocks.lockedPath(storeConfig)
  if (pathAttributesOrNull(canonicalStoreConfig, "resolve") != null) return canonicalStoreConfig
  val legacy =
    repoRoot
      .resolve(LegacyProjectStatePaths.RUNTIME_STATE_DIRECTORY)
      .resolve(REPO_LOCAL_CONFIG_FILE_NAME)
      .toAbsolutePath()
      .normalize()
  val canonicalLegacy = FileConfigDocumentLocks.lockedPath(legacy)
  if (pathAttributesOrNull(canonicalLegacy, "resolve")?.isRegularFile != true) return null
  return try {
    copy(canonicalLegacy, canonicalStoreConfig)
    canonicalStoreConfig
  } catch (failure: IOException) {
    diagnostics.warning(
      "seam=repo_local_config value_expected=$canonicalStoreConfig " +
        "value_used=$canonicalLegacy cause=legacy_config_copy_failed",
      failure,
    )
    canonicalLegacy
  } catch (failure: SecurityException) {
    throw configDocumentIoException(canonicalStoreConfig, "copy", failure)
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
    if (pathAttributesOrNull(storeConfig, "resolve") != null) return
    Files.move(temporary, storeConfig, StandardCopyOption.ATOMIC_MOVE)
    diagnostics.warning(
      "seam=repo_local_config value_expected=$storeConfig value_used=$legacy cause=legacy_config_copied",
    )
  } catch (concurrentCopy: FileAlreadyExistsException) {
    if (pathAttributesOrNull(storeConfig, "resolve") == null) throw concurrentCopy
  } finally {
    Files.deleteIfExists(temporary)
  }
}

private fun pathAttributesOrNull(
  path: Path,
  operation: String,
): BasicFileAttributes? =
  try {
    Files.readAttributes(path, BasicFileAttributes::class.java)
  } catch (_: NoSuchFileException) {
    null
  } catch (error: IOException) {
    throw configDocumentIoException(path, operation, error)
  } catch (error: SecurityException) {
    throw configDocumentIoException(path, operation, error)
  }
