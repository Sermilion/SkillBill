package dev.skillbill.tray.composition

import dev.skillbill.tray.core.common.Diagnostics
import dev.skillbill.tray.core.common.DispatcherProvider
import dev.skillbill.tray.core.common.di.AppScope
import dev.skillbill.tray.core.domain.repository.WorkBoardRepository
import dev.skillbill.tray.core.domain.repository.WorkControlRepository
import dev.skillbill.tray.core.ui.LaunchRequests
import dev.skillbill.tray.infrastructure.RuntimeDiagnostics
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** The I/O and UI dispatchers only the application can name; the environment and home directory arrive with the component. */
@ContributesTo(AppScope::class)
interface HostProviders {
  @Provides
  @SingleIn(AppScope::class)
  fun dispatcherProvider(): DispatcherProvider = DispatcherProvider(io = Dispatchers.IO, ui = Dispatchers.Main)

  @Provides
  @SingleIn(AppScope::class)
  fun launchRequests(
    dispatchers: DispatcherProvider,
    controls: WorkControlRepository,
    boards: WorkBoardRepository,
    clock: Clock,
  ): LaunchRequests = LaunchRequests(CoroutineScope(SupervisorJob() + dispatchers.ui), controls, boards, clock)
}

/** Routes data-layer diagnostics to the desktop log. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RuntimeDiagnosticsAdapter : Diagnostics {
  override fun record(category: String) = RuntimeDiagnostics.record(category)

  override fun recordRequest(category: String, fields: Map<String, Number>) = RuntimeDiagnostics.recordRequest(category, fields)
}
