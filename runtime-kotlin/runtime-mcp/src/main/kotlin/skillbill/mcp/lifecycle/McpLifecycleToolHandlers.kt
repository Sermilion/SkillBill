package skillbill.mcp.lifecycle

import skillbill.application.telemetry.model.FeatureVerifyFinishedRequest
import skillbill.application.telemetry.model.FeatureVerifyStartedRequest
import skillbill.application.telemetry.model.PrDescriptionGeneratedRequest
import skillbill.application.telemetry.model.QualityCheckFinishedRequest
import skillbill.application.telemetry.model.QualityCheckStartedRequest
import skillbill.application.telemetry.validation.historySignalValues
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.mcp.shared.McpComponent
import skillbill.mcp.shared.McpToolArguments
import skillbill.mcp.shared.McpToolPayloadKeys

internal fun qualityCheckStarted(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> =
  withAutoSync(component) {
    it.lifecycleTelemetryService.qualityCheckStarted(
      QualityCheckStartedRequest(
        routedSkill = arguments.string(LifecycleTelemetryPayloadKeys.ROUTED_SKILL),
        detectedStack = arguments.string(LifecycleTelemetryPayloadKeys.DETECTED_STACK),
        fallback = arguments.boolean(LifecycleTelemetryPayloadKeys.FALLBACK),
        fallbackReason = arguments.optionalString(LifecycleTelemetryPayloadKeys.FALLBACK_REASON),
        scopeType = arguments.string(LifecycleTelemetryPayloadKeys.SCOPE_TYPE),
        initialFailureCount = arguments.int(LifecycleTelemetryPayloadKeys.INITIAL_FAILURE_COUNT, 0),
        orchestrated = arguments.boolean(McpToolPayloadKeys.ORCHESTRATED),
      ),
    ).toPayload()
  }

internal fun qualityCheckFinished(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> =
  withAutoSync(component) {
    it.lifecycleTelemetryService.qualityCheckFinished(
      QualityCheckFinishedRequest(
        finalFailureCount = arguments.int(LifecycleTelemetryPayloadKeys.FINAL_FAILURE_COUNT, 0),
        iterations = arguments.int(LifecycleTelemetryPayloadKeys.ITERATIONS, 0),
        result = arguments.string(LifecycleTelemetryPayloadKeys.RESULT),
        sessionId = arguments.string(LifecycleTelemetryPayloadKeys.SESSION_ID),
        failingCheckNames = arguments.stringList(LifecycleTelemetryPayloadKeys.FAILING_CHECK_NAMES),
        unsupportedReason = arguments.string(LifecycleTelemetryPayloadKeys.UNSUPPORTED_REASON),
        orchestrated = arguments.boolean(McpToolPayloadKeys.ORCHESTRATED),
        routedSkill = arguments.string(LifecycleTelemetryPayloadKeys.ROUTED_SKILL),
        detectedStack = arguments.string(LifecycleTelemetryPayloadKeys.DETECTED_STACK),
        fallback = arguments.boolean(LifecycleTelemetryPayloadKeys.FALLBACK),
        fallbackReason = arguments.optionalString(LifecycleTelemetryPayloadKeys.FALLBACK_REASON),
        scopeType = arguments.string(LifecycleTelemetryPayloadKeys.SCOPE_TYPE),
        initialFailureCount = arguments.int(LifecycleTelemetryPayloadKeys.INITIAL_FAILURE_COUNT, 0),
        durationSeconds = arguments.int(LifecycleTelemetryPayloadKeys.DURATION_SECONDS, 0),
      ),
    ).toPayload()
  }

internal fun featureVerifyStarted(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> =
  withAutoSync(component) {
    it.lifecycleTelemetryService.featureVerifyStarted(
      FeatureVerifyStartedRequest(
        acceptanceCriteriaCount = arguments.int(LifecycleTelemetryPayloadKeys.ACCEPTANCE_CRITERIA_COUNT, 0),
        rolloutRelevant = arguments.boolean(LifecycleTelemetryPayloadKeys.ROLLOUT_RELEVANT),
        specSummary = arguments.string(LifecycleTelemetryPayloadKeys.SPEC_SUMMARY),
        orchestrated = arguments.boolean(McpToolPayloadKeys.ORCHESTRATED),
      ),
    ).toPayload()
  }

internal fun featureVerifyFinished(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> =
  withAutoSync(component) {
    it.lifecycleTelemetryService.featureVerifyFinished(
      FeatureVerifyFinishedRequest(
        featureFlagAuditPerformed = arguments.boolean(LifecycleTelemetryPayloadKeys.FEATURE_FLAG_AUDIT_PERFORMED),
        reviewIterations = arguments.int(LifecycleTelemetryPayloadKeys.REVIEW_ITERATIONS, 0),
        auditResult = arguments.string(LifecycleTelemetryPayloadKeys.AUDIT_RESULT),
        completionStatus = arguments.string(LifecycleTelemetryPayloadKeys.COMPLETION_STATUS),
        historyRelevance =
          arguments.optionalString(LifecycleTelemetryPayloadKeys.HISTORY_RELEVANCE) ?: historySignalValues.first(),
        historyHelpfulness =
          arguments.optionalString(LifecycleTelemetryPayloadKeys.HISTORY_HELPFULNESS) ?: historySignalValues.first(),
        sessionId = arguments.string(LifecycleTelemetryPayloadKeys.SESSION_ID),
        gapsFound = arguments.stringList(LifecycleTelemetryPayloadKeys.GAPS_FOUND),
        orchestrated = arguments.boolean(McpToolPayloadKeys.ORCHESTRATED),
        acceptanceCriteriaCount = arguments.int(LifecycleTelemetryPayloadKeys.ACCEPTANCE_CRITERIA_COUNT, 0),
        rolloutRelevant = arguments.boolean(LifecycleTelemetryPayloadKeys.ROLLOUT_RELEVANT),
        specSummary = arguments.string(LifecycleTelemetryPayloadKeys.SPEC_SUMMARY),
        durationSeconds = arguments.int(LifecycleTelemetryPayloadKeys.DURATION_SECONDS, 0),
      ),
    ).toPayload()
  }

internal fun prDescriptionGenerated(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> =
  withAutoSync(component) {
    it.lifecycleTelemetryService.prDescriptionGenerated(
      PrDescriptionGeneratedRequest(
        commitCount = arguments.int(LifecycleTelemetryPayloadKeys.COMMIT_COUNT, 0),
        filesChangedCount = arguments.int(LifecycleTelemetryPayloadKeys.FILES_CHANGED_COUNT, 0),
        wasEditedByUser = arguments.boolean(LifecycleTelemetryPayloadKeys.WAS_EDITED_BY_USER),
        prCreated = arguments.boolean(LifecycleTelemetryPayloadKeys.PR_CREATED),
        prTitle = arguments.string(LifecycleTelemetryPayloadKeys.PR_TITLE),
        orchestrated = arguments.boolean(McpToolPayloadKeys.ORCHESTRATED),
        generatedDescription = arguments.optionalString(McpToolPayloadKeys.GENERATED_DESCRIPTION),
        finalPrBody = arguments.optionalString(McpToolPayloadKeys.FINAL_PR_BODY),
      ),
    ).toPayload()
  }

private fun withAutoSync(
  component: McpComponent,
  block: (McpComponent) -> Map<String, Any?>,
): Map<String, Any?> {
  val payload = block(component)
  component.telemetryService.autoSync()
  return payload
}
