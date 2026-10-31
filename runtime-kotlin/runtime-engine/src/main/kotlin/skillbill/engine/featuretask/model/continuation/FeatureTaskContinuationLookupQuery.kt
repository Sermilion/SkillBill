package skillbill.engine.featuretask.model.continuation

import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

public data class FeatureTaskContinuationLookupQuery(
  public val issueKey: String,
  public val repositoryIdentity: String,
  public val workflowId: String?,
  public val routeScope: FeatureTaskRouteScope,
  internal val admittedDefinition: SkeletonDefinition,
  public val readIfPresent: Boolean = false,
)
