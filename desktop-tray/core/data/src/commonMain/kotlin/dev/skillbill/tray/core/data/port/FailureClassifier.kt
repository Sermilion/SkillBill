package dev.skillbill.tray.core.data.port

/** How a runtime exception describes the store, before the repositories turn it into a typed problem. */
enum class RuntimeFailure { MISSING, BUSY, CORRUPT, INCOMPATIBLE_SCHEMA, ACCESS, OTHER }

/** A failure raised by the SQLite provider; [code] is its native error code when the message carries one. */
class ProviderFailure(val code: Int?)

/** Recognises platform and runtime exceptions so the shared repositories never name their types. */
interface FailureClassifier {
  fun runtimeFailure(error: Throwable): RuntimeFailure?

  fun providerFailure(error: Throwable): ProviderFailure?

  fun isIoFailure(error: Throwable): Boolean

  fun isAccessDenied(error: Throwable): Boolean

  /** True when [error] means change observation, not the data itself, is unavailable. */
  fun isObserverFailure(error: Throwable): Boolean

  /** A lowercase, low-cardinality name for [error] used in diagnostics categories. */
  fun diagnosticName(error: Throwable): String
}

/** Change observation ended while its store file is still present, so no further changes would be seen. */
class ObservationEndedException : RuntimeException("Database change observation ended.")

/** Change observation could not be established or has failed, so a view would silently go stale. */
class ObservationUnavailableException(cause: Throwable) : RuntimeException(cause)
