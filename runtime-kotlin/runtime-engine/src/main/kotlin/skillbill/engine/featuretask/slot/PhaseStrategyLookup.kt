package skillbill.engine.featuretask.slot

import skillbill.workflow.taskruntime.model.core.PhaseSlot
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration

class PhaseStrategyLookup(
  val registry: PhaseStrategyRegistry,
  private val selection: PhaseStrategySelection,
) {
  fun strategyFor(
    stepId: String,
    facts: PhaseStrategySelectionFacts,
  ): PhaseStrategy {
    val slot = PhaseSlot.slotForStep(stepId)
    return registry.strategy(slot, selection.strategyIdFor(slot, facts))
  }

  fun strategyOrNull(
    stepId: String,
    facts: PhaseStrategySelectionFacts,
  ): PhaseStrategy? = if (selection.binds(PhaseSlot.slotForStep(stepId), facts)) strategyFor(stepId, facts) else null

  fun selectedStrategies(facts: PhaseStrategySelectionFacts): List<PhaseStrategy> =
    facts.definition.slots.map { slot -> registry.strategy(slot, selection.strategyIdFor(slot, facts)) }

  fun selectedStepIds(facts: PhaseStrategySelectionFacts): Set<String> =
    selectedStrategies(facts).flatMap(PhaseStrategy::steps).toSet()

  fun unselectedStepIds(facts: PhaseStrategySelectionFacts): Set<String> =
    facts.definition.stepIds.toSet() - selectedStepIds(facts)

  fun traversal(facts: PhaseStrategySelectionFacts): FeatureTaskRuntimeTransitionDeclaration {
    val selected = selectedStrategies(facts)
    return facts.definition.traversal(
      selectedStepIds = selected.flatMap(PhaseStrategy::steps).toSet(),
      entryStepIds = selected.map(PhaseStrategy::entryStep).toSet(),
    )
  }
}
