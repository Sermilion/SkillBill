package skillbill.application.config
import me.tatarka.inject.annotations.Inject
import skillbill.application.config.model.ConfigDocumentRead
import skillbill.application.config.model.ConfigDocumentSnapshotRead
import skillbill.application.config.model.ConfigWriteResult
import skillbill.application.config.model.MachineConfigEdit
import skillbill.application.config.model.MachineConfigValues
import skillbill.application.config.model.ProjectConfigEdit
import skillbill.application.config.model.ProjectConfigValues
import skillbill.ports.config.ConfigDocumentFiles
import skillbill.ports.config.MachineConfigMigrationPort
import skillbill.ports.config.model.ConfigDocumentFileRead
import skillbill.ports.config.model.ConfigDocumentFormat
import skillbill.ports.config.model.ConfigDocumentReplaceRequest
import skillbill.ports.config.model.ConfigDocumentReplaceResult
import skillbill.ports.config.model.ConfigDocumentTree
import skillbill.ports.config.model.ConfigFingerprint
import skillbill.ports.config.model.ProjectConfigLocation
import skillbill.ports.telemetry.transport.TelemetryConfigStore
import skillbill.telemetry.TelemetryConfigFileKeys
import skillbill.telemetry.telemetryLevels
import java.nio.file.Path

