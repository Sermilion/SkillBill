package skillbill.engine.featuretask.runloop.core

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry

/** Read-only session facts for attempt and settlement helpers; mutations route through the transition owner. */
public interface FeatureTaskRuntimeRunSessionObservations {
  public val operatorBlockRetry: FeatureTaskRuntimeOperatorBlockRetry?

  public val checkpointOwnershipDecided: Boolean

  public val resolvedBranch: String?

  public val operatorBlockRetryCompleted: Boolean

  public val pendingReentry: PendingReentry?

  public val activeReentry: PendingReentry?

  public val recordRejectionSettlementPending: Boolean

  public val blocked: FeatureTaskRuntimeRunReport.Blocked?

  public val paused: FeatureTaskRuntimeRunReport.Paused?

  public val decomposed: FeatureTaskRuntimeRunReport.Decomposed?

  public fun phaseContentIdentitiesFor(phaseId: String): Map<String, String>
}
