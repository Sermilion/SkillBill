package dev.skillbill.desktop.app.smoke

import dev.skillbill.desktop.app.AppComponent
import dev.skillbill.desktop.app.create
import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.BoardQuery
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.settings.HistoryWindow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.infrastructure.host.JdkFeatureTaskRuntimeWorkerSupervisor
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

object PackagedDataSmoke {
  @JvmStatic
  fun main(args: Array<String>) {
    runBlocking {
      val home = Path.of(args.firstOrNull() ?: System.getProperty("user.home")).toAbsolutePath()
      Files.createDirectories(home)
      val database = home.resolve("work.db")
      val runtime = RuntimeComponent::class.create(
        RuntimeContext(
          EnvironmentContext(database.toString(), environment = mapOf("SKILL_BILL_TEST" to "1"), userHome = home, repositoryRoot = home),
          TransportContext(),
          WorkflowOpsContext(),
          OptionalCallbacks(),
        ),
      )
      val now = Instant.now()
      val owner = JdkFeatureTaskRuntimeWorkerSupervisor(object : RuntimeDiagnostics {
        override fun warning(message: String, error: Throwable?) = Unit
        override fun error(message: String, error: Throwable?) = Unit
      }, JdkHostPlatformPort).currentProcess()
      runtime.databaseSession.transaction { unit ->
        unit.standalonePhaseStatuses.register(
          StandalonePhaseStatusRegistration(
            "repo-root-realpath-v1:$home", "smoke", "SKILL-447", null, "packaged-phase", "review", "packaged-execution", "active", "review",
            now, "owned-smoke", 1, now.plusSeconds(300), processOwner = owner,
          ),
        )
        if (JdkHostPlatformPort.osName.startsWith("Linux", ignoreCase = true)) {
          val legacyBirth = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli() + 1000
          unit.standalonePhaseStatuses.register(
            StandalonePhaseStatusRegistration(
              "repo-root-realpath-v1:$home", "smoke", "SKILL-426", null, "legacy-phase", "plan", "legacy-execution", "active", "plan",
              now, "legacy-smoke", 1, now.plusSeconds(300), processOwner = owner.copy(processBirthToken = legacyBirth.toString()),
            ),
          )
        }
      }
      val app = AppComponent::class.create(HostEnvironment(mapOf("SKILL_BILL_TEST" to "1"), home.toString(), home.toString()), DiagnosticsSwitch(false))
      try {
        check(app.appSettingsRepository.setDatabasePath(database.toString()) is Outcome.Success) { "Could not select smoke database: $database" }
        app.databaseSessionManager.start()
        withTimeout(60.seconds) {
          val board = app.workBoardRepository.observe(BoardQuery(HistoryWindow.Day, "", 50)).filterIsInstance<BoardState.Loaded>().first()
          val expected = if (JdkHostPlatformPort.osName.startsWith("Linux", ignoreCase = true)) {
            setOf("packaged-phase", "legacy-phase")
          } else setOf("packaged-phase")
          val phaseIds = board.sections.first { it.kind == BoardSectionKind.Phases }.items.map { it.id }.toSet()
          check(phaseIds == expected) { "Packaged board phases were $phaseIds, expected $expected" }
          val item = board.items.first { it.id == "packaged-phase" }
          val detail = app.workDetailRepository.observe(item).first { it.sections is DetailSections.Loaded }
          check(detail.sections is DetailSections.Loaded) { "Packaged detail sections were ${detail.sections}" }
          val saved = app.appSettingsRepository.setHistoryWindow(HistoryWindow.Week)
          check(saved is Outcome.Success) { "Could not save packaged history setting: $saved" }
          val readBack = app.appSettingsRepository.settings.first { it.historyWindow == HistoryWindow.Week }
          check(readBack.historyWindow == HistoryWindow.Week) { "Packaged history setting was ${readBack.historyWindow}" }
        }
        println("Packaged runtime session, board, detail and settings passed without CLI discovery.")
      } finally {
        app.applicationCoroutineScope.scope.cancel()
      }
      exitProcess(0)
    }
  }
}
