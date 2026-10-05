package skillbill.workflow.taskruntime.phase.task

import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

fun SkeletonDefinition.declaration(): FeatureTaskRuntimeTransitionDeclaration =
  deriveTransitions(stepIds, entryStepIds = emptySet())

fun SkeletonDefinition.traversal(
  selectedStepIds: Set<String>,
  entryStepIds: Set<String>,
): FeatureTaskRuntimeTransitionDeclaration = deriveTransitions(stepIds.filter { it in selectedStepIds }, entryStepIds)

fun SkeletonDefinition.traversalOrViolation(
  selectedStepIds: Set<String>,
  entryStepIds: Set<String>,
): SkeletonTraversalResult {
  val steps = stepIds.filter { it in selectedStepIds }
  return if (steps.isEmpty()) {
    SkeletonTraversalResult.Rejected(
      "FeatureTaskRuntimeTransitionDeclaration.forwardPhaseIds must list at least one phase.",
    )
  } else {
    SkeletonTraversalResult.Ready(deriveTransitions(steps, entryStepIds))
  }
}

sealed interface SkeletonTraversalResult {
  data class Ready(val declaration: FeatureTaskRuntimeTransitionDeclaration) : SkeletonTraversalResult

  data class Rejected(val reason: String) : SkeletonTraversalResult
}

private fun deriveTransitions(
  steps: List<String>,
  entryStepIds: Set<String>,
): FeatureTaskRuntimeTransitionDeclaration {
  val canonical = FeatureTaskRuntimePhaseWorkflowDefinition.transitions
  val present = steps.toSet()
  val loopOnly = canonical.loopOnlyPhaseIds.filter { it in present && it !in entryStepIds }.toSet()
  return FeatureTaskRuntimeTransitionDeclaration(
    forwardPhaseIds = steps,
    entryGates = canonical.entryGates.filter { it.phaseId in present && it.requiredPhaseId in present },
    backwardEdges =
      canonical.backwardEdges.filter { it.fromPhaseId in present && it.destinationPhaseId in present },
    loopOnlyPhaseIds = loopOnly,
    loopOnlySuccessors =
      canonical.loopOnlySuccessors.filter { (source, successor) -> source in loopOnly && successor in loopOnly },
  )
}
