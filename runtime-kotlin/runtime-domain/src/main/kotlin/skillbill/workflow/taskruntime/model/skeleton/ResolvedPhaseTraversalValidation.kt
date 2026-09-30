package skillbill.workflow.taskruntime.model.skeleton

import skillbill.error.featuretask.InvalidPhaseStrategyCompositionError
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration

internal fun validateResolvedTraversal(
  selected: Set<String>,
  entries: Set<String>,
  declaration: FeatureTaskRuntimeTransitionDeclaration,
) {
  val forward = declaration.forwardPhaseIds
  val loopOnly = declaration.loopOnlyPhaseIds
  if (forward.toSet() != selected || forward.size != selected.size || !selected.containsAll(entries)) {
    throw InvalidPhaseStrategyCompositionError("traversal must contain exactly the selected steps and entries")
  }
  validateSelectedReferences(selected, declaration)
  validateUniqueTransitions(declaration)
  val reachable = (forward - loopOnly).toMutableSet()
  do {
    val previousSize = reachable.size
    declaration.backwardEdges.filter { it.fromPhaseId in reachable }.forEach { reachable.add(it.destinationPhaseId) }
    declaration.loopOnlySuccessors.filterKeys { it in reachable }.values.forEach(reachable::add)
  } while (previousSize != reachable.size)
  if (!reachable.containsAll(selected)) {
    throw InvalidPhaseStrategyCompositionError(
      "selected steps are unreachable: ${(selected - reachable).sorted().joinToString()}",
    )
  }
}

private fun validateSelectedReferences(
  selected: Set<String>,
  declaration: FeatureTaskRuntimeTransitionDeclaration,
) {
  val loopOnly = declaration.loopOnlyPhaseIds
  val invalidGate = declaration.entryGates.any { it.phaseId !in selected || it.requiredPhaseId !in selected }
  val invalidEdge = declaration.backwardEdges.any { it.fromPhaseId !in selected || it.destinationPhaseId !in selected }
  val invalidSuccessor =
    declaration.loopOnlySuccessors.any { (source, successor) ->
      source !in loopOnly || successor !in loopOnly
    }
  val invalidLoop = !selected.containsAll(loopOnly) || invalidSuccessor
  if (invalidLoop || invalidGate || invalidEdge) {
    throw InvalidPhaseStrategyCompositionError("traversal references an unselected step")
  }
}

private fun validateUniqueTransitions(declaration: FeatureTaskRuntimeTransitionDeclaration) {
  val edges = declaration.backwardEdges
  if (
    edges.map { it.loopId }.distinct().size != edges.size ||
    edges.map { it.fromPhaseId to it.triggeringVerdict }.distinct().size != edges.size ||
    declaration.entryGates.map { it.phaseId to it.requiredPhaseId }.distinct().size != declaration.entryGates.size
  ) {
    throw InvalidPhaseStrategyCompositionError("traversal has ambiguous remediation edges or entry gates")
  }
}
