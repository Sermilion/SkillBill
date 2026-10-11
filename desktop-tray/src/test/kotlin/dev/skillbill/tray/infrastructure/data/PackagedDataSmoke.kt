package dev.skillbill.tray.infrastructure.data

import dev.skillbill.tray.application.Outcome
import dev.skillbill.tray.domain.*
import dev.skillbill.tray.domain.Retention
import dev.skillbill.tray.infrastructure.DataStoreSettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import skillbill.di.core.*
import skillbill.model.EnvironmentContext
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
      EnvironmentContext(database.toString(), environment = emptyMap(), userHome = home, repositoryRoot = home),
      TransportContext(), WorkflowOpsContext(), OptionalCallbacks()))
    val now = Instant.now()
    runtime.databaseSession.transaction { unit ->
      unit.standalonePhaseStatuses.register(StandalonePhaseStatusRegistration(
        "repo-root-realpath-v1:$home", "smoke", "SKILL-423", null, "packaged-phase", "review", "packaged-execution", "active", "review",
        now, "owned-smoke", 1, now.plusSeconds(300)))
    }
    val source = RoomBoardSource(emptyMap(), home, home)
    val legacy = Preferences.userRoot().node("packaged-smoke")
    val settings = DataStoreSettingsStore(home.resolve("settings.json"), legacy)
    try {
      source.selectDatabase(database.toString())
      val board = source.board(WorkSection.PHASES, Retention.DAY)
      check(board is Outcome.Value && board.value.items.single().identity.id == "packaged-phase")
      val detail = source.detail(board.value.items.single().identity)
      check(detail is Outcome.Value && detail.value.item.lifecycle == Lifecycle.ACTIVE)
      check(settings.settings.first() is Outcome.Value)
      check(settings.update { it.copy(databasePath = database.toString(), retention = Retention.WEEK) } is Outcome.Value)
      check(((settings.settings.first() as Outcome.Value).value).retention == Retention.WEEK)
      println("Packaged Room, SQLite runtime projection, detail and DataStore passed without CLI discovery.")
    } finally { source.close(); settings.close(); legacy.removeNode() }
  }
}
