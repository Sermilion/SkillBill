package dev.skillbill.desktop.core.data.config

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.runtime.readOrProblem
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.domain.model.ConfigDocument
import dev.skillbill.desktop.core.domain.model.ConfigFingerprint
import dev.skillbill.desktop.core.domain.model.ConfigWriteOutcome
import dev.skillbill.desktop.core.domain.model.DetectedAgent
import dev.skillbill.desktop.core.domain.model.MachineConfig
import dev.skillbill.desktop.core.domain.model.MachineConfigChange
import dev.skillbill.desktop.core.domain.model.PhaseInstructionFile
import dev.skillbill.desktop.core.domain.model.ProjectConfig
import dev.skillbill.desktop.core.domain.model.ProjectConfigChange
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.TelemetryLevel
import dev.skillbill.desktop.core.domain.model.TelemetryLevelRead
import dev.skillbill.desktop.core.domain.repository.ConfigRead
import dev.skillbill.desktop.core.domain.repository.RuntimeConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import skillbill.application.config.model.ConfigDocumentRead
import skillbill.di.core.RuntimeComponent
import skillbill.engine.featuretask.phase.prompt.compose.PhaseProjectInstructions
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.nameWithoutExtension

private val NO_SESSION = ReadProblem.Runtime("The database session is not open.")

