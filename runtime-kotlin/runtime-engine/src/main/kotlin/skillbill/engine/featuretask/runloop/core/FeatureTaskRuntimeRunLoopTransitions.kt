package skillbill.engine.featuretask.runloop.core

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.engine.featuretask.runloop.checkpoint.FeatureTaskRuntimeRunLoopCheckpointRemediation
import skillbill.engine.featuretask.runloop.observability.loopEdge
import skillbill.engine.featuretask.runner.skeletonDefinitionFor
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.engine.featuretask.slot.attempt.PhaseRunLoopAttemptCollaborators
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeNextPhase
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan

internal fun strategySelectionFacts(request: FeatureTaskRuntimeRunFacts): PhaseStrategySelectionFacts =
  PhaseStrategySelectionFacts(
    skeletonDefinitionFor(request),
    setOfNotNull(request.runInvariants.codeReviewMode, request.goalContinuation?.qualityGateSelection),
  )

internal fun slotStepVerdictRule(
  strategies: PhaseStrategyLookup,
  plan: ResolvedPhaseExecutionPlan,
  diagnostics: RuntimeDiagnostics,
): (String) -> FeatureTaskRuntimeStepVerdictRule? = { stepId -> strategies.verdictRule(stepId, plan, diagnostics) }

internal fun spanBetween(
  transitions: FeatureTaskRuntimeTransitionDeclaration,
  destinationPhaseId: String,
  sourcePhaseId: String,
): List<String> = transitions.spanBetween(destinationPhaseId, sourcePhaseId)

object FeatureTaskRuntimeRunLoopTransitions {
  internal fun transitionTarget(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
    edge: FeatureTaskRuntimeBackwardEdge?,
    effectiveVerdict: FeatureTaskRuntimeVerdict,
    transition: FeatureTaskRuntimeNextPhase,
  ): String? =
    with(context) {
      when (transition) {
        is FeatureTaskRuntimeNextPhase.TerminalAdvance -> null
        is FeatureTaskRuntimeNextPhase.TerminalBlock -> {
          FeatureTaskRuntimeRunLoopPlanningBranch.blockOnCapExhaustion(context, phaseId, transition)
          null
        }
        is FeatureTaskRuntimeNextPhase.Next ->
          nextTransitionTarget(
            context,
            phaseId,
            edge,
            effectiveVerdict,
            transition,
          )
      }
    }

  internal fun nextTransitionTarget(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
    edge: FeatureTaskRuntimeBackwardEdge?,
    effectiveVerdict: FeatureTaskRuntimeVerdict,
    transition: FeatureTaskRuntimeNextPhase.Next,
  ): String? =
    with(context) {
      val loopId = transition.loopId
      return when {
        loopId == null &&
          !establishForwardCheckpoint(
            context,
            precedingPhaseId = phaseId,
            destinationPhaseId = transition.phaseId,
          )
        -> null
        loopId == null -> transition.phaseId
        reentersMutatingPhase(context, requireNotNull(edge), transition.phaseId) &&
          !with(FeatureTaskRuntimeRunLoopCheckpointRemediation) {
            FeatureTaskRuntimeRunLoopCheckpointRemediation.establishRemediationCheckpoint(context, phaseId, loopId)
          } -> null
        else -> {
          with(FeatureTaskRuntimeRunLoopBackwardEdge) {
            FeatureTaskRuntimeRunLoopBackwardEdge.recordBackwardEdge(
              context,
              edge = requireNotNull(edge),
              edgeIteration = requireNotNull(transition.edgeIteration),
              verdict = effectiveVerdict,
            )
            observability.loopEdge(
              transition.phaseId,
              loopId,
              requireNotNull(transition.edgeIteration),
              effectiveVerdict,
            )
            FeatureTaskRuntimeRunLoopBackwardEdge.warnOnThresholdCrossing(
              request,
              diagnostics,
              requireNotNull(edge),
              requireNotNull(transition.edgeIteration),
            )
          }
          transition.phaseId
        }
      }
    }

  internal fun reentersMutatingPhase(
    context: PhaseRunLoopAttemptCollaborators,
    edge: FeatureTaskRuntimeBackwardEdge,
    destinationPhaseId: String,
  ): Boolean =
    spanBetween(
      context.transitions,
      destinationPhaseId,
      edge.fromPhaseId,
    ).any { context.acceptedStepPolicy(it).mutating }

  internal fun establishForwardCheckpoint(
    context: PhaseRunLoopAttemptCollaborators,
    precedingPhaseId: String,
    destinationPhaseId: String,
  ): Boolean {
    val checkpoint =
      context.strategyFor(precedingPhaseId).loopRules?.forwardCheckpoint(precedingPhaseId, destinationPhaseId)
        ?: return true
    return FeatureTaskRuntimeRunLoopCheckpointRemediation.checkpointEstablished(
      context,
      precedingPhaseId = precedingPhaseId,
      loopId = null,
      intent = checkpoint.intent,
      blockedReason = checkpoint.blockedReason,
    )
  }
}
