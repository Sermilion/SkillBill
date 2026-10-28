package skillbill.application.config

import me.tatarka.inject.annotations.Inject
import skillbill.application.config.model.ConfigDocumentRead
import skillbill.application.config.model.ConfigWriteResult
import skillbill.application.config.model.MachineConfigEdit
import skillbill.application.config.model.MachineConfigValues
import skillbill.application.config.model.ProjectConfigEdit
import skillbill.application.config.model.ProjectConfigValues
import skillbill.ports.config.ConfigDocumentFiles
import skillbill.ports.config.model.ConfigDocumentFileRead
import skillbill.ports.config.model.ConfigDocumentFormat
import skillbill.ports.config.model.ConfigDocumentReplaceRequest
import skillbill.ports.config.model.ConfigDocumentReplaceResult
import skillbill.ports.config.model.ConfigDocumentTree
import skillbill.ports.config.model.ConfigFingerprint
import skillbill.ports.telemetry.transport.TelemetryConfigStore
import java.nio.file.Path

@Inject
class RuntimeConfigEditService(
  private val documents: ConfigDocumentFiles,
  private val machineConfigStore: TelemetryConfigStore,
) {
  fun readMachineConfig(): ConfigDocumentRead<MachineConfigValues> {
    val path = machineConfigStore.configPath()
    return when (val read = documents.read(path, ConfigDocumentFormat.JSON)) {
      is ConfigDocumentFileRead.Absent -> ConfigDocumentRead.Absent(read.path)
      is ConfigDocumentFileRead.Malformed -> ConfigDocumentRead.Unreadable(read.path, null, read.reason)
      is ConfigDocumentFileRead.Present ->
        parseMachineConfigValues(read.path, read.fingerprint, read.tree, read.hasComments)
    }
  }

  fun readProjectConfig(repoRoot: Path): ConfigDocumentRead<ProjectConfigValues> {
    val location = documents.projectConfigLocation(repoRoot)
    val readablePath = location.readable ?: return ConfigDocumentRead.Absent(location.target)
    return when (val read = documents.read(readablePath, ConfigDocumentFormat.YAML)) {
      is ConfigDocumentFileRead.Absent -> ConfigDocumentRead.Absent(read.path)
      is ConfigDocumentFileRead.Malformed -> ConfigDocumentRead.Unreadable(read.path, null, read.reason)
      is ConfigDocumentFileRead.Present ->
        parseProjectConfigValues(read.path, read.fingerprint, read.tree, read.hasComments)
    }
  }

  fun writeMachineConfig(
    edits: List<MachineConfigEdit>,
    expected: ConfigFingerprint?,
  ): ConfigWriteResult {
    val path = machineConfigStore.configPath()
    val current = documents.read(path, ConfigDocumentFormat.JSON)
    val tree: ConfigDocumentTree
    val fingerprint: ConfigFingerprint?
    when (current) {
      is ConfigDocumentFileRead.Absent -> {
        tree = ConfigDocumentTree(emptyMap())
        fingerprint = null
      }
      is ConfigDocumentFileRead.Malformed -> return ConfigWriteResult.Unreadable(path, current.reason)
      is ConfigDocumentFileRead.Present -> {
        machineReadError(current)?.let { return ConfigWriteResult.Unreadable(path, it) }
        tree = current.tree
        fingerprint = current.fingerprint
      }
    }
    if (fingerprint != expected) return ConfigWriteResult.ChangedOnDisk(path)
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

  fun writeProjectConfig(
    repoRoot: Path,
    edits: List<ProjectConfigEdit>,
    expected: ConfigFingerprint?,
  ): ConfigWriteResult {
    val location = documents.projectConfigLocation(repoRoot)
    val baseline = location.readable ?: location.target
    val current = documents.read(baseline, ConfigDocumentFormat.YAML)
    val tree: ConfigDocumentTree
    val fingerprint: ConfigFingerprint?
    when (current) {
      is ConfigDocumentFileRead.Absent -> {
        tree = ConfigDocumentTree(emptyMap())
        fingerprint = null
      }
      is ConfigDocumentFileRead.Malformed -> return ConfigWriteResult.Unreadable(baseline, current.reason)
      is ConfigDocumentFileRead.Present -> {
        projectReadError(current)?.let { return ConfigWriteResult.Unreadable(baseline, it) }
        tree = current.tree
        fingerprint = current.fingerprint
      }
    }
    if (fingerprint != expected) return ConfigWriteResult.ChangedOnDisk(location.target)
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

private fun machineReadError(read: ConfigDocumentFileRead.Present): String? =
  (
    parseMachineConfigValues(
      read.path,
      read.fingerprint,
      read.tree,
      read.hasComments,
    ) as? ConfigDocumentRead.Unreadable
  )?.reason

private fun projectReadError(read: ConfigDocumentFileRead.Present): String? =
  (
    parseProjectConfigValues(
      read.path,
      read.fingerprint,
      read.tree,
      read.hasComments,
    ) as? ConfigDocumentRead.Unreadable
  )?.reason
