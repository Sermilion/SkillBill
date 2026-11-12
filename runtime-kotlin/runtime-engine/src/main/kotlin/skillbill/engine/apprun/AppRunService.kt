package skillbill.engine.apprun

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.apprun.AppRunScriptContract
import skillbill.engine.apprun.model.AppRunCancellation
import skillbill.engine.apprun.model.AppRunListener
import skillbill.engine.apprun.model.AppRunOutcome
import skillbill.engine.apprun.model.AppRunProgress
import skillbill.engine.apprun.model.AppRunRefusalReason
import skillbill.engine.apprun.model.AppRunRequest
import skillbill.engine.apprun.model.AppRunScriptOutput
import skillbill.engine.apprun.model.AppRunStep
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.execution.QaNamedDeviceKind
import skillbill.engine.featuretask.qa.lease.QaDeviceFingerprint
import skillbill.error.apprun.AppRunFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.error.featuretask.qa.isQaDeviceLeaseFailure
import skillbill.ports.apprun.AppRunConfigPort
import skillbill.ports.apprun.AppRunProcessPort
import skillbill.ports.apprun.model.AppRunConfig
import skillbill.ports.apprun.model.AppRunProcessExit
import skillbill.ports.apprun.model.AppRunProcessSpec
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.qa.QaDeviceLeasePort
import skillbill.ports.qa.model.QaDeviceLease
import skillbill.ports.qa.model.QaLeaseReleaseOutcome
import skillbill.ports.qa.model.QaLeaseResult
import skillbill.ports.workflow.branchworktree.BranchWorktreeResources
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolder
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolderKind
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeRelease
import java.nio.file.Path
import java.time.Clock

