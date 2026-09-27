package skillbill.engine.featuretask.runner

import skillbill.application.decomposition.specSource
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.continuation.agentAttributionFromPhaseState
import skillbill.engine.featuretask.lifecycle.continuation.isGoalContinuationRun
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCrashReconciliationResult
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFindingVerificationTelemetry
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRegenerationTelemetry
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.review.core.FeatureTaskRuntimeOutputVerification
import skillbill.engine.featuretask.review.core.reviewFixCapExhaustion
import skillbill.engine.featuretask.runloop.durable.DurablePhaseRunRecords
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.observability.emitFeatureTaskRuntimeEventSafely
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

fun FeatureTaskRuntimeRunner.executePreparedRun(
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
      DurablePhaseRunRecords(recorder, phaseGates.decomposeTerminalRecorder),
      runRequest,
      diagnostics,
    )
  val transitions = transitionsFor(runRequest)
  val state = createExecutePreparedRunState(runRequest, transitions)
  val telemetryContext =
    buildExecutePreparedRunTelemetryContext(
      runRequest,
      telemetrySessionId,
      reconciliation,
      state,
    )
  val report =
    runCatching {
      driveExecutePreparedRunLoop(runRequest, specSource, transitions, observability, state)
    }.onFailure { error ->
      lifecycleTelemetry.finishedError(
        telemetryContext,
        error,
      )
    }.getOrThrow()
  val terminalReport = finalizeExecutePreparedRunReport(runRequest, report, specSource)
  lifecycleTelemetry.finished(terminalReport, telemetryContext)
  return terminalReport
}

internal fun FeatureTaskRuntimeRunner.loadReviewFixIterationCount(request: FeatureTaskRuntimeRunRequest): Int =
  recorder.loadPhaseLedger(request.workflowId)
    .orEmpty()
    .filter {
      it.action == FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE &&
        it.loopId == FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID
    }
    .mapNotNull { it.edgeIteration }
    .maxOrNull()
    ?: 0

internal fun FeatureTaskRuntimeRunner.loadFindingVerificationTelemetry(
  request: FeatureTaskRuntimeRunRequest,
): FeatureTaskRuntimeFindingVerificationTelemetry {
  val capExhausted = reviewFixCapExhaustion(request.workflowId)
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

internal fun FeatureTaskRuntimeRunner.loadRegenerationTelemetry(
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

internal fun FeatureTaskRuntimeRunner.finalizingAgentId(request: FeatureTaskRuntimeRunRequest): String? =
  agentAttributionFromPhaseState(recorder.phaseQuery, request.workflowId).finalizingAgentId

internal val FeatureTaskRuntimeRunner.lifecycleTelemetry get() = phaseGates.lifecycleTelemetry
internal val FeatureTaskRuntimeRunner.specSourceResolver get() = phaseGates.specGate.specSourceResolver
