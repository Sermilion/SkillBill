package skillbill.infrastructure.skills.install

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.error.shellcontent.installSelectionMigrationFailed
import skillbill.error.shellcontent.missingInstallSelectionRecordError
import skillbill.error.shellcontent.unreadableInstallSelectionRecordError
import skillbill.infrastructure.host.system.FileConfigDocumentLocks
import skillbill.install.model.InstallationProfile
import skillbill.install.model.SharedInstallSelection
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionRequest
import skillbill.ports.install.selection.model.ReadLatestSuccessfulInstallSelectionResult
import skillbill.ports.install.selection.model.WriteLatestSuccessfulInstallSelectionRequest
import skillbill.ports.install.selection.model.WriteLatestSuccessfulInstallSelectionResult
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

@Inject
class FileSystemInstallSelectionPersistence : InstallSelectionPersistencePort {
  override fun readLatestSuccessfulSelection(
    request: ReadLatestSuccessfulInstallSelectionRequest,
  ): ReadLatestSuccessfulInstallSelectionResult =
    ReadLatestSuccessfulInstallSelectionResult(
      withSelectionLock(selectionPath(request.installHome)) { path ->
        if (Files.exists(path)) readInstallSelectionRecord(path) else null
      },
    )

  override fun writeLatestSuccessfulSelection(
    request: WriteLatestSuccessfulInstallSelectionRequest,
  ): WriteLatestSuccessfulInstallSelectionResult {
    val selectionPath = selectionPath(request.installHome)
    withSelectionLock(selectionPath) { path ->
      val payload = request.selection.toInstallSelectionJson()
      val durablePayload = payload + "\n"
      if (durablePayload.toByteArray(StandardCharsets.UTF_8).size > MAX_INSTALL_SELECTION_RECORD_BYTES) {
        throw unreadableInstallSelectionRecordError(path.toString())
      }
      parseInstallSelectionPayload(path, payload)
      strictAtomicWrite(path, durablePayload.toByteArray(StandardCharsets.UTF_8))
    }
    return WriteLatestSuccessfulInstallSelectionResult(path = selectionPath)
  }
}

internal fun readInstallSelectionRecord(path: Path): SharedInstallSelection {
  requireSelectionFile(path)
  val bytes = readBoundedSelectionBytes(path)
  val payload = decodeSelectionUtf8(path, bytes)
  val rootMap = requireSelectionRootMap(path, payload)
  return when (val version = requireContractVersion(path, rootMap)) {
    INSTALL_SELECTION_CONTRACT_VERSION -> parseInstallSelectionPayload(path, payload)
    INSTALL_SELECTION_PREVIOUS_CONTRACT_VERSION -> migrateLegacyInstallSelection(path, bytes, rootMap)
    else -> throw malformedInstallSelection(path, "Unsupported contract_version '$version'.")
  }
}

private fun requireSelectionFile(path: Path) {
  if (!Files.exists(path)) {
    throw missingInstallSelectionRecordError(path.toString())
  }
}

private fun requireSelectionRootMap(
  path: Path,
  payload: String,
): Map<String, Any?> {
  val root =
    JsonCodec.parseObjectOrNull(payload)
      ?: throw malformedInstallSelection(path, "Root value must be a JSON object.")
  return JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(root))
    ?: throw malformedInstallSelection(path, "Root value must be a JSON object.")
}

private fun requireContractVersion(
  path: Path,
  rootMap: Map<String, Any?>,
): String =
  rootMap[SharedPayloadKeys.CONTRACT_VERSION] as? String
    ?: throw malformedInstallSelection(path, "Missing or invalid contract_version.")

private fun migrateLegacyInstallSelection(
  path: Path,
  originalBytes: ByteArray,
  legacyMap: Map<String, Any?>,
): SharedInstallSelection {
  requireExactKeys(path, legacyMap.keys, INSTALL_SELECTION_LEGACY_KEYS, "root")
  val migrated = legacyMap.toMutableMap()
  migrated[SharedPayloadKeys.CONTRACT_VERSION] = INSTALL_SELECTION_CONTRACT_VERSION
  migrated[INSTALL_SELECTION_PROFILE_KEY] = InstallationProfile.ORDINARY.wireValue
  val converted = JsonCodec.mapToJsonString(migrated)
  val selection = parseInstallSelectionPayload(path, converted)
  val durable = (converted + "\n").toByteArray(StandardCharsets.UTF_8)
  if (durable.size > MAX_INSTALL_SELECTION_RECORD_BYTES) {
    throw unreadableInstallSelectionRecordError(path.toString())
  }
  val recovery = recoveryPath(path)
  publishRecoveryCopy(path, recovery, originalBytes)
  strictAtomicWrite(path, durable)
  return selection
}

