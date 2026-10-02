package skillbill.application.runtimepersistence

import me.tatarka.inject.annotations.Inject
import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.failureCodeLabel
import skillbill.error.featuretask.RuntimeOwnedPersistenceFailureCode
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork

fun runtimeOwnedFactUnavailable(
  code: RuntimeOwnedPersistenceFailureCode,
  seam: String,
  expected: String,
  cause: Exception,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    code,
    "Runtime-owned persistence fact '$expected' could not be established at $seam: ${causeOf(cause)}",
    cause,
  )

private fun causeOf(error: Exception): String =
  error.message?.takeIf(String::isNotBlank) ?: error.failureCodeLabel() ?: error::class.simpleName.orEmpty()

private fun Throwable.isOwnedFactUnavailable(): Boolean =
  (this as? SkillBillRuntimeException)?.code == RuntimeOwnedPersistenceFailureCode.REVIEW_FACT_UNAVAILABLE

@Inject
class RuntimeOwnedPersistenceBoundary(
  private val database: DatabaseSessionFactory,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun <T> read(block: (UnitOfWork) -> T): T = database.read { unitOfWork -> block(unitOfWork) }

  fun <T> transaction(block: (UnitOfWork) -> T): T = database.transaction { unitOfWork -> block(unitOfWork) }

  fun <T> requiredRead(
    seam: String,
    expected: String,
    block: (UnitOfWork) -> T,
  ): T =
    invokeOrHandle({ fail(seam, expected, "read_error", it) }) {
      read(block)
    }

  fun <T> requiredWrite(
    seam: String,
    expected: String,
    block: (UnitOfWork) -> T,
  ): T =
    invokeOrHandle({ fail(seam, expected, "blocked", it) }) {
      transaction(block)
    }

  fun <T> optionalRead(
    seam: String,
    expected: String,
    fallback: T,
    block: (UnitOfWork) -> T,
  ): T =
    invokeOrHandle({
      recordFailure(seam, expected, "degraded", it)
      fallback
    }) {
      read(block)
    }

  fun <T> optionalWrite(
    seam: String,
    expected: String,
    fallback: T,
    block: (UnitOfWork) -> T,
  ): T =
    invokeOrHandle({
      recordFailure(seam, expected, "degraded", it)
      fallback
    }) {
      transaction(block)
    }

  private inline fun <T> invokeOrHandle(
    onFailure: (Exception) -> T,
    block: () -> T,
  ): T {
    val outcome = runCatching(block)
    val error = outcome.exceptionOrNull() ?: return outcome.getOrThrow()
    error.rethrowIfCooperativeCancellationOrInterruption()
    if (error is Exception && !error.isOwnedFactUnavailable()) {
      return onFailure(error)
    }
    throw error
  }

  private fun fail(
    seam: String,
    expected: String,
    used: String,
    error: Exception,
  ): Nothing {
    recordFailure(seam, expected, used, error)
    throw runtimeOwnedFactUnavailable(RuntimeOwnedPersistenceFailureCode.REVIEW_FACT_UNAVAILABLE, seam, expected, error)
  }

  private fun recordFailure(
    seam: String,
    expected: String,
    used: String,
    error: Exception,
  ) {
    val cause = causeOf(error)
    runCatching {
      diagnostics.warning(
        "seam=$seam value_expected=$expected value_used=$used cause=$cause",
      )
    }
  }
}
