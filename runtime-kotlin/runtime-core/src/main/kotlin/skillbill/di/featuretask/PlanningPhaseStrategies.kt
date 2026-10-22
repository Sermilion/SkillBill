package skillbill.di.featuretask

import skillbill.engine.featuretask.slot.intake.RuntimeIntakeStrategy
import skillbill.engine.featuretask.slot.plan.PlanFanOutStrategy

internal class PlanningPhaseStrategies(
  val intake: RuntimeIntakeStrategy,
  val fanOut: PlanFanOutStrategy,
)
