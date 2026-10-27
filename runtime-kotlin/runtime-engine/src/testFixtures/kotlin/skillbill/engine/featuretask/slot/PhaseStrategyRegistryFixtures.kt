package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy
import skillbill.engine.featuretask.slot.standalonereview.StandaloneReviewStrategy
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

internal fun PhaseStrategyRegistry(strategies: Collection<PhaseStrategy>): PhaseStrategyRegistry =
  PhaseStrategyRegistry(strategies.map { PhaseStrategyRegistration(it, UnavailablePhaseStrategyRunner) })

fun PhaseStrategyRegistry.registeredRunners(): List<PhaseRunner> = strategies.map { runner(it.slot, it.strategyId) }

fun PhaseStrategyRegistry.reviewStrategyRunners(): List<PhaseRunner> =
  strategies.mapNotNull { strategy ->
    when (strategy) {
      is InlineReviewStrategy -> strategy.runner
      is StandaloneReviewStrategy -> strategy.runner
      else -> null
    }
  }

fun PhaseStrategyLookup.reachableStrategyPairs(): Set<Pair<PhaseSlot, String>> =
  buildSet {
    SkeletonDefinition.entries.forEach { definition ->
      val slots = definition.slots
      val modes =
        if (PhaseSlot.CODE_REVIEW in slots || PhaseSlot.STANDALONE_REVIEW in slots) {
          CodeReviewExecutionMode.entries
        } else {
          listOf(null)
        }
      val gates =
        when {
          definition == SkeletonDefinition.VALIDATION -> listOf(FeatureTaskRuntimeQualityGateSelection.VALIDATE)
          PhaseSlot.QUALITY_GATE in slots -> FeatureTaskRuntimeQualityGateSelection.entries
          else -> listOf(null)
        }
      val qas = if (PhaseSlot.QA in slots) FeatureTaskRuntimeQaSelection.entries else listOf(null)
      modes.forEach { mode ->
        gates.forEach { gate ->
          qas.forEach { qa ->
            val facts = PhaseStrategySelectionFacts(definition, setOfNotNull<Enum<*>>(mode, gate, qa))
            executionPlan(facts).selectedStrategies.forEach { add(it.slot to it.strategyId) }
          }
        }
      }
    }
  }

private object UnavailablePhaseStrategyRunner : PhaseRunner {
  override fun run(
    input: PhaseStepInput,
    state: PhaseLaunchState,
  ): PhaseStepOutput = error("A runner must be registered before phase execution.")
}
