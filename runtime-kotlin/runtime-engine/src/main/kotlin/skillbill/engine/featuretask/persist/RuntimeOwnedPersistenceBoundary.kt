package skillbill.engine.featuretask.persist

import me.tatarka.inject.annotations.Inject
import skillbill.application.runtimepersistence.runtimeOwnedFactUnavailable
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.error.core.failureCodeLabel
import skillbill.error.featuretask.RuntimeOwnedPersistenceFailureCode
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork

public fun Throwable.isOwnedFactUnavailable(): Boolean =
  (this as? SkillBillRuntimeException)?.code == RuntimeOwnedPersistenceFailureCode.FACT_UNAVAILABLE

@Inject
public class RuntimeOwnedPersistenceBoundary(
  private val database: DatabaseSessionFactory,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun <T> read(block: (UnitOfWork) -> T): T = database.read { unitOfWork -> block(unitOfWork) }

  internal fun <T> transaction(block: (UnitOfWork) -> T): T = database.transaction { unitOfWork -> block(unitOfWork) }

  internal fun <T> requiredRead(
    seam: String,
    expected: String,
    block: (UnitOfWork) -> T,
  ): T =
    invokeOrHandle({ fail(seam, expected, "read_error", it) }) {
      read(block)
    }

  internal fun <T> requiredWrite(
    seam: String,
    expected: String,
    block: (UnitOfWork) -> T,
  ): T =
    invokeOrHandle({ fail(seam, expected, "blocked", it) }) {
      transaction(block)
    }

  internal fun <T> optionalRead(
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

  internal fun <T> optionalWrite(
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
    val outcome = catchingNonCancellation(block)
    val error = outcome.exceptionOrNull() ?: return outcome.getOrThrow()
    if (error is Exception && !error.isOwnedFactUnavailable()) {
      return onFailure(error)
    }
    throw error
  }

  internal fun <T> requiredOwningWrite(
    seam: String,
    expected: String,
    block: (UnitOfWork) -> T,
  ): OwningTransactionResult<T> =
    invokeOrHandle({ fail(seam, expected, "blocked", it) }) {
      database.owningTransaction(block)
    }

  private fun fail(
    seam: String,
    expected: String,
    used: String,
    error: Exception,
  ): Nothing {
    recordFailure(seam, expected, used, error)
    throw runtimeOwnedFactUnavailable(RuntimeOwnedPersistenceFailureCode.FACT_UNAVAILABLE, seam, expected, error)
  }

  private fun recordFailure(
    seam: String,
    expected: String,
    used: String,
    error: Exception,
  ) {
    val cause = causeOf(error)
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=$seam value_expected=$expected value_used=$used cause=$cause",
    )
  }

  private fun causeOf(error: Exception): String =
    error.message?.takeIf(String::isNotBlank) ?: error.failureCodeLabel() ?: error::class.simpleName.orEmpty()
}
