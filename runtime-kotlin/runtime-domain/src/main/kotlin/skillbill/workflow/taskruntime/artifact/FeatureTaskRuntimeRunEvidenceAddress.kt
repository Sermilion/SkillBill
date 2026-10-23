package skillbill.workflow.taskruntime.artifact

private const val SAFE_SEGMENT_PUNCTUATION: String = "._-"

object FeatureTaskRuntimeRunEvidenceAddress {
  const val STORE_ROOT: String = "run-evidence"

  fun pathSegment(raw: String): String {
    val sanitized =
      raw.map { char ->
        if (char.isLetterOrDigit() || char in SAFE_SEGMENT_PUNCTUATION) char else '_'
      }.joinToString("")
    return if (sanitized.isBlank() || sanitized.all { it == '.' }) "_$sanitized" else sanitized
  }

  fun workflowStoreRoot(workflowId: String): String = "$STORE_ROOT/${pathSegment(workflowId)}"
}
