package skillbill.application.workflow.decomposition

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestDecompositionManifestStore
import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.encodeValidatedDecompositionManifestYaml
import skillbill.application.decomposition.executionModel
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.decomposition.runtime.isGoalContinuationChildWorkflow
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.FeatureTaskWorkflowMode.PROSE
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val testWorkflowEngine: WorkflowEngine = WorkflowEngine()

private class CheckoutRecordingGitOperations : WorkflowGitOperations by NoopWorkflowGitOperations {
  val checkoutCalls = mutableListOf<String>()

  override fun checkoutBranch(
    repoRoot: Path,
    branch: String,
    baseBranch: String?,
  ): WorkflowGitOperationResult {
    checkoutCalls += branch
    return WorkflowGitOperationResult.Ok(value = branch)
  }
}

private fun workflowRecord(
  workflowId: String,
  artifactsPatch: WorkflowArtifactPatch?,
  workflowStatus: WorkflowStatus = WorkflowStatus.RUNNING,
): WorkflowStateRecord {
  val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
  val opened = testWorkflowEngine.openRecord(definition, workflowId, "ftr-001", "preplan")
  return testWorkflowEngine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      terminalInstant = Instant.EPOCH,
      workflowStatus = workflowStatus,
      currentStepId = "plan",
      stepUpdates = null,
      artifactsPatch = artifactsPatch,
      sessionId = "ftr-001",
    ),
  ).toRecord()
}

private fun encodeDecompositionManifestYaml(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
  fileStore: DecompositionManifestStore,
): String = encodeValidatedDecompositionManifestYaml(manifest, validator, fileStore, "<in-memory>").yamlText

private fun InMemoryWorkflowStates.decomposedParentRows(issueKey: String): List<WorkflowStateRecord> =
  listFeatureTaskWorkflows(RUNTIME, Int.MAX_VALUE).filter { row ->
    val snapshot = row.toSnapshot()
    row.issueKey == issueKey &&
      !snapshot.isGoalContinuationChildWorkflow() &&
      snapshot.decompositionRuntime() != null
  }

