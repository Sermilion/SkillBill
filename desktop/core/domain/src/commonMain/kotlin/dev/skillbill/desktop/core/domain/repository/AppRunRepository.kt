package dev.skillbill.desktop.core.domain.repository

import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.qa.QaDevicePick
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow

/** Runs a goal's Android app through the current in-process runtime and reports output and progress. */
interface AppRunRepository {
  suspend fun run(start: AppRunStart, events: AppRunEvents): AppRunResult
}

data class AppRunStart(val item: WorkItem, val target: QaDevicePick, val sourcePick: QaDevicePick?, val stop: AppRunStopSignal)

interface AppRunEvents {
  fun onTarget(device: QaDevicePick)
  fun onStep(step: AppRunStep)
  fun onOutput(line: String)
}

data class AppRunStep(val kind: AppRunStepKind, val gradleTask: String? = null)

enum class AppRunStepKind { Preparing, Booting, Leasing, Building, Opening }

class AppRunStopSignal {
  private val state = MutableStateFlow(StopState())
  val isRequested: Boolean get() = state.value.requested

  fun request() {
    while (true) {
      val current = state.value
      if (current.requested) return
      if (!state.compareAndSet(current, current.copy(requested = true))) continue
      current.action?.invoke()
      return
    }
  }

  fun bind(cancel: () -> Unit) {
    while (true) {
      val current = state.value
      if (current.action != null) return
      if (!state.compareAndSet(current, current.copy(action = cancel))) continue
      if (current.requested) cancel()
      return
    }
  }

  private data class StopState(val requested: Boolean = false, val action: (() -> Unit)? = null)
}

sealed interface AppRunResult {
  data class Running(val openedAt: Instant) : AppRunResult
  data class BuildFailed(val task: String) : AppRunResult
  data class ModuleUnresolved(val candidates: List<String>) : AppRunResult
  data object LaunchFailed : AppRunResult
  data object BootFailed : AppRunResult
  data class Contended(val ownerInvocationId: String) : AppRunResult
  data object Stopped : AppRunResult
  data class Refused(val message: String) : AppRunResult
  data class Failed(val exitCode: Int?) : AppRunResult
  data class Unavailable(val message: String) : AppRunResult
}
