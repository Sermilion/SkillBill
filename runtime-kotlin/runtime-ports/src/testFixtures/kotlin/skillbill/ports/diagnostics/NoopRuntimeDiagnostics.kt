package skillbill.ports.diagnostics

object NoopRuntimeDiagnostics : RuntimeDiagnostics {
  override fun info(message: String) = Unit

  override fun warning(
    message: String,
    error: Throwable?,
  ) = Unit

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}
