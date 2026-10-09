package skillbill.engine.goalrunner.reset

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestDecompositionManifestStore
import skillbill.application.decomposition.loadDecompositionManifest
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.repoRelativePath
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.contracts.workflow.identity.task.FEATURE_TASK_RUNTIME_WORKER_OWNERSHIP_CONTRACT_VERSION
import skillbill.engine.decomposition.encodeDecompositionManifestYaml
import skillbill.engine.goalrunner.InMemoryGoalManifestStore
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestPorts
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.execution.core.testPhaseRecorder
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalPurgeOwnership
import skillbill.engine.goalrunner.model.GoalRunnerPurgeRequest
import skillbill.engine.goalrunner.model.GoalRunnerPurgeSpecActionKind
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.infrastructure.sqlite.ensureTestDatabase
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.persistence.model.GoalPurgeTableCounts
import skillbill.ports.persistence.model.GoalPurgeTarget
import skillbill.ports.taskruntime.FeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.goalstate.LocalGoalRuntimeStateFileStore
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.taskruntime.artifact.FeatureTaskRuntimeRunEvidenceAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalRunnerPurgeCoordinatorTest {
  @Test
  fun `live parent execution lease refuses purge`() {
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
    var purgeCalled = false
    val guardedStore =
      object : GoalRunnerManifestStore by store {
        override fun discoverPurgeOwnership(
          issueKey: String,
          repoRoot: Path,
        ): GoalPurgeOwnership {
          val state = checkNotNull(store.loadDurableByIssueKey(issueKey)).copy(parentWorkflowId = "wf-parent")
          return GoalPurgeOwnership(listOf(state), setOf("wf-parent"))
        }

        override fun purgeDecomposedGoal(target: GoalPurgeTarget): GoalPurgeTableCounts {
          purgeCalled = true
          return GoalPurgeTableCounts()
        }
      }
    val service =
      testGoalRunnerStatusService(
        manifestStore = guardedStore,
        outcomeStore = RecordingOutcomeStore(),
        ports = GoalRunnerStatusTestPorts(workerSupervisor = LiveInspectWorkerSupervisor),
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        decompositionManifestStore = TestDecompositionManifestStore,
      )
    val root = Files.createTempDirectory("goal-purge-live-parent")
    val result = service.purge(GoalRunnerPurgeRequest("SKILL-245", root))
    assertContains(result.refusalReason.orEmpty(), "live")
    assertFalse(purgeCalled)
  }

  @Test
  fun `live owned child worker refuses purge even when parent is idle`() {
    val dbPath = Files.createTempDirectory("goal-purge-live-child").resolve("metrics.db")
    val userHome = dbPath.parent
    val database =
      sqliteDatabaseSessionFactory(
        userHome = userHome,
        dbPathOverride = dbPath.toString(),
        environment = emptyMap(),
      )
    ensureTestDatabase(dbPath).use { connection ->
      connection.prepareStatement(
        """
        INSERT INTO feature_task_workflows (workflow_id, mode, contract_version, artifacts_json)
        VALUES ('wf-child', 'runtime', '0.1', '{}')
        """.trimIndent(),
      ).use { statement -> statement.executeUpdate() }
      connection.prepareStatement(
        """
        INSERT INTO feature_task_runtime_worker_leases (
          workflow_id, contract_version, generation, owner_token, host_identity, boot_identity,
          pid, process_birth_token, lease_state, heartbeat_at, expires_at, phase_id, phase_attempt
        ) VALUES ('wf-child', ?, 1, 'child-owner', 'host', 'boot', 42, 'birth-42', 'active',
          '2026-01-01T00:00:00Z', '2999-01-01T00:00:00Z', 'implement', 1)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, FEATURE_TASK_RUNTIME_WORKER_OWNERSHIP_CONTRACT_VERSION)
        statement.executeUpdate()
      }
    }
    val goalManifest = manifest(subtaskCount = 1).copy(issueKey = "SKILL-245")
    val store = InMemoryGoalManifestStore(goalManifest)
    var purgeCalled = false
    val guardedStore =
      object : GoalRunnerManifestStore by store {
        override fun listOwnedGoalChildWorkflowIds(parentWorkflowId: String): List<String> = listOf("wf-child")

        override fun purgeDecomposedGoal(target: GoalPurgeTarget): GoalPurgeTableCounts {
          purgeCalled = true
          return GoalPurgeTableCounts()
        }
      }
    val service =
      testGoalRunnerStatusService(
        manifestStore = guardedStore,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = testPhaseRecorder(database, testWorkflowSnapshotValidator),
        ports = GoalRunnerStatusTestPorts(workerSupervisor = LiveInspectWorkerSupervisor),
        database = database,
        decompositionManifestStore = TestDecompositionManifestStore,
      )
    val result =
      service.purge(
        GoalRunnerPurgeRequest(
          issueKey = "SKILL-245",
          repoRoot = Files.createTempDirectory("goal-purge-child-root"),
        ),
      )

    assertContains(result.refusalReason.orEmpty(), "live")
    assertFalse(purgeCalled)
  }

  @Test
  fun `purge restores missing tracked specs and keeps existing spec bodies`() {
    val fixture = restoreFixture()
    val guardedStore = PurgeTrackingStore(fixture.store, verifiedIds = setOf("wf-child-1"))
    val database = idleRuntimeWorkflowDatabase("wf-child-1")
    val service =
      testGoalRunnerStatusService(
        manifestStore = guardedStore,
        outcomeStore = RecordingOutcomeStore(),
        phaseRecorder = testPhaseRecorder(database, testWorkflowSnapshotValidator),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations =
              TrackedSpecGitOperations(
                mapOf(
                  fixture.manifestPath.toRepoRelative(fixture.root) to "tracked manifest at HEAD",
                  fixture.firstSubtask.toRepoRelative(fixture.root) to "restored first subtask body",
                ),
              ),
            goalStateFiles = LocalGoalRuntimeStateFileStore(),
          ),
        database = database,
        decompositionManifestStore = TestDecompositionManifestStore,
      )
    val result = service.purge(GoalRunnerPurgeRequest("SKILL-245", fixture.root))
    val restored =
      loadDecompositionManifest(
        fixture.manifestPath,
        TestDecompositionManifestStore,
        testDecompositionManifestValidator,
      )

    assertNull(result.refusalReason)
    assertEquals(emptyList(), result.leftovers)
    assertEquals(1, guardedStore.purgedTargets.size)
    val purgedIds = guardedStore.purgedTargets.single().workflowIds
    assertContains(purgedIds, "wf-child-1")
    assertFalse("wf-child-2" in purgedIds)
    assertEquals(
      mapOf(
        fixture.manifestPath.toRepoRelative(fixture.root) to GoalRunnerPurgeSpecActionKind.RESET,
        fixture.firstSubtask.toRepoRelative(fixture.root) to GoalRunnerPurgeSpecActionKind.RESTORED,
      ),
      result.specBundleActions.associate { action -> action.path to action.kind },
    )
    assertEquals("existing parent body", Files.readString(fixture.parentSpec))
    assertEquals("existing second subtask body", Files.readString(fixture.secondSubtask))
    assertEquals("restored first subtask body", Files.readString(fixture.firstSubtask))
    assertUnlaunched(restored)
    assertUnlaunchedYaml(Files.readString(fixture.manifestPath))
  }

  @Test
  fun `untracked manifest and specs are deleted and the parent spec is kept`() {
    val root = Files.createTempDirectory("goal-purge-untracked")
    val specDir = root.resolve(".feature-specs/SKILL-245-goal")
    Files.createDirectories(specDir)
    val manifest =
      manifest(1).copy(
        issueKey = "SKILL-245",
        parentSpecPath = ".feature-specs/SKILL-245-goal/spec.md",
        subtasks =
          manifest(1).subtasks.map {
            it.copy(specPath = ".feature-specs/SKILL-245-goal/spec_subtask_1.md")
          },
      )
    val manifestPath = specDir.resolve("decomposition-manifest.yaml")
    Files.writeString(
      manifestPath,
      encodeDecompositionManifestYaml(
        manifest,
        testDecompositionManifestValidator,
        TestDecompositionManifestStore,
        manifestPath.toString(),
      ),
    )
    Files.writeString(specDir.resolve("spec.md"), "parent")
    Files.writeString(specDir.resolve("spec_subtask_1.md"), "generated subtask body")
    val guardedStore = PurgeTrackingStore(InMemoryGoalManifestStore(manifest))
    val service =
      testGoalRunnerStatusService(
        manifestStore = guardedStore,
        outcomeStore = RecordingOutcomeStore(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = TrackedSpecGitOperations(emptyMap()),
            goalStateFiles = LocalGoalRuntimeStateFileStore(),
          ),
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        decompositionManifestStore = TestDecompositionManifestStore,
      )

    val result = service.purge(GoalRunnerPurgeRequest("SKILL-245", root))

    assertEquals(1, guardedStore.purgedTargets.size)
    assertNull(result.refusalReason)
    assertEquals(emptyList(), result.leftovers)
    assertEquals(
      listOf(
        ".feature-specs/SKILL-245-goal/spec_subtask_1.md" to GoalRunnerPurgeSpecActionKind.DELETED,
        ".feature-specs/SKILL-245-goal/decomposition-manifest.yaml" to GoalRunnerPurgeSpecActionKind.DELETED,
      ),
      result.specBundleActions.map { action -> action.path to action.kind },
    )
    assertEquals("parent", Files.readString(specDir.resolve("spec.md")))
    assertFalse(Files.exists(manifestPath))
    assertFalse(Files.exists(specDir.resolve("spec_subtask_1.md")))
  }

  @Test
  fun `failed directory delete reports leftovers and skips the database and spec steps`() {
    val root = Files.createTempDirectory("goal-purge-leftover")
    val evidenceDir = root.resolve(FeatureTaskRuntimeRunEvidenceAddress.workflowStoreRoot("wf-parent")).normalize()
    Files.createDirectories(evidenceDir)
    Files.writeString(evidenceDir.resolve("evidence.json"), "{}")
    var purgeCalled = false
    val guardedStore =
      object : GoalRunnerManifestStore by InMemoryGoalManifestStore(manifest(subtaskCount = 1)) {
        override fun discoverPurgeOwnership(
          issueKey: String,
          repoRoot: Path,
        ): GoalPurgeOwnership = GoalPurgeOwnership(emptyList(), setOf("wf-parent"))

        override fun purgeDecomposedGoal(target: GoalPurgeTarget): GoalPurgeTableCounts {
          purgeCalled = true
          return GoalPurgeTableCounts()
        }
      }
    val service =
      testGoalRunnerStatusService(
        manifestStore = guardedStore,
        outcomeStore = RecordingOutcomeStore(),
        ports =
          GoalRunnerStatusTestPorts(
            gitOperations = TrackedSpecGitOperations(emptyMap()),
            goalStateFiles = LocalGoalRuntimeStateFileStore(failingPaths = setOf(evidenceDir)),
          ),
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        decompositionManifestStore = TestDecompositionManifestStore,
      )

    val result = service.purge(GoalRunnerPurgeRequest("SKILL-245", root))

    assertNull(result.refusalReason)
    assertFalse(purgeCalled)
    assertTrue(result.leftovers.any { leftover -> leftover.startsWith(".skill-bill/run-evidence/wf-parent") })
    assertTrue(Files.exists(evidenceDir.resolve("evidence.json")))
  }
}

private class PurgeTrackingStore(
  private val delegate: InMemoryGoalManifestStore,
  private val verifiedIds: Set<String> = emptySet(),
) : GoalRunnerManifestStore by delegate {
  val purgedTargets = mutableListOf<GoalPurgeTarget>()

  override fun verifyOwnedWorkflowIds(
    candidateIds: Set<String>,
    parentWorkflowIds: Set<String>,
    issueKey: String,
    repoRoot: Path,
  ): Set<String> = candidateIds.intersect(verifiedIds)

  override fun discoverPurgeOwnership(
    issueKey: String,
    repoRoot: Path,
  ): GoalPurgeOwnership =
    if (purgedTargets.isEmpty()) {
      delegate.discoverPurgeOwnership(issueKey, repoRoot)
    } else {
      GoalPurgeOwnership(emptyList(), emptySet())
    }

  override fun purgeDecomposedGoal(target: GoalPurgeTarget): GoalPurgeTableCounts {
    purgedTargets += target
    return GoalPurgeTableCounts(mapOf("feature_task_workflows" to 1))
  }
}

private fun idleRuntimeWorkflowDatabase(workflowId: String): DatabaseSessionFactory {
  val dbPath = Files.createTempDirectory("goal-purge-idle-workflow").resolve("metrics.db")
  ensureTestDatabase(dbPath).use { connection ->
    connection.prepareStatement(
      """
      INSERT INTO feature_task_workflows (workflow_id, mode, contract_version, artifacts_json)
      VALUES (?, 'runtime', '0.1', '{}')
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.executeUpdate()
    }
  }
  return sqliteDatabaseSessionFactory(
    userHome = dbPath.parent,
    dbPathOverride = dbPath.toString(),
    environment = mapOf("SKILL_BILL_TEST" to "1"),
  )
}

private fun assertUnlaunchedYaml(yaml: String) {
  val lines = yaml.lines()
  assertTrue(lines.none { line -> line.startsWith("workflow_id:") || line.startsWith("last_resumable_step:") })
  assertTrue(lines.filter { line -> line.trimStart().startsWith("status:") }.all { line -> "pending" in line })
}

private fun assertUnlaunched(manifest: DecompositionManifest) {
  assertEquals("pending", manifest.status)
  assertEquals(1, manifest.currentSubtaskIntent.subtaskId)
  assertEquals("start", manifest.currentSubtaskIntent.action)
  manifest.subtasks.forEach { subtask ->
    assertEquals("pending", subtask.status)
    assertNull(subtask.workflowId)
    assertNull(subtask.commitSha)
    assertNull(subtask.branch)
    assertNull(subtask.blockedReason)
    assertNull(subtask.lastResumableStep)
  }
}

private fun restoreFixture(): PurgeRestoreFixture {
  val root = Files.createTempDirectory("goal-purge-restore")
  val specDir = root.resolve(".feature-specs/SKILL-245-goal")
  Files.createDirectories(specDir)
  val parentSpec = specDir.resolve("spec.md")
  val firstSubtask = specDir.resolve("spec_subtask_1.md")
  val secondSubtask = specDir.resolve("spec_subtask_2.md")
  Files.writeString(parentSpec, "existing parent body")
  Files.writeString(secondSubtask, "existing second subtask body")
  val sourceManifest =
    manifest(subtaskCount = 2).copy(
      issueKey = "SKILL-245",
      parentSpecPath = ".feature-specs/SKILL-245-goal/spec.md",
      subtasks =
        manifest(2).subtasks.mapIndexed { index, subtask ->
          subtask.copy(
            specPath = ".feature-specs/SKILL-245-goal/spec_subtask_${index + 1}.md",
            status = if (index == 0) "complete" else "blocked",
            branch = "feat/SKILL-245-goal",
            commitSha = "sha-${index + 1}",
            workflowId = "wf-child-${index + 1}",
            blockedReason = if (index == 1) "blocked" else null,
            lastResumableStep = "implement",
          )
        },
    )
  val manifestPath = specDir.resolve("decomposition-manifest.yaml")
  Files.writeString(
    manifestPath,
    encodeDecompositionManifestYaml(
      sourceManifest,
      testDecompositionManifestValidator,
      TestDecompositionManifestStore,
      manifestPath.toString(),
    ),
  )
  return PurgeRestoreFixture(
    root,
    parentSpec,
    firstSubtask,
    secondSubtask,
    manifestPath,
    InMemoryGoalManifestStore(sourceManifest),
  )
}

private data class PurgeRestoreFixture(
  val root: Path,
  val parentSpec: Path,
  val firstSubtask: Path,
  val secondSubtask: Path,
  val manifestPath: Path,
  val store: InMemoryGoalManifestStore,
)

private class TrackedSpecGitOperations(
  private val trackedFiles: Map<String, String>,
) : WorkflowGitOperations by NoopWorkflowGitOperations {
  override fun listCheckpointRefs(
    repoRoot: Path,
    namespacePrefix: String,
  ): WorkflowGitNameListResult = WorkflowGitNameListResult.Listed(emptyList())

  override fun readHeadTrackedFile(
    repoRoot: Path,
    repoRelativePath: String,
  ): WorkflowGitOperationResult =
    trackedFiles[repoRelativePath]?.let { content -> WorkflowGitOperationResult.Ok(value = content) }
      ?: WorkflowGitOperationResult.Failed("not tracked")
}

private fun Path.toRepoRelative(root: Path): String = root.relativize(this).toString().replace('\\', '/')

private object LiveInspectWorkerSupervisor : FeatureTaskRuntimeWorkerSupervisor {
  override fun currentProcess(): FeatureTaskRuntimeProcessIdentity =
    FeatureTaskRuntimeProcessIdentity("host", "boot", 42, "birth")

  override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection =
    FeatureTaskRuntimeProcessInspection.ExactLive

  override fun awaitExit(
    ownership: FeatureTaskRuntimeWorkerOwnership,
    timeout: Duration,
  ) = Unit

  override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean = false

  override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership): Boolean = false

  override fun startHeartbeat(
    plan: FeatureTaskRuntimeHeartbeatPlan,
    heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
  ): FeatureTaskRuntimeHeartbeat =
    object : FeatureTaskRuntimeHeartbeat {
      override fun stop() = Unit

      override fun fencingLostReason(): String? = null
    }

  override fun pause(durationMillis: Long) = Unit
}
