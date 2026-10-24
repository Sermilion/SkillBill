package skillbill.engine.featuretask.slot.plan

import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.slot.state.PhaseRunFanOut
import skillbill.ports.concurrency.BoundedWorkFanOutPort

internal fun runPlanFanOutWave(
  fanOutPort: BoundedWorkFanOutPort,
  maxInFlight: Int,
  fanOut: PhaseRunFanOut,
  wave: List<Int>,
  runUnit: (Int) -> PhaseOutcome,
): PhaseOutcome? {
  val units =
    wave.map { unitId ->
      {
        val result = runCatching { runUnit(unitId) }
        result.exceptionOrNull()?.rethrowIfCooperativeCancellationOrInterruption()
        fanOutPort.runExclusively { fanOut.settleUnit(unitId, result) }
      }
    }
  val results = fanOutPort.runBounded(maxInFlight, units)
  return wave.indices.firstNotNullOfOrNull { index ->
    results[index].getOrElse { failure -> fanOut.settleUnit(wave[index], Result.failure(failure)) }
  }
}
