package skillbill.goalrunner.model.history

import skillbill.contracts.workflow.goal.GOAL_FINALIZATION_HISTORY_CONTRACT_VERSION

data class GoalFinalRepairOutput(
  val phaseId: String,
  val invocationId: String,
  val occurrence: Int,
  val output: String,
)

data class GoalFinalRepairContext(
  val outputs: List<GoalFinalRepairOutput>,
  val warning: String? = null,
)

data class GoalHistoryCompletion(
  val inputFingerprint: String,
  val preWriterContentFingerprint: String,
  val postWriterContentFingerprint: String,
  val acceptedOutput: String,
)

data class GoalHistoryWriterEntry(
  val subtaskId: Int,
  val childWorkflowId: String,
  val definitionId: String,
  val draft: GoalChildHistoryDraft? = null,
  val legacyDisposition: String? = null,
)

data class GoalHistoryWriterInput(
  val parentWorkflowId: String,
  val entries: List<GoalHistoryWriterEntry>,
  val repairContext: GoalFinalRepairContext,
)

data class GoalFinalizationHistoryRecord(
  val contractVersion: String = GOAL_FINALIZATION_HISTORY_CONTRACT_VERSION,
  val repairContext: GoalFinalRepairContext? = null,
  val completion: GoalHistoryCompletion? = null,
)