/** The runtime's config documents, telemetry level and agent detection, through the open session's runtime. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RuntimeConfigAdapter(
  private val sessions: DatabaseSessionManager,
  private val host: HostEnvironment,
  private val diagnostics: Diagnostics,
) : RuntimeConfigRepository {
  override suspend fun machineConfig(): ConfigRead<MachineConfig> =
    call("config_machine_read", read = true, { ConfigRead.Failed(it) }) { runtime ->
      ConfigRead.Document(runtime.runtimeConfigEditService.readMachineConfig().toDomain { it.toDomain() })
    }

  override suspend fun projectConfig(repoRoot: String): ConfigRead<ProjectConfig> =
    call("config_project_read", read = true, { ConfigRead.Failed(it) }) { runtime ->
      ConfigRead.Document(runtime.runtimeConfigEditService.readProjectConfig(Path.of(repoRoot)).toDomain { it.toDomain() })
    }

  override suspend fun writeMachineConfig(changes: List<MachineConfigChange>, expected: ConfigFingerprint?) =
    call("config_machine_write", read = false, ConfigWriteOutcome::Failed) { runtime ->
      runtime.runtimeConfigEditService.writeMachineConfig(changes.map { it.toRuntime() }, expected?.toRuntime()).toDomain()
    }

  override suspend fun writeProjectConfig(repoRoot: String, changes: List<ProjectConfigChange>, expected: ConfigFingerprint?) =
    call("config_project_write", read = false, ConfigWriteOutcome::Failed) { runtime ->
      runtime.runtimeConfigEditService.writeProjectConfig(Path.of(repoRoot), changes.map { it.toRuntime() }, expected?.toRuntime())
        .toDomain()
    }

  // The telemetry service throws IllegalArgumentException on an unreadable config, so the document is read first.
  override suspend fun telemetryLevel(): TelemetryLevelRead =
    call("telemetry_level_read", read = true, TelemetryLevelRead::Failed) { runtime ->
      val machine = runtime.runtimeConfigEditService.readMachineConfig()
      if (machine is ConfigDocumentRead.Unreadable) return@call TelemetryLevelRead.Unreadable(machine.keyPath, machine.reason)
      try {
        val status = runtime.telemetryService.status()
        TelemetryLevel.entries.firstOrNull { it.wireValue == status.telemetryLevel }
          ?.let { TelemetryLevelRead.Known(it, status.configPath) }
          ?: TelemetryLevelRead.Unreadable(null, "Unknown telemetry level ${status.telemetryLevel}.")
      } catch (failure: IllegalArgumentException) {
        diagnostics.record("desktop_telemetry_level_read_unreadable")
        TelemetryLevelRead.Unreadable(null, failure.message ?: "The telemetry settings cannot be read.")
      }
    }

  override suspend fun setTelemetryLevel(level: TelemetryLevel): ConfigWriteOutcome =
    call("telemetry_level_write", read = false, ConfigWriteOutcome::Failed) { runtime ->
      val service = runtime.runtimeConfigEditService
      val before = service.readMachineConfig()
      if (before is ConfigDocumentRead.Unreadable) return@call ConfigWriteOutcome.Unreadable(before.reason)
      try {
        runtime.telemetryLevelMutator.setLevel(level.wireValue)
      } catch (failure: IllegalArgumentException) {
        diagnostics.record("desktop_telemetry_level_write_unreadable")
        return@call ConfigWriteOutcome.Unreadable(failure.message ?: "The telemetry settings cannot be written.")
      }
      // The new fingerprint keeps the config page's next save from seeing its own level change as a change on disk.
      when (val after = service.readMachineConfig().toDomain { it }) {
        is ConfigDocument.Present -> ConfigWriteOutcome.Written(after.fingerprint)
        is ConfigDocument.Unreadable -> ConfigWriteOutcome.Unreadable(after.reason)
        is ConfigDocument.Absent -> ConfigWriteOutcome.Unreadable("The config file is missing after the level was written.")
      }
    }

  override suspend fun detectedAgents(): List<DetectedAgent> =
    call("agents_detect", read = true, {
      diagnostics.record("desktop_agents_detect_unavailable")
      emptyList()
    }) { runtime ->
      runtime.installAgentService.detectAgentTargets(Path.of(host.userHome), host.variables).map { DetectedAgent(it.name, it.path.value) }
    }

  override suspend fun claudeConfigDirectories(): List<String> =
    call("agents_claude_roots", read = true, {
      diagnostics.record("desktop_agents_claude_roots_unavailable")
      emptyList()
    }) { runtime ->
      runtime.installAgentService.claudeRoots(Path.of(host.userHome), host.variables).map { it.toString() }
    }

  // Lists every file; skipping unknown phase ids is the Settings page's rule.
  override suspend fun phaseInstructionFiles(repoRoot: String): List<PhaseInstructionFile> = withContext(Dispatchers.IO) {
    val directory = Path.of(repoRoot).resolve(PhaseProjectInstructions.PHASES_DIR)
    val entries = try {
      Files.newDirectoryStream(directory, "*.md")
    } catch (_: NoSuchFileException) {
      return@withContext emptyList()
    } catch (_: IOException) {
      return@withContext phaseInstructionInspectionFailed()
    } catch (_: SecurityException) {
      return@withContext phaseInstructionInspectionFailed()
    } catch (_: DirectoryIteratorException) {
      return@withContext phaseInstructionInspectionFailed()
    }
    try {
      entries.use { stream ->
        stream.toList().sortedBy { it.fileName.toString() }.mapNotNull { path ->
          val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
          if (!attributes.isRegularFile) return@mapNotNull null
          val size = attributes.size()
          PhaseInstructionFile(
            path.nameWithoutExtension,
            path.toString(),
            size,
            size > PhaseProjectInstructions.MAX_BYTES,
          )
        }
      }
    } catch (_: IOException) {
      phaseInstructionInspectionFailed()
    } catch (_: SecurityException) {
      phaseInstructionInspectionFailed()
    } catch (_: DirectoryIteratorException) {
      phaseInstructionInspectionFailed()
    }
  }

  private fun phaseInstructionInspectionFailed(): List<PhaseInstructionFile> {
    diagnostics.record("desktop_phase_instructions_failed")
    return emptyList()
  }

  private suspend fun <T> call(name: String, read: Boolean, failed: (ReadProblem) -> T, block: (RuntimeComponent) -> T): T {
    val session = sessions.sessions.value ?: return failed(NO_SESSION)
    val result = readOrProblem(session.runtime, diagnostics) {
      if (read) session.reads.read(name, block) else session.reads.write(name, block)
    }
    return when (result) {
      is Outcome.Success -> result.value
      is Outcome.Failure -> failed(result.error)
    }
  }
}