@Inject
class RuntimeConfigEditService(
  private val documents: ConfigDocumentFiles,
  private val machineConfigStore: TelemetryConfigStore,
  private val machineConfigMigration: MachineConfigMigrationPort,
) {
  fun migrateLegacyMachineConfig(): Boolean = machineConfigMigration.migrateLegacyMachineConfig()

  fun stateDir(): Path = machineConfigStore.stateDir()

  fun readMachineConfig(): ConfigDocumentRead<MachineConfigValues> = readMachineConfigSnapshot().document

  fun readMachineConfigSnapshot(): ConfigDocumentSnapshotRead<MachineConfigValues> =
    machineConfigStore.withConfigTransaction {
      val candidates = machineConfigStore.configCandidatePaths()
      ConfigDocumentSnapshotRead(readMachineConfigLocked(), candidates)
    }

  private fun readMachineConfigLocked(): ConfigDocumentRead<MachineConfigValues> {
    val path = machineConfigStore.configPath()
    return when (val read = documents.read(path, ConfigDocumentFormat.JSON)) {
      is ConfigDocumentFileRead.Absent -> ConfigDocumentRead.Absent(read.path)
      is ConfigDocumentFileRead.Malformed -> ConfigDocumentRead.Unreadable(read.path, read.keyPath, read.reason)
      is ConfigDocumentFileRead.Present ->
        parseMachineConfigValues(read.path, read.fingerprint, read.tree, read.hasComments)
    }
  }

  fun readProjectConfig(repoRoot: Path): ConfigDocumentRead<ProjectConfigValues> =
    readProjectConfigSnapshot(
      repoRoot,
    ).document

  fun readProjectConfigSnapshot(repoRoot: Path): ConfigDocumentSnapshotRead<ProjectConfigValues> =
    documents.withLocks(documents.projectConfigLockPaths(repoRoot)) {
      val location = documents.projectConfigLocation(repoRoot)
      ConfigDocumentSnapshotRead(
        readProjectConfigLocked(location),
        location.candidatePaths,
        location.target,
      )
    }

  private fun readProjectConfigLocked(location: ProjectConfigLocation): ConfigDocumentRead<ProjectConfigValues> {
    val readablePath = location.readable ?: return ConfigDocumentRead.Absent(location.target)
    return when (val read = documents.read(readablePath, ConfigDocumentFormat.YAML)) {
      is ConfigDocumentFileRead.Absent -> ConfigDocumentRead.Absent(read.path)
      is ConfigDocumentFileRead.Malformed -> ConfigDocumentRead.Unreadable(read.path, read.keyPath, read.reason)
      is ConfigDocumentFileRead.Present ->
        parseProjectConfigValues(read.path, read.fingerprint, read.tree, read.hasComments)
    }
  }

  fun writeMachineConfig(
    edits: List<MachineConfigEdit>,
    expected: ConfigFingerprint?,
  ): ConfigWriteResult =
    machineConfigStore.withConfigTransaction {
      writeMachineConfigLocked(machineConfigStore.configPath(), edits, expected, checkUnreadableFirst = true)
    }

  fun writeMachineConfigFromSnapshot(
    observedPath: Path,
    edits: List<MachineConfigEdit>,
    expected: ConfigFingerprint?,
  ): ConfigWriteResult =
    machineConfigStore.withConfigTransaction {
      val selectedPath = machineConfigStore.configPath()
      if (!sameConfigPath(selectedPath, observedPath)) {
        ConfigWriteResult.ChangedOnDisk(selectedPath)
      } else {
        writeMachineConfigLocked(selectedPath, edits, expected)
      }
    }

  private fun writeMachineConfigLocked(
    path: Path,
    edits: List<MachineConfigEdit>,
    expected: ConfigFingerprint?,
    checkUnreadableFirst: Boolean = false,
  ): ConfigWriteResult {
    val current = documents.read(path, ConfigDocumentFormat.JSON)
    if (checkUnreadableFirst) machineConfigUnreadable(current)?.let { return it }
    machineConfigReadFailure(current, path, expected)?.let { return it }
    val tree = if (current is ConfigDocumentFileRead.Present) current.tree else ConfigDocumentTree(emptyMap())
    val applied = applyMachineConfigEdits(tree, edits)
    return if (applied is MachineConfigTreeResult.Invalid) {
      ConfigWriteResult.Invalid(applied.keyPath, applied.reason)
    } else {
      when (
        val replaced =
          documents.replace(
            ConfigDocumentReplaceRequest(
              path,
              path,
              expected,
              ConfigDocumentFormat.JSON,
              (applied as MachineConfigTreeResult.Valid).tree,
            ),
          )
      ) {
        is ConfigDocumentReplaceResult.Written -> ConfigWriteResult.Written(replaced.path, replaced.fingerprint)
        is ConfigDocumentReplaceResult.ChangedOnDisk -> ConfigWriteResult.ChangedOnDisk(replaced.path)
      }
    }
  }

  private fun machineConfigReadFailure(
    current: ConfigDocumentFileRead,
    path: Path,
    expected: ConfigFingerprint?,
  ): ConfigWriteResult? {
    val fingerprint =
      when (current) {
        is ConfigDocumentFileRead.Absent -> null
        is ConfigDocumentFileRead.Malformed -> current.fingerprint
        is ConfigDocumentFileRead.Present -> current.fingerprint
      }
    return if (fingerprint != expected) ConfigWriteResult.ChangedOnDisk(path) else machineConfigUnreadable(current)
  }

  private fun machineConfigUnreadable(current: ConfigDocumentFileRead): ConfigWriteResult.Unreadable? =
    when (current) {
      is ConfigDocumentFileRead.Malformed -> ConfigWriteResult.Unreadable(current.path, current.reason, current.keyPath)
      is ConfigDocumentFileRead.Present ->
        machineReadError(current)?.let {
          ConfigWriteResult.Unreadable(it.path, it.reason, it.keyPath)
        }
      is ConfigDocumentFileRead.Absent -> null
    }

  fun writeTelemetryLevel(
    level: String,
    expected: ConfigFingerprint?,
  ): ConfigWriteResult =
    machineConfigStore.withConfigTransaction {
      writeTelemetryLevelLocked(machineConfigStore.configPath(), level, expected)
    }

  fun writeTelemetryLevelFromSnapshot(
    observedPath: Path,
    level: String,
    expected: ConfigFingerprint?,
  ): ConfigWriteResult =
    machineConfigStore.withConfigTransaction {
      val selectedPath = machineConfigStore.configPath()
      if (!sameConfigPath(selectedPath, observedPath)) {
        ConfigWriteResult.ChangedOnDisk(selectedPath)
      } else {
        writeTelemetryLevelLocked(selectedPath, level, expected)
      }
    }

  private fun writeTelemetryLevelLocked(
    path: Path,
    level: String,
    expected: ConfigFingerprint?,
  ): ConfigWriteResult {
    val current = documents.read(path, ConfigDocumentFormat.JSON)
    telemetryReadError(current, expected)?.let { return it }
    if (level !in telemetryLevels) {
      return ConfigWriteResult.Invalid(
        "telemetry.level",
        "Telemetry level must be one of: ${telemetryLevels.joinToString(", ")}.",
      )
    }
    val tree = (current as? ConfigDocumentFileRead.Present)?.tree ?: ConfigDocumentTree(emptyMap())
    var replacementTree = tree
    val telemetryKey = TelemetryConfigFileKeys.TELEMETRY
    val telemetryRaw = tree.value(telemetryKey)
    val telemetry =
      (telemetryRaw as? Map<*, *>)
        ?.entries
        ?.filter { it.key is String }
        ?.associate { it.key as String to it.value }
        ?.toMutableMap() ?: mutableMapOf()
    if (level != telemetryLevels.first()) {
      if (tree.contains(TelemetryConfigFileKeys.INSTALL_ID)) {
        val installId = tree.value(TelemetryConfigFileKeys.INSTALL_ID)?.toString()?.trim().orEmpty()
        if (installId.isBlank()) {
          return ConfigWriteResult.Invalid(
            TelemetryConfigFileKeys.INSTALL_ID,
            "must be a non-blank value before enabling telemetry.",
          )
        }
      } else {
        replacementTree =
          replacementTree.with(
            TelemetryConfigFileKeys.INSTALL_ID,
            machineConfigStore.newInstallIdCandidate(),
          )
      }
    }
    telemetry[TelemetryConfigFileKeys.LEVEL] = level
    return when (
      val replaced =
        documents.replace(
          ConfigDocumentReplaceRequest(
            path,
            path,
            expected,
            ConfigDocumentFormat.JSON,
            replacementTree.with(telemetryKey, telemetry),
          ),
        )
    ) {
      is ConfigDocumentReplaceResult.Written -> ConfigWriteResult.Written(replaced.path, replaced.fingerprint)
      is ConfigDocumentReplaceResult.ChangedOnDisk -> ConfigWriteResult.ChangedOnDisk(replaced.path)
    }
  }

  fun writeProjectConfig(
    repoRoot: Path,
    edits: List<ProjectConfigEdit>,
    expected: ConfigFingerprint?,
  ): ConfigWriteResult =
    documents.withLocks(documents.projectConfigLockPaths(repoRoot)) {
      writeProjectConfigLocked(edits, expected, documents.projectConfigLocation(repoRoot))
    }

  fun writeProjectConfigFromSnapshot(
    repoRoot: Path,
    observedPath: Path,
    edits: List<ProjectConfigEdit>,
    expected: ConfigFingerprint?,
  ): ConfigWriteResult =
    documents.withLocks(documents.projectConfigLockPaths(repoRoot)) {
      val location = documents.projectConfigLocation(repoRoot)
      val selectedPath = location.readable ?: location.target
      if (!sameConfigPath(selectedPath, observedPath)) {
        ConfigWriteResult.ChangedOnDisk(location.target)
      } else {
        writeProjectConfigLocked(edits, expected, location)
      }
    }

  private fun writeProjectConfigLocked(
    edits: List<ProjectConfigEdit>,
    expected: ConfigFingerprint?,
    location: ProjectConfigLocation,
  ): ConfigWriteResult {
    val baseline = location.readable ?: location.target
    val current = documents.read(baseline, ConfigDocumentFormat.YAML)
    val fingerprint =
      when (current) {
        is ConfigDocumentFileRead.Absent -> null
        is ConfigDocumentFileRead.Malformed -> current.fingerprint
        is ConfigDocumentFileRead.Present -> current.fingerprint
      }
    if (fingerprint != expected) return ConfigWriteResult.ChangedOnDisk(location.target)
    val tree =
      when (current) {
        is ConfigDocumentFileRead.Absent -> ConfigDocumentTree(emptyMap())
        is ConfigDocumentFileRead.Malformed -> return ConfigWriteResult.Unreadable(
          current.path,
          current.reason,
          current.keyPath,
        )
        is ConfigDocumentFileRead.Present -> {
          projectReadError(current)?.let { return ConfigWriteResult.Unreadable(it.path, it.reason, it.keyPath) }
          current.tree
        }
      }
    val applied = applyProjectConfigEdits(tree, edits)
    return if (applied is ProjectConfigTreeResult.Invalid) {
      ConfigWriteResult.Invalid(applied.keyPath, applied.reason)
    } else {
      when (
        val replaced =
          documents.replace(
            ConfigDocumentReplaceRequest(
              location.target,
              baseline,
              expected,
              ConfigDocumentFormat.YAML,
              (applied as ProjectConfigTreeResult.Valid).tree,
            ),
          )
      ) {
        is ConfigDocumentReplaceResult.Written -> ConfigWriteResult.Written(replaced.path, replaced.fingerprint)
        is ConfigDocumentReplaceResult.ChangedOnDisk -> ConfigWriteResult.ChangedOnDisk(replaced.path)
      }
    }
  }
}

