package skillbill.mcp.review

import skillbill.application.learning.model.AddLearningInput
import skillbill.application.learning.toLearningRecordContract
import skillbill.application.learning.toLearningResolveContract
import skillbill.application.review.service.toImportedReviewContract
import skillbill.application.review.service.toReviewFinishedTelemetryPayload
import skillbill.application.review.service.toTriagePayload
import skillbill.application.review.stats.toFeatureVerifyStatsPayload
import skillbill.application.review.stats.toGoalStatsPayload
import skillbill.application.review.stats.toReviewStatsPayload
import skillbill.contracts.learning.LearningPayloadKeys
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.contracts.system.UpdateCheckPayloadKeys
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.learnings.model.LearningScope
import skillbill.mcp.shared.McpComponent
import skillbill.mcp.shared.McpToolArguments
import skillbill.mcp.shared.McpToolPayloadKeys

internal fun importReview(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> {
  val reviewText = arguments.string(ReviewVerificationSignalKeys.REVIEW_TEXT)
  val orchestrated = arguments.boolean(McpToolPayloadKeys.ORCHESTRATED)
  if (!component.telemetryService.isEnabled()) {
    val preview = component.reviewService.previewImport("-", stdinText = reviewText)
    return McpReviewImportSkippedContract(
      reason = "telemetry is disabled",
      reviewRunId = preview.reviewRunId,
      findingCount = preview.findingCount,
    ).toPayload()
  }
  val importResult =
    component.reviewService.importReview(
      "-",
      finishZeroFindingTelemetry = !orchestrated,
      stdinText = reviewText,
    )
  val payload = importResult.toImportedReviewContract().toPayload()
  val result =
    if (orchestrated) {
      val reviewRunId = importResult.preview.reviewRunId
      component.reviewService.markOrchestrated(reviewRunId)
      val telemetryPayload =
        if (importResult.preview.findingCount == 0) {
          component.reviewService.reviewFinishedTelemetryPayload(reviewRunId)
            ?.toReviewFinishedTelemetryPayload()
            ?.toPayload()
        } else {
          null
        }
      McpOrchestratedPayloadContract(basePayload = payload, telemetryPayload = telemetryPayload).toPayload()
    } else {
      payload
    }
  component.telemetryService.autoSync()
  return result
}

internal fun triageFindings(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> {
  val reviewRunId = arguments.string(ReviewVerificationSignalKeys.REVIEW_RUN_ID)
  val decisions = arguments.stringList(ReviewVerificationSignalKeys.DECISIONS)
  val orchestrated = arguments.boolean(McpToolPayloadKeys.ORCHESTRATED)
  if (!component.telemetryService.isEnabled()) {
    return McpTriageSkippedContract(reason = "telemetry is disabled", reviewRunId = reviewRunId).toPayload()
  }
  if (orchestrated) {
    component.reviewService.markOrchestrated(reviewRunId)
  }
  val result =
    component.reviewService.triage(
      reviewRunId,
      decisions,
      listOnly = false,
      listWhenNoDecisions = false,
    )
  val payload =
    if (orchestrated) {
      McpOrchestratedPayloadContract(
        basePayload = result.toTriagePayload().toPayload(),
        telemetryPayload = result.telemetry?.toReviewFinishedTelemetryPayload()?.toPayload(),
      ).toPayload()
    } else {
      result.toTriagePayload().toPayload()
    }
  component.telemetryService.autoSync()
  return payload
}

internal fun resolveLearnings(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> {
  if (!component.telemetryService.isEnabled()) {
    return McpLearningsSkippedContract(reason = "telemetry is disabled").toPayload()
  }
  return component.learningService.resolve(
    arguments.optionalString(McpToolPayloadKeys.REPO),
    arguments.optionalString(LifecycleTelemetryPayloadKeys.SKILL),
    arguments.optionalString(LearningPayloadKeys.REVIEW_SESSION_ID),
  ).toLearningResolveContract().toPayload()
}

internal fun addLearning(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> {
  if (!component.telemetryService.isEnabled()) {
    return McpLearningsSkippedContract(reason = "telemetry is disabled").toPayload()
  }
  return component.learningService.add(
    AddLearningInput(
      scope = LearningScope.fromWireName(arguments.string(LearningPayloadKeys.SCOPE)),
      scopeKey = arguments.optionalString(LearningPayloadKeys.SCOPE_KEY).orEmpty(),
      title = arguments.string(LearningPayloadKeys.TITLE),
      rule = arguments.string(LearningPayloadKeys.RULE_TEXT),
      reason = arguments.optionalString(UpdateCheckPayloadKeys.REASON).orEmpty(),
      fromRun = arguments.string(LearningPayloadKeys.SOURCE_REVIEW_RUN_ID),
      fromFinding = arguments.string(LearningPayloadKeys.SOURCE_FINDING_ID),
    ),
  ).toLearningRecordContract().toPayload()
}

internal fun reviewStats(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> =
  component.reviewService
    .reviewStats(arguments.optionalString(ReviewVerificationSignalKeys.REVIEW_RUN_ID))
    .toReviewStatsPayload()
    .toPayload()

internal fun featureVerifyStats(component: McpComponent): Map<String, Any?> =
  component.reviewService.featureVerifyStats().toFeatureVerifyStatsPayload().toPayload()

internal fun goalStats(component: McpComponent): Map<String, Any?> =
  component.reviewService.goalStats().toGoalStatsPayload().toPayload()
