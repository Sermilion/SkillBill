package dev.skillbill.desktop.benchmark

import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.ui.board.ShellBoard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

object ColdStartProbe {
  @JvmStatic
  fun firstBoardMillis(databasePath: String, home: String): Long = runBlocking {
    val started = System.nanoTime()
    Class.forName("org.sqlite.JDBC", true, ColdStartProbe::class.java.classLoader)
    val diagnostics = RecordingDiagnostics()
    val app = BenchmarkComponent::class.create(
      HostEnvironment(emptyMap(), home, home), DiagnosticsSwitch(true), diagnostics,
    )
    try {
      app.appSettingsRepository.setDatabasePath(databasePath)
      app.databaseSessionManager.start()
      withTimeout(30.seconds) {
        val state = app.workBoardRepository.observe(ShellBoard.query.copy(complete = false)).first { it is BoardState.Loaded || it is BoardState.Failed }
        check(state is BoardState.Loaded) { "Cold benchmark board failed: $state" }
      }
      (System.nanoTime() - started) / 1_000_000
    } finally {
      try {
        app.closeSession()
      } finally {
        app.applicationCoroutineScope.scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
      }
    }
  }
}
