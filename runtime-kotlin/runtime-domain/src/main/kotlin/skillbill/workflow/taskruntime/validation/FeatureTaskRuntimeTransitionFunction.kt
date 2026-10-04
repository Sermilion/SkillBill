package skillbill.workflow.taskruntime.validation

import skillbill.error.shellcontent.featureTaskRuntimePhaseOrderViolationMessage
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeCapExhaustionBehavior
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionContext
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionResult

object FeatureTaskRuntimeTransitionFunction {
  fun nextTransition(
    declaration: FeatureTaskRuntimeTransitionDeclaration,
    currentPhaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
    edgeIterationCount: Int,
    context: FeatureTaskRuntimeTransitionContext = FeatureTaskRuntimeTransitionContext(),
  ): FeatureTaskRuntimeTransitionResult {
    val transition = computeTransition(declaration, currentPhaseId, verdict, edgeIterationCount)
    return guardEntryGate(declaration, transition, context.settledVerdictsByPhaseId)
      ?: FeatureTaskRuntimeTransitionResult.Resolved(transition)
  }

  private fun guardEntryGate(
    declaration: FeatureTaskRuntimeTransitionDeclaration,
    transition: FeatureTaskRuntimeNextPhase,
    settledVerdictsByPhaseId: Map<String, FeatureTaskRuntimeVerdict>,
  ): FeatureTaskRuntimeTransitionResult.PhaseOrderViolation? {
    val targetPhaseId = (transition as? FeatureTaskRuntimeNextPhase.Next)?.phaseId ?: return null
    val gate = declaration.entryGateViolation(targetPhaseId, settledVerdictsByPhaseId) ?: return null
    val observedVerdict = settledVerdictsByPhaseId[gate.requiredPhaseId]?.wireValue
    return FeatureTaskRuntimeTransitionResult.PhaseOrderViolation(
      phaseId = gate.phaseId,
      message =
        featureTaskRuntimePhaseOrderViolationMessage(
          gate.phaseId,
          gate.requiredPhaseId,
          gate.requiredVerdict.wireValue,
          observedVerdict,
        ),
    )
  }

  private fun computeTransition(
    declaration: FeatureTaskRuntimeTransitionDeclaration,
    currentPhaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
    edgeIterationCount: Int,
  ): FeatureTaskRuntimeNextPhase {
    require(edgeIterationCount >= 0) {
      "FeatureTaskRuntimeTransitionFunction.edgeIterationCount must be non-negative, was $edgeIterationCount."
    }
    matchingBackwardEdge(declaration, currentPhaseId, verdict)?.let { edge ->
      val mayReenter = edge.perEdgeCap?.let { edgeIterationCount < it } ?: true
      return if (mayReenter) {
        FeatureTaskRuntimeNextPhase.Next(
          phaseId = edge.destinationPhaseId,
          loopId = edge.loopId,
          edgeIteration = edgeIterationCount + 1,
        )
      } else {
        when (edge.capExhaustionBehavior) {
          FeatureTaskRuntimeCapExhaustionBehavior.ADVANCE -> forwardTransition(declaration, currentPhaseId)
          FeatureTaskRuntimeCapExhaustionBehavior.BLOCK ->
            FeatureTaskRuntimeNextPhase.TerminalBlock(
              loopId = edge.loopId,
              edgeIteration = edgeIterationCount,
              unresolvedVerdict = verdict,
            )
        }
      }
    }
    return forwardTransition(declaration, currentPhaseId)
  }

  private fun matchingBackwardEdge(
    declaration: FeatureTaskRuntimeTransitionDeclaration,
    currentPhaseId: String,
    verdict: FeatureTaskRuntimeVerdict,
  ): FeatureTaskRuntimeBackwardEdge? =
    declaration.backwardEdges.firstOrNull { edge ->
      edge.fromPhaseId == currentPhaseId && edge.triggeringVerdict == verdict
    }

  private fun forwardTransition(
    declaration: FeatureTaskRuntimeTransitionDeclaration,
    currentPhaseId: String,
  ): FeatureTaskRuntimeNextPhase {
    val index = declaration.forwardPhaseIds.indexOf(currentPhaseId)
    require(index >= 0) {
      "Feature-task-runtime transition: phase '$currentPhaseId' is not in the forward pipeline."
    }
    declaration.loopOnlySuccessors[currentPhaseId]?.let { successor ->
      return FeatureTaskRuntimeNextPhase.Next(phaseId = successor)
    }
    val nextIndex =
      (index + 1 until declaration.forwardPhaseIds.size)
        .firstOrNull { declaration.forwardPhaseIds[it] !in declaration.loopOnlyPhaseIds }
    return if (nextIndex != null) {
      FeatureTaskRuntimeNextPhase.Next(phaseId = declaration.forwardPhaseIds[nextIndex])
    } else {
      FeatureTaskRuntimeNextPhase.TerminalAdvance
    }
  }
}
