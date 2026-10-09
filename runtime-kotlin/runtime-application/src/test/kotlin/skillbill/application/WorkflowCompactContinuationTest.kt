package skillbill.application

import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.service.WorkflowService
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.workflow.NoopGoalPlanningPreparationEnvelopeValidator
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.model.WorkflowStatus
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val WORKFLOW_INPUT_PROJECTION_BYTE_CEILING = 64 * 1024

class WorkflowCompactContinuationTest {
  @Test
  fun `continueWorkflow compact projection inlines small current-step artifacts`() {
    val (service, opened) =
      newBlockedImplementService(
        mapOf(
          "branch" to mapOf("branch_name" to "feat/demo"),
          "preplan_digest" to mapOf("risk" to "low"),
          "feature_task_runtime_phase_records" to
            mapOf(
              "plan" to completedPlanPhaseRecord(outputArtifact = """{"mode":"implement","task_count":1}"""),
            ),
        ),
      )

    val standard =
      assertIs<WorkflowContinueResult.Standard>(
        service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, opened.workflowId),
      )
    val compact = standard.view.compact

    assertEquals("reopened", compact.continueStatus.wireValue)
    assertEquals("reopened", standard.view.continueStatus.wireValue)
    assertEquals("blocked", compact.workflowStatusBeforeContinue.wireValue)
    assertEquals("blocked", standard.view.workflowStatusBeforeContinue.wireValue)
    assertEquals(opened.workflowId, compact.workflowId)
    assertEquals("bill-feature-task", compact.skillName)
    assertEquals("implement", compact.resumeStepId)
    assertEquals("Phase 3: Implement", compact.resumeStepLabel)
    assertEquals(listOf("plan"), compact.requiredArtifactKeys)
    assertEquals(
      listOf("branch", "feature_task_runtime_phase_records", "preplan_digest"),
      compact.availableArtifactKeys,
    )
    assertEquals(
      listOf("branch", "feature_task_runtime_phase_records", "preplan_digest"),
      compact.omittedArtifactKeys,
    )
    assertTrue(compact.continuationBrief.contains(opened.workflowId))
    assertTrue(compact.continuationEntryPrompt.contains("Continue status: reopened"))
    assertTrue(compact.continuationBrief.contains("`current_step_artifacts`"))
    assertTrue(compact.continuationEntryPrompt.contains("Current-step artifacts: plan"))
    assertFalse(compact.continuationEntryPrompt.contains("Current-step artifacts: plan, preplan_digest"))
    assertTrue(compact.continuationEntryPrompt.contains("Omitted artifact keys: branch"))
    assertTrue(
      compact.continuationBrief.contains(
        "Omitted artifact keys (branch, feature_task_runtime_phase_records, preplan_digest) remain " +
          "private phase context",
      ),
    )
    assertFalse(compact.continuationBrief.contains("`step_artifacts`"))
    assertFalse(compact.continuationEntryPrompt.contains("Recovered artifacts:"))
    val planSummary = compact.currentStepArtifacts.single { it.key == "plan" }
    assertTrue(planSummary.present)
    assertTrue(planSummary.inline)
    assertFalse(planSummary.truncated)
    assertEquals("""{"mode":"implement","task_count":1}""", planSummary.value.raw)
  }

  @Test
  fun `continueWorkflow compact projection bounds large current-step artifacts with a preview`() {
    val (service, opened) =
      newBlockedImplementService(
        mapOf(
          "preplan_digest" to mapOf("risk" to "low"),
          "feature_task_runtime_phase_records" to
            mapOf(
              "plan" to
                completedPlanPhaseRecord(
                  outputArtifact = """{"mode":"implement","body":"${"x".repeat(5000)}"}""",
                ),
            ),
        ),
      )

    val standard =
      assertIs<WorkflowContinueResult.Standard>(
        service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, opened.workflowId),
      )
    val planSummary = standard.view.compact.currentStepArtifacts.single { it.key == "plan" }

    assertEquals("reopened", standard.view.continueStatus.wireValue)
    assertTrue(planSummary.present)
    assertFalse(planSummary.inline)
    assertTrue(requireNotNull(planSummary.sizeBytes) > 4096)
    assertEquals(null, planSummary.value.raw)
    assertNotNull(planSummary.preview)
    assertTrue(planSummary.truncated)
    assertTrue(planSummary.omitted)
    assertEquals("artifact_exceeds_inline_limit", planSummary.omissionReason)
    assertTrue(standard.view.compact.continuationEntryPrompt.contains("Current-step artifacts: plan"))
    assertFalse(standard.view.compact.continuationEntryPrompt.contains("Current-step artifacts: plan, preplan_digest"))
    assertFalse(standard.view.compact.continuationEntryPrompt.contains("Recovered artifacts:"))
  }

  @Test
  fun `compact continuation stays within projection budget and omits private artifacts`() {
    val (service, opened) =
      newBlockedImplementService(
        mapOf(
          "preplan_digest" to mapOf("risk" to "low", "notes" to "y".repeat(8000)),
          "feature_task_runtime_phase_records" to
            mapOf(
              "plan" to
                completedPlanPhaseRecord(
                  outputArtifact = """{"mode":"implement","body":"${"x".repeat(12000)}"}""",
                ),
            ),
        ),
      )

    val standard =
      assertIs<WorkflowContinueResult.Standard>(
        service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, opened.workflowId),
      )
    val compactMap =
      mapOf(
        "current_step_artifacts" to
          standard.view.compact.currentStepArtifacts.map { artifact ->
            mapOf(
              "key" to artifact.key,
              "preview" to artifact.preview,
              "omission_reason" to artifact.omissionReason,
            )
          },
        "read_only_full_state_guidance" to standard.view.compact.readOnlyFullStateGuidance,
      )
    val serialized = JsonCodec.mapToJsonString(compactMap)
    val byteSize = serialized.toByteArray(Charsets.UTF_8).size

    assertTrue(
      byteSize < WORKFLOW_INPUT_PROJECTION_BYTE_CEILING,
      "Compact continuation exceeded the declared workflow input projection byte ceiling.",
    )
    assertFalse(serialized.contains("\"step_artifacts\""))
    assertFalse(serialized.contains("\"artifacts\":"))

    assertFalse(serialized.contains("x".repeat(2000)))
    assertFalse(serialized.contains("y".repeat(2000)))
    val planSummary = standard.view.compact.currentStepArtifacts.single { it.key == "plan" }
    assertTrue(planSummary.present)
    assertFalse(planSummary.inline)
    assertTrue(requireNotNull(planSummary.sizeBytes) > 4096)
    assertNotNull(planSummary.preview)
    assertEquals("artifact_exceeds_inline_limit", planSummary.omissionReason)
  }

  @Test
  fun `full continue projection exercises the full shape distinctly from compact`() {
    val (service, opened) =
      newBlockedImplementService(
        mapOf(
          "plan" to mapOf("mode" to "implement", "body" to "x".repeat(12000)),
          "preplan_digest" to mapOf("risk" to "low"),
        ),
      )

    val standard =
      assertIs<WorkflowContinueResult.Standard>(
        service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, opened.workflowId),
      )

    val fullMap =
      mapOf(
        "step_artifacts" to standard.view.stepArtifacts,
        "artifacts" to standard.view.resume.snapshot.artifacts,
      )
    val fullSerialized = JsonCodec.mapToJsonString(fullMap)

    assertTrue(fullSerialized.contains("\"step_artifacts\""))
    assertTrue(fullSerialized.contains("x".repeat(2000)))
    assertTrue(fullSerialized.contains("preplan_digest"))
  }
}

