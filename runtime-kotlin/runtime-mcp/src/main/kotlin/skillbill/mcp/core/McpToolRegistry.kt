package skillbill.mcp.core

import skillbill.application.telemetry.validation.featureVerifyCompletionStatuses
import skillbill.application.telemetry.validation.qualityCheckResults
import skillbill.contracts.telemetry.LifecycleTelemetryPayloadKeys
import skillbill.mcp.featuretask.featureTaskPhaseBlock
import skillbill.mcp.featuretask.featureTaskPhaseComplete
import skillbill.mcp.lifecycle.featureVerifyFinished
import skillbill.mcp.lifecycle.featureVerifyStarted
import skillbill.mcp.lifecycle.prDescriptionGenerated
import skillbill.mcp.lifecycle.qualityCheckFinished
import skillbill.mcp.lifecycle.qualityCheckStarted
import skillbill.mcp.review.addLearning
import skillbill.mcp.review.featureVerifyStats
import skillbill.mcp.review.goalStats
import skillbill.mcp.review.importReview
import skillbill.mcp.review.resolveLearnings
import skillbill.mcp.review.reviewStats
import skillbill.mcp.review.triageFindings
import skillbill.mcp.scaffold.newSkillScaffold
import skillbill.mcp.shared.McpComponent
import skillbill.mcp.shared.McpProtocolFramer
import skillbill.mcp.shared.McpToolArguments
import skillbill.mcp.shared.McpToolPayloadKeys
import skillbill.mcp.system.doctor
import skillbill.mcp.system.updateCheck
import skillbill.mcp.telemetry.telemetryProxyCapabilities
import skillbill.mcp.telemetry.telemetryRemoteStats
import skillbill.mcp.workflow.workflowContinue
import skillbill.mcp.workflow.workflowGet
import skillbill.mcp.workflow.workflowLatest
import skillbill.mcp.workflow.workflowList
import skillbill.mcp.workflow.workflowOpen
import skillbill.mcp.workflow.workflowResume
import skillbill.mcp.workflow.workflowUpdate

internal typealias McpToolHandler = (McpToolArguments, McpComponent) -> Map<String, Any?>

internal data class McpTool(
  val name: String,
  val description: String,
  val handler: McpToolHandler,
  val normalize: ((Map<String, Any?>) -> Map<String, Any?>)? = null,
  val runtimeOwnedArgumentKeys: Set<String> = emptySet(),
  val advertisedEnumSubset: Pair<String, List<String>>? = null,
) {
  fun toPayload(): Map<String, Any?> =
    linkedMapOf(
      McpProtocolFramer.NAME_KEY to name,
      McpProtocolFramer.DESCRIPTION_KEY to description,
      McpProtocolFramer.INPUT_SCHEMA_KEY to McpInputSchemaProjection.projectedInputSchema(this),
    )
}

