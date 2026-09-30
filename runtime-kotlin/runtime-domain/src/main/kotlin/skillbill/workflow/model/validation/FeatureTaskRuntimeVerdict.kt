package skillbill.workflow.model.validation

import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError

data class FeatureTaskRuntimeVerdict(
  val wireValue: String,
) {
  init {
    require(wireValue.isNotBlank()) { "FeatureTaskRuntimeVerdict.wireValue must be non-blank." }
  }

  companion object {
    val ADVANCE: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("advance")

    val APPROVED: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("approved")

    val CHANGES_REQUESTED: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("changes_requested")

    val FINDINGS_VERIFIED: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("findings_verified")

    val NO_FINDINGS_VERIFIED: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("no_findings_verified")

    val REVIEW_CAP_REACHED: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("review_cap_reached")

    val REVIEW_SKIPPED_BY_USER: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("review_skipped_by_user")

    val SATISFIED: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("satisfied")

    val GAPS_FOUND: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("gaps_found")

    val RECORD_REJECTED: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("record_rejected")

    val REPAIR_PLANNED: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("repair_planned")

    val ESCALATED: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("escalated")

    val PROGRESS: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("progress")

    val NO_PROGRESS: FeatureTaskRuntimeVerdict = FeatureTaskRuntimeVerdict("no_progress")

    val REMOVED_VERDICTS: Set<FeatureTaskRuntimeVerdict> = setOf(REPAIR_PLANNED, ESCALATED, GAPS_FOUND)

    fun rejectRemovedVerdict(
      value: String,
      context: String,
    ): FeatureTaskRuntimeVerdict {
      val verdict = fromWire(value)
      if (verdict in REMOVED_VERDICTS) {
        throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime verdict '$value' is removed ($context); records naming it must be regenerated.",
        )
      }
      return verdict
    }

    val AUDIT_VERDICTS: Set<FeatureTaskRuntimeVerdict> = setOf(SATISFIED)

    fun fromWire(value: String): FeatureTaskRuntimeVerdict =
      value.takeIf(String::isNotBlank)?.let(::FeatureTaskRuntimeVerdict)
        ?: throw InvalidWorkflowStateSchemaError(
          "Feature-task-runtime verdict wire value must be a non-blank string, was '$value'.",
        )
  }
}
