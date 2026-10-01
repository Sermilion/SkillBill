package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.infrastructure.host.CanonicalRepositoryRoot
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliWorkflowUpdateRuntimeTest {
  @Test
  fun `verify workflow update returns compact acknowledgement with verify-workflow show hint`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-verify-workflow-update")
    val dbPath = tempDir.resolve("metrics.db")
    val opened =
      runJson(
        "--db",
        dbPath.toString(),
        "verify-workflow",
        "open",
        "--current-step-id",
        "code_review",
        "--format",
        "json",
      )
    val workflowId = opened["workflow_id"] as String
    val checkpoint =
      GitWorkflowGitOperations().repositoryFingerprint(
        CanonicalRepositoryRoot.enclosingRepositoryRoot(Path.of("")),
      ).value

    val update =
      runJson(
        "--db",
        dbPath.toString(),
        "verify-workflow",
        "update",
        workflowId,
        "--workflow-status",
        "running",
        "--current-step-id",
        "verdict",
        "--step-updates",
        """[{"step_id":"verdict","status":"blocked","attempt_count":1}]""",
        "--artifacts-patch",
        "{" +
          "\"diff_projection\":{\"checkpoint\":\"$checkpoint\"," +
          "\"comparison_scope\":\"base..head\",\"changed_files\":[]}," +
          "\"feature_flag_audit_receipt\":{\"contract_version\":\"0.1\",\"verdict\":\"approved\",\"findings\":[]}," +
          "\"code_review_receipt\":{\"contract_version\":\"0.1\",\"verdict\":\"approved\",\"findings\":[]}," +
          "\"unit_test_value_receipt\":{\"contract_version\":\"0.1\",\"verdict\":\"approved\",\"findings\":[]}," +
          "\"completeness_audit_receipt\":{\"contract_version\":\"0.1\",\"verdict\":\"approved\",\"findings\":[]}" +
          "}",
        "--format",
        "json",
      )
    assertCompactUpdate(
      payload = update,
      stepId = "verdict",
      artifactKeys =
        listOf(
          "code_review_receipt",
          "completeness_audit_receipt",
          "diff_projection",
          "feature_flag_audit_receipt",
          "unit_test_value_receipt",
        ),
      readOnlyCommand = "skill-bill --db '$dbPath' verify-workflow show '$workflowId' --format json",
    )
    assertFalse(update["read_only_full_state_command"].toString().contains(" workflow show "))
  }

  @Test
  fun `malformed and non-array step updates leave durable state unchanged`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-verify-workflow-step-updates")
    val dbPath = tempDir.resolve("metrics.db")
    val workflowId =
      runJson(
        "--db",
        dbPath.toString(),
        "verify-workflow",
        "open",
        "--current-step-id",
        "code_review",
        "--format",
        "json",
      )["workflow_id"] as String
    val before =
      runJson(
        "--db",
        dbPath.toString(),
        "verify-workflow",
        "show",
        workflowId,
        "--format",
        "json",
      )

    listOf("{broken", """{"step_id":"verdict"}""").forEach { rawUpdates ->
      val rejected =
        CliRuntime.run(
          listOf(
            "--db",
            dbPath.toString(),
            "verify-workflow",
            "update",
            workflowId,
            "--workflow-status",
            "running",
            "--step-updates",
            rawUpdates,
            "--format",
            "json",
          ),
        )

      assertEquals(1, rejected.exitCode, rejected.stdout)
      assertContains(rejected.stderr, "--step-updates must be a JSON array of objects.")
      val after =
        runJson(
          "--db",
          dbPath.toString(),
          "verify-workflow",
          "show",
          workflowId,
          "--format",
          "json",
        )
      assertEquals(before["workflow_status"], after["workflow_status"])
      assertEquals(before["current_step_id"], after["current_step_id"])
      assertEquals(before["steps"], after["steps"])
      assertEquals(before["artifacts"], after["artifacts"])
    }
  }

  @Test
  fun `omitted and explicit empty step updates both preserve status-only update semantics`() {
    val tempDir = Files.createTempDirectory("skillbill-cli-verify-workflow-empty-step-updates")
    val dbPath = tempDir.resolve("metrics.db")
    val omittedWorkflowId =
      runJson(
        "--db",
        dbPath.toString(),
        "verify-workflow",
        "open",
        "--current-step-id",
        "code_review",
        "--format",
        "json",
      )["workflow_id"] as String
    val omitted =
      runJson(
        "--db",
        dbPath.toString(),
        "verify-workflow",
        "update",
        omittedWorkflowId,
        "--workflow-status",
        "running",
        "--format",
        "json",
      )
    val emptyWorkflowId =
      runJson(
        "--db",
        dbPath.toString(),
        "verify-workflow",
        "open",
        "--current-step-id",
        "code_review",
        "--format",
        "json",
      )["workflow_id"] as String
    val explicitEmpty =
      runJson(
        "--db",
        dbPath.toString(),
        "verify-workflow",
        "update",
        emptyWorkflowId,
        "--workflow-status",
        "running",
        "--step-updates",
        "[]",
        "--format",
        "json",
      )

    assertEquals("ok", omitted["status"])
    assertEquals("ok", explicitEmpty["status"])
    assertEquals(emptyList<Any?>(), omitted["updated_step_ids"])
    assertEquals(emptyList<Any?>(), explicitEmpty["updated_step_ids"])
    assertEquals(
      runJson("--db", dbPath.toString(), "verify-workflow", "show", omittedWorkflowId, "--format", "json")["steps"],
      runJson("--db", dbPath.toString(), "verify-workflow", "show", emptyWorkflowId, "--format", "json")["steps"],
    )
  }
}

private fun assertCompactUpdate(
  payload: Map<String, Any?>,
  stepId: String,
  artifactKeys: List<String>,
  readOnlyCommand: String,
) {
  assertEquals("ok", payload["status"])
  assertEquals(stepId, payload["current_step_id"])
  assertEquals(listOf(stepId), payload["updated_step_ids"])
  assertEquals(artifactKeys, payload["updated_artifact_keys"])
  assertEquals(readOnlyCommand, payload["read_only_full_state_command"])
  assertTrue(payload.containsKey("read_only_full_state_guidance"))
  assertFalse(payload.containsKey("artifacts"))
  assertFalse(payload.containsKey("steps"))
}

private fun runJson(vararg arguments: String): Map<String, Any?> {
  val result = CliRuntime.run(arguments.toList())
  assertEquals(0, result.exitCode, result.stdout)
  return decodeJsonObject(result.stdout)
}
