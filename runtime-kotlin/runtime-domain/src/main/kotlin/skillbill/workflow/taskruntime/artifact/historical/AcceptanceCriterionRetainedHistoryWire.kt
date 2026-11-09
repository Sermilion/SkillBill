package skillbill.workflow.taskruntime.artifact.historical

import skillbill.workflow.taskruntime.artifact.AcceptanceCriterionEvidenceWire
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionCompletedMutation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionHistoricalCheck
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRetainedHistory
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionHistoricalRepairState
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionProgressPayloadKeys as Keys
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionHistoricalImplementationProgress as HistoricalImplementationProgress

internal fun AcceptanceCriterionHistoricalRepairState.retainedHistory(): AcceptanceCriterionRetainedHistory? =
  retainedHistory(completedMutations, independentChecks)

internal fun HistoricalImplementationProgress.retainedHistory(): AcceptanceCriterionRetainedHistory? =
  retainedHistory(completedMutations, independentChecks)

private fun retainedHistory(
  mutations: List<AcceptanceCriterionCompletedMutation>?,
  checks: List<AcceptanceCriterionHistoricalCheck>?,
): AcceptanceCriterionRetainedHistory? =
  if (mutations == null && checks == null) {
    null
  } else {
    AcceptanceCriterionRetainedHistory(
      mutations.orEmpty(),
      checks.orEmpty(),
    )
  }

internal fun AcceptanceCriterionRetainedHistory.writeInto(fields: MutableMap<String, Any?>) {
  fields[Keys.COMPLETED_MUTATIONS] = mutations.map { historicalMutation(it) }
  fields[Keys.INDEPENDENT_CHECKS] =
    checks.map {
      mapOf(
        Keys.MUTATION to historicalMutation(it.mutation),
        Keys.COMPARISON to AcceptanceCriterionEvidenceWire.comparison(it.comparison),
        Keys.CONTEXT to AcceptanceCriterionEvidenceWire.context(it.context),
      )
    }
}

private fun historicalMutation(mutation: AcceptanceCriterionCompletedMutation): Map<String, Any?> =
  AcceptanceCriterionEvidenceWire.mutation(mutation) - Keys.TERMINAL_DISPOSITION - Keys.LAUNCH_ATTRIBUTION
