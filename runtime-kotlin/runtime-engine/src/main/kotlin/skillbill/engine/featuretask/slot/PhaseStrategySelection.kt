package skillbill.engine.featuretask.slot

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.PhaseSlotFailureCode
import skillbill.error.featuretask.invalidPhaseStrategyCompositionFailure
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.util.Collections

data class PhaseStrategySelectionFacts(
  val definition: SkeletonDefinition,
  val values: Set<Enum<*>>,
)

sealed interface PhaseStrategyBinding {
  val strategyIds: Set<String>

  fun resolve(facts: PhaseStrategySelectionFacts): String?

  data class Fixed(val strategyId: String) : PhaseStrategyBinding {
    init {
      require(strategyId.isNotBlank()) {
        "Invalid phase strategy composition: fixed selection has a blank strategy identity"
      }
    }

    override val strategyIds: Set<String> get() = setOf(strategyId)

    override fun resolve(facts: PhaseStrategySelectionFacts): String = strategyId
  }

  class ByFact(ids: Map<out Enum<*>, String>) : PhaseStrategyBinding {
    private val ids: Map<Enum<*>, String> = Collections.unmodifiableMap(LinkedHashMap(ids))

    init {
      require(this.ids.values.none(String::isBlank)) {
        "Invalid phase strategy composition: fact selection contains a blank strategy identity"
      }
    }

    override val strategyIds: Set<String> get() = Collections.unmodifiableSet(ids.values.toSet())

    override fun resolve(facts: PhaseStrategySelectionFacts): String? {
      val matches = facts.values.mapNotNull { fact -> ids[fact]?.let { fact to it } }
      if (matches.size > 1) {
        throw invalidPhaseStrategyCompositionFailure(
          "ambiguous selection for ${facts.definition.id}: " + matches.map { it.first.name }.sorted().joinToString(),
        )
      }
      return matches.singleOrNull()?.second
    }
  }
}

class PhaseStrategySelection(
  registry: PhaseStrategyRegistry,
  bindings: Map<SkeletonDefinition, Map<PhaseSlot, PhaseStrategyBinding>>,
) {
  private val bindings: Map<SkeletonDefinition, Map<PhaseSlot, PhaseStrategyBinding>> =
    Collections.unmodifiableMap(
      bindings.mapValues { (_, slots) -> Collections.unmodifiableMap(LinkedHashMap(slots)) },
    )

  init {
    this.bindings.forEach { (definition, slotBindings) ->
      (definition.slots.toSet() xor slotBindings.keys).firstOrNull()?.let { slot ->
        error(
          "Phase strategy selection for skeleton definition '${definition.id}' must bind exactly its slots; " +
            "slot '${slot.wireValue}' is unbound or outside the definition.",
        )
      }
      slotBindings.forEach { (slot, binding) ->
        if (binding.strategyIds.isEmpty()) {
          error(
            "Invalid phase strategy composition: selection for ${definition.id}/${slot.wireValue} has no strategies",
          )
        }
        binding.strategyIds.firstOrNull { !registry.contains(slot, it) }?.let { strategyId ->
          error("Phase strategy selection for slot '${slot.wireValue}' names unregistered strategy '$strategyId'.")
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
  ): String {
    val binding =
      bindings[facts.definition]?.get(slot)
        ?: throw SkillBillRuntimeException(
          PhaseSlotFailureCode.UNKNOWN_PHASE_STRATEGY,
          "Phase slot '${slot.wireValue}' has no strategy 'definition=${facts.definition.id}'.",
        )
    return binding.resolve(facts)
      ?: throw invalidPhaseStrategyCompositionFailure(
        "no matching selection for ${facts.definition.id}/${slot.wireValue}: " +
          facts.values.map { "${it.javaClass.simpleName}.${it.name}" }.sorted().joinToString(),
      )
  }

  private infix fun <T> Set<T>.xor(other: Set<T>): Set<T> = (this - other) + (other - this)
}
