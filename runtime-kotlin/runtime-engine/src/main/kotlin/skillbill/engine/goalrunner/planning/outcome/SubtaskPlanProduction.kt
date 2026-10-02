package skillbill.engine.goalrunner.planning.outcome

import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome

/** How producing one subtask plan ended: planned and checkpointed, stopped, or stopped by a rejected required write. */
internal sealed interface SubtaskPlanProduction {
  data object Planned : SubtaskPlanProduction

  data class Stopped(val outcome: GoalPlanningSweepOutcome.Stopped) : SubtaskPlanProduction

  data class RequiredWriteRejected(val rejection: RequiredPhaseWrite.Rejected) : SubtaskPlanProduction
}
