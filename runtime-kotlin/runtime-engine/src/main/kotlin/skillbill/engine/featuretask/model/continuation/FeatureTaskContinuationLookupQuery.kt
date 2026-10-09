package skillbill.engine.featuretask.model.continuation

import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

data class FeatureTaskContinuationLookupQuery(
  val issueKey: String,
  val repositoryIdentity: String,
  val workflowId: String?,
  val routeScope: FeatureTaskRouteScope,
  val readIfPresent: Boolean = false,
  val admittedDefinition: SkeletonDefinition = SkeletonDefinition.STANDALONE,
)
