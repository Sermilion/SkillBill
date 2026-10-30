package skillbill.application

import skillbill.error.core.isCooperativeCancellation

fun Throwable.rethrowIfCooperativeCancellationOrInterruption(): Nothing? {
  if (isCooperativeCancellation()) throw this
  return null
}

inline fun <T> Result<T>.getOrElseUnlessCooperative(onFailure: () -> T): T {
  exceptionOrNull()?.rethrowIfCooperativeCancellationOrInterruption()
  return getOrElse { onFailure() }
}
