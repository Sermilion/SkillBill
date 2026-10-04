package skillbill.engine.featuretask.runloop.planning

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.lifecycle.continuation.isGoalContinuationRun
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentResolver
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePlanningStopDecision
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.runloop.observability.blocked
import skillbill.engine.featuretask.runloop.observability.emitFeatureTaskRuntimeEventSafely
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runner.STATUS_BLOCKED
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptTraversalRuntimeContext
import skillbill.engine.featuretask.slot.attempt.PhaseOutputSettlementContext
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowIfDatabaseFailure
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import java.io.IOException
import java.nio.file.Path

internal object PlanDecompositionStop {
  private const val DETAIL_MAX_CHARS = 500

  fun requiresBundle(request: FeatureTaskRuntimeRunFacts): Boolean =
    request.specBundleRequired && !isGoalContinuationRun(request)

  fun existingBundleReason(
    issueKey: String,
    existingParentSpec: Path,
  ): String =
    "Plan must author a new .feature-specs/$issueKey-<slug>/ bundle but '$existingParentSpec' already exists; " +
      "the runtime never overwrites a parent spec."

  fun withAuthoredParentSpecPath(
    context: PhaseOutputSettlementContext,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val request = context.request
    val parentSpec =
      context.decompositionPlanner.existingParentSpec(request.repoRoot, request.issueKey)
        ?: return attested
    val parentPath = request.repoRoot.toAbsolutePath().normalize().relativize(parentSpec).toString()
    val envelope = attested.envelopeWireMap().toMutableMap()
    val produced = JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]).orEmpty().toMutableMap()
    produced[SharedPayloadKeys.VALUE] = parentPath
    envelope[SharedPayloadKeys.PRODUCED_OUTPUTS] = produced
    return NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(FeatureTaskRuntimeWorkflowArtifactMap.from(envelope))
  }

  fun notReadyReason(detail: String?): String {
    val bounded =
      detail.orEmpty().takeIf(String::isNotBlank)?.let {
        if (it.length <= DETAIL_MAX_CHARS) it else it.take(DETAIL_MAX_CHARS) + "… [truncated]"
      }
    return "Plan did not author a ready spec bundle; the runtime blocks at planning rather than advancing." +
      (bounded?.let { " Readiness problem: $it" } ?: "")
  }

  fun authoredBundleRejection(
    context: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
  ): String? {
    val request = context.request
    PlanBundleAuthorization
      .violation(
        request.repoRoot,
        request.issueKey,
        capture.fileManifest.introduced,
        context.decompositionPlanner::bundleTree,
      )?.let { return notReadyReason(it) }
    return try {
      context.decompositionPlanner.verifyAuthoredBundle(
        request.repoRoot,
        request.issueKey,
        request.runInvariants,
      )
      null
    } catch (error: SkillBillRuntimeException) {
      error.rethrowIfDatabaseFailure()
      notReadyReason(error.message)
    } catch (error: IOException) {
      notReadyReason(error.message)
    }
  }

  fun apply(
    context: PhaseAttemptTraversalRuntimeContext,
    planOutput: FeatureTaskRuntimePhaseOutput,
  ): String? =
    with(context) {
      val stopper =
        FeatureTaskRuntimePlanningStopper(
          decompositionPlanner,
          recorder,
          diagnostics,
          coupledRunTransitions,
        )
      when (
        val decision =
          stopper.resolve(
            request = request,
            completedOutput = planOutput,
            completedPhaseIds = progress.completedPhaseIds,
            resolvedBranch = session.resolvedBranch,
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
  private val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner,
  private val records: PhaseRunRecords,
  private val diagnostics: RuntimeDiagnostics,
  private val coupledRunTransitions: FeatureTaskRuntimeRunTransitionOwner,
) {
  fun resolve(
    request: FeatureTaskRuntimeRunFacts,
    completedOutput: FeatureTaskRuntimePhaseOutput,
    completedPhaseIds: List<String>,
    resolvedBranch: String?,
  ): FeatureTaskRuntimePlanningStopDecision {
    if (isGoalContinuationRun(request)) {
      return FeatureTaskRuntimePlanningStopDecision.Proceed
    }

    val recordedTerminal = records.loadDecomposeTerminal(request.workflowId)
    return when {
      recordedTerminal != null ->
        FeatureTaskRuntimePlanningStopDecision.Decomposed(
          recordedTerminal.toRunReport(request, completedPhaseIds, resolvedBranch),
        )
      !request.specBundleRequired -> FeatureTaskRuntimePlanningStopDecision.Proceed
      else -> resolveAuthoredBundle(request, completedOutput, completedPhaseIds, resolvedBranch)
    }
  }

  private fun resolveAuthoredBundle(
    request: FeatureTaskRuntimeRunFacts,
    completedOutput: FeatureTaskRuntimePhaseOutput,
    completedPhaseIds: List<String>,
    resolvedBranch: String?,
  ): FeatureTaskRuntimePlanningStopDecision =
    try {
      val bundle =
        decompositionPlanner.verifyAuthoredBundle(request.repoRoot, request.issueKey, request.runInvariants)
      val terminal =
        FeatureTaskRuntimeDecomposeTerminal(
          reason = FeatureTaskRuntimeDecompositionPlanner.AUTHORED_BUNDLE_REASON,
          parentSpecPath = bundle.parentSpecPath,
          decompositionManifestPath = bundle.decompositionManifestPath,
          subtaskSpecPaths = bundle.subtaskSpecPaths,
        )
      coupledRunTransitions.persistDecomposeTerminal(
        records,
        request.workflowId,
        terminal,
        completedOutput.phaseId,
      )
      emitDecomposedAtPlanning(request, terminal, completedOutput.phaseId)
      FeatureTaskRuntimePlanningStopDecision.Decomposed(
        terminal.toRunReport(request, completedPhaseIds, resolvedBranch),
      )
    } catch (error: SkillBillRuntimeException) {
      error.rethrowIfDatabaseFailure()
      FeatureTaskRuntimePlanningStopDecision.Blocked(PlanDecompositionStop.notReadyReason(error.message))
    } catch (error: IOException) {
      FeatureTaskRuntimePlanningStopDecision.Blocked(PlanDecompositionStop.notReadyReason(error.message))
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
}
