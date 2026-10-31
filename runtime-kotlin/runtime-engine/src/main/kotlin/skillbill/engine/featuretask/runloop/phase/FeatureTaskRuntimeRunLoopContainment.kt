package skillbill.engine.featuretask.runloop.phase

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentResolver
import skillbill.engine.featuretask.runloop.attempt.FeatureTaskRuntimeRunLoopHookViews.phaseLoopContext
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopBackwardEdge
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopPlanningBranch
import skillbill.engine.featuretask.runloop.core.containableOrThrow
import skillbill.engine.featuretask.runloop.observability.completedEvent
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runloop.state.coupledSession
import skillbill.engine.featuretask.slot.PhaseFailureContinuationOutput
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.error.core.catchingNonCancellation
import skillbill.error.core.failureCodeLabel
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput

internal object FeatureTaskRuntimeRunLoopContainment {
  fun containedNextPhase(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
    error: Exception,
    route: (String, FeatureTaskRuntimeVerdict) -> String?,
  ): String? =
    with(context) {
      val claim = catchingNonCancellation { FeatureTaskRuntimeRunLoopPlanningBranch.claimsFailure(this, phaseId) }
      claim.exceptionOrNull()?.let { claimFailure -> error.addSuppressed(claimFailure.containableOrThrow()) }
      if (claim.getOrNull() != true) throw error
      val resolved = catchingNonCancellation { containedOutput(phaseId, error) }
      val contained =
        resolved.getOrNull()
          ?: return stopContained(
            phaseId,
            error,
            "QA containment could not be resolved",
            resolved.exceptionOrNull()?.containableOrThrow(),
            originalLogged = false,
          )
      val persisted = catchingNonCancellation { persistContained(phaseId, contained.output) }
      val completion =
        persisted.getOrNull()
          ?: return stopContained(
            phaseId,
            error,
            "The runtime-authored QA completion was not persisted",
            persisted.exceptionOrNull()?.containableOrThrow(),
            originalLogged = true,
          )
      observability.completedEvent(phaseId, completion.resolvedAgentId, completion.iteration)
      val verdict = FeatureTaskRuntimeVerdict.fromWire(requireNotNull(contained.output.verdict))
      catchingNonCancellation { route(phaseId, verdict) }
        .getOrElse { routing ->
          stopContained(
            phaseId,
            error,
            "Routing after the runtime-authored QA completion failed",
            routing.containableOrThrow(),
            originalLogged = true,
          )
        }
    }

  private fun FeatureTaskRuntimeRunLoopContext.containedOutput(
    phaseId: String,
    error: Exception,
  ): PhaseFailureContinuationOutput? =
    FeatureTaskRuntimeRunLoopPlanningBranch.decideByStep(this, runState.strategyFor(phaseId).entryStep) { rules ->
      rules.containFailure(phaseId, error, phaseLoopContext())
    }

  private fun FeatureTaskRuntimeRunLoopContext.persistContained(
    phaseId: String,
    output: NormalizedFeatureTaskRuntimePhaseOutput,
  ): RuntimeAuthoredCompletion? {
    val reentry = FeatureTaskRuntimeRunLoopBackwardEdge.activeReentryCovering(this, phaseId)
    val completion =
      RuntimeAuthoredCompletion(
        workflowId = request.workflowId,
        phaseId = phaseId,
        resolvedAgentId =
          FeatureTaskRuntimeAgentResolver.resolve(
            phaseId = phaseId,
            assignment = request.agentAssignment,
            invokedAgentId = request.invokedAgentId,
          ).resolvedAgentId,
        iteration = containedAttempt(phaseId),
        output = output,
        loopId = reentry?.loopId,
        edgeIteration = reentry?.edgeIteration,
      )
    val write =
      FeatureTaskRuntimeNonBlockingFailure.persistRuntimeAuthoredCompletion(
        recorder,
        coupledRunTransitions,
        observability,
        completion,
      )
    return completion.takeIf { write == RequiredPhaseWrite.Acknowledged }
  }

  private fun FeatureTaskRuntimeRunLoopContext.containedAttempt(phaseId: String): Int {
    val progress = state.phase(phaseId)
    val acknowledged = progress.acknowledgedAttemptCount ?: 0
    return maxOf(progress.nextIteration, if (progress.completed) acknowledged + 1 else acknowledged)
  }

  private fun FeatureTaskRuntimeRunLoopContext.stopContained(
    phaseId: String,
    original: Exception,
    failure: String,
    cause: Throwable?,
    originalLogged: Boolean,
  ): String? {
    val code = original.failureCodeLabel() ?: original.javaClass.simpleName
    val message = original.message?.takeIf(String::isNotBlank) ?: original.javaClass.simpleName
    if (!originalLogged) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "QA failure could not be contained [seam=qa_containment, phase=$phaseId, code=$code].",
        original,
      )
    }
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "$failure [seam=qa_containment, phase=$phaseId, original=$code: $message].",
      cause ?: original,
    )
    FeatureTaskRuntimeRunLoopPhaseBlocking.blockAt(request, state, runState.coupledSession(), phaseId, message)
    return null
  }
}
