package skillbill.engine.featuretask.slot

import skillbill.error.featuretask.DuplicatePhaseStrategyError
import skillbill.error.featuretask.InvalidPhaseStrategyCompositionError
import skillbill.error.featuretask.PhaseStrategyStepOutsideSlotError
import skillbill.error.featuretask.UnknownPhaseStrategyError
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import java.util.Collections

class PhaseStrategyRegistration(val strategy: PhaseStrategy, internal val runner: PhaseRunner)

class PhaseStrategyRegistry(registrations: List<PhaseStrategyRegistration>) {
  val strategies: List<PhaseStrategy> = Collections.unmodifiableList(registrations.map { it.strategy })
  private val byKey: Map<Pair<PhaseSlot, String>, PhaseStrategy>
  private val runners: Map<Pair<PhaseSlot, String>, PhaseRunner>

  init {
    val keyed = linkedMapOf<Pair<PhaseSlot, String>, PhaseStrategy>()
    val keyedRunners = linkedMapOf<Pair<PhaseSlot, String>, PhaseRunner>()
    registrations.forEach { registration ->
      val strategy = registration.strategy
      validateStrategy(strategy)
      strategy.steps.firstOrNull { it !in strategy.slot.steps }?.let { step ->
        throw PhaseStrategyStepOutsideSlotError(strategy.slot.wireValue, strategy.strategyId, step)
      }
      if (keyed.put(strategy.slot to strategy.strategyId, strategy) != null) {
        throw DuplicatePhaseStrategyError(strategy.slot.wireValue, strategy.strategyId)
      }
      keyedRunners[strategy.slot to strategy.strategyId] = registration.runner
    }
    byKey = keyed
    runners = keyedRunners
  }

  fun contains(
    slot: PhaseSlot,
    strategyId: String,
  ): Boolean = (slot to strategyId) in byKey

  fun strategy(
    slot: PhaseSlot,
    strategyId: String,
  ): PhaseStrategy = byKey[slot to strategyId] ?: throw UnknownPhaseStrategyError(slot.wireValue, strategyId)

  internal fun runner(
    slot: PhaseSlot,
    strategyId: String,
  ): PhaseRunner = runners[slot to strategyId] ?: throw UnknownPhaseStrategyError(slot.wireValue, strategyId)
}

private fun validateStrategy(strategy: PhaseStrategy) {
  val reason =
    when {
      strategy.semanticRevision <= 0 -> "has an invalid semantic revision"
      strategy.steps.isEmpty() -> "has no steps"
      strategy.strategyId.isBlank() || strategy.steps.any(String::isBlank) -> "has a blank identity"
      strategy.steps.size != strategy.steps.toSet().size -> "repeats a step"
      !strategy.steps.containsAll(strategy.optionalSteps) -> "marks an unowned step optional"
      strategy.entryStep !in strategy.steps -> "has an entry outside its steps"
      else -> return
    }
  throw InvalidPhaseStrategyCompositionError("strategy ${strategy.slot.wireValue}/${strategy.strategyId} $reason")
}
