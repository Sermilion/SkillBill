package skillbill.engine.featuretask.review.finding

import skillbill.application.testHarnessClock
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.runner.InMemoryRuntimeWorkflowRepository
import skillbill.engine.featuretask.runner.RuntimeFakeDatabaseSessionFactory
import skillbill.engine.goalrunner.persist.decodeWorkflowArtifactsForTest
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeFindingVerificationRecordError
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

private val FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT.label()

class FeatureTaskRuntimeFindingVerificationDurableDecodeTest {
  @Test
  fun `durable finding verification checkpoint round-trips valid dispositions`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    val workflowId = "wftr-finding-verification"
    seedWorkflow(repository, workflowId, verificationCheckpointArtifactsJson(valid = true))

    val recorder = recorderFor(repository)
    val checkpoint = assertNotNull(recorder.loadFindingVerificationCheckpoint(workflowId))
    assertEquals(1, checkpoint.size)
    assertEquals("F-001", checkpoint.single().findingId)
  }

  @Test
  fun `malformed durable finding verification checkpoint loud-fails instead of coercing to absent`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    val workflowId = "wftr-finding-verification-malformed"
    seedWorkflow(
      repository,
      workflowId,
      verificationCheckpointArtifactsJson(valid = false, malformedShape = true),
    )

    val recorder = recorderFor(repository)
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeFindingVerificationRecordError> {
        recorder.loadFindingVerificationCheckpoint(workflowId)
      }
    assertContains(error.reason, "finding_verification_checkpoint")
    assertContains(error.reason, "array")
  }

  @Test
  fun `extra census severity in durable finding verification checkpoint is ignored`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    val workflowId = "wftr-finding-verification-severity"
    seedWorkflow(
      repository,
      workflowId,
      verificationCheckpointArtifactsJson(valid = false, invalidSeverity = true),
    )

    val recorder = recorderFor(repository)
    val checkpoint = assertNotNull(recorder.loadFindingVerificationCheckpoint(workflowId))
    assertEquals("F-001", checkpoint.single().findingId)
  }

  @Test
  fun `retired disposition field in durable finding verification checkpoint loud-fails with named error`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    val workflowId = "wftr-finding-verification-retired"
    seedWorkflow(
      repository,
      workflowId,
      verificationCheckpointArtifactsJson(valid = false, retiredDispositionField = true),
    )

    val recorder = recorderFor(repository)
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeFindingVerificationRecordError> {
        recorder.loadFindingVerificationCheckpoint(workflowId)
      }
    assertContains(error.reason, "disposition")
  }
}

private fun seedWorkflow(
  repository: InMemoryRuntimeWorkflowRepository,
  workflowId: String,
  artifactsJson: String,
) {
  val engine = WorkflowEngine()
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val opened = engine.openRecord(definition, workflowId, "ftr-finding-verification", "verify_findings")
  val artifacts = decodeWorkflowArtifactsForTest(artifactsJson)
  val seeded =
    engine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        terminalInstant = Instant.EPOCH,
        workflowStatus = WorkflowStatus.RUNNING,
        currentStepId = "verify_findings",
        stepUpdates = null,
        artifactsPatch = WorkflowArtifactPatch.from(artifacts),
        sessionId = "ftr-finding-verification",
      ),
    ).toRecord()
  repository.saveFeatureTaskWorkflow(seeded, FeatureTaskWorkflowMode.RUNTIME)
}

private fun recorderFor(repository: InMemoryRuntimeWorkflowRepository): FeatureTaskRuntimePhaseRecorder =
  featureTaskRuntimePhaseRecorder(
    RuntimeFakeDatabaseSessionFactory(repository),
    testWorkflowSnapshotValidator,
    AcceptingFeatureTaskRuntimeWireArtifactValidator,
    AcceptingFeatureTaskRuntimeWireArtifactValidator,
    testHarnessClock,
    NoopRuntimeDiagnostics,
  )

private fun verificationCheckpointArtifactsJson(
  valid: Boolean,
  malformedShape: Boolean = false,
  invalidSeverity: Boolean = false,
  retiredDispositionField: Boolean = false,
): String {
  val checkpointBody =
    when {
      malformedShape -> """{"finding_id":"F-001"}"""
      valid ->
        """
        [{
          "finding_id":"F-001",
          "disposition":"verified",
          "reason":"Matches spec intent."
        }]
        """.trimIndent()
      retiredDispositionField ->
        """
        [{
          "finding_id":"F-001",
          "verdict":"verified",
          "reason":"Matches spec intent."
        }]
        """.trimIndent()
      invalidSeverity ->
        """
        [{
          "finding_id":"F-001",
          "disposition":"verified",
          "reason":"Matches spec intent.",
          "severity":"catastrophic"
        }]
        """.trimIndent()
      else -> "[]"
    }
  return """
    {"$FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_CHECKPOINT_ARTIFACT_KEY":$checkpointBody}
    """.trimIndent()
}
