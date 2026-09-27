package skillbill.engine.featuretask.slot.writehistory

import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeMeasuredFactKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.ports.diagnostics.RuntimeDiagnostics

internal class WriteHistoryMeasurement(private val diagnostics: RuntimeDiagnostics) {
  fun facts(manifest: FeatureTaskRuntimePhaseFileManifest): Map<String, Any> =
    mapOf(
      FeatureTaskRuntimeMeasuredFactKeys.CHANGED_PATHS to manifest.introduced,
      FeatureTaskRuntimeMeasuredFactKeys.HISTORY_WRITTEN to written(manifest, HISTORY_FILE),
      FeatureTaskRuntimeMeasuredFactKeys.DECISIONS_RECORDED to written(manifest, DECISIONS_FILE),
    )

  private fun written(
    manifest: FeatureTaskRuntimePhaseFileManifest,
    file: String,
  ): Any =
    when {
      manifest.introduced.any { it.isFile(file) } -> true
      manifest.before.any { it.isFile(file) } -> {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "write_history cannot measure whether it wrote $file: the file was already dirty before the step",
        )
        FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN
      }
      else -> false
    }

  private fun String.isFile(file: String): Boolean = this == file || endsWith("/$file")

  companion object {
    const val HISTORY_FILE: String = "agent/history.md"
    const val DECISIONS_FILE: String = "agent/decisions.md"
  }
}
