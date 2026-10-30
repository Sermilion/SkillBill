package skillbill.engine.diagnostics

import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.ports.db.DatabaseCheckpoint
import skillbill.ports.diagnostics.RuntimeDiagnostics

internal fun DatabaseCheckpoint.truncateWalBestEffort(
  diagnostics: RuntimeDiagnostics,
  boundary: String,
) {
  runCatching { truncateWal() }.exceptionOrNull()?.let { error ->
    error.rethrowIfCooperativeCancellationOrInterruption()
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
