package skillbill.mcp.workflow

import skillbill.infrastructure.host.CanonicalRepositoryRoot
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.mcp.shared.McpRuntimeContext
import skillbill.mcp.shared.assertGoldenPayload
import skillbill.mcp.shared.assertSqliteTimestampShape
import skillbill.mcp.shared.assertWorkflowIdShape
import skillbill.mcp.shared.callToolPayload
import skillbill.mcp.shared.disabledTelemetryEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpVerifyWorkflowToolsTest {
  @Test
  fun `mcp workflow tools cover verify verbs and reopened continuation`() {
    val tempDir = Files.createTempDirectory("skillbill-mcp-verify-workflow")
    val context = McpRuntimeContext(environment = disabledTelemetryEnvironment(tempDir), userHome = tempDir)

    val opened =
      context.callToolPayload(
        "feature_verify_workflow_open",
        mapOf("session_id" to "", "current_step_id" to "code_review"),
      )
    val workflowId = opened["workflow_id"] as String
    assertWorkflowIdShape(workflowId, "wfv")
    assertSqliteTimestampShape(opened["started_at"].toString(), "verify started_at")
    assertSqliteTimestampShape(opened["updated_at"].toString(), "verify updated_at")
    assertTrue(
      opened["updated_at"].toString() >= opened["started_at"].toString(),
      "verify updated_at must not precede started_at",
    )

    val updated = markVerifyWorkflowVerdictBlocked(context, workflowId)
    val listed = context.callToolPayload("feature_verify_workflow_list")
    val latest = context.callToolPayload("feature_verify_workflow_latest")
    val got = context.callToolPayload("feature_verify_workflow_get", mapOf("workflow_id" to workflowId))
    val resumed = context.callToolPayload("feature_verify_workflow_resume", mapOf("workflow_id" to workflowId))
    val continued =
      context.callToolPayload("feature_verify_workflow_continue", mapOf("workflow_id" to workflowId))

    val continuedAt = continued["updated_at"].toString()
    assertSqliteTimestampShape(got["updated_at"].toString(), "verify updated_at")
    assertSqliteTimestampShape(continuedAt, "verify continued_at")
    assertEquals(opened["started_at"], got["started_at"])
    assertTrue(continuedAt >= got["updated_at"].toString())
    assertGoldenPayload(
      "mcp-feature-verify-workflow.json",
      mapOf(
        "open" to opened,
        "update" to updated,
        "list" to listed,
        "latest" to latest,
        "get" to got,
        "resume" to resumed,
        "continue" to continued,
      ),
      "<DB_PATH>" to tempDir.resolve("metrics.db").toAbsolutePath().normalize().toString(),
      "<WORKFLOW_ID>" to workflowId,
      "<STARTED_AT>" to opened["started_at"].toString(),
      "<UPDATED_AT>" to got["updated_at"].toString(),
      "<CONTINUED_AT>" to continuedAt,
      "<CHECKPOINT>" to repositoryCheckpoint(),
    )
    assertCompactUpdateAcknowledgementPayload(updated)
    assertEquals(1, listed["workflow_count"])
    assertEquals(workflowId, latest["workflow_id"])
    assertEquals("verdict", got["current_step_id"])
    assertEquals("resume", resumed["resume_mode"])
    assertEquals("ok", continued["status"])
    assertEquals("reopened", continued["continue_status"])
    assertEquals("running", continued["workflow_status_before_continue"])
    assertEquals(
      "skill-bill --db '${tempDir.resolve("metrics.db").toAbsolutePath().normalize()}' verify-workflow show " +
        "'$workflowId' --format json",
      continued["read_only_full_state_command"],
    )
    assertCompactContinuationPayload(continued)
  }
}

private fun repositoryCheckpoint(): String =
  GitWorkflowGitOperations()
    .repositoryFingerprint(CanonicalRepositoryRoot.enclosingRepositoryRoot(Path.of(""))).value

private fun markVerifyWorkflowVerdictBlocked(
  context: McpRuntimeContext,
  workflowId: String,
): Map<String, Any?> =
  context.callToolPayload(
    "feature_verify_workflow_update",
    mapOf(
      "workflow_id" to workflowId,
      "session_id" to "",
      "workflow_status" to "running",
      "current_step_id" to "verdict",
      "step_updates" to
        listOf(mapOf("step_id" to "verdict", "status" to "blocked", "attempt_count" to 1)),
      "artifacts_patch" to
        mapOf(
          "diff_projection" to
            mapOf(
              "checkpoint" to repositoryCheckpoint(),
              "comparison_scope" to "base..head",
              "changed_files" to emptyList<String>(),
            ),
          "feature_flag_audit_receipt" to evaluatorReceipt(),
          "code_review_receipt" to evaluatorReceipt(),
          "unit_test_value_receipt" to evaluatorReceipt(),
          "completeness_audit_receipt" to evaluatorReceipt(),
        ),
    ),
  )

private fun evaluatorReceipt(): Map<String, Any?> =
  mapOf(
    "contract_version" to "0.1",
    "verdict" to "approved",
    "findings" to emptyList<Map<String, Any?>>(),
  )

private fun assertCompactUpdateAcknowledgementPayload(payload: Map<String, *>) {
  assertEquals("ok", payload["status"])
  assertEquals(listOf("verdict"), payload["updated_step_ids"])
  assertTrue(payload.containsKey("updated_artifact_keys"))
  assertTrue(payload.containsKey("read_only_full_state_command"))
  assertTrue(payload.containsKey("read_only_full_state_guidance"))
  assertFalse(payload.containsKey("artifacts"))
  assertFalse(payload.containsKey("steps"))
  assertFalse(payload.containsKey("session_id"))
}

private fun assertCompactContinuationPayload(payload: Map<String, *>) {
  assertEquals("verdict", payload["resume_step_id"])
  assertTrue(payload.containsKey("current_step_artifacts"))
  assertTrue(payload.containsKey("read_only_full_state_command"))
  assertFalse(payload.containsKey("workflow"))
  assertFalse(payload.containsKey("artifacts"))
  assertFalse(payload.containsKey("steps"))
}
