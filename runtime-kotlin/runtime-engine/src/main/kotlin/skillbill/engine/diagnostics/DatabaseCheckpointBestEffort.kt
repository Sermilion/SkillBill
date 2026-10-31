package skillbill.engine.diagnostics

import skillbill.error.core.catchingNonCancellation
import skillbill.error.core.isCooperativeCancellation
import skillbill.ports.db.DatabaseCheckpoint
import skillbill.ports.diagnostics.RuntimeDiagnostics

internal fun DatabaseCheckpoint.truncateWalBestEffort(
  diagnostics: RuntimeDiagnostics,
  boundary: String,
) {
  catchingNonCancellation { truncateWal() }.exceptionOrNull()?.let { error ->
    if (error.isCooperativeCancellation()) throw error
    val cause =
      (
        error.message?.takeIf(
          String::isNotBlank,
        ) ?: error::class.simpleName.orEmpty()
      ).take(MAX_CHECKPOINT_CAUSE_LENGTH)
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=wal_checkpoint boundary=$boundary value_expected=truncated value_used=failed cause=$cause",
      error,
    )
  }
}

private const val MAX_CHECKPOINT_CAUSE_LENGTH = 256
