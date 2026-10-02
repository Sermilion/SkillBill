package skillbill.engine.featuretask.lifecycle.core

import skillbill.application.telemetry.lifecycle.LifecycleTelemetryService
import skillbill.application.telemetry.model.FeatureTaskRuntimeAgentContext
import skillbill.application.telemetry.model.FeatureTaskRuntimeFinishedRequest
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeCrashReconciliationResult
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFindingVerificationTelemetry
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeFinishedTelemetryContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRegenerationTelemetry
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.review.core.auditGapIterationCount
import skillbill.error.core.failureCodeLabel
import skillbill.workflow.decomposition.runtime.normalizedBlockedReason
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus

internal fun emitFeatureTaskRuntimeFinished(
  lifecycleTelemetryService: LifecycleTelemetryService,
  report: FeatureTaskRuntimeRunReport,
  context: FeatureTaskRuntimeFinishedTelemetryContext,
  completionStatus: String,
) {
  val outcomes = context.phaseOutcomes()
  val telemetryPayload = resolvedFeatureTaskRuntimeTelemetryPayload(context)
  lifecycleTelemetryService.featureTaskRuntimeFinished(
    FeatureTaskRuntimeFinishedRequest(
      sessionId = context.telemetrySessionId,
      completionStatus = completionStatus,
      completedPhaseIds = completedPhaseIdsOf(report),
      phaseOutcomes = outcomes,
      lastIncompletePhase = lastIncompletePhaseOf(report, outcomes),
      blockedReason = blockedReasonOf(report),
      resolvedBranch = report.resolvedBranch.orEmpty(),
      reviewFixIterationCount = telemetryPayload.reviewFixIterationCount,
      regenerationActivationCount = telemetryPayload.regeneration.activationCount,
      regenerationAttemptCount = telemetryPayload.regeneration.attemptCount,
      regenerationOutcomeCounts = telemetryPayload.regeneration.outcomeCounts,
      crashReconciliationCount = telemetryPayload.reconciliation.reconciledCount,
      crashReconciliationReasonCounts = telemetryPayload.reconciliation.reasonClassCounts,
      estimatedPhaseTokenBreakdownJson = telemetryPayload.tokenBreakdownJson,
      estimatedTotalTokens = telemetryPayload.totalTokens,
      findingVerificationVerifiedCount = telemetryPayload.verificationTelemetry.verifiedCount,
      findingVerificationRejectedCount = telemetryPayload.verificationTelemetry.rejectedCount,
      reviewFixCapExhausted = telemetryPayload.verificationTelemetry.reviewFixCapExhausted,
      auditGapIterationCount = telemetryPayload.auditGapIterationCount,
      agentContext = telemetryPayload.agentContext,
    ),
  )
}

internal fun emitFeatureTaskRuntimeFinishedError(
  lifecycleTelemetryService: LifecycleTelemetryService,
  context: FeatureTaskRuntimeFinishedTelemetryContext,
  outcomes: Map<String, String>,
  error: Throwable? = null,
) {
  val telemetryPayload = resolvedFeatureTaskRuntimeTelemetryPayload(context)
  lifecycleTelemetryService.featureTaskRuntimeFinished(
    FeatureTaskRuntimeFinishedRequest(
      sessionId = context.telemetrySessionId,
      completionStatus = "error",
      completedPhaseIds =
        outcomes
          .filterValues { it.workflowStepStatus() == WorkflowStepStatus.COMPLETED }
          .keys.toList(),
      phaseOutcomes = outcomes,
      lastIncompletePhase = outcomes.firstIncompletePhase(),
      blockedReason =
        normalizedBlockedReason(
          reason = error?.let { "Feature-task-runtime finished with an unhandled ${it.terminalFailureClass()}." },
          category = "runtime",
          fallback = "Feature-task-runtime finished with an unhandled error.",
        ),
      resolvedBranch = "",
      reviewFixIterationCount = telemetryPayload.reviewFixIterationCount,
      regenerationActivationCount = telemetryPayload.regeneration.activationCount,
      regenerationAttemptCount = telemetryPayload.regeneration.attemptCount,
      regenerationOutcomeCounts = telemetryPayload.regeneration.outcomeCounts,
      crashReconciliationCount = telemetryPayload.reconciliation.reconciledCount,
      crashReconciliationReasonCounts = telemetryPayload.reconciliation.reasonClassCounts,
      estimatedPhaseTokenBreakdownJson = telemetryPayload.tokenBreakdownJson,
      estimatedTotalTokens = telemetryPayload.totalTokens,
      findingVerificationVerifiedCount = telemetryPayload.verificationTelemetry.verifiedCount,
      findingVerificationRejectedCount = telemetryPayload.verificationTelemetry.rejectedCount,
      reviewFixCapExhausted = telemetryPayload.verificationTelemetry.reviewFixCapExhausted,
      auditGapIterationCount = telemetryPayload.auditGapIterationCount,
      agentContext = telemetryPayload.agentContext,
    ),
  )
}

