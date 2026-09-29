package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.state.PhaseLaunchState

internal class FeatureTaskRuntimeRunLoopStepLaunchState(
  private val launchBacking: PhaseLaunchState,
  private val acceptedPhaseId: String,
) : PhaseLaunchState {
  override fun prepareLaunch(input: PhaseStepInput): PhaseStepInput? {
    requireAcceptedPhase(input.stepName)
    return launchBacking.prepareLaunch(input)
  }

  override fun settlementTarget(attempt: Int): FeatureTaskRuntimePhaseSettlementTarget? =
    launchBacking.settlementTarget(attempt)

  override fun launchObservation(stepName: String) =
    launchBacking.launchObservation(stepName.also(::requireAcceptedPhase))

  override fun recordTokenUsage(
    stepName: String,
    inputTokens: Int,
    outputTokens: Int,
  ) {
    requireAcceptedPhase(stepName)
    launchBacking.recordTokenUsage(stepName, inputTokens, outputTokens)
  }

  override fun settledEnvelope(
    stepName: String,
    target: FeatureTaskRuntimePhaseSettlementTarget,
  ) = launchBacking.settledEnvelope(stepName.also(::requireAcceptedPhase), target)

  private fun requireAcceptedPhase(stepName: String) {
    check(stepName == acceptedPhaseId) { "Launch operation belongs to accepted step '$acceptedPhaseId'." }
  }
}
