package skillbill.application.uninstall

import skillbill.ports.diagnostics.RuntimeDiagnostics

internal class UninstallMutationRecorder(
  private val diagnostics: RuntimeDiagnostics,
) {
  private val failures = mutableListOf<String>()

  fun recordFailure(
    description: String,
    error: Throwable,
  ) {
    diagnostics.error("uninstall mutation failed: $description", error)
    failures += "$description: ${error.message.orEmpty()}"
  }

  fun recordFailure(
    description: String,
    detail: String,
  ) {
    diagnostics.error("uninstall mutation failed: $description")
    failures += "$description: $detail"
  }

  fun failed(): Boolean = failures.isNotEmpty()

  fun failureMessages(): List<String> = failures.toList()
}
