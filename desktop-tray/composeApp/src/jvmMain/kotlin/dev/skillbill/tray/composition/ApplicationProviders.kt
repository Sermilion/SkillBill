package dev.skillbill.tray.composition

import dev.skillbill.tray.core.common.Diagnostics
import dev.skillbill.tray.core.common.DispatcherProvider
import dev.skillbill.tray.core.common.di.AppScope
import dev.skillbill.tray.infrastructure.RuntimeDiagnostics
import kotlinx.coroutines.Dispatchers
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** The I/O dispatcher only the application can name; the environment and home directory arrive with the component. */
@ContributesTo(AppScope::class)
interface HostProviders {
  @Provides
  @SingleIn(AppScope::class)
  fun dispatcherProvider(): DispatcherProvider = DispatcherProvider(io = Dispatchers.IO)
}

/** Routes data-layer diagnostics to the desktop log. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RuntimeDiagnosticsAdapter : Diagnostics {
  override fun record(category: String) = RuntimeDiagnostics.record(category)

  override fun recordRequest(category: String, fields: Map<String, Number>) = RuntimeDiagnostics.recordRequest(category, fields)
}
