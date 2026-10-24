package skillbill.engine.featuretask.runloop.planning

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.lifecycle.continuation.isGoalContinuationRun
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentResolver
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePlanningStopDecision
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.phase.planning.PlanBundleSnapshots
import skillbill.engine.featuretask.phase.planning.PlanBundleTarget
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.runloop.observability.blocked
import skillbill.engine.featuretask.runloop.observability.emitFeatureTaskRuntimeEventSafely
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runner.STATUS_BLOCKED
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptTraversalRuntimeContext
import skillbill.engine.featuretask.slot.attempt.PhaseOutputSettlementContext
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowIfDatabaseFailure
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.text.sha256HexUtf8
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundleOrigin
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.io.IOException

internal object PlanDecompositionStop {
  private const val DETAIL_MAX_CHARS = 500

  fun withAuthoredParentSpecPath(
    context: PhaseOutputSettlementContext,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val request = context.request
    val parentSpec =
      context.decompositionPlanner.existingParentSpec(request.repoRoot, request.issueKey)
        ?: return attested
    val parentPath = context.decompositionPlanner.bundleLabel(request.repoRoot, parentSpec)
    val envelope = attested.envelopeWireMap().toMutableMap()
    val produced = JsonCodec.anyToStringAnyMap(envelope[SharedPayloadKeys.PRODUCED_OUTPUTS]).orEmpty().toMutableMap()
    produced[SharedPayloadKeys.VALUE] = parentPath
    envelope[SharedPayloadKeys.PRODUCED_OUTPUTS] = produced
    return NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(FeatureTaskRuntimeWorkflowArtifactMap.from(envelope))
  }

