package skillbill.mcp.telemetry

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.application.decomposition.branchName
import skillbill.application.telemetry.validation.auditResults
import skillbill.application.telemetry.validation.featureVerifyCompletionStatuses
import skillbill.application.telemetry.validation.historySignalValues
import skillbill.application.telemetry.validation.qualityCheckResults
import skillbill.application.telemetry.validation.qualityCheckScopeTypes
import skillbill.mcp.core.McpToolRegistry
import skillbill.testing.repoRootFromTest
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpTelemetrySchemaEnumParityTest {
  private val runtimeInternalEmissionEvents =
    setOf(
      "goal_started",
      "goal_subtask_finished",
      "goal_finished",
      "goal_issue_finished",
      "skillbill_review_finished",
      "skillbill_review_stage_degradation",
      "skillbill_review_finished_legacy_regenerated",
    )

  private val schemaNode: JsonNode by lazy {
    val schemaFile = repoRootFromTest().resolve(TelemetryEventSchemaPaths.REPO_RELATIVE_PATH)
    assertTrue(Files.isRegularFile(schemaFile), "Canonical schema file is missing at $schemaFile.")
    YAMLMapper().readTree(Files.readString(schemaFile))
  }

  @Test
  fun `yaml enum constraints match kotlin owners and every tool has a branch`() {
    val defs = schemaNode.path("\$defs")
    assertEquals(
      FeatureTaskRuntimeFailureDisposition.entries.map(FeatureTaskRuntimeFailureDisposition::wireValue),
      enumOnBranch(defs, "featureTaskPhaseBlockEvent", "failure_disposition"),
    )
    val settleablePhaseIds =
      FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds
        .filter { it != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH }.sorted()
    assertEquals(settleablePhaseIds, enumOnBranch(defs, "featureTaskPhaseCompleteEvent", "phase_id").sorted())
    assertEquals(settleablePhaseIds, enumOnBranch(defs, "featureTaskPhaseBlockEvent", "phase_id").sorted())
    assertEquals(
      FeatureVerifyWorkflowDefinition.definition.workflowStatuses.toList().sorted(),
      enumOnBranch(defs, "featureVerifyWorkflowUpdateEvent", "workflow_status").sorted(),
    )
    assertEquals(
      FeatureVerifyWorkflowDefinition.definition.stepIds,
      enumOnBranch(defs, "featureVerifyWorkflowUpdateEvent", "current_step_id"),
    )
    val stepUpdate = defs.path("stepUpdateVerifyShape")
    assertEquals(
      FeatureVerifyWorkflowDefinition.definition.stepIds,
      enumOnRef(stepUpdate.path("properties").path("step_id"), defs),
    )
    assertEquals(
      WorkflowStepStatus.entries.map(WorkflowStepStatus::wireValue),
      enumOnRef(stepUpdate.path("properties").path("status"), defs),
    )
    assertEquals(auditResults, enumOnBranch(defs, "featureVerifyFinishedEvent", "audit_result"))
    assertEquals(
      featureVerifyCompletionStatuses,
      enumOnBranch(defs, "featureVerifyFinishedEvent", "completion_status")
        .filter { it != "stale" },
    )
    assertEquals(
      historySignalValues,
      enumOnRef(
        defs.path("featureVerifyFinishedEvent").path("properties").path("history_relevance"),
        defs,
      ),
    )
    assertEquals(
      historySignalValues,
      enumOnRef(
        defs.path("featureVerifyFinishedEvent").path("properties").path("history_helpfulness"),
        defs,
      ),
    )
    assertEquals(
      qualityCheckScopeTypes,
      enumOnRef(
        defs.path("qualityCheckStartedEvent").path("properties").path("scope_type"),
        defs,
      ),
    )
    assertEquals(
      qualityCheckScopeTypes,
      enumOnRef(
        defs.path("qualityCheckFinishedEvent").path("properties").path("scope_type"),
        defs,
      ),
    )
    assertEquals(
      qualityCheckResults,
      enumOnBranch(defs, "qualityCheckFinishedEvent", "result").filter { it != "stale" },
    )
    assertEquals(
      listOf("verify", "bill-feature-verify", "feature-task-runtime"),
      enumOnBranch(defs, "telemetryRemoteStatsEvent", "workflow"),
    )
    assertEquals(listOf("", "day", "week"), enumOnBranch(defs, "goalStatsEvent", "group_by"))
    assertEquals(listOf("", "day", "week"), enumOnBranch(defs, "telemetryRemoteStatsEvent", "group_by"))

    val knownTools = McpToolRegistry.tools.map { it.name }.toSet()
    defs.fields().forEach { (defName, defNode) ->
      if (!defName.endsWith("Event")) return@forEach
      val eventName = defNode.path("properties").path("event_name").path("const").asText("")
      assertTrue(
        eventName in knownTools || eventName in runtimeInternalEmissionEvents,
        "Branch '$defName' event_name='$eventName' is neither a registered tool nor runtime-internal emission.",
      )
    }
    knownTools.forEach { toolName ->
      val branchName = branchNameFor(toolName)
      assertTrue(
        !defs.path(branchName).isMissingNode,
        "Registered tool '$toolName' is missing branch '\$defs/$branchName'.",
      )
    }
  }

  private fun enumOnBranch(
    defs: JsonNode,
    branchName: String,
    propertyName: String,
  ): List<String> = enumOnRef(defs.path(branchName).path("properties").path(propertyName), defs)

  private fun enumOnRef(
    propertyNode: JsonNode,
    defs: JsonNode,
  ): List<String> {
    val ref = propertyNode.path("\$ref")
    val resolved =
      if (!ref.isMissingNode) {
        defs.path(ref.asText().removePrefix("#/\$defs/"))
      } else {
        propertyNode
      }
    return resolved.path("enum").map { it.asText() }
  }

  private fun branchNameFor(eventName: String): String {
    val parts = eventName.split('_')
    return parts.first() +
      parts.drop(1).joinToString("") { segment ->
        segment.replaceFirstChar { it.uppercase() }
      } + "Event"
  }
}
