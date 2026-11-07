package skillbill.engine.featuretask.validation

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.execution.ValidationGateCommandFamily
import skillbill.engine.featuretask.model.execution.ValidationGateCyclePhase
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.process.ShutdownHookPort
import skillbill.ports.process.ShutdownHookRegistration
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal fun FeatureTaskRuntimeValidationGateProgress.interruptedInitialDiscovery(
  commandFamily: ValidationGateCommandFamily,
): Boolean =
  pendingExternalInterrupted &&
    pendingExternalOperation?.startsWith(gateCommandIdentityPrefix(commandFamily, INITIAL_DISCOVERY)) == true

internal fun gateCommandIdentityPrefix(
  commandFamily: ValidationGateCommandFamily,
  phase: ValidationGateCyclePhase,
): String = "${commandFamily.name.lowercase()}:${phase.name.lowercase()}:"

private val INITIAL_DISCOVERY = ValidationGateCyclePhase.INITIAL_DISCOVERY

internal class GateCommandShutdownGuard(
  private val shutdownHooks: ShutdownHookPort,
  private val diagnostics: RuntimeDiagnostics,
  private val markInterrupted: () -> Unit,
) {
  private val lock = ReentrantLock()
  private val commandExited = CountDownLatch(1)
  private var settled = false
  private var interrupted = false

  fun <T> run(command: () -> T): T {
    val gateThread = Thread.currentThread()
    val registration = shutdownHooks.register { onShutdown(gateThread) }
    try {
      return command()
    } finally {
      val interruptedDuringCommand =
        lock.withLock {
          if (!interrupted) settled = true
          interrupted
        }
      if (interruptedDuringCommand) parkUntilHalt()
      unregister(registration)
    }
  }

  private fun onShutdown(gateThread: Thread) {
    lock.withLock {
      if (settled) return
      interrupted = true
      catchingNonCancellation(markInterrupted).onFailure { failure ->
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "seam=gate_command_shutdown value_expected=interrupted value_used=pending " +
            "reason=interrupted gate command could not be recorded",
          failure,
        )
      }
    }
    gateThread.interrupt()
    try {
      commandExited.await(COMMAND_EXIT_WAIT_MILLIS, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  private fun parkUntilHalt(): Nothing {
    commandExited.countDown()
    while (true) {
      try {
        Thread.sleep(Long.MAX_VALUE)
      } catch (_: InterruptedException) {
      }
    }
  }

  private fun unregister(registration: ShutdownHookRegistration) {
    catchingNonCancellation { registration.unregister() }.onFailure { failure ->
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "seam=gate_command_shutdown_cleanup value_expected=unregistered value_used=retained " +
          "reason=gate command shutdown hook could not be removed",
        failure,
      )
    }
  }

  private companion object {
    const val COMMAND_EXIT_WAIT_MILLIS: Long = 2_000
  }
}
