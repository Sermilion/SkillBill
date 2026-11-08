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

fun accumulateGoalFinalRepairWarnings(vararg warnings: String?): String? =
  accumulateGoalFinalRepairWarnings(warnings.asList())

fun accumulateGoalFinalRepairWarnings(warnings: Iterable<String?>): String? {
  var accumulated: String? = null
  warnings.filterNotNull().filter(String::isNotBlank).forEach { warning ->
    val current = accumulated
    accumulated =
      when {
        current == null -> warning
        current.contains(warning) -> current
        warning.contains(current) -> warning
        else -> "$current $warning"
      }
  }
  return accumulated
}

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