private fun newService(): WorkflowService =
  WorkflowService(
    database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
    gitOperations = NoopWorkflowGitOperations,
    decompositionManifestStore = UnavailableDecompositionManifestStore,
    workflowSnapshotValidator = testWorkflowSnapshotValidator,
    decompositionManifestValidator = testDecompositionManifestValidator,
    decompositionManifestWriter = testDecompositionManifestWriter,
    repositoryRoot = testRepositoryRoot,
    goalObservabilityEventValidator = NoopGoalPlanningPreparationEnvelopeValidator,
    runtimeDiagnostics = NoopRuntimeDiagnostics,
    clock = Clock.systemUTC(),
    repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
  )

private fun newBlockedImplementService(
  artifactsPatch: Map<String, Any?>,
): Pair<WorkflowService, WorkflowOpenResult.Ok> {
  val service = newService()
  val opened =
    assertIs<WorkflowOpenResult.Ok>(
      service.open(WorkflowServiceOpenArgs(kind = WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001")),
    )
  service.update(
    WorkflowFamilyKind.TASK_RUNTIME,
    WorkflowUpdateRequest(
      workflowId = opened.workflowId,
      workflowStatus = WorkflowStatus.BLOCKED.wireValue,
      currentStepId = "implement",
      stepUpdates =
        WorkflowStepUpdates.from(
          listOf(
            mapOf("step_id" to "implement", "status" to "blocked", "attempt_count" to 1),
          ),
        ),
      artifactsPatch = WorkflowArtifactPatch.from(artifactsPatch),
    ),
  )
  return service to opened
}

private fun completedPlanPhaseRecord(outputArtifact: String? = null): Map<String, Any?> =
  linkedMapOf(
    "contract_version" to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
    "record_kind" to "private_phase_record",
    "phase_id" to "plan",
    "status" to "completed",
    "attempt_count" to 1,
    "started_at" to "2026-08-09T10:00:00Z",
    "first_started_at" to "2026-08-09T10:00:00Z",
    "finished_at" to "2026-08-09T10:01:00Z",
    "resolved_agent_id" to "agent-plan",
    "execution_origin" to "agent-executed",
  ).apply {
    outputArtifact?.let { put("output_artifact", it) }
  }
