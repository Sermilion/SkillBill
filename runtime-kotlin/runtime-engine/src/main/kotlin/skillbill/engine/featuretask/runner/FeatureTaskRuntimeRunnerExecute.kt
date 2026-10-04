package skillbill.engine.featuretask.runner

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.continuation.isGoalContinuationRun
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeAgentContextTelemetry
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeLifecycleTelemetry
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCrashReconciliationResult
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFindingVerificationTelemetry
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFinishedTelemetryContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRegenerationTelemetry
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.prepare.SpecSourceResolver
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeOutputVerification
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeReviewFixBudget
import skillbill.engine.featuretask.runloop.durable.DurablePhaseRunRecords
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.observability.emitFeatureTaskRuntimeEventSafely
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

@Inject
class FeatureTaskRuntimeRunnerExecute(
  private val specSourceResolver: SpecSourceResolver,
  private val diagnostics: RuntimeDiagnostics,
  private val lifecycleTelemetry: FeatureTaskRuntimeLifecycleTelemetry,
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
  private val executePrepared: FeatureTaskRuntimeRunnerExecutePrepared,
  private val reviewFixBudget: FeatureTaskRuntimeReviewFixBudget,
  private val agentContextTelemetry: FeatureTaskRuntimeAgentContextTelemetry,
) {
  fun foreignModeWorkflowBlock(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeRunReport.Blocked? {
    val existingMode = recorder.existingWorkflowMode(request.workflowId)
    if (existingMode == null || existingMode == FeatureTaskWorkflowMode.RUNTIME) {
      return null
    }
    return FeatureTaskRuntimeRunReport.Blocked(
      issueKey = request.issueKey,
      workflowId = request.workflowId,
      featureSize = request.runInvariants.featureSize.name,
      lastIncompletePhase = FeatureTaskRuntimePhaseWorkflowDefinition.definition.defaultInitialStepId,
      blockedReason =
        "Cannot resume workflow '${request.workflowId}' in runtime mode: it was created in " +
          "'${existingMode.wireValue}' mode. A feature-task workflow is mode-scoped — prose and runtime are " +
          "not interchangeable. Finish this subtask in '${existingMode.wireValue}' mode, or reset the subtask " +
          "to start a fresh runtime attempt.",
      completedPhaseIds = emptyList(),
      resolvedBranch = null,
    )
  }

  fun terminalWorkflowBlock(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimeRunReport.Blocked? {
    val terminal = recorder.phaseQuery.terminalWorkflow(request.workflowId) ?: return null
    return FeatureTaskRuntimeRunReport.Blocked(
      issueKey = request.issueKey,
      workflowId = request.workflowId,
      featureSize = request.runInvariants.featureSize.name,
      lastIncompletePhase = terminal.currentStepId,
      blockedReason = "Terminal workflows cannot resume execution or regenerate receipts.",
      completedPhaseIds = emptyList(),
      resolvedBranch = null,
    )
  }

  fun executePreparedRun(
    runRequest: FeatureTaskRuntimeRunRequest,
    reconciliation: FeatureTaskRuntimeCrashReconciliationResult,
  ): FeatureTaskRuntimeRunReport {
    val specSource =
      specSourceResolver.resolve(
        repoRoot = runRequest.repoRoot,
        specReference = runRequest.runInvariants.specReference,
        isGoalContinuation = isGoalContinuationRun(runRequest),
      )
    emitFeatureTaskRuntimeEventSafely(
      diagnostics = diagnostics,
      seam = "RunStarted event-sink emission",
    ) {
      runRequest.eventSink.emit(
        FeatureTaskRuntimeRunEvent.RunStarted(runRequest.workflowId, runRequest.runInvariants.featureSize.name),
      )
    }
    val telemetrySessionId = lifecycleTelemetry.started(runRequest)
    val observability =
      FeatureTaskRuntimeRunObservability(
        DurablePhaseRunRecords(recorder, decomposeTerminalRecorder, runRequest.admittedExecution),
        runRequest,
        diagnostics,
      )
    val executionPlan =
      requireNotNull(runRequest.admittedExecution).let { admitted ->
        check(admitted.identity.workflowId == runRequest.workflowId) {
          "Execution admission belongs to another workflow."
        }
        admitted.plan
      }
    val state = executePrepared.createExecutePreparedRunState(runRequest, executionPlan, diagnostics)
    val telemetryContext =
      buildExecutePreparedRunTelemetryContext(
        runRequest,
        telemetrySessionId,
        reconciliation,
        state,
      )
    val report =
      runCatching {
        executePrepared.driveExecutePreparedRunLoop(
          runRequest,
          specSource,
          executionPlan,
          observability,
          state,
        )
      }.onFailure { error ->
        lifecycleTelemetry.finishedError(
          telemetryContext,
          error,
        )
      }.getOrThrow()
    val terminalReport = executePrepared.finalizeExecutePreparedRunReport(runRequest, report, specSource, executionPlan)
    lifecycleTelemetry.finished(terminalReport, telemetryContext)
    return terminalReport
  }

  private fun buildExecutePreparedRunTelemetryContext(
    runRequest: FeatureTaskRuntimeRunRequest,
    telemetrySessionId: String,
    reconciliation: FeatureTaskRuntimeCrashReconciliationResult,
    state: FeatureTaskRuntimeRunState,
  ) = FeatureTaskRuntimeFinishedTelemetryContext(
    telemetrySessionId = telemetrySessionId,
    phaseOutcomes = {
      recorder
        .loadPhaseRecords(runRequest.workflowId)
        .orEmpty()
        .mapValues { (_, record) -> record.status.wireValue }
    },
    reviewFixIterationCount = { loadReviewFixIterationCount(runRequest) },
    auditGapIterationCount = { reviewFixBudget.auditGapIterationCount(runRequest.workflowId) },
    agentContext = { agentContextTelemetry.context(runRequest.workflowId) },
    regenerationTelemetry = { loadRegenerationTelemetry(runRequest) },
    findingVerificationTelemetry = { loadFindingVerificationTelemetry(runRequest) },
    phaseTokenData = { serializeTokenData(state.phaseTokenView) },
    crashReconciliation = { reconciliation },
  )

  internal fun loadReviewFixIterationCount(request: FeatureTaskRuntimeRunRequest): Int =
    recorder.loadPhaseLedger(request.workflowId)
      .orEmpty()
      .filter {
        it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
          it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
      }
      .mapNotNull { it.edgeIteration }
      .maxOrNull()
      ?: 0

  internal fun loadFindingVerificationTelemetry(
    request: FeatureTaskRuntimeRunRequest,
  ): FeatureTaskRuntimeFindingVerificationTelemetry {
    val capExhausted = reviewFixBudget.reviewFixCapExhaustion(request.workflowId)
    val verifyStepId =
      FeatureTaskRuntimePhaseWorkflowDefinition.transitions.backwardEdges
        .firstOrNull { edge -> edge.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID }
        ?.fromPhaseId
    val verifyRecord =
      verifyStepId?.let { stepId -> recorder.loadPhaseRecords(request.workflowId)?.get(stepId) }
        ?: return FeatureTaskRuntimeFindingVerificationTelemetry(reviewFixCapExhausted = capExhausted)
    val outputMap =
      verifyRecord.outputArtifact
        ?.let(JsonCodec::parseObjectOrNull)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?.toWorkflowArtifactMap()
        ?: return FeatureTaskRuntimeFindingVerificationTelemetry(reviewFixCapExhausted = capExhausted)
    return FeatureTaskRuntimeFindingVerificationTelemetry(
      verifiedCount = FeatureTaskRuntimeOutputVerification.verifiedFindingDispositions(outputMap).size,
      rejectedCount = FeatureTaskRuntimeOutputVerification.rejectedFindingDispositions(outputMap).size,
      reviewFixCapExhausted = capExhausted,
    )
  }

  internal fun loadRegenerationTelemetry(
    request: FeatureTaskRuntimeRunRequest,
  ): FeatureTaskRuntimeRegenerationTelemetry {
    val ledger = recorder.loadPhaseLedger(request.workflowId).orEmpty()
    val regenFires =
      ledger.filter {
        it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
          FeatureTaskRuntimePhaseWorkflowDefinition.isRegenerationLoopId(it.loopId.orEmpty())
      }
    val firedLoops = regenFires.mapNotNull { it.loopId }.toSet()
    val blocked =
      recorder.loadPhaseRecords(request.workflowId)
        .orEmpty()
        .values
        .filter { it.status.workflowStepStatus() == WorkflowStepStatus.BLOCKED }
    val capExhaustedLoops =
      blocked
        .mapNotNull { it.loopId }
        .filter(FeatureTaskRuntimePhaseWorkflowDefinition::isRegenerationLoopId)
        .toSet()
    val unattributable =
      blocked.count {
        (it.blockedReason ?: "").contains("cannot attribute to a producing phase")
      }
    val producerNotInPipeline =
      blocked.count {
        (it.blockedReason ?: "").contains("absent from this run's resolved pipeline")
      }
    val regenerated = (firedLoops - capExhaustedLoops).size
    val outcomeCounts =
      buildMap {
        if (regenerated > 0) put("regenerated", regenerated)
        if (capExhaustedLoops.isNotEmpty()) put("cap_exhausted", capExhaustedLoops.size)
        if (unattributable > 0) put("unattributable", unattributable)
        if (producerNotInPipeline > 0) put("producer_not_in_pipeline", producerNotInPipeline)
      }
    return FeatureTaskRuntimeRegenerationTelemetry(
      activationCount = firedLoops.size,
      attemptCount = regenFires.size,
      outcomeCounts = outcomeCounts,
    )
  }
}
