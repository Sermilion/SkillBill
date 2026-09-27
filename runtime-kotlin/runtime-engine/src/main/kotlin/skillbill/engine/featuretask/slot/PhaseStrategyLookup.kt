package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.slot.state.PhaseResumeRules
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

  fun selectedOwnerOf(
    stepId: String,
    facts: PhaseStrategySelectionFacts,
  ): PhaseStrategy? = selectedStrategies(facts).firstOrNull { stepId in it.steps }

  fun selectedStepIds(facts: PhaseStrategySelectionFacts): Set<String> =
    selectedStrategies(facts).flatMap(PhaseStrategy::steps).toSet()

  fun unselectedStepIds(facts: PhaseStrategySelectionFacts): Set<String> =
    facts.definition.stepIds.toSet() - selectedStepIds(facts)

  internal fun resumeRules(facts: PhaseStrategySelectionFacts? = null): (String) -> PhaseResumeRules {
    val owners = facts?.let(::selectedStrategies).orEmpty() + registry.strategies
    return { stepId -> owners.firstOrNull { stepId in it.steps }?.resumeRules(stepId) ?: PhaseResumeRules.None }
  }

  internal fun gateReportedBy(stepId: String?): PhaseReportedGate? =
    stepId?.let { id -> gateReporters().firstNotNullOfOrNull { it.reportedGate(id) } }

  internal fun stepReporting(gate: PhaseReportedGate): String? =
    gateReporters().firstNotNullOfOrNull { strategy ->
      strategy.steps.firstOrNull { strategy.reportedGate(it) == gate }
    }

  private fun gateReporters(): List<PhaseStrategyStatusProjection> =
    registry.strategies.filterIsInstance<PhaseStrategyStatusProjection>()

  fun traversal(facts: PhaseStrategySelectionFacts): FeatureTaskRuntimeTransitionDeclaration {
    val selected = selectedStrategies(facts)
    return facts.definition.traversal(
      selectedStepIds = selected.flatMap(PhaseStrategy::steps).toSet(),
      entryStepIds = selected.map(PhaseStrategy::entryStep).toSet(),
    )
  }
}
