package dev.skillbill.tray.core.data

import dev.skillbill.tray.core.common.DispatcherProvider
import dev.skillbill.tray.core.common.HostEnvironment
import dev.skillbill.tray.core.datastore.DataStoreSettingsRepository
import dev.skillbill.tray.core.datastore.DiagnosticsMigrationSink
import dev.skillbill.tray.core.datastore.JavaPreferencesLegacySource
import dev.skillbill.tray.core.datastore.JvmSettingsFileLocation
import dev.skillbill.tray.core.datastore.JvmSettingsProviders
import dev.skillbill.tray.core.domain.model.Lifecycle
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import skillbill.di.core.*
import skillbill.infrastructure.host.JdkFeatureTaskRuntimeWorkerSupervisor
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.prefs.Preferences

object PackagedDataSmoke {
  @JvmStatic fun main(args: Array<String>) = runBlocking {
    val home = Path.of(args.single()).toAbsolutePath()
    Files.createDirectories(home)
    val database = home.resolve("work.db")
    val runtime = RuntimeComponent::class.create(RuntimeContext(
      EnvironmentContext(database.toString(), environment = mapOf("SKILL_BILL_TEST" to "1"), userHome = home, repositoryRoot = home),
      TransportContext(), WorkflowOpsContext(), OptionalCallbacks()))
    val now = Instant.now()
    val owner = JdkFeatureTaskRuntimeWorkerSupervisor(object : RuntimeDiagnostics {
      override fun info(message: String) = Unit
      override fun warning(message: String, error: Throwable?) = Unit
      override fun error(message: String, error: Throwable?) = Unit
    }, JdkHostPlatformPort).currentProcess()
    runtime.databaseSession.transaction { unit ->
      unit.standalonePhaseStatuses.register(StandalonePhaseStatusRegistration(
        "repo-root-realpath-v1:$home", "smoke", "SKILL-423", null, "packaged-phase", "review", "packaged-execution", "active", "review",
        now, "owned-smoke", 1, now.plusSeconds(300), processOwner = owner))
      val legacyBirth = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli() + 1000
      if (JdkHostPlatformPort.osName.startsWith("Linux", ignoreCase = true)) {
        unit.standalonePhaseStatuses.register(StandalonePhaseStatusRegistration(
          "repo-root-realpath-v1:$home", "smoke", "SKILL-426", null, "legacy-phase", "plan", "legacy-execution", "active", "plan",
          now, "legacy-smoke", 1, now.plusSeconds(300), processOwner = owner.copy(processBirthToken = legacyBirth.toString())))
      }
    }
    val source = workStack(home)
    val legacy = Preferences.userRoot().node("packaged-smoke")
    val settingsHandle = object : JvmSettingsProviders {}.settingsStoreHandle(
      JvmSettingsFileLocation(HostEnvironment(mapOf("SKILL_BILL_TEST" to "1"), home.toString(), home.toString())),
      JavaPreferencesLegacySource.forNode(legacy),
      DiagnosticsMigrationSink(source.diagnostics),
      source.diagnostics,
      DispatcherProvider(io = Dispatchers.IO, ui = Dispatchers.IO),
    )
    val settings = DataStoreSettingsRepository(settingsHandle)
    try {
      source.selectDatabase(database.toString())
      val board = source.board(WorkSection.PHASES, Retention.DAY)
      val expectedIds = if (JdkHostPlatformPort.osName.startsWith("Linux", ignoreCase = true)) {
        setOf("packaged-phase", "legacy-phase")
      } else setOf("packaged-phase")
      check(board is Outcome.Value && board.value.items.map { it.identity.id }.toSet() == expectedIds)
      check(board.value.items.all { it.lifecycle == Lifecycle.ACTIVE })
      val detail = source.detail(board.value.items.first { it.identity.id == "packaged-phase" }.identity)
      check(detail is Outcome.Value && detail.value.item.lifecycle == Lifecycle.ACTIVE)
      check(settings.settings.first { it !is SettingsState.Loading } is SettingsState.Ready)
      check(settings.update { it.copy(databasePath = database.toString(), retention = Retention.WEEK) } is Outcome.Value)
      val saved = settings.settings.first { it !is SettingsState.Loading }
      check(saved is SettingsState.Ready && saved.settings.retention == Retention.WEEK)
      println("Packaged Room, SQLite runtime projection, detail and DataStore passed without CLI discovery.")
    } finally { source.closeAndJoin(); settingsHandle.closeAndJoin(); legacy.removeNode() }
  }
}
