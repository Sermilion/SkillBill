package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.slot.state.PhaseLaunchState

internal fun PhaseStrategyRegistry(strategies: Collection<PhaseStrategy>): PhaseStrategyRegistry =
  PhaseStrategyRegistry(strategies.map { PhaseStrategyRegistration(it, UnavailablePhaseStrategyRunner) })

private object UnavailablePhaseStrategyRunner : PhaseRunner {
  override fun run(
    input: PhaseStepInput,
    state: PhaseLaunchState,
  ): PhaseStepOutput = error("A runner must be registered before phase execution.")
}
