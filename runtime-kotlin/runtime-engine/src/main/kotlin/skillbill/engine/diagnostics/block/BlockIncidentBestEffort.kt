package skillbill.engine.diagnostics.block

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.diagnostics.containableFailure
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.diagnostics.RuntimeDiagnostics

internal inline fun recoverableFailureOf(block: () -> Unit): Exception? {
  return catchingNonCancellation(block).exceptionOrNull()?.let(::containableFailure)
}

internal inline fun <T> RuntimeDiagnostics.bestEffort(
  message: String,
  fallback: T,
  block: () -> T,
): T {
  var result = fallback
  val failure = recoverableFailureOf { result = block() }
  if (failure != null) RuntimeDiagnosticsBestEffortWarning.record(this, message, failure)
  return result
}
