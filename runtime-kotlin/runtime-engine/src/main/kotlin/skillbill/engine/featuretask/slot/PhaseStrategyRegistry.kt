package skillbill.engine.featuretask.slot

import skillbill.error.featuretask.DuplicatePhaseStrategyError
import skillbill.error.featuretask.InvalidPhaseStrategyCompositionError
import skillbill.error.featuretask.PhaseStrategyStepOutsideSlotError
import skillbill.error.featuretask.UnknownPhaseStrategyError
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import java.util.Collections

class PhaseStrategyRegistry(strategies: List<PhaseStrategy>) {
  val strategies: List<PhaseStrategy> = Collections.unmodifiableList(strategies.toList())
  private val byKey: Map<Pair<PhaseSlot, String>, PhaseStrategy>

  init {
    val keyed = linkedMapOf<Pair<PhaseSlot, String>, PhaseStrategy>()
    this.strategies.forEach { strategy ->
      if (strategy.semanticRevision <= 0) {
        throw InvalidPhaseStrategyCompositionError("strategy ${strategy.slot.wireValue}/${strategy.strategyId} has an invalid semantic revision")
      }
      if (strategy.steps.isEmpty()) {
        throw InvalidPhaseStrategyCompositionError("strategy ${strategy.slot.wireValue}/${strategy.strategyId} has no steps")
      }
      if (strategy.strategyId.isBlank() || strategy.steps.any(String::isBlank)) {
        throw InvalidPhaseStrategyCompositionError("strategy ${strategy.slot.wireValue}/${strategy.strategyId} has a blank identity")
      }
      if (strategy.steps.size != strategy.steps.toSet().size) {
        throw InvalidPhaseStrategyCompositionError("strategy ${strategy.slot.wireValue}/${strategy.strategyId} repeats a step")
      }
      if (!strategy.steps.containsAll(strategy.optionalSteps)) {
        throw InvalidPhaseStrategyCompositionError("strategy ${strategy.slot.wireValue}/${strategy.strategyId} marks an unowned step optional")
      }
      if (strategy.entryStep !in strategy.steps) {
        throw InvalidPhaseStrategyCompositionError("strategy ${strategy.slot.wireValue}/${strategy.strategyId} has an entry outside its steps")
      }
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
