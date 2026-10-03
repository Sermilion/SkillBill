package skillbill.engine.goalrunner.findings

import skillbill.engine.featuretask.runner.InMemoryRuntimeWorkflowRepository
import skillbill.engine.featuretask.runner.RuntimeFakeDatabaseSessionFactory
import skillbill.engine.goalrunner.persist.decodeWorkflowArtifactsForTest
import skillbill.goalrunner.model.UnaddressedFinding
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private val FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS.label()

class UnaddressedFindingsLedgerServiceVerificationProvenanceTest {
  @Test
  fun `verification dispositions expose selected heading ids and source paths for the issue key`() {
    val repository = InMemoryRuntimeWorkflowRepository()
    val workflowId = "wftr-provenance"
    seedWorkflow(
      repository,
      workflowId,
      """
      {
        "$FEATURE_TASK_RUNTIME_FINDING_VERIFICATION_DISPOSITIONS_ARTIFACT_KEY": [{
          "finding_id": "F-001",
          "disposition": "verified",
          "reason": "Matches intent",
          "severity": "major",
          "location": "Foo.kt",
          "message": "example",
          "selected_boundary_headings": [{
            "heading_id": "runtime-kotlin/agent/history.md#abc",
            "source_path": "runtime-kotlin/agent/history.md"
          }]
        }]
      }
      """.trimIndent(),
    )
    val database = RuntimeFakeDatabaseSessionFactory(repository)
    database.ledgerRows.add(
      UnaddressedFinding(
        issueKey = "SKILL-202",
        subtaskId = 3,
        workflowId = workflowId,
        reviewPassNumber = 1,
        findingOrdinal = 1,
        severity = "major",
        issueCategory = "behavior_correctness",
        location = "Foo.kt",
        summary = "example",
      ),
    )
    val service = UnaddressedFindingsLedgerService(database, NoopRuntimeDiagnostics)

    val dispositions = assertNotNull(service.verificationDispositions("SKILL-202"))

    assertEquals(1, dispositions.size)
    assertEquals("F-001", dispositions.single().findingId)
    val selectedHeading = dispositions.single().selectedBoundaryHeadings.single()
    assertEquals("runtime-kotlin/agent/history.md#abc", selectedHeading.headingId)
    assertEquals("runtime-kotlin/agent/history.md", selectedHeading.sourcePath)
  }
}

private fun seedWorkflow(
  repository: InMemoryRuntimeWorkflowRepository,
  workflowId: String,
  artifactsJson: String,
) {
  val engine = WorkflowEngine()
  val definition = WorkflowFamily.TASK_RUNTIME.definition
  val opened = engine.openRecord(definition, workflowId, "ftr-provenance", "verify_findings")
  val seeded =
    engine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        terminalInstant = Instant.EPOCH,
        workflowStatus = WorkflowStatus.RUNNING,
        currentStepId = "verify_findings",
        stepUpdates = null,
        artifactsPatch = WorkflowArtifactPatch.from(decodeWorkflowArtifactsForTest(artifactsJson)),
        sessionId = "ftr-provenance",
      ),
    ).toRecord()
  repository.saveFeatureTaskWorkflow(seeded, FeatureTaskWorkflowMode.RUNTIME)
}