private fun sameConfigPath(
  first: Path,
  second: Path,
): Boolean = first.toAbsolutePath().normalize() == second.toAbsolutePath().normalize()

private fun telemetryReadError(
  read: ConfigDocumentFileRead,
  expected: ConfigFingerprint?,
): ConfigWriteResult? {
  return when (read) {
    is ConfigDocumentFileRead.Absent ->
      if (expected != null) ConfigWriteResult.ChangedOnDisk(read.path) else null
    is ConfigDocumentFileRead.Malformed ->
      if (read.fingerprint != expected) {
        ConfigWriteResult.ChangedOnDisk(read.path)
      } else {
        ConfigWriteResult.Unreadable(read.path, read.reason, read.keyPath)
      }
    is ConfigDocumentFileRead.Present -> {
      when {
        read.fingerprint != expected -> ConfigWriteResult.ChangedOnDisk(read.path)
        else -> machineReadError(read)?.let { ConfigWriteResult.Unreadable(it.path, it.reason, it.keyPath) }
      }
    }
  }
}

private fun machineReadError(read: ConfigDocumentFileRead.Present): ConfigDocumentRead.Unreadable? =
  (
    parseMachineConfigValues(
      read.path,
      read.fingerprint,
      read.tree,
      read.hasComments,
    ) as? ConfigDocumentRead.Unreadable
  )

private fun projectReadError(read: ConfigDocumentFileRead.Present): ConfigDocumentRead.Unreadable? =
  (
    parseProjectConfigValues(
      read.path,
      read.fingerprint,
      read.tree,
      read.hasComments,
    ) as? ConfigDocumentRead.Unreadable
  )
