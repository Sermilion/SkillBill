package skillbill.engine.goalrunner.reset

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.storeAwareTestDecompositionManifestStore
import skillbill.application.testDecompositionManifestValidator
import skillbill.engine.decomposition.encodeDecompositionManifestYaml
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestPorts
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerExportRequest
import skillbill.engine.goalrunner.model.GoalRunnerExportResult
import skillbill.engine.goalrunner.model.GoalRunnerImportRequest
import skillbill.engine.goalrunner.model.GoalRunnerImportResult
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class GoalRunnerImportCoordinatorTest {
  @Test
  fun `exported archive restores manifest parent and subtasks at recorded paths`() {
    val fixture = importFixture(bundleName = "specs")
    val archive = fixture.root.resolve("archive.zip")
    val exportService =
      testGoalRunnerStatusService(
        manifestStore = fixture.store,
        outcomeStore = RecordingOutcomeStore(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = BranchHeadGit("feat/SKILL-245-goal", "abc123"),
            projectStore = fixture.port,
            workerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
          ),
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(fixture.port),
      )
    assertIs<GoalRunnerExportResult.Exported>(
      exportService.export(GoalRunnerExportRequest("SKILL-245", fixture.root, archive)),
    )

    val destinationRoot = Files.createTempDirectory("goal-import-roundtrip-dest")
    val destinationPort = FixedProjectStorePort(destinationRoot)
    val importService =
      testGoalRunnerStatusService(
        manifestStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1).copy(issueKey = "SKILL-OTHER")),
        outcomeStore = RecordingOutcomeStore(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = BranchHeadGit("feat/SKILL-245-goal", "abc123"),
            projectStore = destinationPort,
            workerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
          ),
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(destinationPort),
      )

    val result = importService.import(GoalRunnerImportRequest(archive, destinationRoot))

    assertIs<GoalRunnerImportResult.Imported>(result)
    assertContains(
      Files.readString(destinationPort.root.resolve("specs/specs/decomposition-manifest.yaml")),
      "specs/specs/spec.md",
    )
    assertEquals("body of spec.md", Files.readString(destinationPort.root.resolve("specs/specs/spec.md")))
    assertEquals(
      "body of spec_subtask_1.md",
      Files.readString(destinationPort.root.resolve("specs/specs/spec_subtask_1.md")),
    )
    assertEquals(
      "body of spec_subtask_2.md",
      Files.readString(destinationPort.root.resolve("specs/specs/spec_subtask_2.md")),
    )
    assertEquals(
      "body of traceability.md",
      Files.readString(destinationPort.root.resolve("specs/specs/traceability.md")),
    )
  }

  @Test
  fun `wrong branch refuses import without workflow or spec writes`() {
    val fixture = importFixture()
    val archive = fixture.root.resolve("archive.zip")
    val exportService =
      testGoalRunnerStatusService(
        manifestStore = fixture.store,
        outcomeStore = RecordingOutcomeStore(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = BranchHeadGit("feat/SKILL-245-goal", "abc123"),
            projectStore = fixture.port,
            workerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
          ),
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(fixture.port),
      )
    assertIs<GoalRunnerExportResult.Exported>(
      exportService.export(GoalRunnerExportRequest("SKILL-245", fixture.root, archive)),
    )
    val destRoot = Files.createTempDirectory("goal-import-dest")
    val destPort = FixedProjectStorePort(destRoot)
    val destStates = InMemoryWorkflowStates()
    val importService =
      testGoalRunnerStatusService(
        manifestStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1).copy(issueKey = "SKILL-OTHER")),
        outcomeStore = RecordingOutcomeStore(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = BranchHeadGit("main", "abc123"),
            projectStore = destPort,
            workerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
          ),
        database = FakeDatabaseSessionFactory(destStates),
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(destPort),
      )
    val changed = importService.import(GoalRunnerImportRequest(archive, destRoot, "different fingerprint"))
    assertIs<GoalRunnerImportResult.Refused>(changed)
    assertContains(changed.reason, "changed after inspection")
    assertFalse(Files.exists(destPort.root.resolve("specs/SKILL-245-goal/spec.md")))
    assertNull(destStates.getFeatureTaskWorkflow("wfl-parent"))
    val result = importService.import(GoalRunnerImportRequest(archive, destRoot))
    assertIs<GoalRunnerImportResult.Refused>(result)
    assertContains(result.reason, "feat/SKILL-245-goal")
    assertContains(result.reason, "abc123")
    assertFalse(Files.exists(destPort.root.resolve("specs/SKILL-245-goal/spec.md")))
    assertNull(destStates.getFeatureTaskWorkflow("wfl-parent"))
  }
}

private data class ImportFixture(
  val root: Path,
  val port: FixedProjectStorePort,
  val store: InMemoryGoalManifestStore,
)

private fun importFixture(bundleName: String = "SKILL-245-goal"): ImportFixture {
  val root = Files.createTempDirectory("goal-import-root")
  val storeRoot = Files.createTempDirectory("goal-import-store")
  val bundle = storeRoot.resolve("specs/$bundleName")
  Files.createDirectories(bundle)
  listOf("spec.md", "spec_subtask_1.md", "spec_subtask_2.md", "traceability.md").forEach { name ->
    Files.writeString(bundle.resolve(name), "body of $name")
  }
  val sourceManifest =
    manifest(subtaskCount = 2).copy(
      issueKey = "SKILL-245",
      featureBranch = "feat/SKILL-245-goal",
      parentSpecPath = "specs/$bundleName/spec.md",
      subtasks =
        manifest(2).subtasks.mapIndexed { index, subtask ->
          subtask.copy(
            specPath = "specs/$bundleName/spec_subtask_${index + 1}.md",
            branch = "feat/SKILL-245-goal",
          )
        },
    )
  val port = FixedProjectStorePort(storeRoot)
  val manifestPath = bundle.resolve("decomposition-manifest.yaml")
  Files.writeString(
    manifestPath,
    encodeDecompositionManifestYaml(
      sourceManifest,
      testDecompositionManifestValidator,
      storeAwareTestDecompositionManifestStore(port),
      manifestPath.toString(),
    ),
  )
  return ImportFixture(root, port, InMemoryGoalManifestStore(sourceManifest))
}

private class BranchHeadGit(
  private val branch: String,
  private val headSha: String,
) : WorkflowGitOperations by NoopWorkflowGitOperations {
  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = branch)

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = headSha)
}
