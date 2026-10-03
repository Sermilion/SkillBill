package skillbill.engine.featuretask.phase.briefing

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.workflow.taskruntime.handoff.FeatureTaskRuntimeHandoffProjectionValidator
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef

internal fun FeatureTaskRuntimePhaseLaunchBriefing.upstreamReceipt(producingPhaseId: String): String? =
  handoffEnvelope.projections
    .firstOrNull { projection ->
      (projection.sourceRef as? FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput)
        ?.producingPhaseId == producingPhaseId
    }
    ?.fields
    ?.firstOrNull { it.name == FeatureTaskRuntimeHandoffProjectionValidator.PHASE_OUTPUT_RECEIPT_FIELD }
    ?.let { (it.value as? FeatureTaskRuntimeHandoffProjectionValue.Text)?.text }

internal fun FeatureTaskRuntimePhaseLaunchBriefing.requireUpstreamReceipt(producingPhaseId: String): String =
  requireNotNull(upstreamReceipt(producingPhaseId)) {
    "Briefing for phase '$phaseId' carries no delivered receipt for producing phase '$producingPhaseId'."
  }

internal fun FeatureTaskRuntimePhaseLaunchBriefing.hasUpstreamReceipt(producingPhaseId: String): Boolean =
  upstreamReceipt(producingPhaseId) != null

internal val FINALIZATION_PHASE_IDS: Set<String> = setOf("write_history", "commit_push")

internal object PlanningProjectionFixtures {
  const val PREPLAN_DIGEST: String =
    """{"value":"Fixture preplan prose for downstream plan."}"""

  const val PLAN_PROSE: String =
    """{"value":"Fixture plan prose for downstream implement and audit."}"""

  const val IMPLEMENT_PROSE: String =
    """{"value":"Fixture implement prose for downstream audit."}"""

  const val SIMPLIFY_PROSE: String =
    """{"value":"{\"projection_kind\":\"simplification_receipt\",\"contract_version\":\"0.1\",""" +
      """\"changed_paths\":[],\"reductions\":[],\"unresolved_items\":[],""" +
      """\"reconciliation_evidence\":{\"reconciled\":true,\"evidence\":\"fixture\"},""" +
      """\"reconciled_state\":{\"reconciled\":true,\"evidence\":\"fixture\"}}"}"""

  const val IMPLEMENT_PROSE_FIELDS: String =
    """"value":"Fixture implement prose for downstream audit.","""

  fun producedOutputsOrNull(phaseId: String): String? =
    when (phaseId) {
      "preplan" -> PREPLAN_DIGEST
      "plan" -> PLAN_PROSE
      "implement" -> IMPLEMENT_PROSE
      "simplify" -> SIMPLIFY_PROSE
      else -> null
    }
}
