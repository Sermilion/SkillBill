package skillbill.ports.diagnostics

interface RuntimeDiagnostics {
  fun warning(
    message: String,
    error: Throwable? = null,
  )

  fun error(
    message: String,
    error: Throwable? = null,
  )

  fun info(message: String) {
  }
}