@Inject
public class AppRunService(
  private val targets: AppRunTargetResolver,
  private val config: AppRunConfigPort,
  private val processes: AppRunProcessPort,
  private val leases: QaDeviceLeasePort,
  private val branchWorktrees: BranchWorktreeResources,
  private val identifiers: IdentifierGeneratorPort,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) {
  public fun run(
    request: AppRunRequest,
    listener: AppRunListener,
  ): AppRunOutcome {
    val cancellation = request.cancellation
    if (cancellation.isCancelled()) return AppRunOutcome.Stopped
    val invocationId = AppRunScriptContract.INVOCATION_PREFIX + request.issueKey + "-" + identifiers.randomToken()
    val holder = BranchWorktreeHolder(BranchWorktreeHolderKind.APP_RUN, invocationId)
    listener.onProgress(AppRunProgress(AppRunStep.RESOLVING))
    if (cancellation.isCancelled()) return AppRunOutcome.Stopped
    val target = targets.resolve(request.repoRoot, request.issueKey, request.device, holder)
    return try {
      when {
        cancellation.isCancelled() -> AppRunOutcome.Stopped
        target is AppRunTarget.Refused -> AppRunOutcome.Refused(target.reason, target.message)
        target is AppRunTarget.Ready -> runReady(request, listener, invocationId, target)
        else -> error("Unknown app-run target")
      }
    } catch (failure: IllegalStateException) {
      if (cancellation.isCancelled()) {
        AppRunOutcome.Stopped
      } else {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "seam=app-run.process outcome=start_failed issue=${request.issueKey}",
          failure,
        )
        AppRunOutcome.Failed(null, failure.message ?: "Could not start app-run process.")
      }
    } finally {
      if (target is AppRunTarget.Ready && target.detachedHolder != null) {
        releaseWorktree(target.checkout, target.detachedHolder)
      }
    }
  }

  private fun runReady(
    request: AppRunRequest,
    listener: AppRunListener,
    invocationId: String,
    target: AppRunTarget.Ready,
  ): AppRunOutcome {
    val cancellation = request.cancellation
    if (cancellation.isCancelled()) return AppRunOutcome.Stopped
    val appConfig =
      when (val result = readConfig(target.checkout, cancellation)) {
        is ConfigResult.Ready -> result.value
        is ConfigResult.Done -> return result.outcome
      }
    val serial =
      when (val result = serialFor(request, listener, target)) {
        is SerialResult.Ready -> result.value
        is SerialResult.Done -> return result.outcome
      }
    return leaseAndRun(request, listener, invocationId, target, RunDevice(appConfig, serial))
  }

  private data class RunDevice(val config: AppRunConfig, val serial: String)

  private fun leaseAndRun(
    request: AppRunRequest,
    listener: AppRunListener,
    invocationId: String,
    target: AppRunTarget.Ready,
    device: RunDevice,
  ): AppRunOutcome {
    val cancellation = request.cancellation
    if (cancellation.isCancelled()) return AppRunOutcome.Stopped
    listener.onProgress(AppRunProgress(AppRunStep.LEASING))
    if (cancellation.isCancelled()) return AppRunOutcome.Stopped
    val acquired = leases.acquire(request.repoRoot, QaDeviceFingerprint.of(device.serial), invocationId)
    return when (acquired) {
      is QaLeaseResult.Contended ->
        if (cancellation.isCancelled()) {
          AppRunOutcome.Stopped
        } else {
          AppRunOutcome.Contended(request.device, acquired.ownerInvocationId)
        }
      is QaLeaseResult.Acquired ->
        try {
          runScript(request, listener, target, device.config, device.serial)
        } finally {
          releaseLease(request.repoRoot, acquired.lease)
        }
    }
  }

  private fun runScript(
    request: AppRunRequest,
    listener: AppRunListener,
    target: AppRunTarget.Ready,
    appConfig: AppRunConfig,
    serial: String,
  ): AppRunOutcome {
    val cancellation = request.cancellation
    if (cancellation.isCancelled()) return AppRunOutcome.Stopped
    val environment =
      buildMap {
        put(AppRunScriptContract.ENV_CHECKOUT, target.checkout.toString())
        put(AppRunScriptContract.ENV_SERIAL, serial)
        put(AppRunScriptContract.ENV_PLATFORM, AppRunScriptContract.PLATFORM_ANDROID)
        put(AppRunScriptContract.ENV_VARIANT, appConfig.variant)
        appConfig.module?.let { put(AppRunScriptContract.ENV_MODULE, it) }
      }
    val output = AppRunScriptOutput(listener)
    val exit =
      execute(
        AppRunProcessSpec(
          listOf(
            "bash",
            target.recipe.scriptPath.toString(),
            AppRunScriptContract.RUN,
          ),
          target.checkout,
          environment,
        ),
        cancellation,
        output::accept,
      )
        ?: return AppRunOutcome.Stopped
    return if (exit.stopped || cancellation.isCancelled()) {
      AppRunOutcome.Stopped
    } else {
      mapExit(
        request,
        serial,
        output,
        exit,
      )
    }
  }

  private sealed interface ConfigResult {
    data class Ready(val value: AppRunConfig) : ConfigResult

    data class Done(val outcome: AppRunOutcome) : ConfigResult
  }

  private fun readConfig(
    checkout: Path,
    cancellation: AppRunCancellation,
  ): ConfigResult =
    try {
      ConfigResult.Ready(config.read(checkout))
    } catch (failure: SkillBillRuntimeException) {
      when {
        cancellation.isCancelled() -> ConfigResult.Done(AppRunOutcome.Stopped)
        failure.code !is AppRunFailureCode -> throw failure
        else ->
          ConfigResult.Done(
            AppRunOutcome.Refused(AppRunRefusalReason.INVALID_CONFIG, failure.message ?: "App-run config is invalid."),
          )
      }
    }

  private sealed interface SerialResult {
    data class Ready(val value: String) : SerialResult

    data class Done(val outcome: AppRunOutcome) : SerialResult
  }

  private fun serialFor(
    request: AppRunRequest,
    listener: AppRunListener,
    target: AppRunTarget.Ready,
  ): SerialResult {
    if (request.device.kind != QaNamedDeviceKind.AVD) return SerialResult.Ready(request.device.value)
    val cancellation = request.cancellation
    if (cancellation.isCancelled()) return SerialResult.Done(AppRunOutcome.Stopped)
    listener.onProgress(AppRunProgress(AppRunStep.BOOTING))
    val output = AppRunScriptOutput(listener)
    val environment =
      mapOf(
        AppRunScriptContract.ENV_AVD to request.device.value,
        AppRunScriptContract.ENV_CHECKOUT to target.checkout.toString(),
        AppRunScriptContract.ENV_PLATFORM to AppRunScriptContract.PLATFORM_ANDROID,
      )
    val exit =
      execute(
        AppRunProcessSpec(
          listOf(
            "bash",
            target.recipe.scriptPath.toString(),
            AppRunScriptContract.BOOT,
          ),
          target.checkout,
          environment,
        ),
        cancellation,
        output::accept,
      )
        ?: return SerialResult.Done(AppRunOutcome.Stopped)
    val outcome =
      when {
        cancellation.isCancelled() || exit.stopped -> AppRunOutcome.Stopped
        exit.exitCode == AppRunScriptContract.EXIT_BOOT_FAILED -> AppRunOutcome.BootFailed(request.device)
        exit.exitCode != AppRunScriptContract.EXIT_OK ->
          AppRunOutcome.Failed(
            exit.exitCode,
            output.failure ?: "boot exited ${exit.exitCode}",
          )
        output.serial == null -> {
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "seam=app-run.script outcome=no_serial issue=${request.issueKey}",
          )
          AppRunOutcome.Failed(0, "boot printed no serial")
        }
        else -> null
      }
    return if (outcome != null) SerialResult.Done(outcome) else SerialResult.Ready(requireNotNull(output.serial))
  }

  private fun mapExit(
    request: AppRunRequest,
    serial: String,
    output: AppRunScriptOutput,
    exit: AppRunProcessExit,
  ): AppRunOutcome {
    return when (exit.exitCode) {
      AppRunScriptContract.EXIT_OK ->
        output.launched?.let {
          AppRunOutcome.Running(
            request.device,
            serial,
            it,
            clock.instant(),
          )
        } ?: run {
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "seam=app-run.script outcome=no_launched_marker issue=${request.issueKey}",
          )
          AppRunOutcome.Failed(0, "script exited 0 without a launched marker")
        }
      AppRunScriptContract.EXIT_MODULE_UNRESOLVED ->
        AppRunOutcome.ModuleUnresolved(output.candidates.orEmpty())
      AppRunScriptContract.EXIT_BUILD_FAILED ->
        AppRunOutcome.BuildFailed(
          request.device,
          output.failedTask ?: "unknown",
        )
      AppRunScriptContract.EXIT_LAUNCH_FAILED ->
        AppRunOutcome.LaunchFailed(
          request.device,
          output.failure ?: "unknown",
        )
      else -> AppRunOutcome.Failed(exit.exitCode, output.failure ?: "script exited ${exit.exitCode}")
    }
  }

  private fun releaseLease(
    repoRoot: Path,
    lease: QaDeviceLease,
  ) {
    try {
      when (val result = leases.release(repoRoot, lease)) {
        is QaLeaseReleaseOutcome.Failed ->
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "seam=app-run.lease outcome=failed detail=${result.detail}",
          )
        is QaLeaseReleaseOutcome.NotHeld ->
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "seam=app-run.lease outcome=not_held",
          )
        is QaLeaseReleaseOutcome.Released -> Unit
      }
    } catch (failure: SkillBillRuntimeException) {
      if (!failure.isQaDeviceLeaseFailure) throw failure
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, "seam=app-run.lease outcome=failed", failure)
    }
  }

  private fun releaseWorktree(
    path: Path,
    holder: BranchWorktreeHolder,
  ) {
    catchingNonCancellation { branchWorktrees.release(path, holder) }.fold(
      onSuccess = { result ->
        when (result) {
          is BranchWorktreeRelease.Kept ->
            RuntimeDiagnosticsBestEffortWarning.record(
              diagnostics,
              "seam=app-run.worktree outcome=kept detail=${result.detail}",
            )
          BranchWorktreeRelease.StillHeld ->
            RuntimeDiagnosticsBestEffortWarning.record(
              diagnostics,
              "seam=app-run.worktree outcome=still_held path=$path",
            )
          BranchWorktreeRelease.NotHeld ->
            RuntimeDiagnosticsBestEffortWarning.record(
              diagnostics,
              "seam=app-run.worktree outcome=not_held path=$path",
            )
          is BranchWorktreeRelease.Removed -> Unit
        }
      },
      onFailure = { failure ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "seam=app-run.worktree outcome=release_failed path=$path",
          failure,
        )
      },
    )
  }

  private fun execute(
    spec: AppRunProcessSpec,
    cancellation: AppRunCancellation,
    onLine: (String) -> Unit,
  ): AppRunProcessExit? {
    if (cancellation.isCancelled()) return null
    val process = processes.start(spec, onLine)
    cancellation.attach(process)
    return try {
      process.awaitExit()
    } finally {
      cancellation.detach()
    }
  }
}
