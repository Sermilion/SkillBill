package skillbill.engine.goalrunner.telemetry

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.diagnostics.RuntimeDiagnostics

internal object GoalRunnerBestEffortEmission {
  const val MAX_DIAGNOSTIC_MESSAGE_LENGTH: Int = 240

  fun boundedMessage(message: String): String = message.take(MAX_DIAGNOSTIC_MESSAGE_LENGTH)

  inline fun record(
    diagnostics: RuntimeDiagnostics,
    write: () -> Boolean,
    missingMessage: () -> String,
    failureMessage: (Throwable) -> String,
  ) {
    val result =
      try {
        catchingNonCancellation(write)
      } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw interrupted
      }
    when (val failure = result.exceptionOrNull()) {
      null ->
        if (!result.getOrThrow()) {
          recordWarning(diagnostics, missingMessage())
        }
      else -> {
        recordWarning(diagnostics, failureMessage(failure), failure)
      }
    }
  }

  fun recordWarning(
    diagnostics: RuntimeDiagnostics,
    message: String,
    error: Throwable? = null,
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(diagnostics, message, error)
  }
}
