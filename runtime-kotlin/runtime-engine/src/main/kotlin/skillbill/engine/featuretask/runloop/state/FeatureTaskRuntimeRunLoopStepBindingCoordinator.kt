package skillbill.engine.featuretask.runloop.state

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseSettlementTarget
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

internal class FeatureTaskRuntimeRunLoopStepBindingCoordinator {
  private val coordinatorDispatch = AtomicReference<PhaseRun?>(null)
  private val openDispatchBinding = AtomicReference<PhaseRun?>(null)
  private val authorizedFanOutWave = AtomicReference<PhaseRun?>(null)
  private val openFanOutUnitBindings = ConcurrentHashMap.newKeySet<FanOutUnitBindingKey>()

  fun authorizeCoordinatorDispatch(run: PhaseRun) {
    coordinatorDispatch.set(run)
  }

  fun releaseCoordinatorDispatch() {
    coordinatorDispatch.set(null)
  }

  fun authorizeFanOutWave(run: PhaseRun) {
    val dispatch =
      coordinatorDispatch.get()
        ?: error("Fan-out wave requires coordinator-issued dispatch for the active run step.")
    require(dispatch === run) {
      "Fan-out wave does not match the coordinator's active dispatch."
    }
    require(authorizedFanOutWave.compareAndSet(null, run)) {
      "A fan-out wave is already authorized for this coordinator."
    }
  }

  fun releaseFanOutWave(run: PhaseRun) {
    authorizedFanOutWave.compareAndSet(run, null)
  }

  fun requireAuthorizedFanOutWave(): PhaseRun {
    val wave =
      authorizedFanOutWave.get()
        ?: error("Fan-out unit state requires an authorized fan-out wave for the active run step.")
    return wave
  }

  fun trackFanOutUnitBinding(
    run: PhaseRun,
    unitId: Int,
  ) {
    openFanOutUnitBindings.add(FanOutUnitBindingKey(run, unitId))
  }

  fun releaseFanOutUnitBinding(
    run: PhaseRun,
    unitId: Int,
  ) {
    openFanOutUnitBindings.remove(FanOutUnitBindingKey(run, unitId))
  }

  fun beginStepBinding(
    run: PhaseRun,
    fanOutUnitId: Int? = null,
  ) {
    if (fanOutUnitId != null) {
      val wave =
        authorizedFanOutWave.get()
          ?: error("Fan-out unit binding requires an authorized fan-out wave for the active run step.")
      require(wave === run) {
        "Fan-out unit binding does not match the authorized fan-out wave."
      }
      trackFanOutUnitBinding(run, fanOutUnitId)
      return
    }
    val dispatch =
      coordinatorDispatch.get()
        ?: error("Step binding requires coordinator-issued dispatch for the active run step.")
    require(dispatch === run) {
      "Step binding does not match the coordinator's active dispatch."
    }
    require(openDispatchBinding.compareAndSet(null, run)) {
      "A prior step binding was not closed before creating another."
    }
  }

  fun requireActiveStepBinding(
    run: PhaseRun,
    fanOutUnitId: Int? = null,
  ) {
    if (fanOutUnitId != null) {
      require(openFanOutUnitBindings.contains(FanOutUnitBindingKey(run, fanOutUnitId))) {
        "Step operation is not bound to the active fan-out unit."
      }
      return
    }
    val open = openDispatchBinding.get()
    require(open === run) {
      "Step operation is not bound to the coordinator's active step."
    }
  }

  fun endStepBinding(
    run: PhaseRun,
    fanOutUnitId: Int? = null,
  ) {
    if (fanOutUnitId != null) {
      releaseFanOutUnitBinding(run, fanOutUnitId)
      return
    }
    openDispatchBinding.compareAndSet(run, null)
  }

  private data class FanOutUnitBindingKey(
    val requestIdentity: Any,
    val phaseId: String,
    val unitId: Int,
  ) {
    constructor(run: PhaseRun, unitId: Int) : this(run.request, run.phaseId, unitId)
  }
}

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
