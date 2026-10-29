package skillbill.infrastructure.host

import me.tatarka.inject.annotations.Inject
import skillbill.config.model.MachineConfigDocumentParse
import skillbill.config.parseMachineConfigDocument
import skillbill.contracts.JsonCodec
import skillbill.infrastructure.host.system.FileConfigDocumentLocks
import skillbill.infrastructure.host.system.configDocumentIoException
import skillbill.model.EnvironmentContext
import skillbill.ports.config.MachineConfigMigrationPort
import skillbill.ports.config.model.ConfigDocumentReplaceResult
import skillbill.ports.config.model.ConfigFingerprint
import skillbill.ports.telemetry.model.TelemetryConfigLevelWriteResult
import skillbill.ports.telemetry.model.TelemetryConfigRead
import skillbill.ports.telemetry.model.TelemetryConfigSnapshotRead
import skillbill.ports.telemetry.model.TelemetryConfigWriteRequest
import skillbill.ports.telemetry.transport.TelemetryConfigStore
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.INSTALL_ID_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_ENABLED_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_LEVEL_ENVIRONMENT_KEY
import skillbill.telemetry.TelemetryConfigFileKeys
import skillbill.telemetry.defaultLocalTelemetryConfig
import skillbill.telemetry.model.TelemetryConfigDocument
import skillbill.telemetry.model.TelemetryConfigDocumentParse
import skillbill.telemetry.model.TelemetryOpenDocument
import skillbill.telemetry.parseTelemetryBoolValueOrNull
import skillbill.telemetry.parseTelemetryConfigDocument
import skillbill.telemetry.parseTelemetryLevelValueOrNull
import skillbill.telemetry.withTelemetryLevel
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

