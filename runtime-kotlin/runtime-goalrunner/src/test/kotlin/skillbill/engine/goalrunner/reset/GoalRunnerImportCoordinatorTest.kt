package skillbill.engine.goalrunner.reset

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.storeAwareTestDecompositionManifestStore
import skillbill.application.testDecompositionManifestValidator
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GOAL_PLANNING_PREPARATION_CONTRACT_VERSION
import skillbill.engine.decomposition.encodeDecompositionManifestYaml
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.archive.RecordingGoalArchiveSnapshotPort
import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestPorts
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.goalRepositoryIdentity
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalPurgeOwnership
import skillbill.engine.goalrunner.model.GoalRunnerExportRequest
import skillbill.engine.goalrunner.model.GoalRunnerExportResult
import skillbill.engine.goalrunner.model.GoalRunnerImportRequest
import skillbill.engine.goalrunner.model.GoalRunnerImportResult
import skillbill.engine.goalrunner.persist.seedCurrentParent
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_IMPORTED
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.targetReached
import skillbill.infrastructure.sqlite.ensureTestDatabase
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.infrastructure.sqlite.withGoalRunnerControlRepository
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalPlanningPreparationRepositoryDefaults
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.goalrunner.model.GoalArchiveOwnedSnapshot
import skillbill.ports.goalrunner.model.GoalArchiveOwnedTables
import skillbill.ports.goalrunner.model.GoalArchiveTableRow
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanLookupResult
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.text.sha256HexUtf8
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalRunnerImportCoordinatorTest {
  @Test
  fun `archive import preserves control choices while clearing execution ownership`() {
    val sourceRoot = Files.createTempDirectory("goal-import-control-source")
    val sourceDb = sourceRoot.resolve("metrics.db")
    val parentId = "wfl-parent"
    val reviewPolicy = GoalRunnerReviewPolicy(CodeReviewExecutionMode.DELEGATED)
    val acceptance = testAcceptance()
    val archivedControlRow = seedArchivedControls(sourceDb, parentId, reviewPolicy, acceptance)
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
            archiveSnapshotPort = snapshotWithControls(archivedControlRow),
            workerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
          ),
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(fixture.port),
      )
    assertIs<GoalRunnerExportResult.Exported>(
      exportService.export(GoalRunnerExportRequest("SKILL-245", fixture.root, archive)),
    )

    val destinationRoot = Files.createTempDirectory("goal-import-control-destination")
    val destinationPort = FixedProjectStorePort(destinationRoot)
    val destinationDb = destinationRoot.resolve("metrics.db")
    ensureTestDatabase(destinationDb).close()
    val database =
      sqliteDatabaseSessionFactory(
        userHome = destinationRoot,
        dbPathOverride = destinationDb.toString(),
        environment = mapOf("SKILL_BILL_TEST" to "1"),
      )
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
        database = database,
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(destinationPort),
      )

    val result = importService.import(GoalRunnerImportRequest(archive, destinationRoot))
    assertIs<GoalRunnerImportResult.Imported>(result, result.toString())

    database.read { unit ->
      val controls = unit.goalRunnerControls
      val imported = controls.controlState(parentId)
      assertEquals(FeatureTaskRuntimeQaSelection.OFF, imported.qaSelection)
      assertEquals(2, imported.stopAfterSubtaskId)
      assertEquals(mapOf(1 to 3, 2 to 1), imported.validationQualityRetriesBySubtask)
      assertFalse(imported.stopAfterConsumed)
      assertTrue(imported.targetReached(completedManifest()))
      assertTrue(imported.paused)
      assertEquals(GOAL_PAUSE_REASON_IMPORTED, imported.pauseReason)
      assertNull(imported.executionLease)
      assertNull(imported.executionWorktreePath)
      assertEquals(reviewPolicy, controls.reviewPolicy(parentId))
      assertEquals(mapOf(2 to acceptance), controls.outOfBandAcceptances(parentId))
    }
  }

  private fun completedManifest() =
    manifest(subtaskCount = 2).copy(
      subtasks = manifest(2).subtasks.map { it.copy(status = DecompositionStatus.COMPLETE.wireValue) },
    )

  private fun snapshotWithControls(row: GoalArchiveTableRow): RecordingGoalArchiveSnapshotPort {
    val snapshot = parentSnapshot()
    return RecordingGoalArchiveSnapshotPort { target ->
      snapshot.snapshot(target).copy(
        ownedTables = GoalArchiveOwnedTables.of(mapOf("goal_runner_controls" to listOf(row))),
      )
    }
  }

  private fun testAcceptance() =
    GoalRunnerOutOfBandAcceptance(
      subtaskId = 2,
      commitSha = "abc123",
      reason = "accepted outside the runner",
      acceptedAt = "2026-08-01T10:00:00Z",
    )

  private fun seedArchivedControls(
    sourceDb: Path,
    parentId: String,
    reviewPolicy: GoalRunnerReviewPolicy,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalArchiveTableRow {
    withGoalRunnerControlRepository(sourceDb) { controls ->
      controls.persistControlState(
        parentId,
        GoalRunnerControlState(
          qaSelection = FeatureTaskRuntimeQaSelection.OFF,
          stopAfterSubtaskId = 2,
          validationQualityRetriesBySubtask = mapOf(1 to 3, 2 to 1),
          executionLease =
            GoalRunnerExecutionLease(
              generation = 1,
              ownerToken = "source-owner",
              hostIdentity = "source-host",
              bootIdentity = "source-boot",
              pid = 123,
              processBirthToken = "source-process",
              heartbeatAt = Instant.parse("2026-08-01T10:00:00Z"),
              expiresAt = Instant.parse("2026-08-01T10:01:00Z"),
            ),
          executionWorktreePath = "/source/worktree",
        ),
      )
      controls.persistReviewPolicy(parentId, reviewPolicy)
      controls.persistOutOfBandAcceptance(parentId, acceptance)
    }
    return ensureTestDatabase(sourceDb).use { connection ->
      connection.prepareStatement(
        "SELECT parent_workflow_id, control_state_json, review_policy_json, out_of_band_acceptances_json " +
          "FROM goal_runner_controls WHERE parent_workflow_id = ?",
      ).use { statement ->
        statement.setString(1, parentId)
        statement.executeQuery().use { rows ->
          check(rows.next())
          val columns = linkedMapOf<String, Any?>()
          for (index in 1..rows.metaData.columnCount) {
            columns[rows.metaData.getColumnName(index)] = rows.getObject(index)
          }
          GoalArchiveTableRow(columns)
        }
      }
    }
  }

  @Test
  fun `legacy checkout bundle exports and imports into destination project specs`() {
    val archive = exportLegacyBundle()
    val destinationRoot = Files.createTempDirectory("goal-import-legacy-destination")
    val destinationPort = FixedProjectStorePort(destinationRoot)
    val states = InMemoryWorkflowStates()
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
        database = FakeDatabaseSessionFactory(states, goalRunnerControls = RecordingImportControls()),
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(destinationPort),
      )

    assertIs<GoalRunnerImportResult.Imported>(importService.import(GoalRunnerImportRequest(archive, destinationRoot)))

    val destinationBundle = destinationPort.root.resolve("specs/SKILL-245-goal")
    val restoredManifest = Files.readString(destinationBundle.resolve("decomposition-manifest.yaml"))
    assertContains(restoredManifest, "specs/SKILL-245-goal/spec.md")
    assertFalse(restoredManifest.contains(".feature-specs"))
    assertEquals("body of spec.md", Files.readString(destinationBundle.resolve("spec.md")))
    assertEquals("body of traceability.md", Files.readString(destinationBundle.resolve("traceability.md")))
  }

  private fun exportLegacyBundle(): Path {
    val sourceRoot = Files.createTempDirectory("goal-import-legacy-source")
    val sourceStore = FixedProjectStorePort(Files.createTempDirectory("goal-import-legacy-source-store"))
    val sourceBundle = sourceRoot.resolve(".feature-specs/SKILL-245-goal")
    Files.createDirectories(sourceBundle)
    listOf("spec.md", "spec_subtask_1.md", "spec_subtask_2.md", "traceability.md").forEach { name ->
      Files.writeString(sourceBundle.resolve(name), "body of $name")
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
    val manifestPath = sourceBundle.resolve("decomposition-manifest.yaml")
    Files.writeString(
      manifestPath,
      encodeDecompositionManifestYaml(
        sourceManifest,
        testDecompositionManifestValidator,
        storeAwareTestDecompositionManifestStore(sourceStore),
        manifestPath.toString(),
      ),
    )
    val archive = sourceRoot.resolve("archive.zip")
    val exportService =
      testGoalRunnerStatusService(
        manifestStore = InMemoryGoalManifestStore(sourceManifest),
        outcomeStore = RecordingOutcomeStore(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = BranchHeadGit("feat/SKILL-245-goal", "abc123"),
            projectStore = sourceStore,
            archiveSnapshotPort = parentSnapshot(),
            workerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
          ),
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(sourceStore),
      )
    assertIs<GoalRunnerExportResult.Exported>(
      exportService.export(GoalRunnerExportRequest("SKILL-245", sourceRoot, archive)),
    )
    return archive
  }

  @Test
  fun `unmappable absolute archive reference refuses before restoring rows or specs`() {
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
            archiveSnapshotPort = parentSnapshot(),
            workerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
          ),
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(fixture.port),
      )
    assertIs<GoalRunnerExportResult.Exported>(
      exportService.export(GoalRunnerExportRequest("SKILL-245", fixture.root, archive)),
    )
    rewriteArchiveEntry(archive, "specs/specs/decomposition-manifest.yaml") { content ->
      content.replace("specs/specs/spec.md", "/outside/specs/missing.md")
    }
    val destinationRoot = Files.createTempDirectory("goal-import-unmappable-dest")
    val destinationPort = FixedProjectStorePort(destinationRoot)
    val states = InMemoryWorkflowStates()
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
        database = FakeDatabaseSessionFactory(states),
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(destinationPort),
      )

    assertIs<GoalRunnerImportResult.Refused>(importService.import(GoalRunnerImportRequest(archive, destinationRoot)))
    assertNull(states.getFeatureTaskWorkflow("wfl-parent"))
    assertFalse(Files.exists(destinationPort.root.resolve("specs/specs/decomposition-manifest.yaml")))
  }

  @Test
  fun `exported archive restores manifest parent and subtasks at recorded paths`() {
    val fixture = importFixture(bundleName = "specs", absolutePaths = true)
    val sourcePreplan = archivedPreplan()
    val archive = fixture.root.resolve("archive.zip")
    val exportService =
      testGoalRunnerStatusService(
        manifestStore = fixture.store,
        outcomeStore = RecordingOutcomeStore(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = BranchHeadGit("feat/SKILL-245-goal", "abc123"),
            projectStore = fixture.port,
            archiveSnapshotPort = parentSnapshot(listOf(sourcePreplan)),
            workerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
          ),
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(fixture.port),
      )
    assertIs<GoalRunnerExportResult.Exported>(
      exportService.export(GoalRunnerExportRequest("SKILL-245", fixture.root, archive)),
    )

    val destinationRoot = Files.createTempDirectory("goal-import-roundtrip-dest")
    val destinationPort = FixedProjectStorePort(destinationRoot)
    val destStates = InMemoryWorkflowStates()
    val planningPreparations = RecordingImportPlanningPreparations()
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
        database =
          FakeDatabaseSessionFactory(
            destStates,
            planningPreparations = planningPreparations,
            goalRunnerControls = RecordingImportControls(),
          ),
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(destinationPort),
      )

    val result = importService.import(GoalRunnerImportRequest(archive, destinationRoot))

    assertIs<GoalRunnerImportResult.Imported>(result)
    val destinationIdentity = GoalPlanningIdentity("wfl-parent", "SKILL-245", goalRepositoryIdentity(destinationRoot))
    assertFalse(sourcePreplan.identity.repositoryIdentity == destinationIdentity.repositoryIdentity)
    val restoredPreplan =
      assertIs<SharedGoalPreplanLookupResult.Found>(planningPreparations.findSharedPreplan(destinationIdentity))
        .checkpoint
    assertEquals(destinationIdentity, restoredPreplan?.identity)
    assertEquals(sourcePreplan.preplanPayload, restoredPreplan?.preplanPayload)
    assertEquals(sourcePreplan.payloadSha256, restoredPreplan?.payloadSha256)
    assertEquals(sourcePreplan.preparationStatus, restoredPreplan?.preparationStatus)
    assertEquals(sourcePreplan.provenance, restoredPreplan?.provenance)
    assertEquals(sourcePreplan.createdAt, restoredPreplan?.createdAt)
    assertEquals(sourcePreplan.contractVersion, restoredPreplan?.contractVersion)
    assertRestoredSpecFiles(destinationPort.root, fixture.port.root)
    val importedWorkflow = requireNotNull(destStates.getFeatureTaskWorkflow("wfl-parent"))
    destStates.saveFeatureTaskWorkflow(importedWorkflow.copy(updatedAt = "later"), FeatureTaskWorkflowMode.RUNTIME)
    val progressedWorkflow = requireNotNull(destStates.getFeatureTaskWorkflow("wfl-parent"))
    val reimport = importService.import(GoalRunnerImportRequest(archive, destinationRoot))
    assertIs<GoalRunnerImportResult.Refused>(reimport)
    assertEquals(progressedWorkflow, destStates.getFeatureTaskWorkflow("wfl-parent"))
    assertEquals("body of spec.md", Files.readString(destinationPort.root.resolve("specs/specs/spec.md")))
  }

  private fun assertRestoredSpecFiles(
    destinationRoot: Path,
    sourceRoot: Path,
  ) {
    assertContains(
      Files.readString(destinationRoot.resolve("specs/specs/decomposition-manifest.yaml")),
      "specs/specs/spec.md",
    )
    assertFalse(
      Files.readString(destinationRoot.resolve("specs/specs/decomposition-manifest.yaml"))
        .contains(sourceRoot.toString()),
    )
    assertEquals("body of spec.md", Files.readString(destinationRoot.resolve("specs/specs/spec.md")))
    assertEquals(
      "body of spec_subtask_1.md",
      Files.readString(destinationRoot.resolve("specs/specs/spec_subtask_1.md")),
    )
    assertEquals(
      "body of spec_subtask_2.md",
      Files.readString(destinationRoot.resolve("specs/specs/spec_subtask_2.md")),
    )
    assertEquals(
      "body of traceability.md",
      Files.readString(destinationRoot.resolve("specs/specs/traceability.md")),
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
            archiveSnapshotPort = parentSnapshot(),
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

  @Test
  fun `different destination parent refuses import without writes`() {
    val fixture = importFixture(bundleName = "specs")
    val archive = exportImportArchive(fixture)
    val destinationRoot = Files.createTempDirectory("goal-import-different-parent-dest")
    val destinationPort = FixedProjectStorePort(destinationRoot)
    val destinationManifestStore =
      InMemoryGoalManifestStore(manifest(subtaskCount = 1).copy(issueKey = "SKILL-245"))
    val destinationParent =
      checkNotNull(destinationManifestStore.loadDurableByIssueKey("SKILL-245"))
        .copy(parentWorkflowId = "destination-parent")
    val destinationOwnership = GoalPurgeOwnership(listOf(destinationParent), setOf("destination-parent"))
    val guardedManifestStore =
      object : GoalRunnerManifestStore by destinationManifestStore {
        override fun discoverPurgeOwnership(
          issueKey: String,
          repoRoot: Path,
        ): GoalPurgeOwnership = destinationOwnership
      }
    val destinationStates = InMemoryWorkflowStates()
    val controls = RecordingImportControls()
    val importService =
      testGoalRunnerStatusService(
        manifestStore = guardedManifestStore,
        outcomeStore = RecordingOutcomeStore(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = BranchHeadGit("feat/SKILL-245-goal", "abc123"),
            projectStore = destinationPort,
          ),
        database = FakeDatabaseSessionFactory(destinationStates, goalRunnerControls = controls),
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(destinationPort),
      )

    val result = importService.import(GoalRunnerImportRequest(archive, destinationRoot))

    assertIs<GoalRunnerImportResult.Refused>(result)
    assertContains(result.reason, "already has parent workflow ids")
    assertContains(result.reason, "destination-parent")
    assertContains(result.reason, "wfl-parent")
    assertEquals(destinationOwnership, guardedManifestStore.discoverPurgeOwnership("SKILL-245", destinationRoot))
    assertNull(destinationStates.getFeatureTaskWorkflow("wfl-parent"))
    assertEquals(emptyList(), controls.persistedWrites)
    listOf(
      "spec.md",
      "spec_subtask_1.md",
      "spec_subtask_2.md",
      "traceability.md",
      "decomposition-manifest.yaml",
    ).forEach { name -> assertFalse(Files.exists(destinationPort.root.resolve("specs/specs/$name"))) }
  }

  @Test
  fun `failed spec write retries without restoring workflow rows again`() {
    val fixture = importFixture(bundleName = "specs")
    val archive = fixture.root.resolve("archive.zip")
    val sourceDb = Files.createTempDirectory("goal-import-retry-controls-source").resolve("metrics.db")
    val archivedControlRow =
      seedArchivedControls(
        sourceDb,
        "wfl-parent",
        GoalRunnerReviewPolicy(CodeReviewExecutionMode.DELEGATED),
        testAcceptance(),
      )
    val exportService =
      testGoalRunnerStatusService(
        manifestStore = fixture.store,
        outcomeStore = RecordingOutcomeStore(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = BranchHeadGit("feat/SKILL-245-goal", "abc123"),
            projectStore = fixture.port,
            archiveSnapshotPort =
              RecordingGoalArchiveSnapshotPort { target ->
                parentSnapshot().snapshot(target).copy(
                  ownedTables = GoalArchiveOwnedTables.of(mapOf("goal_runner_controls" to listOf(archivedControlRow))),
                )
              },
          ),
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(fixture.port),
      )
    assertIs<GoalRunnerExportResult.Exported>(
      exportService.export(GoalRunnerExportRequest("SKILL-245", fixture.root, archive)),
    )

    val destinationRoot = Files.createTempDirectory("goal-import-retry-dest")
    val destinationPort = FixedProjectStorePort(destinationRoot)
    val destinationStates = InMemoryWorkflowStates()
    val controls = RecordingImportControls()
    val database = retryDatabase(sourceDb, destinationStates, controls, archivedControlRow)
    val delegate = storeAwareTestDecompositionManifestStore(destinationPort)
    var failWrite = true
    val failingStore =
      object : DecompositionManifestStore by delegate {
        override fun <T> writeBundleAtomically(
          writes: List<Pair<Path, String>>,
          verify: () -> T,
        ): T {
          if (failWrite) {
            failWrite = false
            error("spec write failed")
          }
          return delegate.writeBundleAtomically(writes, verify)
        }
      }
    val importService =
      testGoalRunnerStatusService(
        manifestStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1).copy(issueKey = "SKILL-OTHER")),
        outcomeStore = RecordingOutcomeStore(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = BranchHeadGit("feat/SKILL-245-goal", "abc123"),
            projectStore = destinationPort,
          ),
        database = database,
        decompositionManifestStore = failingStore,
      )

    assertIs<GoalRunnerImportResult.Incomplete>(importService.import(GoalRunnerImportRequest(archive, destinationRoot)))
    assertEquals(2, controls.controlState("wfl-parent").stopAfterSubtaskId)
    assertEquals(mapOf(1 to 3, 2 to 1), controls.controlState("wfl-parent").validationQualityRetriesBySubtask)
    val importedWorkflow = requireNotNull(destinationStates.getFeatureTaskWorkflow("wfl-parent"))
    destinationStates.failSaveWhen = { true }
    assertIs<GoalRunnerImportResult.Imported>(importService.import(GoalRunnerImportRequest(archive, destinationRoot)))
    assertEquals(2, controls.controlState("wfl-parent").stopAfterSubtaskId)
    assertEquals(mapOf(1 to 3, 2 to 1), controls.controlState("wfl-parent").validationQualityRetriesBySubtask)
    assertEquals(importedWorkflow, destinationStates.getFeatureTaskWorkflow("wfl-parent"))
    assertEquals("body of spec.md", Files.readString(destinationPort.root.resolve("specs/specs/spec.md")))
  }

  private fun retryDatabase(
    sourceDb: Path,
    states: InMemoryWorkflowStates,
    controls: RecordingImportControls,
    row: GoalArchiveTableRow,
  ): FakeDatabaseSessionFactory {
    lateinit var archivedControl: GoalRunnerControlState
    withGoalRunnerControlRepository(sourceDb) { archivedControl = it.controlState("wfl-parent") }
    return FakeDatabaseSessionFactory(
      states,
      goalRunnerControls = controls,
      archiveOwnedRestorer = { tables ->
        assertEquals(listOf(row), tables.rows("goal_runner_controls"))
        controls.persistControlState("wfl-parent", archivedControl)
      },
    )
  }

  @Test
  fun `completion refuses when a parent pending fingerprint disappears during spec write`() {
    val fixture = importFixture(bundleName = "specs")
    val archive = exportImportArchive(fixture)
    val destinationRoot = Files.createTempDirectory("goal-import-missing-marker-dest")
    val destinationPort = FixedProjectStorePort(destinationRoot)
    val destinationStates = InMemoryWorkflowStates()
    val controls = RecordingImportControls()
    val delegate = storeAwareTestDecompositionManifestStore(destinationPort)
    val store =
      object : DecompositionManifestStore by delegate {
        override fun <T> writeBundleAtomically(
          writes: List<Pair<Path, String>>,
          verify: () -> T,
        ): T {
          controls.clear()
          return delegate.writeBundleAtomically(writes, verify)
        }
      }
    val importService =
      testGoalRunnerStatusService(
        manifestStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1).copy(issueKey = "SKILL-OTHER")),
        outcomeStore = RecordingOutcomeStore(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = BranchHeadGit("feat/SKILL-245-goal", "abc123"),
            projectStore = destinationPort,
          ),
        database = FakeDatabaseSessionFactory(destinationStates, goalRunnerControls = controls),
        decompositionManifestStore = store,
      )

    val result = importService.import(GoalRunnerImportRequest(archive, destinationRoot))

    assertIs<GoalRunnerImportResult.Refused>(result)
    assertContains(result.reason, "Pending archive import state changed")
    assertEquals("body of spec.md", Files.readString(destinationPort.root.resolve("specs/specs/spec.md")))
  }

  @Test
  fun `retry refuses when archive owned state changes after the restore commit`() {
    val fixture = importFixture(bundleName = "specs")
    val archive = exportImportArchive(fixture)
    val destinationRoot = Files.createTempDirectory("goal-import-owned-change-dest")
    val destinationPort = FixedProjectStorePort(destinationRoot)
    val destinationStates = InMemoryWorkflowStates()
    var snapshots = 0
    val database =
      FakeDatabaseSessionFactory(
        destinationStates,
        goalRunnerControls = RecordingImportControls(),
        archiveSnapshotReader = { target ->
          snapshots++
          if (snapshots == 1) {
            GoalArchiveOwnedSnapshot(emptyList(), emptyList(), emptyList(), emptyList())
          } else {
            GoalArchiveOwnedSnapshot(
              workflows = target.workflowIds.mapNotNull(destinationStates::getFeatureTaskWorkflow),
              sharedPreplans = emptyList(),
              subtaskPlans = emptyList(),
              legacyPreparations = emptyList(),
              ownedTables =
                GoalArchiveOwnedTables.of(
                  mapOf("goal_run_sessions" to listOf(GoalArchiveTableRow(mapOf("session_id" to "changed")))),
                ),
            )
          }
        },
      )
    val delegate = storeAwareTestDecompositionManifestStore(destinationPort)
    var failWrite = true
    val store =
      object : DecompositionManifestStore by delegate {
        override fun <T> writeBundleAtomically(
          writes: List<Pair<Path, String>>,
          verify: () -> T,
        ): T {
          if (failWrite) {
            failWrite = false
            error("spec write failed")
          }
          return delegate.writeBundleAtomically(writes, verify)
        }
      }
    val importService =
      testGoalRunnerStatusService(
        manifestStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1).copy(issueKey = "SKILL-OTHER")),
        outcomeStore = RecordingOutcomeStore(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = BranchHeadGit("feat/SKILL-245-goal", "abc123"),
            projectStore = destinationPort,
          ),
        database = database,
        decompositionManifestStore = store,
      )

    assertIs<GoalRunnerImportResult.Incomplete>(importService.import(GoalRunnerImportRequest(archive, destinationRoot)))
    val restored = requireNotNull(destinationStates.getFeatureTaskWorkflow("wfl-parent"))
    val retry = importService.import(GoalRunnerImportRequest(archive, destinationRoot))

    assertIs<GoalRunnerImportResult.Refused>(retry)
    assertEquals(restored, destinationStates.getFeatureTaskWorkflow("wfl-parent"))
    assertFalse(Files.exists(destinationPort.root.resolve("specs/specs/spec.md")))
  }

  @Test
  fun `archive without a discovered parent refuses before a failed spec write`() {
    val fixture = importFixture(bundleName = "specs")
    val archive =
      exportImportArchive(
        fixture,
        RecordingGoalArchiveSnapshotPort {
          GoalArchiveOwnedSnapshot(emptyList(), emptyList(), emptyList(), emptyList())
        },
      )
    val destinationRoot = Files.createTempDirectory("goal-import-parentless-dest")
    val destinationPort = FixedProjectStorePort(destinationRoot)
    val delegate = storeAwareTestDecompositionManifestStore(destinationPort)
    val store =
      object : DecompositionManifestStore by delegate {
        override fun <T> writeBundleAtomically(
          writes: List<Pair<Path, String>>,
          verify: () -> T,
        ): T = error("spec write must not run without a parent marker")
      }
    val states = InMemoryWorkflowStates()
    val importService =
      testGoalRunnerStatusService(
        manifestStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1).copy(issueKey = "SKILL-OTHER")),
        outcomeStore = RecordingOutcomeStore(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = BranchHeadGit("feat/SKILL-245-goal", "abc123"),
            projectStore = destinationPort,
          ),
        database = FakeDatabaseSessionFactory(states),
        decompositionManifestStore = store,
      )

    val result = importService.import(GoalRunnerImportRequest(archive, destinationRoot))

    assertIs<GoalRunnerImportResult.Refused>(result)
    assertContains(result.reason, "no discovered parent workflow")
    assertNull(states.getFeatureTaskWorkflow("wfl-parent"))
    assertFalse(Files.exists(destinationPort.root.resolve("specs/specs/spec.md")))
  }

  @Test
  fun `existing destination spec is preserved and blocks a fresh import`() {
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
            archiveSnapshotPort = parentSnapshot(),
          ),
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(fixture.port),
      )
    assertIs<GoalRunnerExportResult.Exported>(
      exportService.export(GoalRunnerExportRequest("SKILL-245", fixture.root, archive)),
    )

    val destinationRoot = Files.createTempDirectory("goal-import-existing-spec-dest")
    val destinationPort = FixedProjectStorePort(destinationRoot)
    val existingSpec = destinationPort.root.resolve("specs/specs/spec.md")
    Files.createDirectories(existingSpec.parent)
    Files.writeString(existingSpec, "keep this destination spec")
    val states = InMemoryWorkflowStates()
    val importService =
      testGoalRunnerStatusService(
        manifestStore = InMemoryGoalManifestStore(manifest(subtaskCount = 1).copy(issueKey = "SKILL-OTHER")),
        outcomeStore = RecordingOutcomeStore(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = BranchHeadGit("feat/SKILL-245-goal", "abc123"),
            projectStore = destinationPort,
          ),
        database = FakeDatabaseSessionFactory(states),
        decompositionManifestStore = storeAwareTestDecompositionManifestStore(destinationPort),
      )

    assertIs<GoalRunnerImportResult.Refused>(importService.import(GoalRunnerImportRequest(archive, destinationRoot)))
    assertEquals("keep this destination spec", Files.readString(existingSpec))
    assertNull(states.getFeatureTaskWorkflow("wfl-parent"))
  }
}

