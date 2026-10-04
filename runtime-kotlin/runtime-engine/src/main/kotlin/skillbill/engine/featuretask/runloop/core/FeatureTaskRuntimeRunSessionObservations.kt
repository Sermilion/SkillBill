package skillbill.engine.featuretask.runloop.core

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry

/** Read-only session facts for attempt and settlement helpers; mutations route through the transition owner. */
internal interface FeatureTaskRuntimeRunSessionObservations {
  val operatorBlockRetry: FeatureTaskRuntimeOperatorBlockRetry?

  val checkpointOwnershipDecided: Boolean

  val resolvedBranch: String?

  val operatorBlockRetryCompleted: Boolean

  val pendingReentry: PendingReentry?

  val activeReentry: PendingReentry?

  val recordRejectionSettlementPending: Boolean

  val blocked: FeatureTaskRuntimeRunReport.Blocked?

  val paused: FeatureTaskRuntimeRunReport.Paused?

  val decomposed: FeatureTaskRuntimeRunReport.Decomposed?

  fun phaseContentIdentitiesFor(phaseId: String): Map<String, String>
}
