package skillbill.infrastructure.workflow.featuretask

import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowContinueDecisionOverrides
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureVerifyWorkflowRuntimeTest {
  private val definition = FeatureVerifyWorkflowDefinition.definition
  private val engine = WorkflowEngine()

  @Test
  fun `verify open completes steps before the initial step`() {
    val record = engine.openRecord(definition, "wfv-001", "fvr-001", "code_review")
    val steps = engine.snapshotView(definition, record).steps

    assertEquals("completed", steps.single { it.stepId == "collect_inputs" }.status.wireValue)
    assertEquals("completed", steps.single { it.stepId == "gather_diff" }.status.wireValue)
    assertEquals("running", steps.single { it.stepId == "code_review" }.status.wireValue)
    assertEquals("pending", steps.single { it.stepId == "verdict" }.status.wireValue)
  }

  @Test
  fun `verify resume reports done and recover modes`() {
    val running = engine.openRecord(definition, "wfv-001", "fvr-001", "gather_diff")
    val completed =
      engine.updateRecord(
        definition,
        running,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.COMPLETED,
          currentStepId = "finish",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(mapOf("step_id" to "finish", "status" to "completed", "attempt_count" to 1)),
            ),
          artifactsPatch = WorkflowArtifactPatch.from(mapOf("verdict_result" to mapOf("verdict" to "pass"))),
          sessionId = "",
        ),
      )
    val failed = completed.copy(workflowStatus = WorkflowStatus.FAILED)

    assertEquals("done", engine.resumeView(definition, completed).resumeMode.wireValue)
    assertEquals("recover", engine.resumeView(definition, failed).resumeMode.wireValue)
  }

  @Test
  fun `verify continuation preserves artifact order and directives`() {
    val record =
      engine.updateRecord(
        definition,
        engine.openRecord(definition, "wfv-001", "fvr-001", "code_review"),
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "verdict",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(mapOf("step_id" to "verdict", "status" to "blocked", "attempt_count" to 1)),
            ),
          artifactsPatch =
            WorkflowArtifactPatch.from(
              linkedMapOf(
                "feature_flag_audit_receipt" to evaluatorReceipt("not_applicable"),
                "code_review_receipt" to evaluatorReceipt("pass"),
                "unit_test_value_receipt" to evaluatorReceipt("strong"),
                "completeness_audit_receipt" to evaluatorReceipt("pass"),
                "diff_projection" to
                  mapOf(
                    "checkpoint" to "abc123",
                    "comparison_scope" to "branch_diff",
                    "changed_files" to listOf("Changed.kt"),
                  ),
              ),
            ),
          sessionId = "",
        ),
      )

    val decision =
      engine.continueDecision(
        definition,
        record,
        overrides = WorkflowContinueDecisionOverrides(repositoryCheckpointIdentity = "abc123"),
      )

    assertEquals("reopened", decision.view.continueStatus.wireValue)
    assertEquals(
      listOf(
        "feature_flag_audit_receipt",
        "code_review_receipt",
        "unit_test_value_receipt",
        "completeness_audit_receipt",
        "diff_projection",
      ),
      decision.view.stepArtifactKeys,
    )
    assertTrue(decision.view.continueStepDirective.contains("final verdict"))
  }

  @Test
  fun `verify validation preserves workflow status contract`() {
    val pending =
      WorkflowUpdateInput(
        workflowStatus = WorkflowStatus.PENDING,
        currentStepId = "code_review",
        stepUpdates =
          WorkflowStepUpdates.from(
            listOf(mapOf("step_id" to "code_review", "status" to "failed", "attempt_count" to 1)),
          ),
        artifactsPatch = null,
        sessionId = "",
        terminalInstant = Instant.EPOCH,
      )
    val abandoned = pending.copy(workflowStatus = WorkflowStatus.ABANDONED)

    val existing = engine.openRecord(definition, "wfv-validation", "fvr-001", "code_review")
    assertEquals(WorkflowStatus.PENDING, engine.updateRecord(definition, existing, pending).workflowStatus)
    assertEquals(WorkflowStatus.ABANDONED, engine.updateRecord(definition, existing, abandoned).workflowStatus)
    assertEquals("recover", engine.resumeView(definition, completedAs("abandoned")).resumeMode.wireValue)
    val failure =
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        engine.updateRecord(definition, existing, pending.copy(workflowStatus = WorkflowStatus.BLOCKED))
      }
    assertEquals(
      "Invalid workflow_status 'blocked'. Allowed: pending, running, completed, failed, abandoned",
      failure.message,
    )
  }

  @Test
  fun `verify continuation directives preserve oracle text`() {
    assertEquals(
      "Reuse criteria_summary, review_rubric, and diff_projection, pass orchestrated=true to " +
        "`skill-bill phase review`, persist code_review_receipt, and keep telemetry in its dedicated store.",
      definition.continuationDirectives["code_review"],
    )
    assertEquals(
      "Reuse only compact typed evaluator receipts to produce the final verdict without rerunning earlier phases.",
      definition.continuationDirectives["verdict"],
    )
  }

  private fun evaluatorReceipt(verdict: String) =
    mapOf(
      "contract_version" to "0.1",
      "verdict" to verdict,
      "findings" to emptyList<String>(),
    )

  private fun completedAs(status: String) =
    engine.updateRecord(
      definition,
      engine.openRecord(definition, "wfv-terminal", "fvr-001", "gather_diff"),
      WorkflowUpdateInput(
        terminalInstant = Instant.EPOCH,
        workflowStatus = WorkflowStatus.fromWire(status) ?: error("Unknown workflow status '$status'."),
        currentStepId = "finish",
        stepUpdates =
          WorkflowStepUpdates.from(
            listOf(mapOf("step_id" to "finish", "status" to "completed", "attempt_count" to 1)),
          ),
        artifactsPatch = WorkflowArtifactPatch.from(mapOf("verdict_result" to emptyMap<String, Any?>())),
        sessionId = "",
      ),
    )
}
