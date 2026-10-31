package skillbill.engine.diagnostics

import skillbill.error.core.catchingNonCancellation
import skillbill.ports.diagnostics.RuntimeDiagnostics

public object RuntimeDiagnosticsBestEffortWarning {
  public fun record(
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
