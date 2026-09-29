package skillbill.engine.featuretask.runloop.planning

import skillbill.engine.featuretask.lifecycle.continuation.isGoalContinuationRun
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentResolver
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePlanningStopDecision
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.runloop.observability.blocked
import skillbill.engine.featuretask.runloop.observability.emitFeatureTaskRuntimeEventSafely
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunLoopTransitionOwner
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runner.STATUS_BLOCKED
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptTraversalRuntimeContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.artifact.decomposePlanOutcomeFromPhaseOutput
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeDecomposePlanOutcome
import skillbill.workflow.taskruntime.model.phase.requireAcceptedOutput
import java.io.IOException

internal object PlanDecompositionStop {
  const val SPEC_BUNDLE_MISSING_REASON =
    "Plan must persist as a governed spec bundle but emitted a direct plan with no decomposition package; " +
      "the runtime blocks rather than completing without a spec."

  fun completionRejection(
    context: PhaseStepOutputContext,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): String? {
    if (!context.request.specBundleRequired || isGoalContinuationRun(context.request)) return null
    return try {
      val outcome = decomposePlanOutcomeFromPhaseOutput(outputMap, context.specSource)
      if (outcome == null) SPEC_BUNDLE_MISSING_REASON else null
    } catch (error: SkillBillRuntimeException) {
      "Plan emitted a malformed decomposition package: ${error.message}"
    } catch (error: IllegalArgumentException) {
      "Plan emitted a malformed decomposition package: ${error.message}"
    }
  }

  fun apply(
    context: PhaseAttemptTraversalRuntimeContext,
    planOutput: FeatureTaskRuntimePhaseOutput,
  ): String? =
    with(context) {
      val stopper =
        FeatureTaskRuntimePlanningStopper(
          outputValidator,
          phaseGates.decompositionPlanner,
          recorder,
          diagnostics,
          coupledRunTransitions,
        )
      when (
        val decision =
          stopper.resolve(
            request = request,
            completedOutput = planOutput,
            completedPhaseIds = progress.completedPhaseIds(),
            resolvedBranch = session.resolvedBranch,
            specSource = specSource,
          )
      ) {
        is FeatureTaskRuntimePlanningStopDecision.Proceed -> null
        is FeatureTaskRuntimePlanningStopDecision.Decomposed -> {
          coupledRunTransitions.transitionTerminalDecomposed(decision.report)
          null
        }
        is FeatureTaskRuntimePlanningStopDecision.Blocked -> {
          persistPlanningStopBlock(context, planOutput.phaseId, decision.reason)
          decision.reason
        }
      }
    }

  private fun persistPlanningStopBlock(
    context: PhaseAttemptTraversalRuntimeContext,
    phaseId: String,
    reason: String,
  ) = with(context) {
    val resolvedAgentId =
      FeatureTaskRuntimeAgentResolver
        .resolve(
          phaseId = phaseId,
          assignment = request.agentAssignment,
          invokedAgentId = request.invokedAgentId,
        ).resolvedAgentId
    coupledRunTransitions.persistBlockedPhaseState(
      recorder,
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = request.workflowId,
        phaseId = phaseId,
        status = STATUS_BLOCKED,
        attemptCount = 1,
        resolvedAgentId = resolvedAgentId,
        finished = false,
        outputArtifact = null,
        blockedReason = reason,
      ),
    )
    observability.blocked(phaseId, resolvedAgentId, 1, reason)
  }
}

