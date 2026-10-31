package skillbill.engine.diagnostics

import skillbill.error.core.catchingNonCancellation
import skillbill.ports.diagnostics.RuntimeDiagnostics

internal object RuntimeDiagnosticsBestEffortWarning {
  fun record(
    diagnostics: RuntimeDiagnostics,
    message: String,
    cause: Throwable? = null,
  ) {
    catchingNonCancellation {
      if (cause == null) {
        diagnostics.warning(message)
      } else {
        diagnostics.warning(message, cause)
      }
    }
  }
}