@Inject
class FileTelemetryConfigStore(
  private val context: EnvironmentContext,
) : TelemetryConfigStore, MachineConfigMigrationPort {
  private val resolvedContext = context.withProcessDefaults()

  override fun stateDir(): Path = resolveTelemetryStateDir(resolvedContext.environment, resolvedContext.userHome)

  override fun configPath(): Path = resolveTelemetryConfigPath(resolvedContext.environment, resolvedContext.userHome)

  override fun configCandidatePaths(): List<Path> =
    FileConfigDocumentLocks.withLocks(configLockPaths()) {
      resolveTelemetryConfigCandidatePaths(resolvedContext.environment, resolvedContext.userHome)
        .map { FileConfigDocumentLocks.lockedPath(it) }
    }

  private fun configLockPaths(): List<Path> =
    resolveTelemetryConfigLockPaths(
      resolvedContext.environment,
      resolvedContext.userHome,
    )

  override fun <T> withConfigTransaction(action: () -> T): T =
    FileConfigDocumentLocks.withLocks(
      resolveTelemetryConfigMigrationLockPaths(resolvedContext.environment, resolvedContext.userHome),
      action,
    )

  override fun migrateLegacyMachineConfig(): Boolean =
    FileConfigDocumentLocks.withLocks(
      resolveTelemetryConfigMigrationLockPaths(resolvedContext.environment, resolvedContext.userHome),
    ) {
      if (resolvedContext.environment[CONFIG_ENVIRONMENT_KEY]?.isNotBlank() == true) return@withLocks false
      val durableCandidate =
        resolvedContext.userHome.resolve(".config").resolve("skill-bill").resolve("config.json")
          .toAbsolutePath().normalize()
      val legacyCandidate =
        resolveTelemetryStateDir(
          resolvedContext.environment,
          resolvedContext.userHome,
        ).resolve("config.json")
      val durable = FileConfigDocumentLocks.lockedPath(durableCandidate)
      val legacy = FileConfigDocumentLocks.lockedPath(legacyCandidate)
      if (durable == legacy || configPathAttributes(
          durable,
        ) != null || configPathAttributes(legacy)?.isRegularFile != true
      ) {
        return@withLocks false
      }
      try {
        Files.createDirectories(durable.parent)
        if (Files.isSymbolicLink(legacyCandidate)) {
          val temporary = Files.createTempFile(durable.parent, ".${durable.fileName}-", ".tmp")
          try {
            Files.copy(legacy, temporary, REPLACE_EXISTING)
            Files.move(temporary, durable, ATOMIC_MOVE)
          } finally {
            Files.deleteIfExists(temporary)
          }
        } else {
          Files.move(legacy, durable, ATOMIC_MOVE)
        }
      } catch (error: IOException) {
        throw configDocumentIoException(durable, "migrate", error)
      } catch (error: SecurityException) {
        throw configDocumentIoException(durable, "migrate", error)
      } catch (error: UnsupportedOperationException) {
        throw configDocumentIoException(durable, "migrate", error)
      }
      true
    }

  override fun newInstallIdCandidate(): String = resolvedContext.environment.telemetryInstallIdCandidate

  override fun read(): TelemetryConfigRead =
    FileConfigDocumentLocks.withLocks(configLockPaths()) { readTelemetryConfigFileRead(configPath()) }

  override fun readSnapshot(): TelemetryConfigSnapshotRead =
    FileConfigDocumentLocks.withLocks(configLockPaths()) {
      readTelemetryConfigSnapshotLocked(configPath())
    }

  override fun ensure(): TelemetryConfigDocument =
    FileConfigDocumentLocks.withLocks(configLockPaths()) {
      ensureTelemetryConfigFile(configPath(), resolvedContext.environment)
    }

  override fun write(request: TelemetryConfigWriteRequest) =
    FileConfigDocumentLocks.withLocks(configLockPaths()) {
      val selectedPath = FileConfigDocumentLocks.lockedPath(configPath())
      if (FileConfigDocumentLocks.canonicalPath(request.path) != selectedPath) {
        return@withLocks ConfigDocumentReplaceResult.ChangedOnDisk(selectedPath)
      }
      val currentFingerprint = readTelemetryConfigBytes(selectedPath)?.let(::telemetryConfigFingerprint)
      if (currentFingerprint != request.fingerprint) {
        return@withLocks ConfigDocumentReplaceResult.ChangedOnDisk(selectedPath)
      }
      writeTelemetryConfigFile(selectedPath, request.document, request.fingerprint)
    }

  override fun mutateTelemetryLevelAtomically(level: String): TelemetryConfigLevelWriteResult =
    FileConfigDocumentLocks.withLocks(configLockPaths()) {
      require(level in skillbill.telemetry.telemetryLevels) {
        "Telemetry level must be one of: ${skillbill.telemetry.telemetryLevels.joinToString(", ")}."
      }
      val path = configPath()
      val bytes = readTelemetryConfigBytes(path)
      val expected = bytes?.let(::telemetryConfigFingerprint)
      val current = readTelemetryConfigFileRead(path)
      val existing =
        when (current) {
          TelemetryConfigRead.Absent -> null
          is TelemetryConfigRead.Malformed -> throw IllegalArgumentException(current.reason)
          is TelemetryConfigRead.Present -> current.document
        }
      val previousLevel =
        existing?.let { document ->
          when (val parsed = parseTelemetryConfigDocument(path.toString(), document)) {
            is TelemetryConfigDocumentParse.Valid -> parsed.settings.level
            is TelemetryConfigDocumentParse.Invalid -> throw IllegalArgumentException(parsed.reason)
          }
        } ?: "off"
      if (existing == null && level == "off") return@withLocks TelemetryConfigLevelWriteResult(false, previousLevel)
      val updated = telemetryDocumentForLevel(existing, level, path, resolvedContext.environment)
      requireTelemetryWrite(path, writeTelemetryConfigFile(path, updated, expected))
      TelemetryConfigLevelWriteResult(true, previousLevel)
    }
}

fun readTelemetryConfigFileRead(path: Path): TelemetryConfigRead {
  return FileConfigDocumentLocks.withLocks(listOf(path)) {
    readTelemetryConfigFileReadLocked(path)
  }
}

private fun readTelemetryConfigFileReadLocked(path: Path): TelemetryConfigRead {
  return when (val read = readTelemetryConfigSnapshotLocked(path)) {
    is TelemetryConfigSnapshotRead.Absent -> TelemetryConfigRead.Absent
    is TelemetryConfigSnapshotRead.Malformed -> TelemetryConfigRead.Malformed(read.reason)
    is TelemetryConfigSnapshotRead.Present -> TelemetryConfigRead.Present(read.document)
  }
}

private fun readTelemetryConfigSnapshotLocked(path: Path): TelemetryConfigSnapshotRead {
  val actualPath = FileConfigDocumentLocks.lockedPath(path)
  val bytes = readTelemetryConfigBytes(actualPath) ?: return TelemetryConfigSnapshotRead.Absent(actualPath)
  val fingerprint = telemetryConfigFingerprint(bytes)
  val text =
    try {
      Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    } catch (_: CharacterCodingException) {
      return TelemetryConfigSnapshotRead.Malformed(
        actualPath,
        fingerprint,
        "Telemetry config at '$actualPath' is not valid UTF-8.",
      )
    }
  return parseTelemetryConfigSnapshotText(actualPath, fingerprint, text)
}

