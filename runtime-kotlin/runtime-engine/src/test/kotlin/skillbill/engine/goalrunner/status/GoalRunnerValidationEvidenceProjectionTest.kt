package skillbill.engine.goalrunner.status

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.identity.evidence.ValidationEvidencePayloadKeys
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.goalRunnerDefaultPhaseRecorder
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.goalrunner.model.GoalRunnerPhaseConclusion
import skillbill.goalrunner.model.GoalRunnerQaCheck
import skillbill.goalrunner.model.GoalRunnerQaCriterion
import skillbill.goalrunner.model.GoalRunnerQaCriterionFinding
import skillbill.goalrunner.model.GoalRunnerQaFinding
import skillbill.goalrunner.model.GoalRunnerQaResult
import skillbill.ports.featuretask.model.FeatureTaskQaFinding
import skillbill.ports.featuretask.model.FeatureTaskQaFindingStatus.FIXED
import skillbill.ports.featuretask.model.FeatureTaskQaFindingStatus.NOT_FIXED
import skillbill.ports.featuretask.model.FeatureTaskQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaCheck
import skillbill.ports.idestatus.model.StandaloneQaCriterion
import skillbill.ports.idestatus.model.StandaloneQaCriterionFinding
import skillbill.ports.idestatus.model.StandaloneQaCriterionStatus
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaReport
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class GoalRunnerValidationEvidenceProjectionTest {
  @Test
  fun `detail pagination projects validation only for the selected subtask ids`() {
    val manifest =
      manifest(2).let { original ->
        original.copy(subtasks = original.subtasks.map { it.copy(status = "complete") })
      }
    val service =
      testGoalRunnerStatusService(
        manifestStore = InMemoryGoalManifestStore(manifest),
        outcomeStore = RecordingOutcomeStore(),
      )
    val result =
      requireNotNull(
        service.status(
          GoalRunnerStatusRequest(issueKey = manifest.issueKey, completedSubtaskValidationIds = setOf(2)),
        ),
      )
    assertEquals(listOf(2), result.completedSubtaskValidation.map { it.subtaskId })
    assertEquals(2, result.completeCount)
  }

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
      recorder.openTestWorkflow(workflowId, "goal-validation-result")
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

  @Test
  fun `completed subtask status projects the review and audit conclusions and the goal QA outcome`() {
    val workflowId = "wfl-phase-results"
    val recorder = goalRunnerDefaultPhaseRecorder()
    recorder.openTestWorkflow(workflowId, "goal-phase-results")
    recorder.recordPhaseState(
      phaseConclusionRequest(
        workflowId,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        verdict = "approved",
        summary = "No production-blocking findings.",
        detail = "The retained field values survive a restart.",
        attemptCount = 1,
      ),
    )
    recorder.recordPhaseState(
      phaseConclusionRequest(
        workflowId,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        verdict = "satisfied",
        summary = "All criteria met.",
        detail = "AC-001 met.\nAC-002 met.",
        attemptCount = 2,
      ),
    )
    val manifest = completedManifest(workflowId)
    val outcomeStore =
      RecordingOutcomeStore().apply {
        goalQaOutcome =
          FeatureTaskQaOutcome(
            workflowId = "wfl-parent",
            outcome = StandaloneQaOutcome.FINDINGS,
            reason = "Login flow failed.",
            qaFixAttempts = 1,
            testedTreeIdentity = null,
            recordedAt = "2026-10-20T10:15:30Z",
            findings = storedQaFindings,
            report = storedQaReport,
          )
      }
    val result =
      requireNotNull(
        testGoalRunnerStatusService(
          manifestStore = InMemoryGoalManifestStore(manifest),
          outcomeStore = outcomeStore,
          phaseRecorder = recorder,
        ).status(GoalRunnerStatusRequest(issueKey = manifest.issueKey, invokedAgentId = "codex")),
      )
    val phaseResults = result.completedSubtaskPhaseResults.single()
    assertEquals(1, phaseResults.subtaskId)
    assertEquals(emptyList(), phaseResults.reviewPasses)
    assertEquals(
      GoalRunnerPhaseConclusion(
        "approved",
        "No production-blocking findings.",
        "The retained field values survive a restart.",
        1,
      ),
      phaseResults.review,
    )
    assertEquals(
      GoalRunnerPhaseConclusion("satisfied", "All criteria met.", "AC-001 met.\nAC-002 met.", 2),
      phaseResults.audit,
    )
    assertEquals(
      GoalRunnerQaResult(
        "findings",
        "Login flow failed.",
        1,
        Instant.parse("2026-10-20T10:15:30Z"),
        projectedQaFindings,
        projectedQaCriteria,
      ),
      result.goalQaResult,
    )
    assertFalse(result.degradedDurableRead)
  }

  private val storedQaFindings =
    listOf(
      FeatureTaskQaFinding("F-1", "Login fails.", listOf("AC-001"), listOf("/qa/1.png"), null, FIXED),
      FeatureTaskQaFinding("F-2", "Home is blank.", listOf("AC-002"), emptyList(), "/qa/r.json", NOT_FIXED),
    )

  private val projectedQaFindings =
    listOf(
      GoalRunnerQaFinding("F-1", "Login fails.", "/qa/1.png", fixed = true),
      GoalRunnerQaFinding("F-2", "Home is blank.", "/qa/r.json", fixed = false),
    )

  private val storedQaReport =
    StandaloneQaReport(
      listOf(
        StandaloneQaCriterion(
          "AC-001",
          StandaloneQaCriterionStatus.FAILED,
          listOf(StandaloneQaCheck("Login works.", false)),
          listOf(StandaloneQaCriterionFinding("F-2", emptyList(), "Home shows user.", "Home is blank.")),
        ),
        StandaloneQaCriterion(
          "AC-002",
          StandaloneQaCriterionStatus.PASSED,
          listOf(StandaloneQaCheck("Settings open.", true)),
          emptyList(),
        ),
      ),
    )

  private val projectedQaCriteria =
    listOf(
      GoalRunnerQaCriterion(
        "AC-001",
        false,
        listOf(GoalRunnerQaCheck("Login works.", false)),
        listOf(GoalRunnerQaCriterionFinding("F-2", emptyList(), "Home shows user.", "Home is blank.")),
      ),
      GoalRunnerQaCriterion(
        "AC-002",
        true,
        listOf(GoalRunnerQaCheck("Settings open.", true)),
        emptyList(),
      ),
    )

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

  private fun phaseConclusionRequest(
    workflowId: String,
    phaseId: String,
    verdict: String,
    summary: String,
    detail: String,
    attemptCount: Int,
  ): FeatureTaskRuntimePhaseStateRequest =
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = workflowId,
      phaseId = phaseId,
      status = "completed",
      attemptCount = attemptCount,
      resolvedAgentId = "claude",
      finished = true,
      outputArtifact =
        JsonCodec.mapToJsonString(
          mapOf(
            SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
            SharedPayloadKeys.PHASE_ID to phaseId,
            SharedPayloadKeys.STATUS to "completed",
            SharedPayloadKeys.SUMMARY to summary,
            SharedPayloadKeys.VERDICT to verdict,
            SharedPayloadKeys.PRODUCED_OUTPUTS to mapOf(SharedPayloadKeys.VALUE to detail),
          ),
        ),
    )
}
