package skillbill.infrastructure.skills.install

import me.tatarka.inject.annotations.Inject
import skillbill.error.shellcontent.missingInstallSelectionRecordError
import skillbill.error.shellcontent.unreadableInstallSelectionRecordError
import skillbill.infrastructure.host.jvm.atomicWriteString
import skillbill.install.model.SharedInstallSelection
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionRequest
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionResult
import skillbill.ports.install.selection.model.WriteLatestSuccessfulInstallSelectionRequest
import skillbill.ports.install.selection.model.WriteLatestSuccessfulInstallSelectionResult
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemInstallSelectionPersistence : InstallSelectionPersistencePort {
  override fun readLatestSuccessfulSelection(
    request: ReadLatestSuccessfulInstallSelectionRequest,
  ): ReadLatestSuccessfulInstallSelectionResult =
    ReadLatestSuccessfulInstallSelectionResult(readInstallSelectionRecord(selectionPath(request.installHome)))

  override fun writeLatestSuccessfulSelection(
    request: WriteLatestSuccessfulInstallSelectionRequest,
  ): WriteLatestSuccessfulInstallSelectionResult {
    val selectionPath = selectionPath(request.installHome)
    selectionPath.parent?.let(Files::createDirectories)
    val payload = request.selection.toInstallSelectionJson()
    val durablePayload = payload + "\n"
    if (durablePayload.toByteArray(StandardCharsets.UTF_8).size > MAX_INSTALL_SELECTION_RECORD_BYTES) {
      throw unreadableInstallSelectionRecordError(selectionPath.toString())
    }
    parseInstallSelectionPayload(selectionPath, payload)
    writeInstallSelectionRecord(selectionPath, durablePayload)
    return WriteLatestSuccessfulInstallSelectionResult(path = selectionPath)
  }
}

internal fun readInstallSelectionRecord(path: Path): SharedInstallSelection {
  if (!Files.exists(path)) {
    throw missingInstallSelectionRecordError(path.toString())
  }
  val size = installSelectionRecordSize(path)
  if (size > MAX_INSTALL_SELECTION_RECORD_BYTES) {
    throw unreadableInstallSelectionRecordError(path.toString())
  }
  return parseInstallSelectionPayload(path, readInstallSelectionPayload(path))
}

private fun writeInstallSelectionRecord(
  path: Path,
  payload: String,
) {
  atomicWriteString(path, payload)
}

private fun installSelectionRecordSize(path: Path): Long =
  try {
    Files.size(path)
  } catch (error: IOException) {
    throw unreadableInstallSelectionRecordError(path.toString(), error)
  } catch (error: SecurityException) {
    throw unreadableInstallSelectionRecordError(path.toString(), error)
  }

private fun readInstallSelectionPayload(path: Path): String =
  try {
    Files.readString(path)
  } catch (error: IOException) {
    throw unreadableInstallSelectionRecordError(path.toString(), error)
  } catch (error: SecurityException) {
    throw unreadableInstallSelectionRecordError(path.toString(), error)
  }

private fun selectionPath(installHome: Path): Path =
  installHome
    .resolve(".skill-bill")
    .resolve(INSTALL_SELECTION_FILE_NAME)
    .toAbsolutePath()
    .normalize()

private const val INSTALL_SELECTION_FILE_NAME = "install-selection.json"
private const val MAX_INSTALL_SELECTION_RECORD_BYTES = 64 * 1024
