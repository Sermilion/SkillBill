package dev.skillbill.desktop.benchmark

import dev.skillbill.desktop.core.common.ApplicationCoroutineScope
import dev.skillbill.desktop.core.common.DatabaseDispatcher
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.DiagnosticsSwitch
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.data.runtime.LoggerDiagnostics
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@MergeComponent(AppScope::class, exclude = [LoggerDiagnostics::class])
@SingleIn(AppScope::class)
abstract class BenchmarkComponent(
  @get:Provides val host: HostEnvironment,
  @get:Provides val diagnosticsSwitch: DiagnosticsSwitch,
  @get:Provides val diagnostics: Diagnostics,
) {
  abstract val databaseSessionManager: DatabaseSessionManager
  abstract val appSettingsRepository: AppSettingsRepository
  abstract val workBoardRepository: WorkBoardRepository
  abstract val workDetailRepository: WorkDetailRepository
  abstract val applicationCoroutineScope: ApplicationCoroutineScope
}

internal suspend fun BenchmarkComponent.closeSession() {
  val closingPath = Path.of(host.userHome).resolve("benchmark-closed-${UUID.randomUUID()}.db").toString()
  when (val result = appSettingsRepository.setDatabasePath(closingPath)) {
    is Outcome.Success -> Unit
    is Outcome.Failure -> error("Could not select a closing database path: ${result.error}.")
  }
  withTimeout(30.seconds) {
    databaseSessionManager.state.first { it is DatabaseSessionState.Failed && it.path == closingPath }
  }
}

@ContributesTo(AppScope::class)
interface BenchmarkProviders {
  @Provides
  @SingleIn(AppScope::class)
  fun databaseDispatcher(): DatabaseDispatcher = DatabaseDispatcher(Dispatchers.IO.limitedParallelism(2))

  @Provides
  fun clock(): Clock = Clock.System

  @Provides
  @SingleIn(AppScope::class)
  fun applicationScope(): ApplicationCoroutineScope = ApplicationCoroutineScope(CoroutineScope(SupervisorJob() + Dispatchers.Default))
}

class RecordingDiagnostics : Diagnostics {
  private val reads = CopyOnWriteArrayList<Long>()

  override fun record(category: String) = Unit

  override fun recordRequest(category: String, fields: Map<String, Number>) {
    if (category == "desktop_read") fields["elapsed_ms"]?.toLong()?.let(reads::add)
  }

  fun snapshot(): List<Long> = reads.toList()
}
