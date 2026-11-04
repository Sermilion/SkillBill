package skillbill.engine.goalrunner.archive

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.storeAwareTestDecompositionManifestStore
import skillbill.application.testDecompositionManifestValidator
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.goal.GoalArchiveEnvelopeKeys
import skillbill.contracts.workflow.goal.GoalArchiveRecordKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.engine.decomposition.encodeDecompositionManifestYaml
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestPorts
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalPurgeOwnership
import skillbill.engine.goalrunner.model.GoalRunnerExportRequest
import skillbill.engine.goalrunner.model.GoalRunnerExportResult
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.goalrunner.model.GoalArchiveOwnedSnapshot
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoalRunnerArchiveCoordinatorTest {
  @Test
  fun `live parent execution lease refuses export without writing output`() {
    val goalManifest = manifest(subtaskCount = 1).copy(issueKey = "SKILL-245")
    val store =
      InMemoryGoalManifestStore(goalManifest).apply {
        executionLeaseForTest =
          GoalRunnerExecutionLease(
            generation = 1,
            ownerToken = "parent-owner",
            hostIdentity = "host",
            bootIdentity = "boot",
            pid = 42,
            processBirthToken = "birth-42",
            heartbeatAt = "2026-01-01T00:00:00Z",
            expiresAt = "2999-01-01T00:00:00Z",
          )
      }
    val guardedStore =
      object : GoalRunnerManifestStore by store {
        override fun discoverPurgeOwnership(
          issueKey: String,
          repoRoot: Path,
        ): GoalPurgeOwnership {
          val state = checkNotNull(store.loadDurableByIssueKey(issueKey)).copy(parentWorkflowId = "wf-parent")
          return GoalPurgeOwnership(listOf(state), setOf("wf-parent"))
        }
      }
    val output = Files.createTempDirectory("goal-export-live").resolve("out.zip")
    val service =
      exportService(
        manifestStore = guardedStore,
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = BranchHeadGitOperations("feat/SKILL-245-goal", "abc123"),
            workerSupervisor = LiveInspectWorkerSupervisor,
          ),
      )
    val root = Files.createTempDirectory("goal-export-live-root")

    val result = service.export(GoalRunnerExportRequest("SKILL-245", root, output))

    assertIs<GoalRunnerExportResult.Refused>(result)
    assertContains(result.reason, "GoalPauseCommand")
    assertContains(result.reason, "GoalStopCommand")
    assertFalse(Files.exists(output))
  }

  @Test
  fun `wrong branch refuses export without writing output`() {
    val fixture = storeBundleFixture()
    val output = fixture.root.resolve("out.zip")
    val service =
      exportService(
        manifestStore = fixture.store,
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = BranchHeadGitOperations("main", "abc123"),
            projectStore = fixture.port,
            workerSupervisor = IdleWorkerSupervisor,
          ),
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(fixture.port),
      )

    val result = service.export(GoalRunnerExportRequest("SKILL-245", fixture.root, output))

    assertIs<GoalRunnerExportResult.Refused>(result)
    assertContains(result.reason, "feat/SKILL-245-goal")
    assertFalse(Files.exists(output))
  }

  @Test
  fun `unclassified workflows refuse export without writing output`() {
    val store = InMemoryGoalManifestStore(manifest(subtaskCount = 1).copy(issueKey = "SKILL-245"))
    val guardedStore =
      object : GoalRunnerManifestStore by store {
        override fun discoverPurgeOwnership(
          issueKey: String,
          repoRoot: Path,
        ): GoalPurgeOwnership =
          GoalPurgeOwnership(
            manifests = emptyList(),
            parentWorkflowIds = setOf("wf-parent"),
            unclassifiedWorkflows = listOf("workflow wf-orphan matches SKILL-245 but its state cannot be decoded"),
          )
      }
    val output = Files.createTempDirectory("goal-export-unclassified").resolve("out.zip")
    val service =
      exportService(
        manifestStore = guardedStore,
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = BranchHeadGitOperations("feat/SKILL-56-goal", "abc123"),
            workerSupervisor = IdleWorkerSupervisor,
          ),
      )
    val root = Files.createTempDirectory("goal-export-unclassified-root")

    val result = service.export(GoalRunnerExportRequest("SKILL-245", root, output))

    assertIs<GoalRunnerExportResult.Refused>(result)
    assertContains(result.reason, "undecodable")
    assertFalse(Files.exists(output))
  }

  @Test
  fun `exported zip retains plan workflow step and typed plan bundle fields`() {
    val fixture = storeBundleFixture()
    val planWorkflow =
      WorkflowStateRecord(
        workflowId = "wf-plan",
        sessionId = "session-plan",
        workflowName = "bill-feature-task",
        contractVersion = "0.1",
        workflowStatus = "in_progress",
        currentStepId = "plan_subtasks",
        stepsJson = "[]",
        artifactsJson = "{}",
        startedAt = null,
        updatedAt = null,
        finishedAt = null,
        mode = FeatureTaskWorkflowMode.RUNTIME,
        typedArtifacts =
          DurableWorkflowArtifacts.fromMap(
            mapOf(
              DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.label() to
                mapOf("contract_version" to "1.0", "subtasks" to emptyList<Any>()),
            ),
          ),
      )
    val archivePort =
      RecordingGoalArchiveSnapshotPort {
        GoalArchiveOwnedSnapshot(listOf(planWorkflow), emptyList(), emptyList(), emptyList())
      }
    val output = fixture.root.resolve("archive.zip")
    val service =
      exportService(
        manifestStore = fixture.store,
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = BranchHeadGitOperations("feat/SKILL-245-goal", "deadbeef"),
            projectStore = fixture.port,
            workerSupervisor = IdleWorkerSupervisor,
            archiveSnapshotPort = archivePort,
          ),
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(fixture.port),
      )

    val result = service.export(GoalRunnerExportRequest("SKILL-245", fixture.root, output))

    assertIs<GoalRunnerExportResult.Exported>(result)
    assertTrue(Files.exists(output))
    val workflows = readZipJsonList(output, GoalArchiveEnvelopeKeys.WORKFLOWS_JSON)
    assertEquals(1, workflows.size)
    val row = workflows.first()
    assertEquals("plan_subtasks", row[WorkflowWirePayloadKeys.CURRENT_STEP_ID])
    val typed = JsonCodec.anyToStringAnyMap(row[GoalArchiveRecordKeys.TYPED_ARTIFACTS])!!
    assertTrue(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.label() in typed)
    val specEntries = readZipEntryNames(output).filter { it.startsWith(GoalArchiveEnvelopeKeys.SPECS_PREFIX) }.toSet()
    assertEquals(
      setOf(
        "specs/SKILL-245-goal/decomposition-manifest.yaml",
        "specs/SKILL-245-goal/spec.md",
        "specs/SKILL-245-goal/spec_subtask_1.md",
        "specs/SKILL-245-goal/spec_subtask_2.md",
      ),
      specEntries,
    )
  }

  @Test
  fun `legacy checkout specs refuse export with migration guidance`() {
    val root = Files.createTempDirectory("goal-export-legacy-root")
    val storeRoot = Files.createTempDirectory("goal-export-legacy-store")
    val bundle = root.resolve(".feature-specs/SKILL-245-goal")
    Files.createDirectories(bundle)
    listOf("spec.md", "spec_subtask_1.md", "spec_subtask_2.md").forEach { name ->
      Files.writeString(bundle.resolve(name), "body of $name")
    }
    val sourceManifest =
      manifest(subtaskCount = 2).copy(
        issueKey = "SKILL-245",
        featureBranch = "feat/SKILL-245-goal",
        parentSpecPath = ".feature-specs/SKILL-245-goal/spec.md",
        subtasks =
          manifest(2).subtasks.mapIndexed { index, subtask ->
            subtask.copy(
              specPath = ".feature-specs/SKILL-245-goal/spec_subtask_${index + 1}.md",
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
    val output = root.resolve("archive.zip")
    val service =
      exportService(
        manifestStore = InMemoryGoalManifestStore(sourceManifest),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = BranchHeadGitOperations("feat/SKILL-245-goal", "abc123"),
            projectStore = port,
            workerSupervisor = IdleWorkerSupervisor,
          ),
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(port),
      )

    val result = service.export(GoalRunnerExportRequest("SKILL-245", root, output))

    assertIs<GoalRunnerExportResult.Refused>(result)
    assertContains(result.reason, ".feature-specs/SKILL-245-goal")
    assertContains(result.reason, "Move the bundle under specs/<bundle>/")
    assertFalse(Files.exists(output))
  }

  private fun readZipEntryNames(zipPath: Path): List<String> {
    ZipInputStream(Files.newInputStream(zipPath)).use { zip ->
      val names = mutableListOf<String>()
      while (true) {
        val entry = zip.nextEntry ?: break
        names += entry.name
        zip.closeEntry()
      }
      return names
    }
  }

  private fun readZipJsonList(
    zipPath: Path,
    entryName: String,
  ): List<Map<String, Any?>> {
    ZipInputStream(Files.newInputStream(zipPath)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.name == entryName) {
          return checkNotNull(JsonCodec.anyToStringAnyMapList(JsonCodec.parseValue(zip.readBytes().decodeToString())))
        }
        zip.closeEntry()
      }
    }
    error("Missing zip entry $entryName")
  }

  private fun exportService(
    manifestStore: GoalRunnerManifestStore,
    ports: GoalRunnerStatusTestPorts = GoalRunnerStatusTestPorts(workerSupervisor = IdleWorkerSupervisor),
    decompositionManifestStore: DecompositionManifestStore =
      storeAwareTestDecompositionManifestStore(
        ports.projectStore as? FixedProjectStorePort
          ?: FixedProjectStorePort(Files.createTempDirectory("goal-export-store")),
      ),
  ) = testGoalRunnerStatusService(
    manifestStore = manifestStore,
    outcomeStore = RecordingOutcomeStore(),
    ports = ports,
    database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
    decompositionManifestStore = decompositionManifestStore,
  )

  private fun storeBundleFixture(): ExportStoreBundleFixture {
    val root = Files.createTempDirectory("goal-export-store-root")
    val storeRoot = Files.createTempDirectory("goal-export-store-bundle")
    val bundle = storeRoot.resolve("specs/SKILL-245-goal")
    Files.createDirectories(bundle)
    listOf("spec.md", "spec_subtask_1.md", "spec_subtask_2.md", "traceability.md").forEach { name ->
      Files.writeString(bundle.resolve(name), "body of $name")
    }
    val sourceManifest =
      manifest(subtaskCount = 2).copy(
        issueKey = "SKILL-245",
        featureBranch = "feat/SKILL-245-goal",
        parentSpecPath = "specs/SKILL-245-goal/spec.md",
        subtasks =
          manifest(2).subtasks.mapIndexed { index, subtask ->
            subtask.copy(
              specPath = "specs/SKILL-245-goal/spec_subtask_${index + 1}.md",
              branch = "feat/SKILL-245-goal",
            )
          },
      )
    val manifestPath = bundle.resolve("decomposition-manifest.yaml")
    Files.writeString(
      manifestPath,
      encodeDecompositionManifestYaml(
        sourceManifest,
        testDecompositionManifestValidator,
        storeAwareTestDecompositionManifestStore(FixedProjectStorePort(storeRoot)),
        manifestPath.toString(),
      ),
    )
    return ExportStoreBundleFixture(
      root,
      FixedProjectStorePort(storeRoot),
      InMemoryGoalManifestStore(sourceManifest),
    )
  }
}

private data class ExportStoreBundleFixture(
  val root: Path,
  val port: FixedProjectStorePort,
  val store: InMemoryGoalManifestStore,
)

private class BranchHeadGitOperations(
  private val branch: String,
  private val headSha: String,
) : WorkflowGitOperations by NoopWorkflowGitOperations {
  override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok(value = branch)

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = headSha)
}

private object LiveInspectWorkerSupervisor :
  FeatureTaskRuntimeWorkerSupervisor by NoopFeatureTaskRuntimeWorkerSupervisor {
  override fun currentProcess(): FeatureTaskRuntimeProcessIdentity =
    FeatureTaskRuntimeProcessIdentity("host", "boot", 42, "birth")

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = FeatureTaskRuntimeProcessInspection.ExactLive
}

private object IdleWorkerSupervisor : FeatureTaskRuntimeWorkerSupervisor by NoopFeatureTaskRuntimeWorkerSupervisor {
  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = FeatureTaskRuntimeProcessInspection.NotRunning
}
