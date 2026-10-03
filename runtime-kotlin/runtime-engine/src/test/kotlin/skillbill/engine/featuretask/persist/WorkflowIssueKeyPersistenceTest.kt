package skillbill.engine.featuretask.persist

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.application.testRepositoryRoot
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.persist.openFeatureTask
import skillbill.application.workflow.service.WorkflowService
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.slot.testExecutionPlan
import skillbill.engine.goalrunner.execution.core.testPhaseRecorder
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.error.shellcontent.WorkflowIssueKeyConflictError
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class WorkflowIssueKeyPersistenceTest {
  @Test
  fun `opening every issue keyed workflow persists its normalized issue key in workflow metadata`() {
    val workflows = InMemoryWorkflowStates()
    val service =
      WorkflowService(
        database = FakeDatabaseSessionFactory(workflows),
        gitOperations = NoopWorkflowGitOperations,
        decompositionManifestStore = UnavailableDecompositionManifestStore,
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestWriter = testDecompositionManifestWriter,
        repositoryRoot = testRepositoryRoot,
        goalObservabilityEventValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
        runtimeDiagnostics = NoopRuntimeDiagnostics,
        clock = Clock.systemUTC(),
      )

    val firstRuntime =
      assertIs<WorkflowOpenResult.Ok>(
        service.openFeatureTask(
          WorkflowServiceOpenFeatureTaskArgs(
            executionPlan = testExecutionPlan(),
            kind = WorkflowFamilyKind.TASK_RUNTIME,
            issueKey = "  SKILL-117  ",
            repositoryIdentity = "repo-root-realpath-v1:/test/repository",
            governedSpecPath = ".feature-specs/SKILL-117/spec.md",
          ),
        ),
      )
    val secondRuntime =
      assertIs<WorkflowOpenResult.Ok>(
        service.openFeatureTask(
          WorkflowServiceOpenFeatureTaskArgs(
            executionPlan = testExecutionPlan(),
            kind = WorkflowFamilyKind.TASK_RUNTIME,
            issueKey = " SKILL-118 ",
            repositoryIdentity = "repo-root-realpath-v1:/test/repository",
            governedSpecPath = ".feature-specs/SKILL-118/spec.md",
          ),
        ),
      )
    val verify =
      assertIs<WorkflowOpenResult.Ok>(
        service.open(WorkflowServiceOpenArgs(kind = WorkflowFamilyKind.VERIFY, issueKey = " SKILL-119 ")),
      )

    assertEquals("SKILL-117", assertNotNull(workflows.runtimeRecord(firstRuntime.workflowId)).issueKey)
    assertEquals("SKILL-118", assertNotNull(workflows.runtimeRecord(secondRuntime.workflowId)).issueKey)
    assertEquals("SKILL-119", assertNotNull(workflows.verifyRecord(verify.workflowId)).issueKey)
  }

  @Test
  fun `opening a workflow rejects control-bearing and oversized issue keys`() {
    val workflows = InMemoryWorkflowStates()
    val service =
      WorkflowService(
        database = FakeDatabaseSessionFactory(workflows),
        gitOperations = NoopWorkflowGitOperations,
        decompositionManifestStore = UnavailableDecompositionManifestStore,
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestWriter = testDecompositionManifestWriter,
        repositoryRoot = testRepositoryRoot,
        goalObservabilityEventValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
        runtimeDiagnostics = NoopRuntimeDiagnostics,
        clock = Clock.systemUTC(),
      )

    assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
      service.openFeatureTask(
        WorkflowServiceOpenFeatureTaskArgs(
          executionPlan = testExecutionPlan(),
          kind = WorkflowFamilyKind.TASK_RUNTIME,
          issueKey = "SKILL-117\nspoofed",
          repositoryIdentity = "repo-root-realpath-v1:/test/repository",
          governedSpecPath = ".feature-specs/SKILL-117/spec.md",
        ),
      )
    }
    assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
      service.openFeatureTask(
        WorkflowServiceOpenFeatureTaskArgs(
          executionPlan = testExecutionPlan(),
          kind = WorkflowFamilyKind.TASK_RUNTIME,
          issueKey = "S".repeat(129),
          repositoryIdentity = "repo-root-realpath-v1:/test/repository",
          governedSpecPath = ".feature-specs/SKILL-117/spec.md",
        ),
      )
    }
  }

  @Test
  fun `runtime workflow reopen heals a missing issue key and rejects a conflicting normalized key`() {
    val workflows = InMemoryWorkflowStates()
    val recorder =
      testPhaseRecorder(
        database = FakeDatabaseSessionFactory(workflows),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        handoffEnvelopeValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
        handoffFoundationValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
      )

    recorder.openTestWorkflow("wftr-117", "session-117")
    recorder.openTestWorkflow("wftr-117", "session-117", issueKey = " SKILL-117 ")
    recorder.openTestWorkflow("wftr-117", "session-117", issueKey = "SKILL-117")

    val healed = assertNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-117", FeatureTaskWorkflowMode.RUNTIME))
    assertEquals("SKILL-117", healed.issueKey)

    val conflict =
      assertFailsWith<WorkflowIssueKeyConflictError> {
        recorder.openTestWorkflow("wftr-117", "session-117", issueKey = "SKILL-118")
      }
    assertEquals("wftr-117", conflict.workflowId)
    assertEquals("SKILL-117", conflict.persistedIssueKey)
    assertEquals("SKILL-118", conflict.requestedIssueKey)
    assertEquals("SKILL-117", assertNotNull(workflows.runtimeRecord("wftr-117")).issueKey)
  }
}
