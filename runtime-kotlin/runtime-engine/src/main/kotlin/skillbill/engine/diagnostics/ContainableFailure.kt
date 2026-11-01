package skillbill.engine.diagnostics

public fun containableFailure(failure: Throwable): Exception = failure as? Exception ?: throw failure

internal fun containableUnlessInterrupted(failure: Throwable): Exception =
  if (failure is Exception && !Thread.currentThread().isInterrupted) failure else throw failure
