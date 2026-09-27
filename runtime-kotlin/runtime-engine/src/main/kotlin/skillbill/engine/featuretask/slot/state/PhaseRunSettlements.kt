package skillbill.engine.featuretask.slot.state

import skillbill.engine.featuretask.model.phase.FeatureTaskPhaseSettlementEnvelope

/** The step settlements a launched agent recorded for one run, keyed by workflow id, step, and attempt. */
internal interface PhaseRunSettlements {
  /** The envelope settled for [phaseId] at [attempt], if any. */
  fun findEnvelope(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): FeatureTaskPhaseSettlementEnvelope?

  /** Clears the settlement of [phaseId] at [attempt]. Returns whether one was cleared. */
  fun clear(
    workflowId: String,
    phaseId: String,
    attempt: Int,
  ): Boolean
}
