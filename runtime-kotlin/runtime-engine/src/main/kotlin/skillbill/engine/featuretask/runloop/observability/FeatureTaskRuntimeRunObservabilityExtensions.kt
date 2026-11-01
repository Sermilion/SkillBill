package skillbill.engine.featuretask.runloop.observability

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction

public fun FeatureTaskRuntimeRunObservability.fixLoopIteration(
  phaseId: String,
  resolvedAgentId: String,
  attemptCount: Int,
  fixLoopIteration: Int,
) {
  continuation(
    phaseId,
    resolvedAgentId,
    attemptCount,
    fixLoopIteration,
    FeatureTaskRuntimeContinuationKind.SCHEMA_CORRECTION,
  )
}

public fun FeatureTaskRuntimeRunObservability.continuation(
  phaseId: String,
  resolvedAgentId: String,
  attemptCount: Int,
  iteration: Int,
  kind: FeatureTaskRuntimeContinuationKind,
) {
  emitSafely(
    FeatureTaskRuntimeRunEvent.PhaseFixLoopIteration(
      workflowId = observabilityRequest.workflowId,
      phaseId = phaseId,
      resolvedAgentId = resolvedAgentId,
      attemptCount = attemptCount,
      fixLoopIteration = iteration,
      continuationKind = kind.wireValue,
    ),
  )
  appendLedger(
    FeatureTaskRuntimePhaseLedgerRequest(
      workflowId = observabilityRequest.workflowId,
      action = FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION,
      phaseId = phaseId,
      attemptCount = attemptCount,
      resolvedAgentId = resolvedAgentId,
      fixLoopIteration = iteration,
      blockedReason = "${FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX}${kind.wireValue}",
    ),
  )
}

internal fun FeatureTaskRuntimeRunObservability.ciStillRunning(
  phaseId: String,
  pullRequestUrl: String,
  pendingChecks: List<PullRequestCheck>,
) {
  emitSafely(
    FeatureTaskRuntimeRunEvent.CiStillRunning(
      workflowId = observabilityRequest.workflowId,
      phaseId = phaseId,
      pullRequestUrl = pullRequestUrl,
      pendingChecks = pendingChecks,
    ),
  )
}

internal fun FeatureTaskRuntimeRunObservability.completedEvent(
  phaseId: String,
  resolvedAgentId: String,
  attemptCount: Int,
) {
  emitSafely(
    FeatureTaskRuntimeRunEvent.PhaseCompleted(
      workflowId = observabilityRequest.workflowId,
      phaseId = phaseId,
      resolvedAgentId = resolvedAgentId,
      attemptCount = attemptCount,
    ),
  )
}

public fun FeatureTaskRuntimeRunObservability.paused(
  phaseId: String,
  resolvedAgentId: String,
  attemptCount: Int,
  pauseReason: String,
) {
  emitSafely(
    FeatureTaskRuntimeRunEvent.PhasePaused(
      workflowId = observabilityRequest.workflowId,
      phaseId = phaseId,
      resolvedAgentId = resolvedAgentId,
      attemptCount = attemptCount,
      pauseReason = pauseReason,
    ),
  )
  appendLedger(
    FeatureTaskRuntimePhaseLedgerRequest(
      workflowId = observabilityRequest.workflowId,
      action = FeatureTaskRuntimePhaseLedgerAction.PAUSED,
      phaseId = phaseId,
      attemptCount = attemptCount,
      resolvedAgentId = resolvedAgentId,
      blockedReason = pauseReason,
    ),
  )
}

public fun FeatureTaskRuntimeRunObservability.blocked(
  phaseId: String,
  resolvedAgentId: String,
  attemptCount: Int,
  blockedReason: String,
) {
  emitSafely(
    FeatureTaskRuntimeRunEvent.PhaseBlocked(
      workflowId = observabilityRequest.workflowId,
      phaseId = phaseId,
      resolvedAgentId = resolvedAgentId,
      attemptCount = attemptCount,
      blockedReason = blockedReason,
    ),
  )
  appendLedger(
    FeatureTaskRuntimePhaseLedgerRequest(
      workflowId = observabilityRequest.workflowId,
      action = FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
      phaseId = phaseId,
      attemptCount = attemptCount,
      resolvedAgentId = resolvedAgentId,
      blockedReason = blockedReason,
    ),
  )
}

internal fun FeatureTaskRuntimeRunObservability.loopEdge(
  phaseId: String,
  loopId: String,
  edgeIteration: Int,
  drivingVerdict: FeatureTaskRuntimeVerdict,
) {
  emitSafely(
    FeatureTaskRuntimeRunEvent.PhaseLoopEdge(
      workflowId = observabilityRequest.workflowId,
      phaseId = phaseId,
      loopId = loopId,
      edgeIteration = edgeIteration,
      drivingVerdict = drivingVerdict.wireValue,
      continuationKind = FeatureTaskRuntimeContinuationKind.VERIFIER_REENTRY.wireValue,
    ),
  )
  appendLedger(
    FeatureTaskRuntimePhaseLedgerRequest(
      workflowId = observabilityRequest.workflowId,
      action = FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
      phaseId = phaseId,
      attemptCount = 1,
      loopId = loopId,
      edgeIteration = edgeIteration,
      blockedReason =
        "${FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX}" +
          "${FeatureTaskRuntimeContinuationKind.VERIFIER_REENTRY.wireValue} " +
          "driving_verdict=${drivingVerdict.wireValue}",
    ),
  )
}

internal fun FeatureTaskRuntimeRunObservability.loopCapExhausted(
  phaseId: String,
  loopId: String,
  declaredCap: Int,
  drivingVerdict: FeatureTaskRuntimeVerdict,
) {
  appendLedger(
    FeatureTaskRuntimePhaseLedgerRequest(
      workflowId = observabilityRequest.workflowId,
      action = FeatureTaskRuntimePhaseLedgerAction.LOOP_CAP_EXHAUSTED,
      phaseId = phaseId,
      attemptCount = 1,
      loopId = loopId,
      edgeIteration = declaredCap,
      blockedReason = "declared_cap=$declaredCap driving_verdict=${drivingVerdict.wireValue}",
    ),
  )
}

internal val FeatureTaskRuntimeRunObservability.observabilityRequest get() = request

internal fun FeatureTaskRuntimeRunObservability.emitSafely(event: FeatureTaskRuntimeRunEvent) {
  emitFeatureTaskRuntimeEventSafely(
    diagnostics = observabilityDiagnostics,
    seam = "event-sink emission (${event::class.simpleName})",
  ) {
    observabilityRequest.eventSink.emit(event)
  }
}

internal fun FeatureTaskRuntimeRunObservability.appendLedger(ledgerRequest: FeatureTaskRuntimePhaseLedgerRequest) {
  observabilityRecorder.appendLedgerEntry(ledgerRequest)
}

internal val FeatureTaskRuntimeRunObservability.observabilityRecorder get() = recorder
internal val FeatureTaskRuntimeRunObservability.observabilityDiagnostics get() = diagnostics
