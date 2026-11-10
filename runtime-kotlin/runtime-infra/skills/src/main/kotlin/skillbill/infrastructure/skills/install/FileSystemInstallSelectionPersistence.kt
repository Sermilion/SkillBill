package skillbill.infrastructure.skills.install

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException
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
    val payload = request.selection.toInstallSelectionJson()
    val durablePayload = payload + "\n"
    if (durablePayload.toByteArray(StandardCharsets.UTF_8).size > MAX_INSTALL_SELECTION_RECORD_BYTES) {
      throw unreadableInstallSelectionRecordError(selectionPath.toString())
    }
    parseInstallSelectionPayload(selectionPath, payload)
    try {
      selectionPath.parent?.let(Files::createDirectories)
      writeInstallSelectionRecord(selectionPath, durablePayload)
    } catch (error: IOException) {
      throw installSelectionWriteError(selectionPath, error)
    } catch (error: SecurityException) {
      throw installSelectionWriteError(selectionPath, error)
    }
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
  atomicWriteString(path, payload, strictAtomic = true)
}

private enum class InstallSelectionFailureCode : RuntimeFailureCode {
  WRITE_FAILED,
}

private fun installSelectionWriteError(
  path: Path,
  cause: Exception,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    InstallSelectionFailureCode.WRITE_FAILED,
    "Install selection publication at '$path' did not complete cleanly. Inspect and preserve the current selection " +
      "record before retrying. Check directory access and atomic replacement support, then retry the same install. " +
      "Provider changes completed before this failure may remain applied.",
    cause,
  )

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
