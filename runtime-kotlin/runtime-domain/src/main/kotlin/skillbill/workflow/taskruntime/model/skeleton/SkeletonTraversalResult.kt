package skillbill.workflow.taskruntime.model.skeleton

import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration

sealed interface SkeletonTraversalResult {
  data class Ready(val declaration: FeatureTaskRuntimeTransitionDeclaration) : SkeletonTraversalResult

  data class Rejected(val reason: String) : SkeletonTraversalResult
}
