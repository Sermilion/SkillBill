package skillbill.engine.goalrunner.planning.context

import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint

/** The result of producing a shared preplan: the checkpoint, the stop that ended it, or the rejected required write. */
internal sealed interface SharedPreplanProduction {
  data class Produced(val checkpoint: SharedGoalPreplanCheckpoint) : SharedPreplanProduction

  data class Stopped(val outcome: GoalPlanningSweepOutcome.Stopped) : SharedPreplanProduction

  data class RequiredWriteRejected(val rejection: RequiredPhaseWrite.Rejected) : SharedPreplanProduction
}