private fun publishRecoveryCopy(
  livePath: Path,
  recovery: Path,
  originalBytes: ByteArray,
) {
  if (Files.exists(recovery)) {
    val existing = readBoundedSelectionBytes(recovery)
    if (!existing.contentEquals(originalBytes)) {
      throw installSelectionMigrationFailed(
        livePath = livePath.toString(),
        recoveryPath = recovery.toString(),
        reason = "Recovery copy already exists with different bytes.",
      )
    }
    return
  }
  strictAtomicWrite(recovery, originalBytes)
}

private fun recoveryPath(path: Path): Path =
  path.resolveSibling("${path.fileName}.v$INSTALL_SELECTION_PREVIOUS_CONTRACT_VERSION.recovery")

private fun selectionPath(installHome: Path): Path =
  installHome
    .resolve(".skill-bill")
    .resolve(INSTALL_SELECTION_FILE_NAME)
    .toAbsolutePath()
    .normalize()

private const val INSTALL_SELECTION_FILE_NAME = "install-selection.json"
private const val MAX_INSTALL_SELECTION_RECORD_BYTES = 64 * 1024

private fun <T> withSelectionLock(
  path: Path,
  action: (Path) -> T,
): T =
  FileConfigDocumentLocks.withLocks(listOf(path)) {
    action(FileConfigDocumentLocks.lockedPath(path))
  }

private fun strictAtomicWrite(
  path: Path,
  bytes: ByteArray,
) {
  val parent = requireSelectionParent(path)
  runSelectionIo(path) {
    Files.createDirectories(parent)
    val temp = Files.createTempFile(parent, ".${path.fileName}.", ".tmp")
    try {
      writeSelectionBytes(temp, bytes)
      replaceAtomically(path, temp)
    } finally {
      catchingNonCancellation { Files.deleteIfExists(temp) }
    }
  }
}

private fun requireSelectionParent(path: Path): Path =
  path.parent ?: throw unreadableInstallSelectionRecordError(path.toString())

private fun writeSelectionBytes(
  temp: Path,
  bytes: ByteArray,
) {
  FileChannel.open(temp, WRITE).use { channel ->
    val buffer = ByteBuffer.wrap(bytes)
    while (buffer.hasRemaining()) {
      channel.write(buffer)
    }
    channel.force(true)
  }
}

private fun replaceAtomically(
  path: Path,
  temp: Path,
) {
  try {
    Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
  } catch (error: AtomicMoveNotSupportedException) {
    throw installSelectionMigrationFailed(
      livePath = path.toString(),
      recoveryPath = null,
      reason = "Atomic replace is not supported on this filesystem.",
      cause = error,
    )
  }
}

private fun <T> runSelectionIo(
  path: Path,
  action: () -> T,
): T {
  val result =
    try {
      Result.success(action())
    } catch (error: SkillBillRuntimeException) {
      Result.failure(error)
    } catch (error: IOException) {
      Result.failure(unreadableInstallSelectionRecordError(path.toString(), error))
    } catch (error: SecurityException) {
      Result.failure(unreadableInstallSelectionRecordError(path.toString(), error))
    }
  return result.getOrThrow()
}

private fun readBoundedSelectionBytes(path: Path): ByteArray =
  runSelectionIo(path) {
    Files.newInputStream(path).use { input ->
      val bytes = input.readNBytes(MAX_INSTALL_SELECTION_RECORD_BYTES + 1)
      if (bytes.size > MAX_INSTALL_SELECTION_RECORD_BYTES) {
        throw unreadableInstallSelectionRecordError(path.toString())
      }
      bytes
    }
  }

private fun decodeSelectionUtf8(
  path: Path,
  bytes: ByteArray,
): String =
  try {
    StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes))
      .toString()
  } catch (error: CharacterCodingException) {
    throw unreadableInstallSelectionRecordError(path.toString(), error)
  }
