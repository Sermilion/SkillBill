package skillbill.engine.featuretask.slot

import skillbill.error.featuretask.PhaseStrategySelectionSlotMismatchError
import skillbill.error.featuretask.UnknownPhaseStrategyError
import skillbill.error.featuretask.UnregisteredPhaseStrategySelectionError
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

data class PhaseStrategySelectionFacts(
  val definition: SkeletonDefinition,
  val values: Set<Enum<*>>,
)

sealed interface PhaseStrategyBinding {
  val strategyIds: Set<String>

  fun resolve(facts: PhaseStrategySelectionFacts): String?

  data class Fixed(val strategyId: String) : PhaseStrategyBinding {
    override val strategyIds: Set<String> get() = setOf(strategyId)

    override fun resolve(facts: PhaseStrategySelectionFacts): String = strategyId
  }

  data class ByFact(val ids: Map<out Enum<*>, String>) : PhaseStrategyBinding {
    override val strategyIds: Set<String> get() = ids.values.toSet()

    override fun resolve(facts: PhaseStrategySelectionFacts): String? = facts.values.firstNotNullOfOrNull { ids[it] }
  }
}

class PhaseStrategySelection(
  registry: PhaseStrategyRegistry,
  private val bindings: Map<SkeletonDefinition, Map<PhaseSlot, PhaseStrategyBinding>>,
) {
  init {
    bindings.forEach { (definition, slotBindings) ->
      (definition.slots.toSet() xor slotBindings.keys).firstOrNull()?.let { slot ->
        throw PhaseStrategySelectionSlotMismatchError(definition.id, slot.wireValue)
      }
      slotBindings.forEach { (slot, binding) ->
        binding.strategyIds.firstOrNull { !registry.contains(slot, it) }?.let { strategyId ->
          throw UnregisteredPhaseStrategySelectionError(slot.wireValue, strategyId)
        }
      }
    }
  }

  fun binds(
    slot: PhaseSlot,
    facts: PhaseStrategySelectionFacts,
  ): Boolean = bindings[facts.definition]?.containsKey(slot) == true

  fun strategyIdFor(
    slot: PhaseSlot,
    facts: PhaseStrategySelectionFacts,
  ): String =
    bindings[facts.definition]?.get(slot)?.resolve(facts)
      ?: throw UnknownPhaseStrategyError(
        slot.wireValue,
        "definition=${facts.definition.id}, facts=${facts.values.joinToString { it.name }}",
      )

  private infix fun <T> Set<T>.xor(other: Set<T>): Set<T> = (this - other) + (other - this)
}
