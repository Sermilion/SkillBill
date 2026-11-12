package dev.skillbill.desktop.core.ui.apprun

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.AppRunEvents
import dev.skillbill.desktop.core.domain.repository.AppRunRepository
import dev.skillbill.desktop.core.domain.repository.AppRunResult
import dev.skillbill.desktop.core.domain.repository.AppRunStart
import dev.skillbill.desktop.core.domain.repository.AppRunStep
import dev.skillbill.desktop.core.domain.repository.AppRunStepKind
import dev.skillbill.desktop.core.domain.repository.AppRunStopSignal
import dev.skillbill.desktop.core.domain.qa.QaDevicePick
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(WindowScope::class)
class AppRunController(
  private val repository: AppRunRepository,
  private val devices: RunAppDeviceHost,
  private val scope: WindowCoroutineScope,
  private val diagnostics: Diagnostics,
) {
  private val mutableStates = MutableStateFlow<Map<String, AppRunState>>(emptyMap())
  val states: StateFlow<Map<String, AppRunState>> = mutableStates.asStateFlow()
  private val outputByGoal = mutableMapOf<String, AppRunOutput>()
  private val stopByGoal = mutableMapOf<String, AppRunStopSignal>()

  fun state(goalKey: String): AppRunState = mutableStates.value[goalKey] ?: AppRunState.Idle

  @Synchronized
  fun output(goalKey: String): AppRunOutput = outputByGoal.getOrPut(goalKey, ::AppRunOutput)

  @Synchronized
  fun run(item: WorkItem, target: QaDevicePick, sourcePick: QaDevicePick?) {
    val key = item.issueKey ?: item.id
    if (state(key) is AppRunState.Building) return
    val output = output(key)
    output.reset()
    val stop = AppRunStopSignal()
    synchronized(this) { stopByGoal[key] = stop }
    val device = AtomicReference(target.displayName)
    mutableStates.update { it + (key to AppRunState.Building(device.get(), "Preparing the checkout")) }
    scope.scope.launch {
      val unknownTaskRecorded = AtomicBoolean(false)
      try {
        val result = repository.run(AppRunStart(item, target, sourcePick, stop), object : AppRunEvents {
          override fun onTarget(devicePick: QaDevicePick) {
            device.set(devicePick.displayName)
            mutableStates.update { states ->
              val current = states[key] as? AppRunState.Building
              if (current == null) states else states + (key to current.copy(device = device.get()))
            }
          }

          override fun onStep(step: AppRunStep) {
            val label = step.label(device.get()) {
              if (unknownTaskRecorded.compareAndSet(false, true)) {
                diagnostics.record("desktop_app_run_build_task_unknown")
              }
            }
            mutableStates.update { states -> states + (key to AppRunState.Building(device.get(), label)) }
          }

          override fun onOutput(line: String) = output.append(line)
        })
        val next = result.toState(device.get(), devices.leaseHolders(), diagnostics)
        mutableStates.update { states -> states + (key to next) }
      } catch (cancelled: CancellationException) {
        stop.request()
        throw cancelled
      } catch (failure: Exception) {
        diagnostics.record("desktop_app_run_failed")
        val next = AppRunState.Problem(failure.message ?: "Run app failed.")
        mutableStates.update { states -> states + (key to next) }
      } finally {
        synchronized(this@AppRunController) { stopByGoal.remove(key, stop) }
      }
    }
  }

  fun stop(goalKey: String) {
    synchronized(this) { stopByGoal[goalKey] }?.request()
  }

  private fun AppRunStep.label(device: String, unknown: () -> Unit): String = when (kind) {
    AppRunStepKind.Preparing -> "Preparing the checkout"
    AppRunStepKind.Booting -> "Booting $device"
    AppRunStepKind.Leasing -> "Leasing $device"
    AppRunStepKind.Building -> gradleTask?.let { "gradlew $it" } ?: "gradlew install".also { unknown() }
    AppRunStepKind.Opening -> "Opening the app"
  }

  private fun AppRunResult.toState(device: String, holders: Map<String, String>, diagnostics: Diagnostics): AppRunState = when (this) {
    is AppRunResult.Running -> AppRunState.Running(device, openedAt)
    is AppRunResult.BuildFailed -> AppRunState.BuildFailed(device, task)
    is AppRunResult.ModuleUnresolved -> AppRunState.Problem(
      if (candidates.isEmpty()) "No Android app module found."
      else "Several app modules: ${candidates.joinToString(", ")}. Set module in .skill-bill/app-run.yaml.",
    )
    AppRunResult.LaunchFailed -> AppRunState.Problem("Built and installed, but the app did not open on $device.")
    AppRunResult.BootFailed -> AppRunState.Problem("$device did not boot. Nothing was installed.")
    is AppRunResult.Contended -> {
      val label = holders[ownerInvocationId] ?: ownerInvocationId.also { diagnostics.record("desktop_app_run_lease_holder_unmapped") }
      AppRunState.Contended(device, label)
    }
    AppRunResult.Stopped -> AppRunState.Stopped
    is AppRunResult.Refused -> AppRunState.Problem(message)
    is AppRunResult.Failed -> AppRunState.Problem("Run app failed with exit ${exitCode ?: "unknown"}.")
    is AppRunResult.Unavailable -> AppRunState.Problem(message)
  }

}
