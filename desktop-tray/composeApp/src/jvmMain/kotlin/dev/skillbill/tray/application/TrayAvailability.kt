package dev.skillbill.tray.application

import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether the system tray can host the application's icon: unknown until a probe or the tray itself answers. */
data class TrayAvailabilityState(val probeComplete: Boolean = false, val problem: Problem? = null) {
  /** The main window may hide to the tray only once the tray is known to work. */
  val canHideToTray: Boolean get() = probeComplete && problem == null
}

class TrayAvailability(scope: CoroutineScope, native: NativeServices) {
  private val mutable = MutableStateFlow(TrayAvailabilityState())
  val state: StateFlow<TrayAvailabilityState> = mutable.asStateFlow()

  init {
    scope.launch { if (native.hasSystemTray()) available() else unavailable() }
  }

  fun available() = mutable.update { it.copy(probeComplete = true, problem = null) }

  fun unavailable() = mutable.update {
    it.copy(probeComplete = true, problem = Problem(ProblemCode.TRAY_UNAVAILABLE, "The system tray is unavailable. Skill Bill remains open in its desktop window."))
  }
}
