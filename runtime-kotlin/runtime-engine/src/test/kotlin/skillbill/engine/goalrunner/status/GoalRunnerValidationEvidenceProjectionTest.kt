package skillbill.engine.goalrunner.status

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.goalRunnerDefaultPhaseRecorder
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoalRunnerValidationEvidenceProjectionTest {
  @Test
  fun `completed subtask status derives the validation result from the validate envelope status`() {
    data class Case(
      val envelopeStatus: String,
      val recordStatus: String,
      val legacySignal: Any?,
      val expectedPassed: Boolean?,
      val expectedProblem: String?,
    )
    listOf(
      Case("completed", "completed", null, true, null),
      Case("completed", "completed", true, true, null),
      Case("completed", "completed", false, true, null),
      Case("blocked", "completed", null, null, "Validation phase is not completed."),
      Case("failed", "completed", true, null, "Validation phase is not completed."),
      Case("completed", "blocked", null, true, "Validation phase is not completed."),
    ).forEach { case ->
      val workflowId = "wfl-validation-result"
      val recorder = goalRunnerDefaultPhaseRecorder()
      recorder.ensureWorkflowOpen(workflowId, "goal-validation-result")
      val produced =
        buildMap<String, Any?> {
          put(SharedPayloadKeys.VALUE, "Project validation result.")
          if (case.legacySignal != null) put(ValidationEvidencePayloadKeys.VALIDATION_PASSED, case.legacySignal)
        }
      recorder.recordPhaseState(
        FeatureTaskRuntimePhaseStateRequest(
          workflowId = workflowId,
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
          status = case.recordStatus,
          attemptCount = 1,
          resolvedAgentId = "claude",
          finished = true,
          outputArtifact =
            JsonCodec.mapToJsonString(
              mapOf(
                SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
                SharedPayloadKeys.PHASE_ID to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
                SharedPayloadKeys.STATUS to case.envelopeStatus,
                SharedPayloadKeys.SUMMARY to "Validate output.",
                SharedPayloadKeys.PRODUCED_OUTPUTS to produced,
              ),
            ),
        ),
      )
      val manifest = completedManifest(workflowId)
      val service =
        testGoalRunnerStatusService(
          manifestStore = InMemoryGoalManifestStore(manifest),
          outcomeStore = RecordingOutcomeStore(),
          phaseRecorder = recorder,
        )
      val validation =
        requireNotNull(
          service.status(GoalRunnerStatusRequest(issueKey = manifest.issueKey, invokedAgentId = "codex")),
        ).completedSubtaskValidation.single()
      assertEquals(case.expectedPassed, validation.validationPassed, "$case")
      assertEquals(case.expectedProblem, validation.integrityProblem, "$case")
      assertNull(validation.evidence)
      assertNull(validation.gateExecutionEvidence)
      val wire = JsonCodec.anyToStringAnyMap(validation.toStatusWire())!!
      assertEquals(case.expectedPassed, wire[ValidationEvidencePayloadKeys.VALIDATION_PASSED], "$case")
    }
  }

  private fun completedManifest(workflowId: String): DecompositionManifest =
    manifest(1).copy(
      status = "complete",
      subtasks =
        listOf(
          DecompositionSubtask(
            id = 1,
            name = "Subtask 1",
            specPath = ".feature-specs/SKILL-56-goal/spec_subtask_1.md",
            dependencies = emptyList(),
            status = "complete",
            workflowId = workflowId,
            commitSha = "sha-1",
          ),
        ),
    )
}