private fun Throwable.terminalFailureClass(): String =
  failureCodeLabel()
    ?: (this::class.simpleName ?: "Throwable")
      .let { name -> if (name.endsWith("Exception") || name.endsWith("Error")) name else "$name exception" }

internal data class ResolvedFeatureTaskRuntimeTelemetryPayload(
  val tokenBreakdownJson: String?,
  val totalTokens: Int?,
  val reviewFixIterationCount: Int,
  val auditGapIterationCount: Int?,
  val agentContext: FeatureTaskRuntimeAgentContext,
  val verificationTelemetry: FeatureTaskRuntimeFindingVerificationTelemetry,
  val regeneration: FeatureTaskRuntimeRegenerationTelemetry,
  val reconciliation: FeatureTaskRuntimeCrashReconciliationResult,
)

internal fun resolvedFeatureTaskRuntimeTelemetryPayload(
  context: FeatureTaskRuntimeFinishedTelemetryContext,
): ResolvedFeatureTaskRuntimeTelemetryPayload {
  val (tokenBreakdownJson, totalTokens) = runCatching(context.phaseTokenData).getOrDefault(null to null)
  val phaseOutcomes = runCatching(context.phaseOutcomes).getOrDefault(emptyMap())
  val verificationTelemetry =
    runCatching(context.findingVerificationTelemetry)
      .getOrDefault(FeatureTaskRuntimeFindingVerificationTelemetry())
  val regeneration = runCatching(context.regenerationTelemetry).getOrNull() ?: FeatureTaskRuntimeRegenerationTelemetry()
  val reconciliation =
    runCatching(context.crashReconciliation).getOrNull()
      ?: FeatureTaskRuntimeCrashReconciliationResult.NONE
  return ResolvedFeatureTaskRuntimeTelemetryPayload(
    tokenBreakdownJson = tokenBreakdownJson,
    totalTokens = totalTokens,
    reviewFixIterationCount = runCatching(context.reviewFixIterationCount).getOrDefault(0),
    auditGapIterationCount = runCatching(context.auditGapIterationCount).getOrNull(),
    agentContext = runCatching(context.agentContext).getOrNull() ?: FeatureTaskRuntimeAgentContext(),
    verificationTelemetry = verificationTelemetry,
    regeneration = regeneration,
    reconciliation = reconciliation,
  )
}

internal fun completionStatusOf(report: FeatureTaskRuntimeRunReport): String =
  when (report) {
    is FeatureTaskRuntimeRunReport.Completed -> "completed"
    is FeatureTaskRuntimeRunReport.Blocked -> "blocked"
    is FeatureTaskRuntimeRunReport.Paused -> "paused"
    is FeatureTaskRuntimeRunReport.Decomposed -> "decomposed_at_planning"
  }

fun completedPhaseIdsOf(report: FeatureTaskRuntimeRunReport): List<String> =
  when (report) {
    is FeatureTaskRuntimeRunReport.Completed -> report.completedPhaseIds
    is FeatureTaskRuntimeRunReport.Blocked -> report.completedPhaseIds
    is FeatureTaskRuntimeRunReport.Paused -> report.completedPhaseIds
    is FeatureTaskRuntimeRunReport.Decomposed -> report.completedPhaseIds
  }

fun lastIncompletePhaseOf(
  report: FeatureTaskRuntimeRunReport,
  outcomes: Map<String, String>,
): String =
  when (report) {
    is FeatureTaskRuntimeRunReport.Completed -> "completed"
    is FeatureTaskRuntimeRunReport.Decomposed -> "decomposed_at_planning"
    is FeatureTaskRuntimeRunReport.Paused -> report.pausedPhase
    is FeatureTaskRuntimeRunReport.Blocked ->
      report.lastIncompletePhase.takeIf(String::isNotBlank) ?: outcomes.firstIncompletePhase()
  }

fun Map<String, String>.firstIncompletePhase(): String =
  entries.firstOrNull { it.value.workflowStepStatus() != WorkflowStepStatus.COMPLETED }?.key?.takeIf(String::isNotBlank)
    ?: "unknown"

fun blockedReasonOf(report: FeatureTaskRuntimeRunReport): String =
  when (report) {
    is FeatureTaskRuntimeRunReport.Blocked ->
      normalizedBlockedReason(
        reason = report.blockedReason,
        category = "runtime",
        fallback = "Feature-task-runtime blocked without a specific reason.",
      )
    is FeatureTaskRuntimeRunReport.Paused ->
      normalizedBlockedReason(
        reason = report.pauseReason,
        category = "runtime",
        fallback = "Feature-task-runtime paused in phase '${report.pausedPhase}' without a specific reason.",
      )
    is FeatureTaskRuntimeRunReport.Completed,
    is FeatureTaskRuntimeRunReport.Decomposed,
    -> ""
  }
