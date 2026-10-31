package skillbill.engine.featuretask.runloop.planning

fun settledPlanDecompositionOutputForTest(
  phaseId: String,
  summary: String,
  value: String,
) = PlanDecompositionStop.settledOutput(phaseId, summary, value)
