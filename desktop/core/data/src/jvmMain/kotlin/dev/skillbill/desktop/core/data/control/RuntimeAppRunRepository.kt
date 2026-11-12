package dev.skillbill.desktop.core.data.control

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.data.session.DatabaseSessionManager
import dev.skillbill.desktop.core.domain.qa.QaDeviceDiscoveryRepository
import dev.skillbill.desktop.core.domain.qa.resolveQaRunTarget
import dev.skillbill.desktop.core.domain.repository.AppRunEvents
import dev.skillbill.desktop.core.domain.repository.AppRunRepository
import dev.skillbill.desktop.core.domain.repository.AppRunResult
import dev.skillbill.desktop.core.domain.repository.AppRunStart
import dev.skillbill.desktop.core.domain.repository.AppRunStep
import dev.skillbill.desktop.core.domain.repository.AppRunStepKind
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import skillbill.engine.apprun.model.AppRunCancellation
import skillbill.engine.apprun.model.AppRunListener
import skillbill.engine.apprun.model.AppRunOutcome
import skillbill.engine.apprun.model.AppRunProgress
import skillbill.engine.apprun.model.AppRunRequest
import skillbill.engine.apprun.model.AppRunStep as RuntimeAppRunStep
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import dev.skillbill.desktop.core.common.di.AppScope
import java.nio.file.Path
import kotlin.time.toKotlinInstant

private const val REFRESH_BEFORE_CONTROLS = "Refresh the detail before using run controls."

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class RuntimeAppRunRepository(
  private val sessions: DatabaseSessionManager,
  private val settings: AppSettingsRepository,
  private val discovery: QaDeviceDiscoveryRepository,
  private val diagnostics: Diagnostics,
) : AppRunRepository {
  override suspend fun run(start: AppRunStart, events: AppRunEvents): AppRunResult {
    val item = start.item
    val session = sessions.sessions.value?.takeIf {
      it.selection.selection.id == item.control.sessionId && sessions.isCurrent(it)
    } ?: return AppRunResult.Unavailable(REFRESH_BEFORE_CONTROLS)
    val pick = try {
      when (val result = settings.readQaDevice()) {
        is Outcome.Success -> result.value
        is Outcome.Failure -> {
          diagnostics.record("desktop_app_run_pick_unavailable")
          return AppRunResult.Unavailable("Could not read the QA device pick. Refresh the detail before using run controls.")
        }
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      diagnostics.record("desktop_app_run_pick_unavailable")
      return AppRunResult.Unavailable("Could not read the QA device pick. Refresh the detail before using run controls.")
    }
    val currentDiscovery = try {
      discovery.refresh(listOf(item.repositoryRoot))
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      diagnostics.record("desktop_app_run_device_discovery_failed")
      return AppRunResult.Unavailable("Could not refresh devices. Open the device panel for details.")
    }
    val target = resolveQaRunTarget(currentDiscovery, pick)
      ?: return AppRunResult.Unavailable("No device. Plug in a phone with USB debugging on, or create an AVD.")
    if (!sessions.isCurrent(session)) return AppRunResult.Unavailable(REFRESH_BEFORE_CONTROLS)
    events.onTarget(target.pick)
    val cancellation = AppRunCancellation()
    start.stop.bind(cancellation::cancel)
    return try {
      val outcome = withContext(Dispatchers.IO) {
        session.runtime.appRunService.run(
          AppRunRequest(Path.of(item.repositoryRoot), item.issueKey ?: item.id, target.pick.toRuntimeDevice(), cancellation),
          object : AppRunListener {
            override fun onProgress(progress: AppRunProgress) = events.onStep(progress.toAppRunStep())
            override fun onOutput(line: String) = events.onOutput(line)
          },
        )
      }
      outcome.toAppRunResult()
    } catch (cancelled: CancellationException) {
      start.stop.request()
      throw cancelled
    } catch (failure: Exception) {
      diagnostics.record("desktop_app_run_failed")
      AppRunResult.Unavailable(failure.message ?: "Run app failed.")
    }
  }

  private fun AppRunProgress.toAppRunStep(): AppRunStep = AppRunStep(
    when (step) {
      RuntimeAppRunStep.RESOLVING -> AppRunStepKind.Preparing
      RuntimeAppRunStep.BOOTING -> AppRunStepKind.Booting
      RuntimeAppRunStep.LEASING -> AppRunStepKind.Leasing
      RuntimeAppRunStep.BUILDING -> AppRunStepKind.Building
      RuntimeAppRunStep.LAUNCHING -> AppRunStepKind.Opening
    },
    detail.takeIf { step == RuntimeAppRunStep.BUILDING },
  )

  private fun AppRunOutcome.toAppRunResult(): AppRunResult = when (this) {
    is AppRunOutcome.Running -> AppRunResult.Running(openedAt.toKotlinInstant())
    is AppRunOutcome.BuildFailed -> AppRunResult.BuildFailed(task)
    is AppRunOutcome.ModuleUnresolved -> AppRunResult.ModuleUnresolved(candidates)
    is AppRunOutcome.LaunchFailed -> AppRunResult.LaunchFailed
    is AppRunOutcome.BootFailed -> AppRunResult.BootFailed
    is AppRunOutcome.Contended -> AppRunResult.Contended(ownerInvocationId)
    AppRunOutcome.Stopped -> AppRunResult.Stopped
    is AppRunOutcome.Refused -> AppRunResult.Refused(message)
    is AppRunOutcome.Failed -> AppRunResult.Failed(exitCode)
  }
}
