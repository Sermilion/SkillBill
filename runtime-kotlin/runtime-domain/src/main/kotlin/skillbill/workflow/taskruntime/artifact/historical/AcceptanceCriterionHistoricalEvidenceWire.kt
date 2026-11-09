package skillbill.workflow.taskruntime.artifact.historical

import skillbill.error.shellcontent.invalidAcceptanceCriterionAuditResultSchema
import skillbill.workflow.taskruntime.artifact.AcceptanceCriterionEvidenceWire
import skillbill.workflow.taskruntime.artifact.accounting.evidenceRows
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionCompletedMutation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionEstablishedObligation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionHistoricalCheck
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionMutationDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.validateHistoricalContext
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionProgressPayloadKeys as Keys

internal object AcceptanceCriterionHistoricalEvidenceWire {
  fun mutations(payload: FeatureTaskRuntimeWorkflowArtifactMap): List<AcceptanceCriterionCompletedMutation> =
    payload.evidenceRows(Keys.COMPLETED_MUTATIONS).map(::mutation).distinct()

  fun checks(payload: FeatureTaskRuntimeWorkflowArtifactMap): List<AcceptanceCriterionHistoricalCheck> =
    payload.evidenceRows(Keys.INDEPENDENT_CHECKS).map { row ->
      if (row.keys != setOf(Keys.MUTATION, Keys.COMPARISON, Keys.CONTEXT)) invalid("Invalid historical check fields.")
      val mutation = mutation(FeatureTaskRuntimeWorkflowArtifactMap.from(row[Keys.MUTATION]))
      val comparison =
        AcceptanceCriterionEvidenceWire.comparison(
          FeatureTaskRuntimeWorkflowArtifactMap.from(
            row[Keys.COMPARISON],
          ),
        )
      val context =
        AcceptanceCriterionEvidenceWire.context(
          FeatureTaskRuntimeWorkflowArtifactMap.from(
            row[Keys.CONTEXT],
          ),
        )
      mutation.validateContext(context)
      comparison.validateHistoricalContext(context)
      AcceptanceCriterionHistoricalCheck(mutation, comparison, context)
    }.distinct()

  fun validate(
    mutations: List<AcceptanceCriterionCompletedMutation>,
    checks: List<AcceptanceCriterionHistoricalCheck>,
  ) {
    val overCapacity =
      mutations.size > AcceptanceCriterionRepairState.MAX_REPAIR_OUTCOMES ||
        checks.size > AcceptanceCriterionRepairState.MAX_REPAIR_OUTCOMES
    val unique =
      mutations.map { it.repairAttemptId }.distinct().size == mutations.size &&
        checks.map { it.mutation.repairAttemptId }.distinct().size == checks.size
    val bound = checks.all { it.mutation in mutations }
    if (overCapacity || !unique || !bound) invalid("Historical evidence has duplicate or unbound attempts.")
    val inventory = mutableMapOf<String, List<AcceptanceCriterionEstablishedObligation>>()
    mutations.forEach { mutation ->
      val previous = inventory[mutation.criterionRef]
      if (previous != null && previous != mutation.establishedObligations) {
        invalid(
          "Historical obligation inventory was replaced.",
        )
      }
      checks.singleOrNull { it.mutation == mutation }?.let { check ->
        inventory[mutation.criterionRef] = check.comparison.preservedObligations.map {
          AcceptanceCriterionEstablishedObligation(it.obligationId, it.obligation, it.currentCitations)
        } +
          check.comparison.resolvedObligations.map {
            AcceptanceCriterionEstablishedObligation(it.obligationId, it.obligation, it.currentCitations)
          }
      }
    }
  }

  private fun mutation(value: FeatureTaskRuntimeWorkflowArtifactMap): AcceptanceCriterionCompletedMutation {
    if (value.containsKey(Keys.TERMINAL_DISPOSITION) || value.containsKey(Keys.LAUNCH_ATTRIBUTION)) {
      invalid("Historical mutation cannot contain current terminal or launch facts.")
    }
    return AcceptanceCriterionEvidenceWire.mutation(
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        value +
          mapOf(
            Keys.TERMINAL_DISPOSITION to AcceptanceCriterionMutationDisposition.HISTORICAL_UNKNOWN.wireValue,
            Keys.LAUNCH_ATTRIBUTION to null,
          ),
      ),
    )
  }

  private fun invalid(reason: String): Nothing =
    throw invalidAcceptanceCriterionAuditResultSchema(
      "historical-evidence",
      reason,
    )
}
