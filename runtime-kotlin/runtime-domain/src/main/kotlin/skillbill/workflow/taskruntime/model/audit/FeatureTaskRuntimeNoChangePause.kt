package skillbill.workflow.taskruntime.model.audit

import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.workflow.model.persistence.artifact.durableArtifactMapReader

data class FeatureTaskRuntimeNoChangePause(
  val reason: FeatureTaskRuntimeNoChangeReason,
  val criteria: List<FeatureTaskRuntimeNoChangeCriterion>,
  val citations: List<String>,
  val boundaryTrace: String,
  val owningSystem: String?,
  val suggestedHandoff: String,
  val auditSummary: String,
  val operatorDecision: String? = null,
  val operatorInstructions: String? = null,
) {
  init {
    require(boundaryTrace.isNotBlank()) { "FeatureTaskRuntimeNoChangePause.boundaryTrace must be non-blank." }
    require(operatorDecision == null || operatorDecision in OPERATOR_DECISIONS) {
      "FeatureTaskRuntimeNoChangePause.operatorDecision must be one of $OPERATOR_DECISIONS, was '$operatorDecision'."
    }
  }

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf(
      "record_kind" to RECORD_KIND,
      "reason" to reason.wireValue,
      "criteria" to
        criteria.map { criterion ->
          mapOf(
            "criterion_id" to criterion.criterionId,
            "verdict" to criterion.verdict.wireValue,
            "evidence" to criterion.evidence,
          )
        },
      "citations" to citations,
      "boundary_trace" to boundaryTrace,
      "owning_system" to owningSystem,
      "suggested_handoff" to suggestedHandoff,
      "audit_summary" to auditSummary,
      "operator_decision" to operatorDecision,
      "operator_instructions" to operatorInstructions,
    )

  companion object {
    const val RECORD_KIND: String = "no_change_pause"

    val OPERATOR_DECISIONS: Set<String> = setOf("accept_and_advance", "retry_fix", "abandon_subtask")

    private val EXPECTED_FIELDS: Set<String> =
      setOf(
        "record_kind",
        "reason",
        "criteria",
        "citations",
        "boundary_trace",
        "owning_system",
        "suggested_handoff",
        "audit_summary",
        "operator_decision",
        "operator_instructions",
      )

    fun fromClaim(
      claim: FeatureTaskRuntimeNoChangeClaim,
      auditSummary: String,
    ): FeatureTaskRuntimeNoChangePause =
      FeatureTaskRuntimeNoChangePause(
        reason = claim.reason,
        criteria = claim.criteria,
        citations = claim.citations,
        boundaryTrace = claim.boundaryTrace,
        owningSystem = claim.owningSystem,
        suggestedHandoff = claim.suggestedHandoff,
        auditSummary = auditSummary,
      )

    internal fun fromArtifactMap(raw: Map<String, Any?>): FeatureTaskRuntimeNoChangePause {
      if (raw.keys != EXPECTED_FIELDS) {
        throw invalidWorkflowStateSchemaError(
          "Feature-task-runtime no-change pause artifact must carry exactly the fields " +
            "${EXPECTED_FIELDS.sorted().joinToString()}.",
        )
      }
      if (raw["record_kind"] != RECORD_KIND) {
        throw invalidWorkflowStateSchemaError(
          "Feature-task-runtime no-change pause artifact must have kind '$RECORD_KIND'.",
        )
      }
      val reader = durableArtifactMapReader(raw)
      val reason =
        FeatureTaskRuntimeNoChangeReason.fromWireOrNull(reader.requiredString("reason"))
          ?: throw invalidWorkflowStateSchemaError("Feature-task-runtime no-change pause has an unknown reason.")
      val operatorDecision = reader.optionalString("operator_decision")
      if (operatorDecision != null && operatorDecision !in OPERATOR_DECISIONS) {
        throw invalidWorkflowStateSchemaError(
          "Feature-task-runtime no-change pause has an unknown operator_decision '$operatorDecision'.",
        )
      }
      return FeatureTaskRuntimeNoChangePause(
        reason = reason,
        criteria = decodeCriteria(reader.optionalList("criteria").orEmpty()),
        citations = reader.requiredStringList("citations"),
        boundaryTrace = reader.requiredString("boundary_trace"),
        owningSystem = reader.optionalString("owning_system"),
        suggestedHandoff = reader.requiredString("suggested_handoff"),
        auditSummary = reader.requiredString("audit_summary"),
        operatorDecision = operatorDecision,
        operatorInstructions = reader.optionalString("operator_instructions"),
      )
    }

    private fun decodeCriteria(entries: List<*>): List<FeatureTaskRuntimeNoChangeCriterion> =
      entries.map { entry ->
        val map =
          JsonCodec.anyToStringAnyMap(entry) ?: throw invalidWorkflowStateSchemaError(
            "Feature-task-runtime no-change pause criteria must be objects.",
          )
        val verdict =
          FeatureTaskRuntimeNoChangeReason.fromWireOrNull(map["verdict"]) ?: throw invalidWorkflowStateSchemaError(
            "Feature-task-runtime no-change pause criterion has an unknown verdict.",
          )
        FeatureTaskRuntimeNoChangeCriterion(
          criterionId = map["criterion_id"] as? String ?: throw invalidWorkflowStateSchemaError(
            "Feature-task-runtime no-change pause criterion is missing criterion_id.",
          ),
          verdict = verdict,
          evidence = map["evidence"] as? String ?: throw invalidWorkflowStateSchemaError(
            "Feature-task-runtime no-change pause criterion is missing evidence.",
          ),
        )
      }
  }
}