private fun parseTelemetryConfigSnapshotText(
  actualPath: Path,
  fingerprint: ConfigFingerprint,
  text: String,
): TelemetryConfigSnapshotRead {
  val parsedObject =
    JsonCodec.parseObjectOrNull(text)
      ?: return TelemetryConfigSnapshotRead.Malformed(
        actualPath,
        fingerprint,
        "Telemetry config at '$actualPath' is not valid JSON.",
      )
  JsonCodec.duplicateObjectKeyPath(text)?.let { keyPath ->
    return TelemetryConfigSnapshotRead.Malformed(
      actualPath,
      fingerprint,
      "Telemetry config at '$actualPath' repeats the key '$keyPath'. " +
        "Remove the duplicate; saving would keep only one of the values.",
    )
  }
  val payload =
    JsonCodec.anyToStringAnyMap(JsonCodec.telemetryConfigJsonElementToValue(parsedObject))
      ?: return TelemetryConfigSnapshotRead.Malformed(
        actualPath,
        fingerprint,
        "Telemetry config at '$actualPath' must contain a JSON object.",
      )
  return TelemetryConfigSnapshotRead.Present(
    actualPath,
    fingerprint,
    TelemetryConfigDocument(TelemetryOpenDocument.from(payload)),
  )
}

fun readTelemetryConfigFile(path: Path): TelemetryConfigDocument? =
  when (val result = readTelemetryConfigFileRead(path)) {
    TelemetryConfigRead.Absent -> null
    is TelemetryConfigRead.Malformed -> throw IllegalArgumentException(result.reason)
    is TelemetryConfigRead.Present -> result.document
  }

internal fun ensureTelemetryConfigFile(
  path: Path,
  environment: Map<String, String> = System.getenv(),
): TelemetryConfigDocument =
  FileConfigDocumentLocks.withLocks(listOf(path)) {
    val actualPath = FileConfigDocumentLocks.lockedPath(path)
    val expected = readTelemetryConfigBytes(actualPath)?.let(::telemetryConfigFingerprint)
    val existing =
      readTelemetryConfigFileReadLocked(actualPath).let { read ->
        when (read) {
          TelemetryConfigRead.Absent -> null
          is TelemetryConfigRead.Malformed -> throw IllegalArgumentException(read.reason)
          is TelemetryConfigRead.Present -> read.document
        }
      }
    val document = ensureTelemetryConfigDocument(existing, environment, actualPath)
    if (existing != document || expected == null) {
      requireTelemetryWrite(actualPath, writeTelemetryConfigFile(actualPath, document, expected))
    }
    document
  }

private fun ensureTelemetryConfigDocument(
  existing: TelemetryConfigDocument?,
  environment: Map<String, String>,
  path: Path,
): TelemetryConfigDocument {
  if (existing == null) return defaultLocalTelemetryConfig(environment.telemetryInstallIdCandidate)
  val parsed = parseMachineConfigDocument(path.toString(), existing.payload)
  if (parsed is MachineConfigDocumentParse.Invalid) throw IllegalArgumentException(parsed.reason)
  val configuredLevel = (parsed as MachineConfigDocumentParse.Valid).settings.telemetry.level
  val environmentLevel = environmentTelemetryLevel(configuredLevel, environment) ?: return existing
  if (
    existing.payload.containsKey(TelemetryConfigFileKeys.INSTALL_ID) ||
    environmentLevel == "off"
  ) {
    return existing
  }
  val payload = LinkedHashMap<String, Any?>(existing.payload)
  payload[TelemetryConfigFileKeys.INSTALL_ID] = environment.telemetryInstallIdCandidate
  return TelemetryConfigDocument(TelemetryOpenDocument.from(payload))
}

private fun telemetryDocumentForLevel(
  existing: TelemetryConfigDocument?,
  level: String,
  path: Path,
  environment: Map<String, String>,
): TelemetryConfigDocument {
  if (existing == null) {
    val created = defaultLocalTelemetryConfig(environment.telemetryInstallIdCandidate)
    return created.withTelemetryLevel(level, path.toString())
  }
  var updated = existing.withTelemetryLevel(level, path.toString())
  if (level == "off") return updated
  val payload = LinkedHashMap<String, Any?>(updated.payload)
  if (payload.containsKey(TelemetryConfigFileKeys.INSTALL_ID)) {
    val installId = payload[TelemetryConfigFileKeys.INSTALL_ID]?.toString()?.trim().orEmpty()
    require(installId.isNotBlank()) {
      "${TelemetryConfigFileKeys.INSTALL_ID} must be a non-blank value before enabling telemetry at '$path'."
    }
  } else {
    payload[TelemetryConfigFileKeys.INSTALL_ID] = environment.telemetryInstallIdCandidate
    updated = TelemetryConfigDocument(TelemetryOpenDocument.from(payload))
  }
  return updated
}

