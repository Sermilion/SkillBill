package skillbill.engine.goalrunner.planning.model

import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys

internal enum class GoalPlanningSubtaskPlanningDisposition(val wireValue: String) {
  INCLUDED("included"),
  SKIPPED(DecompositionPlanningPayloadKeys.SKIPPED),
  ;

  companion object {
    fun fromWire(value: String): GoalPlanningSubtaskPlanningDisposition? = entries.firstOrNull { it.wireValue == value }
  }
}