internal object McpToolRegistry {
  val tools: List<McpTool> =
    listOf(
      McpTool(
        name = McpToolPayloadKeys.ADD_LEARNING,
        description = "Create a learning from a rejected review finding after user confirmation.",
        handler = ::addLearning,
      ),
      McpTool(
        name = "doctor",
        description = "Check skill-bill installation health.",
        handler = { _, component -> doctor(component) },
      ),
      McpTool(
        name = "feature_task_phase_block",
        description = "Durable-block a prose feature-task phase.",
        handler = ::featureTaskPhaseBlock,
      ),
      McpTool(
        name = "feature_task_phase_complete",
        description = "Complete a prose feature-task phase via durable settlement.",
        handler = ::featureTaskPhaseComplete,
      ),
      McpTool(
        name = McpToolPayloadKeys.FEATURE_VERIFY_FINISHED,
        description = "Record completion of a feature-verify session.",
        handler = ::featureVerifyFinished,
        runtimeOwnedArgumentKeys = setOf(LifecycleTelemetryPayloadKeys.DURATION_SECONDS_AVAILABILITY),
        advertisedEnumSubset = LifecycleTelemetryPayloadKeys.COMPLETION_STATUS to featureVerifyCompletionStatuses,
      ),
      McpTool(
        name = "feature_verify_stats",
        description = "Show aggregate bill-feature-verify metrics.",
        handler = { _, component -> featureVerifyStats(component) },
      ),
      McpTool(
        name = "feature_verify_started",
        description = "Record start of a feature-verify session.",
        handler = ::featureVerifyStarted,
      ),
      McpTool(
        name = "feature_verify_workflow_get",
        description = "Fetch read-only full durable bill-feature-verify workflow state.",
        handler = ::workflowGet,
      ),
      McpTool(
        name = "feature_verify_workflow_latest",
        description = "Fetch the latest bill-feature-verify workflow.",
        handler = { _, component -> workflowLatest(component) },
      ),
      McpTool(
        name = "feature_verify_workflow_list",
        description = "List bill-feature-verify workflows.",
        handler = ::workflowList,
      ),
      McpTool(
        name = "feature_verify_workflow_continue",
        description = "Continue durable bill-feature-verify workflow state.",
        handler = ::workflowContinue,
      ),
      McpTool(
        name = "feature_verify_workflow_open",
        description = "Open durable bill-feature-verify workflow state.",
        handler = ::workflowOpen,
      ),
      McpTool(
        name = "feature_verify_workflow_resume",
        description = "Summarize bill-feature-verify workflow resume state.",
        handler = ::workflowResume,
      ),
      McpTool(
        name = "feature_verify_workflow_update",
        description =
          "Update durable bill-feature-verify workflow state and return a compact acknowledgement.",
        handler = ::workflowUpdate,
      ),
      McpTool(
        name = "goal_stats",
        description = "Show aggregate decomposed-goal runtime metrics.",
        handler = { _, component -> goalStats(component) },
      ),
      McpTool(
        name = "import_review",
        description = "Import code review output into the local telemetry store.",
        handler = ::importReview,
      ),
      McpTool(
        name = "new_skill_scaffold",
        description = "Scaffold a new skill from a validated payload.",
        handler = ::newSkillScaffold,
      ),
      McpTool(
        name = "pr_description_generated",
        description = "Record PR description generation telemetry.",
        handler = ::prDescriptionGenerated,
      ),
      McpTool(
        name = McpToolPayloadKeys.QUALITY_CHECK_FINISHED,
        description = "Record completion of a quality-check session.",
        handler = ::qualityCheckFinished,
        normalize = ::normalizeQualityCheckFinished,
        runtimeOwnedArgumentKeys =
          setOf(
            LifecycleTelemetryPayloadKeys.COMPLETION,
            LifecycleTelemetryPayloadKeys.FINAL_FAILURE_COUNT_AVAILABILITY,
            LifecycleTelemetryPayloadKeys.STALE_REASON,
          ),
        advertisedEnumSubset = LifecycleTelemetryPayloadKeys.RESULT to qualityCheckResults,
      ),
      McpTool(
        name = "quality_check_started",
        description = "Record start of a quality-check session.",
        handler = ::qualityCheckStarted,
      ),
      McpTool(
        name = "resolve_learnings",
        description = "Resolve active learnings for a review context.",
        handler = ::resolveLearnings,
      ),
      McpTool(
        name = "review_stats",
        description = "Show review acceptance metrics.",
        handler = ::reviewStats,
      ),
      McpTool(
        name = "telemetry_proxy_capabilities",
        description = "Show configured telemetry proxy capabilities.",
        handler = { _, component -> telemetryProxyCapabilities(component) },
      ),
      McpTool(
        name = "telemetry_remote_stats",
        description = "Fetch aggregate org-wide workflow metrics.",
        handler = ::telemetryRemoteStats,
      ),
      McpTool(
        name = "triage_findings",
        description = "Record triage decisions for imported review findings.",
        handler = ::triageFindings,
      ),
      McpTool(
        name = "update_check",
        description = "Check whether the installed skill-bill runtime is up to date.",
        handler = { _, component -> updateCheck(component) },
      ),
    )

  private val toolsByName: Map<String, McpTool> = tools.associateBy(McpTool::name)

  fun toolNamed(name: String): McpTool? = toolsByName[name]
}