private fun environmentTelemetryLevel(
  configuredLevel: String,
  environment: Map<String, String>,
): String? {
  environment[TELEMETRY_LEVEL_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let { rawLevel ->
    return parseTelemetryLevelValueOrNull(rawLevel)
  }
  environment[TELEMETRY_ENABLED_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let { rawEnabled ->
    return parseTelemetryBoolValueOrNull(rawEnabled)?.let { if (it) "anonymous" else "off" }
  }
  return configuredLevel
}

fun writeTelemetryConfigFile(
  path: Path,
  document: TelemetryConfigDocument,
  expected: ConfigFingerprint?,
): ConfigDocumentReplaceResult =
  FileConfigDocumentLocks.withLocks(listOf(path)) {
    val target = FileConfigDocumentLocks.lockedPath(path)
    val bytes = (JsonCodec.mapToJsonString(document.payload) + "\n").toByteArray(Charsets.UTF_8)
    val current = readTelemetryConfigBytes(target)
    if (current?.let(::telemetryConfigFingerprint) != expected) {
      return@withLocks ConfigDocumentReplaceResult.ChangedOnDisk(target)
    }
    when (val read = readTelemetryConfigFileRead(target)) {
      TelemetryConfigRead.Absent -> Unit
      is TelemetryConfigRead.Malformed -> throw IllegalArgumentException(read.reason)
      is TelemetryConfigRead.Present ->
        when (val parsed = parseMachineConfigDocument(target.toString(), read.document.payload)) {
          is MachineConfigDocumentParse.Valid -> Unit
          is MachineConfigDocumentParse.Invalid -> throw IllegalArgumentException(parsed.reason)
        }
    }
    when (val parsed = parseMachineConfigDocument(target.toString(), document.payload)) {
      is MachineConfigDocumentParse.Valid -> Unit
      is MachineConfigDocumentParse.Invalid -> throw IllegalArgumentException(parsed.reason)
    }
    val parent =
      target.parent ?: throw configDocumentIoException(
        target,
        "write",
        IOException("Target has no parent directory."),
      )
    try {
      Files.createDirectories(parent)
      val temporary = Files.createTempFile(parent, ".${target.fileName}-", ".tmp")
      try {
        Files.write(temporary, bytes)
        Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
      } finally {
        Files.deleteIfExists(temporary)
      }
    } catch (error: IOException) {
      throw configDocumentIoException(target, "replace", error)
    } catch (error: SecurityException) {
      throw configDocumentIoException(target, "replace", error)
    }
    ConfigDocumentReplaceResult.Written(target, telemetryConfigFingerprint(bytes))
  }

private fun requireTelemetryWrite(
  path: Path,
  result: ConfigDocumentReplaceResult,
) {
  if (result is ConfigDocumentReplaceResult.ChangedOnDisk) {
    throw configDocumentIoException(path, "replace", IOException("Config changed while it was being updated."))
  }
}

private fun readTelemetryConfigBytes(path: Path): ByteArray? =
  try {
    Files.readAllBytes(path)
  } catch (_: NoSuchFileException) {
    null
  } catch (error: IOException) {
    throw configDocumentIoException(path, "read", error)
  } catch (error: SecurityException) {
    throw configDocumentIoException(path, "read", error)
  }

private fun telemetryConfigFingerprint(bytes: ByteArray): ConfigFingerprint =
  ConfigFingerprint(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

private fun configPathAttributes(path: Path): BasicFileAttributes? =
  try {
    Files.readAttributes(path, BasicFileAttributes::class.java)
  } catch (_: NoSuchFileException) {
    null
  } catch (error: IOException) {
    throw configDocumentIoException(path, "inspect", error)
  } catch (error: SecurityException) {
    throw configDocumentIoException(path, "inspect", error)
  }

fun telemetryConfigFingerprintAt(path: Path): ConfigFingerprint? =
  FileConfigDocumentLocks.withLocks(listOf(path)) {
    readTelemetryConfigBytes(FileConfigDocumentLocks.lockedPath(path))?.let(::telemetryConfigFingerprint)
  }

private val Map<String, String>.telemetryInstallIdCandidate: String
  get() = this[INSTALL_ID_ENVIRONMENT_KEY]?.trim()?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
