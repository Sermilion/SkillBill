package skillbill.infrastructure.sqlite.core.ops

import skillbill.ports.diagnostics.RuntimeDiagnostics

internal fun RuntimeDiagnostics.recordMigrationNormalization(
  seam: String,
  parentWorkflowId: String,
  movedArtifactKeys: List<String>,
) {
  warning(
    "skillbill sqlite: record_kind=migration; seam=$seam; parent_workflow_id=$parentWorkflowId; " +
      "moved_artifact_keys=${movedArtifactKeys.joinToString(",")}",
  )
}

internal fun RuntimeDiagnostics.recordDegradedValue(
  seam: String,
  expected: String,
  used: String,
  error: Throwable? = null,
) {
  warning(
    "skillbill sqlite: degraded $seam; expected=$expected; used=$used",
    error,
  )
}
