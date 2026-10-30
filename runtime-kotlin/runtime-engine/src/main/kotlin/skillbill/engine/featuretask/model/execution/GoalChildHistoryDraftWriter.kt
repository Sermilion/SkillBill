package skillbill.engine.featuretask.model.execution

import skillbill.goalrunner.model.history.GoalChildHistoryDraft
import skillbill.goalrunner.model.history.GoalChildHistoryDraftSaveResult

/** Records a goal child's history draft for a feature-task run; the goal runner's outcome store implements it. */
interface GoalChildHistoryDraftWriter {
  fun saveGoalChildHistoryDraft(
    draft: GoalChildHistoryDraft,
    admitted: AdmittedFeatureTaskRuntimeExecution? = null,
  ): GoalChildHistoryDraftSaveResult
}
