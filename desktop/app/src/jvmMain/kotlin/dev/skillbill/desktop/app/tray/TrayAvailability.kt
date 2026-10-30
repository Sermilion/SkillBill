package dev.skillbill.desktop.app.tray

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.di.AppScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.concurrent.atomic.AtomicBoolean

data class TrayAvailabilityState(val probeComplete: Boolean = false, val available: Boolean = false) {
  val canHideToTray: Boolean get() = probeComplete && available
}

@Inject
@SingleIn(AppScope::class)
class TrayAvailability(private val diagnostics: Diagnostics) {
  private val mutable = MutableStateFlow(TrayAvailabilityState())
  val state: StateFlow<TrayAvailabilityState> = mutable.asStateFlow()
  private val recordedUnavailable = AtomicBoolean(false)

  init {
    TrayDiagnostics.bind(diagnostics)
  }

  fun available() { mutable.value = TrayAvailabilityState(true, true) }
  fun unavailable() {
    mutable.update { it.copy(probeComplete = true, available = false) }
    if (recordedUnavailable.compareAndSet(false, true)) diagnostics.record("tray_unavailable")
  }
}
