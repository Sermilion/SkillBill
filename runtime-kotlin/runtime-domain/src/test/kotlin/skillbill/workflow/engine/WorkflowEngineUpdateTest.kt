package skillbill.workflow.engine

import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WorkflowEngineUpdateTest {
  private val engine = WorkflowEngine()
  private val definition = FeatureVerifyWorkflowDefinition.definition
  private val initial = engine.openRecord(definition, "wfv-update", "session", "gather_diff")
  private val input = WorkflowUpdateInput(WorkflowStatus.RUNNING, "gather_diff", null, null, "session")

  @Test
  fun `update rejects undeclared steps disallowed statuses duplicates and invalid attempts`() {
    val invalid =
      listOf(
        input.copy(currentStepId = "not-declared"),
        input.copy(workflowStatus = WorkflowStatus.BLOCKED),
        input.copy(stepUpdates = updates("not-declared", "running", 1)),
        input.copy(stepUpdates = updates("gather_diff", "unknown", 1)),
        input.copy(stepUpdates = updates("gather_diff", "running", -1)),
        input.copy(stepUpdates = updates("gather_diff", "running", 1.5)),
        input.copy(stepUpdates = WorkflowStepUpdates.from(listOf(step(), step()))),
      )
    invalid.forEach { update ->
      assertFailsWith<InvalidWorkflowStateSchemaError> { engine.updateRecord(definition, initial, update) }
    }
    val restricted = definition.copy(stepStatusEnums = setOf(WorkflowStepStatus.PENDING))
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      engine.updateRecord(restricted, initial, input.copy(stepUpdates = updates("gather_diff", "running", 1)))
    }
    assertEquals(WorkflowStatus.RUNNING, initial.workflowStatus)
    assertEquals(1, initial.steps.single { it.stepId == "gather_diff" }.attemptCount)
  }

  @Test
  fun `terminal updates require an instant retain it on repeat and clear it when reopened`() {
    val terminal = input.copy(workflowStatus = WorkflowStatus.COMPLETED)
    assertFailsWith<InvalidWorkflowStateSchemaError> { engine.updateRecord(definition, initial, terminal) }
    val time = Instant.parse("2026-06-02T10:00:00.123456789Z")
    val completed = engine.updateRecord(definition, initial, terminal.copy(terminalInstant = time))
    val repeated = engine.updateRecord(definition, completed, terminal.copy(terminalInstant = time.plusSeconds(10)))
    assertEquals(time, completed.finishedAt)
    assertEquals(time, repeated.finishedAt)
    assertNull(engine.updateRecord(definition, repeated, input).finishedAt)
  }

  @Test
  fun `step updates keep definition order and artifact replacement removes old keys`() {
    val first =
      engine.updateRecord(
        definition,
        initial,
        input.copy(
          artifactsPatch = WorkflowArtifactPatch.from(linkedMapOf("first" to 1, "second" to 2)),
        ),
      )
    val updated =
      engine.updateRecord(
        definition,
        first,
        input.copy(
          stepUpdates = updates("gather_diff", "completed", 2),
          artifactsPatch = WorkflowArtifactPatch.from(mapOf("only" to 3)),
          replaceArtifacts = true,
        ),
      )
    assertEquals(definition.stepIds, updated.steps.map { it.stepId })
    assertEquals(WorkflowStepStatus.COMPLETED, updated.steps.single { it.stepId == "gather_diff" }.status)
    assertEquals(2, updated.steps.single { it.stepId == "gather_diff" }.attemptCount)
    assertEquals(mapOf("only" to 3), updated.artifacts.toMap())
    assertEquals(mapOf("first" to 1, "second" to 2), first.artifacts.toMap())
  }

  private fun step(
    id: String = "gather_diff",
    status: String = "running",
    attempts: Number = 1,
  ) = mapOf("step_id" to id, "status" to status, "attempt_count" to attempts)

  private fun updates(
    id: String,
    status: String,
    attempts: Number,
  ) = WorkflowStepUpdates.from(listOf(step(id, status, attempts)))
}
