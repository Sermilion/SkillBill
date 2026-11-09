package skillbill.workflow.taskruntime.model.audit.history

import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionCompletedMutation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionHistoricalCheck
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState

data class AcceptanceCriterionHistoricalRepairState(
  val contractVersion: String,
  val state: AcceptanceCriterionRepairState,
  val completedMutations: List<AcceptanceCriterionCompletedMutation>?,
  val independentChecks: List<AcceptanceCriterionHistoricalCheck>?,
)

data class AcceptanceCriterionHistoricalImplementationProgress(
  val criterionIds: List<String>,
  val currentIndex: Int,
  val workerCount: Int,
  val launchCount: Int,
  val closedIds: List<String>,
  val completedMutations: List<AcceptanceCriterionCompletedMutation>?,
  val independentChecks: List<AcceptanceCriterionHistoricalCheck>?,
)