internal class FeatureTaskRuntimePlanningStopper(
  private val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  private val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner,
  private val records: PhaseRunRecords,
  private val diagnostics: RuntimeDiagnostics,
  private val coupledRunTransitions: FeatureTaskRuntimeRunLoopTransitionOwner,
) {
  fun resolve(
    request: FeatureTaskRuntimeRunFacts,
    completedOutput: FeatureTaskRuntimePhaseOutput,
    completedPhaseIds: List<String>,
    resolvedBranch: String?,
    specSource: SpecSource,
  ): FeatureTaskRuntimePlanningStopDecision {
    if (isGoalContinuationRun(request)) {
      return FeatureTaskRuntimePlanningStopDecision.Proceed
    }

    val recordedTerminal = records.loadDecomposeTerminal(request.workflowId)
    return if (recordedTerminal != null) {
      FeatureTaskRuntimePlanningStopDecision.Decomposed(
        recordedTerminal.toRunReport(request, completedPhaseIds, resolvedBranch),
      )
    } else {
      resolveFreshPlanOutput(request, completedOutput, completedPhaseIds, resolvedBranch, specSource)
    }
  }

  private fun resolveFreshPlanOutput(
    request: FeatureTaskRuntimeRunFacts,
    completedOutput: FeatureTaskRuntimePhaseOutput,
    completedPhaseIds: List<String>,
    resolvedBranch: String?,
    specSource: SpecSource,
  ): FeatureTaskRuntimePlanningStopDecision =
    try {
      resolveFromPlanOutput(request, completedOutput, completedPhaseIds, resolvedBranch, specSource)
    } catch (error: SkillBillRuntimeException) {
      FeatureTaskRuntimePlanningStopDecision.Blocked(malformedDecomposeReason(error.message.orEmpty()))
    } catch (error: IOException) {
      FeatureTaskRuntimePlanningStopDecision.Blocked(malformedDecomposeReason(error.message.orEmpty()))
    }

  private fun resolveFromPlanOutput(
    request: FeatureTaskRuntimeRunFacts,
    completedOutput: FeatureTaskRuntimePhaseOutput,
    completedPhaseIds: List<String>,
    resolvedBranch: String?,
    specSource: SpecSource,
  ): FeatureTaskRuntimePlanningStopDecision {
    val parsed =
      outputValidator
        .validatePhaseOutput(completedOutput.payload, completedOutput.phaseId)
        .requireAcceptedOutput(completedOutput.phaseId)
        .normalizedOutput
        .envelopePayload()
    val outcome =
      decomposePlanOutcomeFromPhaseOutput(parsed, specSource)
        ?: return if (request.specBundleRequired) {
          FeatureTaskRuntimePlanningStopDecision.Blocked(PlanDecompositionStop.SPEC_BUNDLE_MISSING_REASON)
        } else {
          FeatureTaskRuntimePlanningStopDecision.Proceed
        }
    val terminal = writeDecompositionTerminal(request, outcome)
    coupledRunTransitions.persistDecomposeTerminal(
      records,
      request.workflowId,
      terminal,
      completedOutput.phaseId,
    )
    emitDecomposedAtPlanning(request, terminal, completedOutput.phaseId)
    return FeatureTaskRuntimePlanningStopDecision.Decomposed(
      terminal.toRunReport(request, completedPhaseIds, resolvedBranch),
    )
  }

  private fun writeDecompositionTerminal(
    request: FeatureTaskRuntimeRunFacts,
    outcome: FeatureTaskRuntimeDecomposePlanOutcome,
  ): FeatureTaskRuntimeDecomposeTerminal {
    val writeResult =
      decompositionPlanner.writeDecomposition(
        repoRoot = request.repoRoot,
        issueKey = request.issueKey,
        runInvariants = request.runInvariants,
        outcome = outcome,
      )
    return FeatureTaskRuntimeDecomposeTerminal(
      reason = outcome.reason,
      parentSpecPath = writeResult.parentSpecPath,
      decompositionManifestPath =
        requireNotNull(writeResult.decompositionManifestPath) {
          "Decomposed feature-spec write result must include a decomposition manifest path."
        },
      subtaskSpecPaths = writeResult.subtaskSpecPaths,
    )
  }

  private fun emitDecomposedAtPlanning(
    request: FeatureTaskRuntimeRunFacts,
    terminal: FeatureTaskRuntimeDecomposeTerminal,
    planStepId: String,
  ) {
    emitFeatureTaskRuntimeEventSafely(
      diagnostics = diagnostics,
      seam = "DecomposedAtPlanning event-sink emission",
    ) {
      request.eventSink.emit(
        FeatureTaskRuntimeRunEvent.DecomposedAtPlanning(
          workflowId = request.workflowId,
          phaseId = planStepId,
          reason = terminal.reason,
          subtaskCount = terminal.subtaskCount,
          parentSpecPath = terminal.parentSpecPath,
          decompositionManifestPath = terminal.decompositionManifestPath,
        ),
      )
    }
  }

  private fun FeatureTaskRuntimeDecomposeTerminal.toRunReport(
    request: FeatureTaskRuntimeRunFacts,
    completedPhaseIds: List<String>,
    resolvedBranch: String?,
  ): FeatureTaskRuntimeRunReport.Decomposed =
    FeatureTaskRuntimeRunReport.Decomposed(
      issueKey = request.issueKey,
      workflowId = request.workflowId,
      featureSize = request.runInvariants.featureSize.name,
      reason = reason,
      completedPhaseIds = completedPhaseIds,
      parentSpecPath = parentSpecPath,
      decompositionManifestPath = decompositionManifestPath,
      subtaskSpecPaths = subtaskSpecPaths,
      resolvedBranch = resolvedBranch,
    )

  private fun malformedDecomposeReason(detail: String): String {
    val bounded =
      detail.takeIf(String::isNotBlank)?.let {
        if (it.length <= MALFORMED_DETAIL_MAX_CHARS) it else it.take(MALFORMED_DETAIL_MAX_CHARS) + "… [truncated]"
      }
    return "Plan declared mode 'decompose' but emitted a malformed decomposition package; the runtime " +
      "blocks at planning rather than crashing or advancing to implement." +
      (bounded?.let { " Schema problem: $it" } ?: "")
  }

  private companion object {
    const val MALFORMED_DETAIL_MAX_CHARS = 500
  }
}
