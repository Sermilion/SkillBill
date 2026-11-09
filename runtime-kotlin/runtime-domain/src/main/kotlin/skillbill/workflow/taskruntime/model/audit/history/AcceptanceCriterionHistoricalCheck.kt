package skillbill.workflow.taskruntime.model.audit.history

import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionCompletedMutation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionIndependentProgress
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProgressContext

data class AcceptanceCriterionHistoricalCheck(
  val mutation: AcceptanceCriterionCompletedMutation,
  val comparison: AcceptanceCriterionIndependentProgress,
  val context: AcceptanceCriterionProgressContext,
)