  fun settledOutput(
    phaseId: String,
    summary: String,
    value: String,
  ): NormalizedFeatureTaskRuntimePhaseOutput =
    NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        linkedMapOf<String, Any?>(
          SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
          SharedPayloadKeys.PHASE_ID to phaseId,
          SharedPayloadKeys.STATUS to STATUS_COMPLETED,
          SharedPayloadKeys.SUMMARY to summary,
          SharedPayloadKeys.PRODUCED_OUTPUTS to linkedMapOf<String, Any?>(SharedPayloadKeys.VALUE to value),
        ),
      ),
    )

  fun notReadyReason(detail: String?): String {
    val bounded =
      detail.orEmpty().takeIf(String::isNotBlank)?.let {
        if (it.length <= DETAIL_MAX_CHARS) it else it.take(DETAIL_MAX_CHARS) + "… [truncated]"
      }
    return "Plan did not author a ready spec bundle; the runtime blocks at planning rather than advancing." +
      (bounded?.let { " Readiness problem: $it" } ?: "")
  }

  fun recordAuthoredBundleOrRejection(
    context: PhaseOutputSettlementContext,
    capture: ValidatedOutputCapture,
  ): String? {
    val request = context.request
    val planner = context.decompositionPlanner
    val target = PlanBundleStagingPromotion.targetFor(planner, capture.run)
    val introduced = capture.fileManifest.introduced
    val authoredSince = context.progress.phase(capture.run.phaseId).record?.firstStartedAt
    val listing =
      PlanBundleListing(
        planner::bundleTree,
        planner::bundleChildDirectories,
        planner::bundleLastModified,
        authoredSince,
      )
    if (target is PlanBundleTarget.Staged) {
      PlanBundleAuthorization.violation(request.issueKey, target, introduced, listing)
        ?.let { violation -> return notReadyReason(violation) }
      PlanBundleStagingPromotion.promote(
        planner,
        context.diagnostics,
        PromotionRequest(
          target,
          request.repoRoot,
          capture.run.resolvedAgent.resolvedAgentId,
          parentSpecProtected = request.protectedSpecSha256 != null,
        ),
      )
    }
    protectedSpecRejection(context)?.let { return it }
    val authorized = if (target is PlanBundleTarget.Staged) PlanBundleTarget.Store(target.storeDirectory) else target
    PlanBundleAuthorization.violation(request.issueKey, authorized, introduced, listing)
      ?.let { violation -> return notReadyReason(violation) }
    return recordBundle(context, FeatureTaskRuntimePlanBundleOrigin.AUTHORED, keptUnitStepId = null)
  }

  fun recordExistingBundleOrRejection(
    context: PhaseOutputSettlementContext,
    unitStepId: String,
  ): String? = recordBundle(context, FeatureTaskRuntimePlanBundleOrigin.EXISTING, unitStepId)

  private fun recordBundle(
    context: PhaseOutputSettlementContext,
    origin: FeatureTaskRuntimePlanBundleOrigin,
    keptUnitStepId: String?,
  ): String? {
    val request = context.request
    return try {
      val written =
        context.decompositionPlanner.verifyAuthoredBundle(
          request.repoRoot,
          request.issueKey,
          request.runInvariants,
        )
      val bundle = PlanBundleSnapshots(context.decompositionPlanner).capture(request.repoRoot, written, origin)
      val now = context.clock.instant()
      val keptUnits =
        keptUnitStepId?.let { stepId ->
          bundle.subtasks.mapNotNull { subtask ->
            subtask.planText?.let { details ->
              subtask.id to
                PlanUnitRecords.keptDetails(PlanUnit(request.workflowId, stepId, subtask.id), details, now)
            }
          }.toMap()
        }.orEmpty()
      if (context.recorder.recordPlanBundleWithUnits(request.workflowId, bundle, keptUnits)) {
        null
      } else {
        notReadyReason("The plan bundle snapshot could not be recorded.")
      }
    } catch (error: SkillBillRuntimeException) {
      error.rethrowIfDatabaseFailure()
      notReadyReason(error.message)
    } catch (error: IOException) {
      notReadyReason(error.message)
    }
  }

  private fun protectedSpecRejection(context: PhaseOutputSettlementContext): String? {
    val request = context.request
    val expected = request.protectedSpecSha256 ?: return null
    val parentSpec = context.decompositionPlanner.existingParentSpec(request.repoRoot, request.issueKey)
    val current =
      try {
        parentSpec?.let { sha256HexUtf8(context.decompositionPlanner.specText(it)) }
      } catch (error: IOException) {
        return notReadyReason(error.message)
      }
    return if (current == expected) {
      null
    } else {
      notReadyReason(
        "The operator-authored spec.md changed during planning; the plan must leave it byte-identical " +
          "and author only the manifest and subtask specs.",
      )
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
    return if (recordedTerminal != null) {
      FeatureTaskRuntimePlanningStopDecision.Decomposed(
        recordedTerminal.toRunReport(request, completedPhaseIds, resolvedBranch),
      )
    } else {
      resolveAuthoredBundle(request, completedOutput, completedPhaseIds, resolvedBranch)
    }
  }

  private fun unplannedBundleReason(request: FeatureTaskRuntimeRunFacts): String? {
    val snapshots = PlanBundleSnapshots(decompositionPlanner)
    val recorded =
      records.loadPlanBundle(request.workflowId)
        ?: return "The plan workflow recorded no plan bundle; re-plan the subtasks."
    snapshots.drift(request.repoRoot, request.issueKey, recorded)?.let { return it }
    val unitRecords = records.loadPhaseRecords(request.workflowId).orEmpty()
    return recorded.subtasks
      .firstOrNull {
        !snapshots.unitComplete(
          request.repoRoot,
          it,
          unitRecords[FeatureTaskRuntimePhaseWorkflowDefinition.planSubtaskUnitRecordId(it.id)],
        )
      }
      ?.let { "Subtask ${it.id} has no recorded Implementation Details that match its spec on disk." }
  }

  private fun resolveAuthoredBundle(
    request: FeatureTaskRuntimeRunFacts,
    completedOutput: FeatureTaskRuntimePhaseOutput,
    completedPhaseIds: List<String>,
    resolvedBranch: String?,
  ): FeatureTaskRuntimePlanningStopDecision =
    try {
      unplannedBundleReason(request)?.let {
        return FeatureTaskRuntimePlanningStopDecision.Blocked(PlanDecompositionStop.notReadyReason(it))
      }
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
