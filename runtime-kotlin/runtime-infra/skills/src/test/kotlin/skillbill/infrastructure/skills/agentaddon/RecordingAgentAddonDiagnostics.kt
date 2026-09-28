package skillbill.infrastructure.skills.agentaddon

import skillbill.ports.diagnostics.RuntimeDiagnostics

internal class RecordingAgentAddonDiagnostics : RuntimeDiagnostics {
  val warnings = mutableListOf<String>()

  val migrationRecords: List<String> get() = warnings.filter { it.contains("record_kind=migration") }

  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    warnings += message
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}
