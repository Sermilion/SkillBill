package skillbill.engine.diagnostics.block

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.util.concurrent.CancellationException

internal inline fun recoverableFailureOf(block: () -> Unit): Exception? {
  val failure = runCatching(block).exceptionOrNull() ?: return null
  if (failure is CancellationException || failure !is Exception) throw failure
  return failure
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
