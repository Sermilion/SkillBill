package skillbill.workflow.taskruntime.model.persistence

internal data class FeatureTaskRuntimePriorGapMemory(
  val round: Int,
  val priorAuditValues: List<String>,
) {
  companion object {
    const val FIELD_ROUND: String = "round"
    const val FIELD_PRIOR_AUDIT_VALUES: String = "prior_audit_values"

    internal fun fromMap(raw: Map<String, Any?>): FeatureTaskRuntimePriorGapMemory {
      val round =
        (raw[FIELD_ROUND] as? Number)?.toInt()
          ?: (raw[FIELD_ROUND] as? String)?.toIntOrNull()
          ?: throw IllegalArgumentException("$FIELD_ROUND must decode to an integer.")
      require(round >= 1) { "FeatureTaskRuntimePriorGapMemory.round must be >= 1, was $round." }
      val priorAuditValues = decodePriorAuditValues(raw[FIELD_PRIOR_AUDIT_VALUES])
      return FeatureTaskRuntimePriorGapMemory(round = round, priorAuditValues = priorAuditValues)
    }

    private fun decodePriorAuditValues(raw: Any?): List<String> =
      (raw as? List<*>)?.map {
        (it as? String)?.takeIf(String::isNotBlank)
          ?: throw IllegalArgumentException("$FIELD_PRIOR_AUDIT_VALUES entries must be non-blank strings.")
      } ?: throw IllegalArgumentException("$FIELD_PRIOR_AUDIT_VALUES must decode to a list of strings.")
  }
}
