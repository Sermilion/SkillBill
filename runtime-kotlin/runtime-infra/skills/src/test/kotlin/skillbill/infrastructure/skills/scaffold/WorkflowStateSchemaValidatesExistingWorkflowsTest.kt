package skillbill.infrastructure.skills.scaffold

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.infrastructure.contracts.workflow.WorkflowStateSchemaValidator
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.verify.FeatureVerifyWorkflowDefinition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkflowStateSchemaValidatesExistingWorkflowsTest {
  private val validator = WorkflowStateSchemaValidator()
  private val engine: WorkflowEngine = WorkflowEngine()

  @Test
  fun `every feature-task step snapshot from the engine validates clean`() {
    validateEverySnapshotPerStep(FeatureTaskRuntimePhaseWorkflowDefinition.definition)
  }

  @Test
  fun `every feature-verify step snapshot from the engine validates clean`() {
    validateEverySnapshotPerStep(FeatureVerifyWorkflowDefinition.definition)
  }

  @Test
  fun `every feature-task workflow_status snapshot from the engine validates clean`() {
    validateEveryWorkflowStatus(FeatureTaskRuntimePhaseWorkflowDefinition.definition)
  }

  @Test
  fun `every feature-verify workflow_status snapshot from the engine validates clean`() {
    validateEveryWorkflowStatus(FeatureVerifyWorkflowDefinition.definition)
  }

  @Test
  fun `unknown durable workflow status token raises the typed schema error`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        engine.openRecord(FeatureVerifyWorkflowDefinition.definition, "wfv-invalid", "", "gather_diff")
          .toRecord().copy(workflowStatus = "unknown").toSnapshot()
      }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }

    assertEquals("Workflow state workflow_status has unsupported value 'unknown'.", error.message)
  }

  private fun validateEverySnapshotPerStep(definition: WorkflowDefinition) {
    definition.stepIds.forEach { activeStepId ->
      val record =
        engine.openRecord(
          definition = definition,
          workflowId = "wftr-19700101-000000-aaaa",
          sessionId = "",
          currentStepId = activeStepId,
        )

      validator.validate(record, definition.workflowName)
      engine.snapshotView(definition, record)
      engine.summaryView(definition, record)
      engine.resumeView(definition, record)
    }
  }

  private fun validateEveryWorkflowStatus(definition: WorkflowDefinition) {
    definition.workflowStatuses.forEach { status ->
      val opened =
        engine.openRecord(
          definition = definition,
          workflowId = "wftr-19700101-000000-aaaa",
          sessionId = "",
          currentStepId = definition.defaultInitialStepId,
        )
      val terminal = status in definition.terminalStatuses
      val stepUpdates =
        if (terminal) {
          definition.stepIds.map { stepId ->
            linkedMapOf<String, Any?>(
              "step_id" to stepId,
              "status" to "completed",
              "attempt_count" to 1,
            )
          }
        } else {
          null
        }
      val updated =
        engine.updateRecord(
          definition = definition,
          existing = opened,
          input =
            WorkflowUpdateInput(
              terminalInstant = Instant.EPOCH,
              workflowStatus =
                WorkflowStatus.fromWire(status)
                  ?: error("Unsupported workflow status '$status'"),
              currentStepId = definition.defaultInitialStepId,
              stepUpdates = stepUpdates?.let(WorkflowStepUpdates::from),
              artifactsPatch = null,
              sessionId = "",
            ),
        )
      val withFinishedAt =
        if (terminal) {
          updated.copy(finishedAt = Instant.EPOCH)
        } else {
          updated
        }
      validator.validate(withFinishedAt, definition.workflowName)
      engine.snapshotView(definition, withFinishedAt)
    }
  }
}
