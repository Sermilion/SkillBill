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
import dev.skillbill.desktop.core.domain.model.DetectedAgentsRead
import dev.skillbill.desktop.core.domain.model.MachineConfig
import dev.skillbill.desktop.core.domain.model.MachineConfigChange
import dev.skillbill.desktop.core.domain.model.ModelMatrixView
import dev.skillbill.desktop.core.domain.model.PhaseInstructionFile
import dev.skillbill.desktop.core.domain.model.PhaseInstructionFilesRead
import dev.skillbill.desktop.core.domain.model.ProjectConfig
import dev.skillbill.desktop.core.domain.model.ProjectConfigChange
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.TelemetryEffectiveLevel
import dev.skillbill.desktop.core.domain.model.TelemetryInstallIdSource
import dev.skillbill.desktop.core.domain.model.TelemetryLevel
import dev.skillbill.desktop.core.domain.model.TelemetryLevelRead
import dev.skillbill.desktop.core.domain.model.TelemetryLevelSnapshot
import dev.skillbill.desktop.core.domain.model.TelemetryOperationalAvailability
import dev.skillbill.desktop.core.domain.model.TelemetryPreviousLevel
import dev.skillbill.desktop.core.domain.repository.ClaudeConfigDirectoriesRead
import dev.skillbill.desktop.core.domain.repository.ConfigRead
import dev.skillbill.desktop.core.domain.repository.ModelMatrixRead
import dev.skillbill.desktop.core.domain.repository.RuntimeConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import skillbill.application.config.model.ConfigDocumentRead
import skillbill.application.telemetry.config.telemetrySettingsSnapshotFromConfig
import skillbill.application.telemetry.model.TelemetrySettingsSnapshot
import skillbill.application.telemetry.model.TelemetrySnapshotAvailability
import skillbill.application.telemetry.model.TelemetrySnapshotEffectiveLevel
import skillbill.application.telemetry.model.TelemetrySnapshotEnvironment
import skillbill.application.telemetry.model.TelemetrySnapshotInstallIdSource
import skillbill.application.telemetry.model.TelemetrySnapshotPriorLevel
import skillbill.di.core.RuntimeComponent
import skillbill.engine.featuretask.phase.prompt.compose.PhaseProjectInstructions
import skillbill.telemetry.INSTALL_ID_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_ENABLED_ENVIRONMENT_KEY
import skillbill.telemetry.TELEMETRY_LEVEL_ENVIRONMENT_KEY
import skillbill.telemetry.parsePositiveTelemetryIntOrNull
import skillbill.telemetry.parseTelemetryBoolValueOrNull
import skillbill.telemetry.parseTelemetryLevelValueOrNull
import skillbill.telemetry.positiveTelemetryIntError
import skillbill.telemetry.telemetryBoolValueError
import skillbill.telemetry.telemetryLevelValueError
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

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
  private val defaults by lazy { runtimeConfigDefaults(Path.of(host.userHome), diagnostics) }

  override fun configDefaults() = defaults

  override suspend fun modelMatrix(): ModelMatrixRead =
    call("config_matrix_read", read = true, { ModelMatrixRead.Failed(it) }) { runtime ->
      val document = runtime.runtimeConfigEditService.readMachineConfigSnapshot()
      val mapped = document.toDomain { it.toDomain() }
      val agents = when (val read = document.document) {
        is ConfigDocumentRead.Present -> resolveModelMatrix(read.values.executionMatrix, defaults)
        is ConfigDocumentRead.Absent -> resolveModelMatrix(null, defaults)
        is ConfigDocumentRead.Unreadable -> emptyMap()
      }
      ModelMatrixRead.Success(ModelMatrixView(mapped, agents))
    }

  override suspend fun machineConfig(): ConfigRead<MachineConfig> =
    call("config_machine_read", read = true, { ConfigRead.Failed(it) }) { runtime ->
      ConfigRead.Document(runtime.runtimeConfigEditService.readMachineConfigSnapshot().toDomain { it.toDomain() })
    }

  override suspend fun projectConfig(repoRoot: String): ConfigRead<ProjectConfig> =
    call("config_project_read", read = true, { ConfigRead.Failed(it) }) { runtime ->
      ConfigRead.Document(runtime.runtimeConfigEditService.readProjectConfigSnapshot(Path.of(repoRoot)).toDomain { it.toDomain() })
    }

  override suspend fun writeMachineConfig(changes: List<MachineConfigChange>, expected: ConfigFingerprint?) =
    call("config_machine_write", read = false, ConfigWriteOutcome::Failed) { runtime ->
      runtime.runtimeConfigEditService.writeMachineConfig(changes.map { it.toRuntime() }, expected?.toRuntime()).toDomain()
    }

  override suspend fun writeMachineConfigFromSnapshot(
    path: String,
    changes: List<MachineConfigChange>,
    expected: ConfigFingerprint?,
  ) = call("config_machine_write", read = false, ConfigWriteOutcome::Failed) { runtime ->
    runtime.runtimeConfigEditService
      .writeMachineConfigFromSnapshot(Path.of(path), changes.map { it.toRuntime() }, expected?.toRuntime())
      .toDomain()
  }

  override suspend fun writeProjectConfig(repoRoot: String, changes: List<ProjectConfigChange>, expected: ConfigFingerprint?) =
    call("config_project_write", read = false, ConfigWriteOutcome::Failed) { runtime ->
      runtime.runtimeConfigEditService.writeProjectConfig(Path.of(repoRoot), changes.map { it.toRuntime() }, expected?.toRuntime())
        .toDomain()
    }

  override suspend fun writeProjectConfigFromSnapshot(
    repoRoot: String,
    path: String,
    changes: List<ProjectConfigChange>,
    expected: ConfigFingerprint?,
  ) = call("config_project_write", read = false, ConfigWriteOutcome::Failed) { runtime ->
    runtime.runtimeConfigEditService
      .writeProjectConfigFromSnapshot(
        Path.of(repoRoot),
        Path.of(path),
        changes.map { it.toRuntime() },
        expected?.toRuntime(),
      ).toDomain()
  }

  override suspend fun telemetryLevel(): TelemetryLevelRead =
    call("telemetry_level_read", read = true, TelemetryLevelRead::Failed) { runtime ->
      telemetryLevelFromSnapshot(
        runtime.runtimeConfigEditService.readMachineConfigSnapshot().toDomain { it.toDomain() },
        host.variables,
      )
    }

  override suspend fun telemetryLevel(document: ConfigDocument<MachineConfig>): TelemetryLevelRead =
    telemetrySettingsFromSnapshot(document, host.variables)

  override suspend fun setTelemetryLevel(
    level: TelemetryLevel,
    expected: ConfigFingerprint?,
  ): ConfigWriteOutcome =
    call("telemetry_level_write", read = false, ConfigWriteOutcome::Failed) { runtime ->
      try {
        runtime.telemetryService
          .setLevel(level.wireValue, expected?.toRuntime(), runtime.runtimeConfigEditService)
          .toDomain()
      } catch (failure: IllegalArgumentException) {
        diagnostics.record("desktop_telemetry_level_write_unreadable")
        return@call ConfigWriteOutcome.Failed(ReadProblem.Runtime(failure.message ?: "The telemetry settings cannot be written."))
      }
    }

  override suspend fun setTelemetryLevelFromSnapshot(
    level: TelemetryLevel,
    path: String,
    expected: ConfigFingerprint?,
    previousLevel: String,
  ): ConfigWriteOutcome = setTelemetryLevelFromSnapshot(
    level,
    path,
    expected,
    TelemetrySnapshotPriorLevel.Known(previousLevel),
  )

  override suspend fun setTelemetryLevelFromSnapshot(
    level: TelemetryLevel,
    path: String,
    expected: ConfigFingerprint?,
    previousLevel: TelemetryPreviousLevel,
  ): ConfigWriteOutcome = setTelemetryLevelFromSnapshot(
    level,
    path,
    expected,
    when (previousLevel) {
      is TelemetryPreviousLevel.Known -> TelemetrySnapshotPriorLevel.Known(previousLevel.level.wireValue)
      TelemetryPreviousLevel.Unavailable -> TelemetrySnapshotPriorLevel.Unavailable
    },
  )

  private suspend fun setTelemetryLevelFromSnapshot(
    level: TelemetryLevel,
    path: String,
    expected: ConfigFingerprint?,
    previousLevel: TelemetrySnapshotPriorLevel,
  ): ConfigWriteOutcome =
    call("telemetry_level_write", read = false, ConfigWriteOutcome::Failed) { runtime ->
      try {
        runtime.telemetryService
          .setLevelFromSnapshot(
            level.wireValue,
            Path.of(path),
            expected?.toRuntime(),
            previousLevel,
            runtime.runtimeConfigEditService,
          ).toDomain()
      } catch (failure: IllegalArgumentException) {
        diagnostics.record("desktop_telemetry_level_write_unreadable")
        ConfigWriteOutcome.Failed(ReadProblem.Runtime(failure.message ?: "The telemetry settings cannot be written."))
      }
    }

  override suspend fun detectedAgentsRead(): DetectedAgentsRead =
    call("agents_detect", read = true, {
      diagnostics.record("desktop_agents_detect_unavailable")
      DetectedAgentsRead.Failed(it)
    }) { runtime ->
      DetectedAgentsRead.Success(
        runtime.installAgentService.detectAgentTargets(Path.of(host.userHome), host.variables)
          .map { DetectedAgent(it.name, it.path.value) },
      )
    }

  override suspend fun claudeConfigDirectories(sessionId: Long): ClaudeConfigDirectoriesRead {
    val session = sessions.currentComponent(sessionId) ?: return unavailableClaudeRoots()
    val read = readOrProblem(session.runtime, diagnostics) {
      session.reads.read("agents_claude_roots") { runtime ->
        runtime.installAgentService.claudeRoots(Path.of(host.userHome), host.variables).map { it.toString() }
      }
    }
    if (!sessions.isCurrent(session)) return unavailableClaudeRoots()
    return when (read) {
      is Outcome.Success -> ClaudeConfigDirectoriesRead.Success(read.value)
      is Outcome.Failure -> {
        diagnostics.record("desktop_agents_claude_roots_unavailable")
        ClaudeConfigDirectoriesRead.Failed(read.error)
      }
    }
  }

  private fun unavailableClaudeRoots(): ClaudeConfigDirectoriesRead.Unavailable {
    diagnostics.record("desktop_agents_claude_roots_unavailable")
    return ClaudeConfigDirectoriesRead.Unavailable
  }

  override suspend fun phaseInstructionFiles(repoRoot: String): PhaseInstructionFilesRead = withContext(Dispatchers.IO) {
    val directory = try {
      Path.of(repoRoot).resolve(PhaseProjectInstructions.PHASES_DIR)
    } catch (failure: InvalidPathException) {
      diagnostics.record("desktop_phase_instructions_listing_incomplete")
      return@withContext PhaseInstructionFilesRead.Incomplete(
        emptyList(),
        listOf(ReadProblem.Runtime("The project path for phase instructions is invalid: ${failure.message ?: "invalid path"}")),
      )
    }
    val orderedIds = phaseInstructionIds()
    val recognizedIds = orderedIds.toSet()
    val listedEntries = mutableListOf<Pair<String, Path>>()
    val problems = mutableListOf<ReadProblem>()
    val stream = try {
      Files.newDirectoryStream(directory)
    } catch (_: NoSuchFileException) {
      return@withContext PhaseInstructionFilesRead.Complete(emptyList())
    } catch (failure: IOException) {
      recordPhaseInstructionListingFailure(directory, failure, problems)
      null
    } catch (failure: SecurityException) {
      recordPhaseInstructionListingFailure(directory, failure, problems)
      null
    } catch (failure: DirectoryIteratorException) {
      recordPhaseInstructionListingFailure(directory, failure, problems)
      null
    }
    if (stream != null) {
      try {
        stream.use { entries ->
          val iterator = entries.iterator()
          var listingComplete = true
          while (listingComplete) {
            val entry = try {
              if (iterator.hasNext()) iterator.next() else null
            } catch (failure: DirectoryIteratorException) {
              recordPhaseInstructionListingFailure(directory, failure, problems)
              null
            } catch (failure: SecurityException) {
              recordPhaseInstructionListingFailure(directory, failure, problems)
              null
            }
            if (entry == null) {
              listingComplete = false
            } else {
              val filename = entry.fileName?.toString()
              val stem = filename?.takeIf { it.endsWith(".md") }?.removeSuffix(".md")
              if (stem != null && stem in recognizedIds) listedEntries += stem to entry
            }
          }
        }
      } catch (failure: IOException) {
        recordPhaseInstructionListingFailure(directory, failure, problems)
      } catch (failure: SecurityException) {
        recordPhaseInstructionListingFailure(directory, failure, problems)
      } catch (failure: DirectoryIteratorException) {
        recordPhaseInstructionListingFailure(directory, failure, problems)
      }
    }

    val filesById = listedEntries.toMap()
    val files = orderedIds.mapNotNull { id ->
      val path = filesById[id] ?: return@mapNotNull null
      val attributes = try {
        Files.readAttributes(path, BasicFileAttributes::class.java)
      } catch (failure: IOException) {
        recordPhaseInstructionMetadataFailure(path, failure, problems)
        return@mapNotNull PhaseInstructionFile(id, path.toString(), null, null)
      } catch (failure: SecurityException) {
        recordPhaseInstructionMetadataFailure(path, failure, problems)
        return@mapNotNull PhaseInstructionFile(id, path.toString(), null, null)
      }
      if (!attributes.isRegularFile) return@mapNotNull null
      val size = attributes.size()
      PhaseInstructionFile(id, path.toString(), size, size > PhaseProjectInstructions.MAX_BYTES)
    }
    if (problems.isEmpty()) PhaseInstructionFilesRead.Complete(files) else PhaseInstructionFilesRead.Incomplete(files, problems)
  }

  private fun phaseInstructionIds(): List<String> {
    val steps = PhaseSlot.entries.flatMap { it.steps }.distinct()
    val stepOrder = steps.withIndex().associate { (index, step) -> step to index }
    val aliasesByTarget = PhaseSlot.stepAliases.entries.groupBy { it.value }
      .mapValues { (_, aliases) -> aliases.map { it.key }.sorted() }
    val aliasesWithUnknownTargets = PhaseSlot.stepAliases.entries
      .filter { it.value !in stepOrder }
      .sortedWith(compareBy({ it.value }, { it.key }))
      .map { it.key }
    return buildList {
      steps.forEach { step ->
        add(step)
        addAll(aliasesByTarget[step].orEmpty())
      }
      addAll(aliasesWithUnknownTargets)
    }.distinct()
  }

  private fun recordPhaseInstructionListingFailure(
    directory: Path,
    failure: Throwable,
    problems: MutableList<ReadProblem>,
  ) {
    diagnostics.record("desktop_phase_instructions_listing_incomplete")
    problems += ReadProblem.Runtime(
      "Could not completely list phase instructions in $directory: ${failure.message ?: failure.javaClass.simpleName}",
    )
  }

  private fun recordPhaseInstructionMetadataFailure(
    path: Path,
    failure: Throwable,
    problems: MutableList<ReadProblem>,
  ) {
    diagnostics.record("desktop_phase_instruction_metadata_unavailable")
    problems += ReadProblem.Runtime(
      "Could not read metadata for ${path.fileName}: ${failure.message ?: failure.javaClass.simpleName}",
    )
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

private fun telemetryLevelFromSnapshot(
  document: ConfigDocument<MachineConfig>,
  environment: Map<String, String>,
): TelemetryLevelRead {
  val path: String
  var level: String
  var installId: String
  when (document) {
    is ConfigDocument.Absent -> {
      path = document.path
      level = "off"
      installId = ""
    }
    is ConfigDocument.Unreadable -> return TelemetryLevelRead.Unreadable(document.keyPath, document.reason)
    is ConfigDocument.Present -> {
      path = document.path
      level = document.values.telemetryLevel
      installId = document.values.telemetryInstallId.orEmpty()
    }
  }
  environment[TELEMETRY_LEVEL_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let { raw ->
    level = parseTelemetryLevelValueOrNull(raw)
      ?: return TelemetryLevelRead.Failed(ReadProblem.Runtime(telemetryLevelValueError(TELEMETRY_LEVEL_ENVIRONMENT_KEY)))
  } ?: environment[TELEMETRY_ENABLED_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let { raw ->
    val enabled = parseTelemetryBoolValueOrNull(raw)
      ?: return TelemetryLevelRead.Failed(ReadProblem.Runtime(telemetryBoolValueError(TELEMETRY_ENABLED_ENVIRONMENT_KEY)))
    level = if (enabled) "anonymous" else "off"
  }
  environment[TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY]?.takeIf(String::isNotBlank)?.let { raw ->
    if (parsePositiveTelemetryIntOrNull(raw) == null) {
      return TelemetryLevelRead.Failed(ReadProblem.Runtime(positiveTelemetryIntError(raw, TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY)))
    }
  }
  installId = environment[INSTALL_ID_ENVIRONMENT_KEY]?.trim()?.takeIf(String::isNotBlank) ?: installId
  if (level != "off" && installId.isBlank()) {
    return TelemetryLevelRead.Failed(
      ReadProblem.Runtime(
        "Telemetry is enabled but no install_id is configured at '$path'. " +
          "Run 'skill-bill telemetry enable' to create one.",
      ),
    )
  }
  val resolved = TelemetryLevel.entries.firstOrNull { it.wireValue == level }
    ?: return TelemetryLevelRead.Failed(ReadProblem.Runtime("Unknown telemetry level $level."))
  return TelemetryLevelRead.Known(resolved, path)
}

private fun telemetrySettingsFromSnapshot(
  document: ConfigDocument<MachineConfig>,
  environment: Map<String, String>,
): TelemetryLevelRead {
  val path: String
  val filePresent: Boolean
  val configuredLevel: String?
  val configuredInstallId: String?
  when (document) {
    is ConfigDocument.Absent -> {
      path = document.path
      filePresent = false
      configuredLevel = null
      configuredInstallId = null
    }
    is ConfigDocument.Unreadable -> return TelemetryLevelRead.Unreadable(document.keyPath, document.reason)
    is ConfigDocument.Present -> {
      path = document.path
      filePresent = true
      configuredLevel = document.values.telemetryLevel
      configuredInstallId = document.values.telemetryInstallId
    }
  }
  val resolved = telemetrySettingsSnapshotFromConfig(
    configPath = path,
    filePresent = filePresent,
    configuredLevel = configuredLevel,
    configuredInstallId = configuredInstallId,
    environment = TelemetrySnapshotEnvironment(
      level = environment[TELEMETRY_LEVEL_ENVIRONMENT_KEY],
      enabled = environment[TELEMETRY_ENABLED_ENVIRONMENT_KEY],
      installId = environment[INSTALL_ID_ENVIRONMENT_KEY],
      batchSize = environment[TELEMETRY_BATCH_SIZE_ENVIRONMENT_KEY],
    ),
  )
  return resolved.toDomain(path)
}

private fun TelemetrySettingsSnapshot.toDomain(path: String): TelemetryLevelRead {
  val configured = configuredLevel.toDesktopTelemetryLevel()
    ?: return TelemetryLevelRead.Failed(ReadProblem.Runtime("Unknown telemetry level $configuredLevel."))
  val effective = when (val value = effectiveLevel) {
    is TelemetrySnapshotEffectiveLevel.Known -> TelemetryEffectiveLevel.Known(
      value.level.toDesktopTelemetryLevel()
        ?: return TelemetryLevelRead.Failed(ReadProblem.Runtime("Unknown telemetry level ${value.level}.")),
    )
    is TelemetrySnapshotEffectiveLevel.Unavailable -> TelemetryEffectiveLevel.Unavailable(value.reason)
  }
  val availability = when (val value = this.availability) {
    is TelemetrySnapshotAvailability.Available -> TelemetryOperationalAvailability.Available(
      value.level.toDesktopTelemetryLevel()
        ?: return TelemetryLevelRead.Failed(ReadProblem.Runtime("Unknown telemetry level ${value.level}.")),
    )
    is TelemetrySnapshotAvailability.Unavailable -> TelemetryOperationalAvailability.Unavailable(value.reason)
  }
  val previous = when (val value = previousLevel) {
    is TelemetrySnapshotPriorLevel.Known -> TelemetryPreviousLevel.Known(
      value.level.toDesktopTelemetryLevel()
        ?: return TelemetryLevelRead.Failed(ReadProblem.Runtime("Unknown telemetry level ${value.level}.")),
    )
    TelemetrySnapshotPriorLevel.Unavailable -> TelemetryPreviousLevel.Unavailable
  }
  val idSource = when (installIdSource) {
    TelemetrySnapshotInstallIdSource.CONFIG -> TelemetryInstallIdSource.CONFIG
    TelemetrySnapshotInstallIdSource.ENVIRONMENT -> TelemetryInstallIdSource.ENVIRONMENT
    TelemetrySnapshotInstallIdSource.MISSING -> TelemetryInstallIdSource.MISSING
  }
  return TelemetryLevelRead.Snapshot(
    TelemetryLevelSnapshot(
      configuredLevel = configured,
      effectiveLevel = effective,
      availability = availability,
      previousLevel = previous,
      levelEnvironmentOverride = levelEnvironmentOverride,
      installIdSource = idSource,
      configHasInstallId = configHasInstallId,
      configPath = path,
    ),
  )
}

private fun String.toDesktopTelemetryLevel(): TelemetryLevel? =
  TelemetryLevel.entries.firstOrNull { it.wireValue == this }
