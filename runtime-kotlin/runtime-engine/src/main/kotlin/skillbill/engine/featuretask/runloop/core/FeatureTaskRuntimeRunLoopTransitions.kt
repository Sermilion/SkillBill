package skillbill.engine.featuretask.runloop.core

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.persist.sha256Hex
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeStepVerdictRule
import skillbill.engine.featuretask.runloop.checkpoint.FeatureTaskRuntimeRunLoopCheckpointRemediation
import skillbill.engine.featuretask.runloop.observability.blocked
import skillbill.engine.featuretask.runloop.observability.loopEdge
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runloop.state.coupledSession
import skillbill.engine.featuretask.runner.skeletonDefinitionFor
import skillbill.engine.featuretask.slot.PhaseStrategyLookup
import skillbill.engine.featuretask.slot.PhaseStrategySelectionFacts
import skillbill.engine.featuretask.slot.attempt.PhaseRunLoopAttemptCollaborators
import skillbill.engine.featuretask.slot.state.AcceptanceCriterionRepairCycleRecords
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
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
  private enum class FinalAuditRoute {
    NOT_REQUIRED,
    REENTER_AUDIT,
    STOP,
  }

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
        loopId == null ->
          when (requireFinalAuditAfterCheckpoint(context)) {
            FinalAuditRoute.REENTER_AUDIT ->
              requireNotNull((context.request as FeatureTaskRuntimeRunRequest).acceptanceCriterionRepair?.steps).audit
            FinalAuditRoute.STOP -> null
            FinalAuditRoute.NOT_REQUIRED -> transition.phaseId
          }
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

  private fun requireFinalAuditAfterCheckpoint(
    context: FeatureTaskRuntimeRunLoopContext,
  ): FinalAuditRoute {
    val request = context.request as? FeatureTaskRuntimeRunRequest ?: return FinalAuditRoute.NOT_REQUIRED
    val scope = request.acceptanceCriterionRepair ?: return FinalAuditRoute.NOT_REQUIRED
    val state = scope.snapshot ?: return FinalAuditRoute.NOT_REQUIRED
    if (state.stage != AcceptanceCriterionRepairStage.FINAL_ACCEPTED) return FinalAuditRoute.NOT_REQUIRED
    val auditPhase = requireNotNull(scope.steps).audit
    val accepted = state.finalAudits.lastOrNull()
      ?: return blockFinalAudit(context, auditPhase, "The accepted final audit has no durable proof.")
    val fingerprint = context.gitOperations.repositoryFingerprint(request.repoRoot)
    if (!fingerprint.ok || fingerprint.value.isBlank()) {
      return blockFinalAudit(
        context,
        auditPhase,
        "The source fingerprint could not be verified after the final audit: ${fingerprint.error}",
      )
    }
    if (sha256Hex(fingerprint.value) == accepted.treeDigest) return FinalAuditRoute.NOT_REQUIRED
    val record = context.state.phase(auditPhase).record
      ?: return blockFinalAudit(context, auditPhase, "The final audit phase record is missing.")
    val phase =
      FeatureTaskRuntimePhaseStateRequest(
        request.workflowId,
        auditPhase,
        WorkflowStepStatus.COMPLETED.wireValue,
        record.attemptCount,
        record.resolvedAgentId,
        true,
        outputArtifact = record.outputArtifact,
        loopId = record.loopId,
        edgeIteration = record.edgeIteration,
      )
    val committed = (context.recorder as? AcceptanceCriterionRepairCycleRecords)?.requireFinalAudit(phase) ?: false
    if (committed) {
      context.runState.coupledRunTransitions.reenterAcceptanceSteps(listOf(auditPhase))
      return FinalAuditRoute.REENTER_AUDIT
    }
    return blockFinalAudit(context, auditPhase, "The changed source could not be admitted for a new final audit.")
  }

  private fun blockFinalAudit(
    context: FeatureTaskRuntimeRunLoopContext,
    auditPhase: String,
    reason: String,
  ): FinalAuditRoute {
    val record = context.state.phase(auditPhase).record
    context.observability.blocked(
      auditPhase,
      record?.resolvedAgentId ?: context.request.invokedAgentId,
      record?.attemptCount ?: 0,
      reason,
    )
    FeatureTaskRuntimeRunLoopPhaseBlocking.blockAt(
      context.request,
      context.state,
      context.runState.coupledSession(),
      auditPhase,
      reason,
    )
    return FinalAuditRoute.STOP
  }
}
