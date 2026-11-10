package skillbill.engine.featuretask.model.phase

import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityIdentity

public data class PlanBundleFeasibilityStateWrite(
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val expectedRevision: Int?,
  val transitionId: String?,
  val state: FeatureTaskRuntimePlanBundleFeasibilityState,
)

public data class PlanBundleFeasibilityRepairAppliedWrite(
  val workflowId: String,
  val phaseId: String,
  val attempt: Int,
  val expectedRevision: Int,
  val transitionId: String,
  val intendedIdentity: PlanBundleFeasibilityIdentity,
  val bundle: FeatureTaskRuntimePlanBundle,
  val state: FeatureTaskRuntimePlanBundleFeasibilityState,
)

public sealed interface PlanBundleFeasibilityStateWriteResult {
  public data class Applied(
    val state: FeatureTaskRuntimePlanBundleFeasibilityState,
  ) : PlanBundleFeasibilityStateWriteResult

  public data class Existing(
    val state: FeatureTaskRuntimePlanBundleFeasibilityState,
  ) : PlanBundleFeasibilityStateWriteResult

  public data class Replayed(
    val state: FeatureTaskRuntimePlanBundleFeasibilityState,
    val outcome: String,
  ) : PlanBundleFeasibilityStateWriteResult

  public data class Refused(
    val reason: String,
    val observedRevision: Int? = null,
  ) : PlanBundleFeasibilityStateWriteResult
}
