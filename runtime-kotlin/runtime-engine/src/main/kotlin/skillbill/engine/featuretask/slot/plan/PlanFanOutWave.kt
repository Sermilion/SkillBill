package skillbill.engine.featuretask.slot.plan

import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.slot.state.PhaseRunFanOut
import skillbill.error.core.catchingNonCancellation
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
        val result = catchingNonCancellation { runUnit(unitId) }
        fanOutPort.runExclusively { fanOut.settleUnit(unitId, result) }
      }
    }
  val results = fanOutPort.runBounded(maxInFlight, units)
  return wave.indices.firstNotNullOfOrNull { index ->
    results[index].getOrElse { failure -> fanOut.settleUnit(wave[index], Result.failure(failure)) }
  }
}