class DecompositionDiskBootstrapTest {
  @Test
  fun `invalid decomposition issue key fails before branch checkout`() {
    val invalidIssueKey = "S".repeat(129)
    val manifest =
      DecompositionManifest(
        issueKey = invalidIssueKey,
        featureName = "invalid-issue-key",
        parentSpecPath = ".feature-specs/invalid/spec.md",
        status = "in_progress",
        executionModel = DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
        baseBranch = "main",
        featureBranch = "feat/invalid-issue-key",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "implement"),
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 1,
              name = "first-subtask",
              specPath = ".feature-specs/invalid/spec_subtask_1.md",
              status = "pending",
            ),
          ),
      )
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-invalid-issue-key",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "plan" to mapOf("mode" to "decompose"),
              DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label() to
                testDecompositionManifestValidator.encodeManifestWireMap(manifest),
            ),
          ),
      ),
      RUNTIME,
    )
    val gitOperations = CheckoutRecordingGitOperations()
    val continuation =
      DecompositionWorkflowContinuation(
        engine = testWorkflowEngine,
        gitOperations = gitOperations,
        validator = testDecompositionManifestValidator,
        repoRoot = Path.of("").toAbsolutePath().normalize(),
        manifestWriter = testDecompositionManifestWriter,
        clock = Clock.systemUTC(),
        workflowIdRandom = Random.Default,
      )

    assertFailsWith<IllegalArgumentException> {
      FakeDatabaseSessionFactory(workflows).transaction { unitOfWork ->
        continuation.continueDecomposedParentByIssueKey(invalidIssueKey, unitOfWork)
      }
    }

    assertTrue(gitOperations.checkoutCalls.isEmpty())
  }

  @Test
  fun `continueDecomposedParentByIssueKey bootstraps from disk when no DB parent row exists`() {
    val repoRoot = Files.createTempDirectory("skillbill-disk-bootstrap")
    val manifestPath = repoRoot.resolve(".feature-specs/SKILL-TEST-feature/decomposition-manifest.yaml")
    Files.createDirectories(manifestPath.parent)
    val manifest =
      DecompositionManifest(
        issueKey = "SKILL-TEST",
        featureName = "test-feature",
        parentSpecPath = ".feature-specs/SKILL-TEST-feature/spec.md",
        status = "in_progress",
        executionModel = DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
        baseBranch = "main",
        featureBranch = "feat/SKILL-TEST",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "implement"),
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 1,
              name = "first-subtask",
              specPath = ".feature-specs/SKILL-TEST-feature/spec_subtask_1.md",
              status = "pending",
            ),
          ),
      )
    Files.writeString(
      manifestPath,
      encodeDecompositionManifestYaml(manifest, testDecompositionManifestValidator, TestDecompositionManifestStore),
    )
    val workflows = InMemoryWorkflowStates()
    val db = FakeDatabaseSessionFactory(workflows)
    val continuation =
      DecompositionWorkflowContinuation(
        engine = testWorkflowEngine,
        gitOperations = NoopWorkflowGitOperations,
        validator = testDecompositionManifestValidator,
        fileStore = TestDecompositionManifestStore,
        repoRoot = repoRoot,
        manifestWriter = testDecompositionManifestWriter,
        clock = Clock.systemUTC(),
        workflowIdRandom = Random.Default,
      )

    val result =
      db.transaction { unitOfWork ->
        continuation.continueDecomposedParentByIssueKey("SKILL-TEST", unitOfWork)
      }

    assertFalse(
      result.result is WorkflowContinueResult.UnknownWorkflow,
      "Expected disk bootstrap to resolve the manifest; got UnknownWorkflow instead",
    )
    assertTrue(
      workflows.listFeatureTaskWorkflows(RUNTIME, Int.MAX_VALUE).size >= 2,
      "Expected parent bootstrap row and child subtask row in DB",
    )
  }

  @Test
  fun `continueDecomposedParentByIssueKey without a manifest file store reports an unknown workflow`() {
    val repoRoot = Files.createTempDirectory("skillbill-disk-bootstrap-no-store")
    val manifestPath = repoRoot.resolve(".feature-specs/SKILL-TEST-feature/decomposition-manifest.yaml")
    Files.createDirectories(manifestPath.parent)
    val manifest =
      DecompositionManifest(
        issueKey = "SKILL-TEST",
        featureName = "test-feature",
        parentSpecPath = ".feature-specs/SKILL-TEST-feature/spec.md",
        status = "in_progress",
        executionModel = DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
        baseBranch = "main",
        featureBranch = "feat/SKILL-TEST",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "implement"),
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 1,
              name = "first-subtask",
              specPath = ".feature-specs/SKILL-TEST-feature/spec_subtask_1.md",
              status = "pending",
            ),
          ),
      )
    Files.writeString(
      manifestPath,
      encodeDecompositionManifestYaml(manifest, testDecompositionManifestValidator, TestDecompositionManifestStore),
    )
    val workflows = InMemoryWorkflowStates()
    val db = FakeDatabaseSessionFactory(workflows)
    val continuation =
      DecompositionWorkflowContinuation(
        engine = testWorkflowEngine,
        gitOperations = NoopWorkflowGitOperations,
        validator = testDecompositionManifestValidator,
        repoRoot = repoRoot,
        manifestWriter = testDecompositionManifestWriter,
        clock = Clock.systemUTC(),
        workflowIdRandom = Random.Default,
      )

    val result =
      db.transaction { unitOfWork ->
        continuation.continueDecomposedParentByIssueKey("SKILL-TEST", unitOfWork)
      }

    assertTrue(result.result is WorkflowContinueResult.UnknownWorkflow)
    assertTrue(workflows.listFeatureTaskWorkflows(RUNTIME, Int.MAX_VALUE).isEmpty())
  }

  @Test
  fun `continueDecomposedParentByIssueKey bootstraps the parent as paused rather than abandoned`() {
    val repoRoot = Files.createTempDirectory("skillbill-disk-bootstrap-paused")
    val manifestPath = repoRoot.resolve(".feature-specs/SKILL-TEST-feature/decomposition-manifest.yaml")
    Files.createDirectories(manifestPath.parent)
    val manifest =
      DecompositionManifest(
        issueKey = "SKILL-TEST",
        featureName = "test-feature",
        parentSpecPath = ".feature-specs/SKILL-TEST-feature/spec.md",
        status = "in_progress",
        executionModel = DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
        baseBranch = "main",
        featureBranch = "feat/SKILL-TEST",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "implement"),
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 1,
              name = "first-subtask",
              specPath = ".feature-specs/SKILL-TEST-feature/spec_subtask_1.md",
              status = "pending",
            ),
          ),
      )
    Files.writeString(
      manifestPath,
      encodeDecompositionManifestYaml(manifest, testDecompositionManifestValidator, TestDecompositionManifestStore),
    )
    val workflows = InMemoryWorkflowStates()
    val db = FakeDatabaseSessionFactory(workflows)
    val continuation =
      DecompositionWorkflowContinuation(
        engine = testWorkflowEngine,
        gitOperations = NoopWorkflowGitOperations,
        validator = testDecompositionManifestValidator,
        fileStore = TestDecompositionManifestStore,
        repoRoot = repoRoot,
        manifestWriter = testDecompositionManifestWriter,
        clock = Clock.systemUTC(),
        workflowIdRandom = Random.Default,
      )

    db.transaction { unitOfWork ->
      continuation.continueDecomposedParentByIssueKey("SKILL-TEST", unitOfWork)
    }
    val parent =
      assertNotNull(
        workflows.findDecomposedParentWorkflow("SKILL-TEST"),
        "Expected the bootstrapped parent to stay discoverable for a later resume",
      )

    assertEquals("paused", parent.workflowStatus)
    assertFalse(
      parent.workflowStatus in FeatureTaskRuntimePhaseWorkflowDefinition.definition.terminalStatuses,
      "A bootstrapped parent must not be terminal; it is interrupted work awaiting resume.",
    )
  }

  @Test
  fun `continueDecomposedParentByIssueKey reuses existing parent when decomposition artifact is corrupt`() {
    val (workflows, continuation) = corruptParentContinuation()
    val db = FakeDatabaseSessionFactory(workflows)
    db.transaction { unitOfWork ->
      continuation.continueDecomposedParentByIssueKey("SKILL-TEST", unitOfWork)
    }
    val parentRow = requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wfl-corrupt-parent", RUNTIME))
    assertEquals("paused", parentRow.workflowStatus)

    val parentRows = workflows.decomposedParentRows("SKILL-TEST")
    assertEquals(1, parentRows.size, "Bootstrap must reuse the existing parent, not mint a second one.")
    assertEquals("wfl-corrupt-parent", parentRows.single().workflowId)
    assertNotNull(
      parentRows.single().toSnapshot().decompositionRuntime(),
      "Reclaimed parent must carry a decodable decomposition_runtime artifact.",
    )
  }

  private fun corruptParentContinuation(): Pair<InMemoryWorkflowStates, DecompositionWorkflowContinuation> {
    val repoRoot = Files.createTempDirectory("skillbill-corrupt-artifact-reuse")
    val manifestPath = repoRoot.resolve(".feature-specs/SKILL-TEST-feature/decomposition-manifest.yaml")
    Files.createDirectories(manifestPath.parent)
    val manifest =
      DecompositionManifest(
        issueKey = "SKILL-TEST",
        featureName = "test-feature",
        parentSpecPath = ".feature-specs/SKILL-TEST-feature/spec.md",
        status = "in_progress",
        executionModel = DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
        baseBranch = "main",
        featureBranch = "feat/SKILL-TEST",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "implement"),
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 1,
              name = "first-subtask",
              specPath = ".feature-specs/SKILL-TEST-feature/spec_subtask_1.md",
              status = "pending",
            ),
          ),
      )
    Files.writeString(
      manifestPath,
      encodeDecompositionManifestYaml(manifest, testDecompositionManifestValidator, TestDecompositionManifestStore),
    )
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-corrupt-parent",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "plan" to mapOf("mode" to "decompose"),
              DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label() to "not-a-map",
            ),
          ),
      ).copy(issueKey = "SKILL-TEST"),
      RUNTIME,
    )
    return workflows to
      DecompositionWorkflowContinuation(
        engine = testWorkflowEngine,
        gitOperations = NoopWorkflowGitOperations,
        validator = testDecompositionManifestValidator,
        fileStore = TestDecompositionManifestStore,
        repoRoot = repoRoot,
        manifestWriter = testDecompositionManifestWriter,
        clock = Clock.systemUTC(),
        workflowIdRandom = Random.Default,
      )
  }

  @Test
  fun `continueDecomposedParentByIssueKey is idempotent and does not mint a second parent row`() {
    val repoRoot = Files.createTempDirectory("skillbill-disk-bootstrap-idempotent")
    val manifestPath = repoRoot.resolve(".feature-specs/SKILL-TEST-feature/decomposition-manifest.yaml")
    Files.createDirectories(manifestPath.parent)
    val manifest =
      DecompositionManifest(
        issueKey = "SKILL-TEST",
        featureName = "test-feature",
        parentSpecPath = ".feature-specs/SKILL-TEST-feature/spec.md",
        status = "in_progress",
        executionModel = DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
        baseBranch = "main",
        featureBranch = "feat/SKILL-TEST",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "implement"),
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 1,
              name = "first-subtask",
              specPath = ".feature-specs/SKILL-TEST-feature/spec_subtask_1.md",
              status = "pending",
            ),
          ),
      )
    Files.writeString(
      manifestPath,
      encodeDecompositionManifestYaml(manifest, testDecompositionManifestValidator, TestDecompositionManifestStore),
    )
    val workflows = InMemoryWorkflowStates()
    val db = FakeDatabaseSessionFactory(workflows)
    val continuation =
      DecompositionWorkflowContinuation(
        engine = testWorkflowEngine,
        gitOperations = NoopWorkflowGitOperations,
        validator = testDecompositionManifestValidator,
        fileStore = TestDecompositionManifestStore,
        repoRoot = repoRoot,
        manifestWriter = testDecompositionManifestWriter,
        clock = Clock.systemUTC(),
        workflowIdRandom = Random.Default,
      )

    db.transaction { unitOfWork ->
      continuation.continueDecomposedParentByIssueKey("SKILL-TEST", unitOfWork)
    }
    val parentRowsAfterFirst = workflows.decomposedParentRows("SKILL-TEST")
    assertEquals(1, parentRowsAfterFirst.size)
    val firstParentId = parentRowsAfterFirst.single().workflowId

    db.transaction { unitOfWork ->
      continuation.continueDecomposedParentByIssueKey("SKILL-TEST", unitOfWork)
    }
    val parentRowsAfterSecond = workflows.decomposedParentRows("SKILL-TEST")

    assertEquals(1, parentRowsAfterSecond.size, "Repeat call must not mint a second parent row")
    assertEquals(
      firstParentId,
      parentRowsAfterSecond.single().workflowId,
      "Repeat call must use the same parent workflowId",
    )
  }

  @Test
  fun `continueDecomposedParentByIssueKey is idempotent when starting from a corrupt parent row`() {
    val repoRoot = Files.createTempDirectory("skillbill-corrupt-idempotent")
    val manifestPath = repoRoot.resolve(".feature-specs/SKILL-TEST-feature/decomposition-manifest.yaml")
    Files.createDirectories(manifestPath.parent)
    val manifest =
      DecompositionManifest(
        issueKey = "SKILL-TEST",
        featureName = "test-feature",
        parentSpecPath = ".feature-specs/SKILL-TEST-feature/spec.md",
        status = "in_progress",
        executionModel = DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
        baseBranch = "main",
        featureBranch = "feat/SKILL-TEST",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "implement"),
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 1,
              name = "first-subtask",
              specPath = ".feature-specs/SKILL-TEST-feature/spec_subtask_1.md",
              status = "pending",
            ),
          ),
      )
    Files.writeString(
      manifestPath,
      encodeDecompositionManifestYaml(manifest, testDecompositionManifestValidator, TestDecompositionManifestStore),
    )
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-corrupt-idempotent",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "plan" to mapOf("mode" to "decompose"),
              DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label() to "not-a-map",
            ),
          ),
      ).copy(issueKey = "SKILL-TEST"),
      RUNTIME,
    )
    val db = FakeDatabaseSessionFactory(workflows)
    val continuation =
      DecompositionWorkflowContinuation(
        engine = testWorkflowEngine,
        gitOperations = NoopWorkflowGitOperations,
        validator = testDecompositionManifestValidator,
        fileStore = TestDecompositionManifestStore,
        repoRoot = repoRoot,
        manifestWriter = testDecompositionManifestWriter,
        clock = Clock.systemUTC(),
        workflowIdRandom = Random.Default,
      )

    db.transaction { unitOfWork ->
      continuation.continueDecomposedParentByIssueKey("SKILL-TEST", unitOfWork)
    }
    db.transaction { unitOfWork ->
      continuation.continueDecomposedParentByIssueKey("SKILL-TEST", unitOfWork)
    }
    val parentRows = workflows.decomposedParentRows("SKILL-TEST")
    assertEquals(1, parentRows.size, "Two calls must not mint a second parent row")
    assertEquals("wfl-corrupt-idempotent", parentRows.single().workflowId, "Both calls must reuse the original row")
  }

  @Test
  fun `continueDecomposedParentByIssueKey does not reclaim an explicitly abandoned corrupt parent`() {
    val repoRoot = Files.createTempDirectory("skillbill-abandoned-corrupt")
    val manifestPath = repoRoot.resolve(".feature-specs/SKILL-TEST-feature/decomposition-manifest.yaml")
    Files.createDirectories(manifestPath.parent)
    val manifest =
      DecompositionManifest(
        issueKey = "SKILL-TEST",
        featureName = "test-feature",
        parentSpecPath = ".feature-specs/SKILL-TEST-feature/spec.md",
        status = "in_progress",
        executionModel = DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
        baseBranch = "main",
        featureBranch = "feat/SKILL-TEST",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "implement"),
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 1,
              name = "first-subtask",
              specPath = ".feature-specs/SKILL-TEST-feature/spec_subtask_1.md",
              status = "pending",
            ),
          ),
      )
    Files.writeString(
      manifestPath,
      encodeDecompositionManifestYaml(manifest, testDecompositionManifestValidator, TestDecompositionManifestStore),
    )
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-abandoned-corrupt",
        workflowStatus = WorkflowStatus.ABANDONED,
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "plan" to mapOf("mode" to "decompose"),
              DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label() to "not-a-map",
            ),
          ),
      ).copy(issueKey = "SKILL-TEST"),
      PROSE,
    )
    val db = FakeDatabaseSessionFactory(workflows)
    val continuation =
      DecompositionWorkflowContinuation(
        engine = testWorkflowEngine,
        gitOperations = NoopWorkflowGitOperations,
        validator = testDecompositionManifestValidator,
        fileStore = TestDecompositionManifestStore,
        repoRoot = repoRoot,
        manifestWriter = testDecompositionManifestWriter,
        clock = Clock.systemUTC(),
        workflowIdRandom = Random.Default,
      )

    db.transaction { unitOfWork ->
      continuation.continueDecomposedParentByIssueKey("SKILL-TEST", unitOfWork)
    }
    val abandonedRow = requireNotNull(workflows.getFeatureTaskWorkflow("wfl-abandoned-corrupt"))
    assertEquals("abandoned", abandonedRow.workflowStatus, "Operator-abandoned parent must stay abandoned")
  }

  @Test
  fun `continueDecomposedParentByIssueKey returns UnknownWorkflow when disk has no manifest for issue key`() {
    val repoRoot = Files.createTempDirectory("skillbill-disk-bootstrap-miss")
    val workflows = InMemoryWorkflowStates()
    val db = FakeDatabaseSessionFactory(workflows)
    val continuation =
      DecompositionWorkflowContinuation(
        engine = testWorkflowEngine,
        gitOperations = NoopWorkflowGitOperations,
        validator = testDecompositionManifestValidator,
        fileStore = TestDecompositionManifestStore,
        repoRoot = repoRoot,
        manifestWriter = testDecompositionManifestWriter,
        clock = Clock.systemUTC(),
        workflowIdRandom = Random.Default,
      )

    val result =
      db.transaction { unitOfWork ->
        continuation.continueDecomposedParentByIssueKey("SKILL-MISSING", unitOfWork)
      }

    assertIs<WorkflowContinueResult.UnknownWorkflow>(result.result)
  }
}
