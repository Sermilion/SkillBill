package skillbill.cli.apprun

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.DocumentedNoOpCliCommand
import skillbill.cli.kernel.cli.formatOption
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliFormat
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.apprun.AppRunPayloadKeys
import skillbill.contracts.qa.QaDeviceLaunchTokens.QA_DEVICE_FLAG
import skillbill.engine.apprun.AppRunService
import skillbill.engine.apprun.model.AppRunCancellation
import skillbill.engine.apprun.model.AppRunListener
import skillbill.engine.apprun.model.AppRunOutcome
import skillbill.engine.apprun.model.AppRunProgress
import skillbill.engine.apprun.model.AppRunRequest
import skillbill.engine.featuretask.model.execution.QaNamedDevice
import skillbill.ports.process.ShutdownHookPort
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Inject
class AppCommand(run: AppRunCommand) : DocumentedNoOpCliCommand("app", "Build and open a goal's app on a QA device.") {
  init {
    subcommands(run)
  }
}

@Inject
class AppRunCommand(
  private val service: AppRunService,
  private val shutdownHooks: ShutdownHookPort,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand("run", "Build and open a goal app on a serial or Android virtual device.") {
  private val goal by option("--goal", help = "Goal issue key.").required()
  private val device by option(QA_DEVICE_FLAG, help = "Device as serial:<serial> or avd:<name>.").convert { raw ->
    QaNamedDevice.fromWireValue(raw) ?: throw UsageError("Invalid QA device '$raw'. Use serial:<serial> or avd:<name>.")
  }.required()
  private val repoRoot by option("--repo-root", help = "Repository root. Defaults to the invocation repository root.")
  private val format by formatOption()

  override fun run() {
    val cancellation = AppRunCancellation()
    val finished = CountDownLatch(1)
    val hook =
      shutdownHooks.register {
        cancellation.cancel()
        finished.await(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)
      }
    try {
      val root = resolveCliRepositoryRoot(repoRoot, inputs)
      val outcome =
        service.run(
          AppRunRequest(root, goal, device, cancellation),
          object : AppRunListener {
            override fun onProgress(progress: AppRunProgress) = Unit

            override fun onOutput(line: String) {
              val output = if (format == CliFormat.TEXT) inputs.liveStdout else inputs.liveStderr
              output("$line\n")
            }
          },
        )
      state.complete(outcome.toPayload(), format, exitCode = if (outcome is AppRunOutcome.Running) 0 else 1)
    } finally {
      finished.countDown()
      try {
        hook.unregister()
      } catch (_: IllegalStateException) {
      }
    }
  }

  private fun AppRunOutcome.toPayload(): Map<String, Any?> =
    buildMap {
      put(AppRunPayloadKeys.OUTCOME, wireValue.value)
      when (this@toPayload) {
        is AppRunOutcome.Running -> {
          put(AppRunPayloadKeys.DEVICE, device.wireValue)
          put(AppRunPayloadKeys.SERIAL, serial)
          put(AppRunPayloadKeys.OPENED_AT, openedAt.toString())
        }
        is AppRunOutcome.BuildFailed -> {
          put(AppRunPayloadKeys.DEVICE, device.wireValue)
          put(AppRunPayloadKeys.FAILED_TASK, task)
        }
        is AppRunOutcome.ModuleUnresolved -> put(AppRunPayloadKeys.CANDIDATES, candidates)
        is AppRunOutcome.LaunchFailed -> {
          put(AppRunPayloadKeys.DEVICE, device.wireValue)
          put(AppRunPayloadKeys.REASON, reason)
        }
        is AppRunOutcome.BootFailed -> put(AppRunPayloadKeys.DEVICE, device.wireValue)
        is AppRunOutcome.Contended -> {
          put(AppRunPayloadKeys.DEVICE, device.wireValue)
          put(AppRunPayloadKeys.OWNER_INVOCATION_ID, ownerInvocationId)
        }
        is AppRunOutcome.Refused -> {
          put(AppRunPayloadKeys.REASON, reason.wireValue)
          put(AppRunPayloadKeys.MESSAGE, message)
        }
        is AppRunOutcome.Failed -> put(AppRunPayloadKeys.REASON, detail)
        AppRunOutcome.Stopped -> Unit
      }
    }

  private val AppRunOutcome.wireValue: AppRunOutcomeWire
    get() =
      when (this) {
        is AppRunOutcome.Running -> AppRunOutcomeWire.RUNNING
        is AppRunOutcome.BuildFailed -> AppRunOutcomeWire.BUILD_FAILED
        is AppRunOutcome.ModuleUnresolved -> AppRunOutcomeWire.MODULE_UNRESOLVED
        is AppRunOutcome.LaunchFailed -> AppRunOutcomeWire.LAUNCH_FAILED
        is AppRunOutcome.BootFailed -> AppRunOutcomeWire.BOOT_FAILED
        is AppRunOutcome.Contended -> AppRunOutcomeWire.CONTENDED
        AppRunOutcome.Stopped -> AppRunOutcomeWire.STOPPED
        is AppRunOutcome.Refused -> AppRunOutcomeWire.REFUSED
        is AppRunOutcome.Failed -> AppRunOutcomeWire.FAILED
      }

  private enum class AppRunOutcomeWire(val value: String) {
    RUNNING("running"),
    BUILD_FAILED("build_failed"),
    MODULE_UNRESOLVED("module_unresolved"),
    LAUNCH_FAILED("launch_failed"),
    BOOT_FAILED("boot_failed"),
    CONTENDED("contended"),
    STOPPED("stopped"),
    REFUSED("refused"),
    FAILED("failed"),
  }

  private companion object {
    const val SHUTDOWN_GRACE_SECONDS = 60L
  }
}
