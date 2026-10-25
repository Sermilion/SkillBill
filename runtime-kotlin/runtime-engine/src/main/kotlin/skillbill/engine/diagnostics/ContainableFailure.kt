package skillbill.engine.diagnostics

import java.util.concurrent.CancellationException

internal fun containableFailure(failure: Throwable): Exception =
  if (failure is Exception && failure !is CancellationException && failure !is InterruptedException) {
    failure
  } else {
    throw failure
  }
