package skillbill.workflow.taskruntime.phase.task

import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.skeleton.SkeletonTraversalResult

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
    val reversedGate =
      FeatureTaskRuntimePhaseWorkflowDefinition.transitions.entryGates.firstOrNull {
        it.phaseId in steps && it.requiredPhaseId in steps &&
          steps.indexOf(it.requiredPhaseId) >= steps.indexOf(it.phaseId)
      }
    if (reversedGate == null) {
      SkeletonTraversalResult.Ready(deriveTransitions(steps, entryStepIds))
    } else {
      SkeletonTraversalResult.Rejected(
        "FeatureTaskRuntimePhaseEntryGate requires '${reversedGate.requiredPhaseId}' to precede " +
          "'${reversedGate.phaseId}' in the forward pipeline, but it is at index " +
          "${steps.indexOf(reversedGate.requiredPhaseId)} against ${steps.indexOf(reversedGate.phaseId)}.",
      )
    }
  }
}

private fun deriveTransitions(
  steps: List<String>,
  entryStepIds: Set<String>,
): FeatureTaskRuntimeTransitionDeclaration {
  val canonical = FeatureTaskRuntimePhaseWorkflowDefinition.transitions
  val present = steps.toSet()
  val loopOnly =
    (canonical.loopOnlyPhaseIds - entryStepIds).filter(present::contains).toSet()
  val backwardEdges =
    canonical.backwardEdges.filter { edge ->
      val requiredSteps = FeatureTaskRuntimePhaseWorkflowTransitions.composedOnlyWithSteps[edge.loopId].orEmpty()
      val forbiddenSteps = FeatureTaskRuntimePhaseWorkflowTransitions.composedOnlyWithoutSteps[edge.loopId].orEmpty()
      edge.fromPhaseId in present && edge.destinationPhaseId in present &&
        requiredSteps.all(present::contains) && forbiddenSteps.none(present::contains)
    }
  return FeatureTaskRuntimeTransitionDeclaration(
    forwardPhaseIds = steps,
    entryGates = canonical.entryGates.filter { it.phaseId in present && it.requiredPhaseId in present },
    backwardEdges = backwardEdges,
    loopOnlyPhaseIds = loopOnly,
    loopOnlySuccessors =
      canonical.loopOnlySuccessors.filter { (source, successor) -> source in loopOnly && successor in loopOnly },
  )
}
