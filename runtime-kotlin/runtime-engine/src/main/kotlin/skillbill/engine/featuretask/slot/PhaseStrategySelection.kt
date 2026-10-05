package skillbill.engine.featuretask.slot

import skillbill.error.featuretask.InvalidPhaseStrategyCompositionError
import skillbill.error.featuretask.PhaseStrategySelectionSlotMismatchError
import skillbill.error.featuretask.UnknownPhaseStrategyError
import skillbill.error.featuretask.UnregisteredPhaseStrategySelectionError
import skillbill.workflow.taskruntime.model.skeleton.PhaseModelProfile
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.skeleton.StepLaunchAssignment
import skillbill.workflow.taskruntime.model.skeleton.immutableStepLaunchAssignments
import java.util.Collections

data class PhaseStrategySelectionFacts(
  val definition: SkeletonDefinition,
  val values: Set<Enum<*>>,
  val stepAssignments: Map<String, StepLaunchAssignment> = emptyMap(),
) {
  init {
    stepAssignments.forEach { (stepId, assignment) -> require(assignment.stepId == stepId) }
  }

  fun copiedAssignments(): Map<String, StepLaunchAssignment> = immutableStepLaunchAssignments(stepAssignments)
}

sealed interface PhaseStrategyBinding {
  val strategyIds: Set<String>

  fun resolve(facts: PhaseStrategySelectionFacts): String?

  data class Fixed(val strategyId: String) : PhaseStrategyBinding {
    init {
      if (strategyId.isBlank()) {
        throw InvalidPhaseStrategyCompositionError("fixed selection has a blank strategy identity")
      }
    }

    override val strategyIds: Set<String> get() = setOf(strategyId)

    override fun resolve(facts: PhaseStrategySelectionFacts): String = strategyId
  }

  class ByFact(ids: Map<out Enum<*>, String>) : PhaseStrategyBinding {
    private val ids: Map<Enum<*>, String> = Collections.unmodifiableMap(LinkedHashMap(ids))

    init {
      if (this.ids.values.any(String::isBlank)) {
        throw InvalidPhaseStrategyCompositionError("fact selection contains a blank strategy identity")
      }
    }

    override val strategyIds: Set<String> get() = Collections.unmodifiableSet(ids.values.toSet())

    override fun resolve(facts: PhaseStrategySelectionFacts): String? {
      val matches = facts.values.mapNotNull { fact -> ids[fact]?.let { fact to it } }
      if (matches.size > 1) {
        throw InvalidPhaseStrategyCompositionError(
          "ambiguous selection for ${facts.definition.id}: " + matches.map { it.first.name }.sorted().joinToString(),
        )
      }
      return matches.singleOrNull()?.second
    }
  }

  class ByProfile(
    private val definitionBinding: PhaseStrategyBinding,
    opusVariants: Map<String, String>,
  ) : PhaseStrategyBinding {
    private val opusVariants: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(opusVariants))

    init {
      if (this.opusVariants.isEmpty() || this.opusVariants.any { it.key.isBlank() || it.value.isBlank() }) {
        throw InvalidPhaseStrategyCompositionError("profile selection contains a blank strategy identity")
      }
    }

    override val strategyIds: Set<String>
      get() = definitionBinding.strategyIds + opusVariants.values

    override fun resolve(facts: PhaseStrategySelectionFacts): String? = definitionBinding.resolve(facts)

    fun specialize(
      canonicalId: String,
      participatingSteps: Collection<String>,
      facts: PhaseStrategySelectionFacts,
    ): String {
      val qualifies =
        participatingSteps.any { step -> facts.stepAssignments[step]?.profile == PhaseModelProfile.OPUS_5_5 }
      if (!qualifies) return canonicalId
      return opusVariants[canonicalId]
        ?: throw InvalidPhaseStrategyCompositionError("no opus variant registered for $canonicalId")
    }
  }
}

fun PhaseStrategyBinding.withOpus(opusStrategyId: String): PhaseStrategyBinding.ByProfile {
  val canonicalIds = strategyIds
  if (canonicalIds.size != 1) {
    throw InvalidPhaseStrategyCompositionError("opus profile binding requires exactly one canonical identity")
  }
  return PhaseStrategyBinding.ByProfile(this, mapOf(canonicalIds.single() to opusStrategyId))
}

fun PhaseStrategyBinding.withOpus(opusVariants: Map<String, String>): PhaseStrategyBinding.ByProfile =
  PhaseStrategyBinding.ByProfile(this, opusVariants)

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
        throw PhaseStrategySelectionSlotMismatchError(definition.id, slot.wireValue)
      }
      slotBindings.forEach { (slot, binding) ->
        if (binding.strategyIds.isEmpty()) {
          throw InvalidPhaseStrategyCompositionError(
            "selection for ${definition.id}/${slot.wireValue} has no strategies",
          )
        }
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
  ): String {
    val binding =
      bindings[facts.definition]?.get(slot)
        ?: throw UnknownPhaseStrategyError(slot.wireValue, "definition=${facts.definition.id}")
    return binding.resolve(facts)
      ?: throw InvalidPhaseStrategyCompositionError(
        "no matching selection for ${facts.definition.id}/${slot.wireValue}: " +
          facts.values.map { "${it.javaClass.simpleName}.${it.name}" }.sorted().joinToString(),
      )
  }

  fun specializedStrategyIdFor(
    slot: PhaseSlot,
    facts: PhaseStrategySelectionFacts,
    participatingSteps: Collection<String>,
  ): String {
    val canonicalId = strategyIdFor(slot, facts)
    val binding = bindings[facts.definition]?.get(slot) ?: return canonicalId
    return if (binding is PhaseStrategyBinding.ByProfile) {
      binding.specialize(canonicalId, participatingSteps, facts)
    } else {
      canonicalId
    }
  }

  private infix fun <T> Set<T>.xor(other: Set<T>): Set<T> = (this - other) + (other - this)
}