private class RecordingImportControls : GoalRunnerControlRepository by EmptyGoalRunnerControlRepository {
  private val states = mutableMapOf<String, GoalRunnerControlState>()
  val persistedWrites = mutableListOf<Pair<String, GoalRunnerControlState>>()

  override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
    states[parentWorkflowId] ?: GoalRunnerControlState()

  override fun persistControlState(
    parentWorkflowId: String,
    state: GoalRunnerControlState,
  ): GoalRunnerControlState {
    states[parentWorkflowId] = state
    persistedWrites += parentWorkflowId to state
    return state
  }

  fun clear() {
    states.clear()
  }
}

private data class ImportFixture(
  val root: Path,
  val port: FixedProjectStorePort,
  val store: InMemoryGoalManifestStore,
)

private fun importFixture(
  bundleName: String = "SKILL-245-goal",
  absolutePaths: Boolean = false,
): ImportFixture {
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
      parentSpecPath = if (absolutePaths) bundle.resolve("spec.md").toString() else "specs/$bundleName/spec.md",
      subtasks =
        manifest(2).subtasks.mapIndexed { index, subtask ->
          subtask.copy(
            specPath =
              if (absolutePaths) {
                bundle.resolve("spec_subtask_${index + 1}.md").toString()
              } else {
                "specs/$bundleName/spec_subtask_${index + 1}.md"
              },
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

private fun parentSnapshot(
  sharedPreplans: List<SharedGoalPreplanCheckpoint> = emptyList(),
): RecordingGoalArchiveSnapshotPort {
  val states = InMemoryWorkflowStates()
  seedCurrentParent(states, "wfl-child", "SKILL-245")
  val parent = requireNotNull(states.getFeatureTaskWorkflow("wfl-parent")).copy(issueKey = "SKILL-245")
  return RecordingGoalArchiveSnapshotPort {
    GoalArchiveOwnedSnapshot(listOf(parent), sharedPreplans, emptyList(), emptyList())
  }
}

private fun archivedPreplan(): SharedGoalPreplanCheckpoint {
  val payload =
    """
    {"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"preplan","status":"completed",
    "summary":"archive preplan","produced_outputs":{"value":"resume from restored planning state"}}
    """.trimIndent()
  return SharedGoalPreplanCheckpoint(
    identity = GoalPlanningIdentity("wfl-parent", "SKILL-245", "repo-root-realpath-v1:/archived/source"),
    provenance =
      GoalPlanningContractProvenance(
        parentSpecHash = "a".repeat(64),
        decompositionManifestHash = "b".repeat(64),
        planningContractId = "https://skill-bill.dev/contracts/goal-planning-preparation-schema.yaml",
        planningContractVersion = GOAL_PLANNING_PREPARATION_CONTRACT_VERSION,
      ),
    payloadSha256 = sha256HexUtf8(payload),
    preplanPayload = payload,
    createdAt = "2026-01-01T00:00:00Z",
  )
}

private class RecordingImportPlanningPreparations : GoalPlanningPreparationRepositoryDefaults() {
  private var sharedPreplan: SharedGoalPreplanCheckpoint? = null

  override fun saveSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) {
    sharedPreplan = checkpoint
  }

  override fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanLookupResult =
    SharedGoalPreplanLookupResult.Found(sharedPreplan?.takeIf { it.identity == expectedIdentity })
}

private fun exportImportArchive(
  fixture: ImportFixture,
  snapshotPort: RecordingGoalArchiveSnapshotPort = parentSnapshot(),
): Path {
  val archive = fixture.root.resolve("archive.zip")
  val exportService =
    testGoalRunnerStatusService(
      manifestStore = fixture.store,
      outcomeStore = RecordingOutcomeStore(),
      ports =
        GoalRunnerStatusTestPorts(
          gitOperations = BranchHeadGit("feat/SKILL-245-goal", "abc123"),
          projectStore = fixture.port,
          archiveSnapshotPort = snapshotPort,
          workerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
        ),
      decompositionManifestStore = storeAwareTestDecompositionManifestStore(fixture.port),
    )
  assertIs<GoalRunnerExportResult.Exported>(
    exportService.export(GoalRunnerExportRequest("SKILL-245", fixture.root, archive)),
  )
  return archive
}

private fun rewriteArchiveEntry(
  archive: Path,
  entryName: String,
  rewrite: (String) -> String,
) {
  val entries = mutableListOf<Pair<String, ByteArray>>()
  ZipInputStream(Files.newInputStream(archive)).use { input ->
    while (true) {
      val entry = input.nextEntry ?: break
      val bytes = input.readBytes()
      entries += entry.name to
        if (entry.name == entryName) rewrite(bytes.decodeToString()).encodeToByteArray() else bytes
    }
  }
  ZipOutputStream(Files.newOutputStream(archive)).use { output ->
    entries.forEach { (name, bytes) ->
      output.putNextEntry(ZipEntry(name))
      output.write(bytes)
      output.closeEntry()
    }
  }
}
