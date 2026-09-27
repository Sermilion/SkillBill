package skillbill.engine.featuretask.slot

import skillbill.error.featuretask.DuplicatePhaseStrategyError
import skillbill.error.featuretask.PhaseStrategyStepOutsideSlotError
import skillbill.error.featuretask.UnknownPhaseStrategyError
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot

class PhaseStrategyRegistry(val strategies: List<PhaseStrategy>) {
  private val byKey: Map<Pair<PhaseSlot, String>, PhaseStrategy>

  init {
    val keyed = linkedMapOf<Pair<PhaseSlot, String>, PhaseStrategy>()
    strategies.forEach { strategy ->
      strategy.steps.firstOrNull { it !in strategy.slot.steps }?.let { step ->
        throw PhaseStrategyStepOutsideSlotError(strategy.slot.wireValue, strategy.strategyId, step)
      }
      if (keyed.put(strategy.slot to strategy.strategyId, strategy) != null) {
        throw DuplicatePhaseStrategyError(strategy.slot.wireValue, strategy.strategyId)
      }
    }
    byKey = keyed
  }

  fun contains(
    slot: PhaseSlot,
    strategyId: String,
  ): Boolean = (slot to strategyId) in byKey

  fun strategy(
    slot: PhaseSlot,
    strategyId: String,
  ): PhaseStrategy = byKey[slot to strategyId] ?: throw UnknownPhaseStrategyError(slot.wireValue, strategyId)
}
