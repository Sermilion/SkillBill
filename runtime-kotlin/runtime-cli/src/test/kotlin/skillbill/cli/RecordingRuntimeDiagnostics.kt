package skillbill.cli

import skillbill.ports.diagnostics.RuntimeDiagnostics

internal class RecordingRuntimeDiagnostics : RuntimeDiagnostics {
  val warnings = mutableListOf<String>()
  val errors = mutableListOf<String>()

  override fun info(message: String) = Unit

  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    warnings += message
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) {
    errors += message
  }
}
