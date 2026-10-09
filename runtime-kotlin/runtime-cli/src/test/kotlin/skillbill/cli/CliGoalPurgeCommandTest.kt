package skillbill.cli

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.decomposition.loadDecompositionManifest
import skillbill.application.testDecompositionManifestValidator
import skillbill.cli.core.CliRuntime
import skillbill.cli.goal.purge.goalPurgeExitCode
import skillbill.contracts.JsonCodec
import skillbill.engine.goalrunner.model.GoalRunnerPurgeResult
import skillbill.infrastructure.host.CanonicalRepositoryRoot
import skillbill.infrastructure.sqlite.ensureTestDatabase
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PURGE_ISSUE_KEY = "SKILL-901"
private const val PURGE_BUNDLE_PREFIX = ".feature-specs/SKILL-901-goal/"

class CliGoalPurgeCommandTest {
  @Test
  fun `goal purge requires confirmation and leaves database and specs unchanged`() {
    val fixture = goalFixture(subtaskCount = 2)
    val manifestPath = fixture.parentSpec.parent.resolve("decomposition-manifest.yaml")
    val beforeWorkflowCount = workflowCount(fixture)
    val beforeParentSpec = Files.readString(fixture.parentSpec)
    val beforeManifest = Files.readString(manifestPath)
    val beforeSubtaskSpecs = fixture.subtaskSpecs.map { path -> Files.readString(path) }
    val denied =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "purge",
          PURGE_ISSUE_KEY,
          "--repo-root",
          fixture.tempDir.toString(),
        ),
        fixture.context(launcher = GoalFixtureAgentRunLauncher(fixture)),
      )
    assertEquals(1, denied.exitCode, denied.stderr)
    assertContains(denied.stderr, "Goal purge requires explicit confirmation")
    assertEquals(beforeWorkflowCount, workflowCount(fixture))
    assertEquals(beforeParentSpec, Files.readString(fixture.parentSpec))
    assertEquals(beforeManifest, Files.readString(manifestPath))
    assertEquals(beforeSubtaskSpecs, fixture.subtaskSpecs.map { path -> Files.readString(path) })
  }

  @Test
  fun `confirmed goal purge restores a tracked bundle and preflight reports new work`() {
    val fixture = goalFixture(subtaskCount = 2)
    val manifestPath = fixture.parentSpec.parent.resolve("decomposition-manifest.yaml")
    val seed = we5006Seed(fixture)
    seedGoalOwnedState(fixture, seed)

    val result = runPurge(fixture, PurgeGitOperations(trackedPrefix = PURGE_BUNDLE_PREFIX))

    assertEquals(0, result.exitCode, result.stdout + result.stderr)
    assertRestoredBundle(fixture, manifestPath)
    assertEquals(0, ownedRowCounts(fixture, seed).values.sum())
    assertTrue(ownedRuntimeDirectories(fixture.tempDir, seed.workflowIds()).none(Files::exists))

    val preflight = runPreflight(fixture)
    assertEquals(0, preflight.exitCode, preflight.stdout)
    assertFalse(preflight.stdout.contains("unsafe_import"), preflight.stdout)
    val payload = payloadOf(preflight.stdout)
    assertEquals("new_work", payload["verdict"])
    assertNull(payload["candidate"])
    assertEquals(emptyList<Any?>(), payload["candidates"])
    assertNull(payload["goal"])
    assertTrue(payload["manifest_missing"] == false)
  }

  @Test
  fun `goal purge removes every WE-5006 runtime artifact and a second purge has nothing to remove`() {
    val fixture = goalFixture(subtaskCount = 1)
    val seed = we5006Seed(fixture)
    seedGoalOwnedState(fixture, seed)
    val manifestPath = fixture.parentSpec.parent.resolve("decomposition-manifest.yaml")
    val parentSpecBytes = Files.readAllBytes(fixture.parentSpec)
    assertTrue(ownedRowCounts(fixture, seed).values.all { count -> count > 0 }, "every owned table must be seeded")
    assertTrue(ownedRuntimeDirectories(fixture.tempDir, seed.workflowIds()).all(Files::exists))

    val purged = runPurge(fixture, PurgeGitOperations(trackedPrefix = null))

    assertEquals(0, purged.exitCode, purged.stdout + purged.stderr)
    val payload = requireNotNull(purged.payload)
    assertEquals("ok", payload["status"])
    assertEquals(emptyList<Any?>(), payload["leftovers"])
    assertEquals(false, payload["nothing_to_remove"])
    assertEquals(2, ((payload["removed_row_counts"] as Map<*, *>)["feature_task_workflows"] as Number).toInt())
    assertEquals(0, ownedRowCounts(fixture, seed).values.sum())
    assertTrue(ownedRuntimeDirectories(fixture.tempDir, seed.workflowIds()).none(Files::exists))
    assertContentEquals(parentSpecBytes, Files.readAllBytes(fixture.parentSpec))
    assertFalse(Files.exists(manifestPath))
    fixture.subtaskSpecs.forEach { path -> assertFalse(Files.exists(path)) }

    val planningLog = runGoalCommand(fixture, "planning-log")
    assertEquals(0, planningLog.exitCode, planningLog.stdout)
    assertNull(requireNotNull(planningLog.payload)["parent_workflow_id"])
    val status = runGoalCommand(fixture, "status")
    assertFalse((status.stdout + status.stderr).contains(seed.parentId))

    val repeated = runPurge(fixture, PurgeGitOperations(trackedPrefix = null))
    assertEquals(0, repeated.exitCode, repeated.stdout)
    assertEquals(true, requireNotNull(repeated.payload)["nothing_to_remove"])
  }

  @Test
  fun `goal purge leaves another goal and the same key in another repository untouched`() {
    val fixture = goalFixture(subtaskCount = 1)
    val repositoryIdentity = CanonicalRepositoryRoot.repositoryIdentity(fixture.tempDir)
    val target = we5006Seed(fixture)
    val otherGoal =
      GoalSeed("SKILL-902", repositoryIdentity, "wftr-other-goal-parent", "wftr-other-goal-child", "session-other-goal")
    val otherRepository =
      GoalSeed(
        issueKey = PURGE_ISSUE_KEY,
        repositoryIdentity = CanonicalRepositoryRoot.repositoryIdentity(Files.createTempDirectory("skillbill-other")),
        parentId = "wftr-other-repo-parent",
        childId = "wftr-other-repo-child",
        sessionId = "session-other-repo",
      )
    listOf(target, otherGoal, otherRepository).forEach { seed -> seedGoalOwnedState(fixture, seed) }
    val otherSpecs = seedOtherGoalSpecBundle(fixture.tempDir)
    val otherSpecBytes = otherSpecs.map { path -> Files.readAllBytes(path) }
    val untouched = listOf(otherGoal, otherRepository)
    val countsBefore = untouched.map { seed -> ownedRowCounts(fixture, seed) }

    val result = runPurge(fixture, PurgeGitOperations(trackedPrefix = null))

    assertEquals(0, result.exitCode, result.stdout + result.stderr)
    assertEquals(0, ownedRowCounts(fixture, target).values.sum())
    assertEquals(countsBefore, untouched.map { seed -> ownedRowCounts(fixture, seed) })
    untouched.forEach { seed ->
      assertTrue(ownedRuntimeDirectories(fixture.tempDir, seed.workflowIds()).all(Files::exists), seed.parentId)
    }
    otherSpecs.forEachIndexed { index, path -> assertContentEquals(otherSpecBytes[index], Files.readAllBytes(path)) }
  }

  @Test
  fun `goal purge exits non-zero when anything is left behind or refused`() {
    assertEquals(0, goalPurgeExitCode(GoalRunnerPurgeResult(issueKey = PURGE_ISSUE_KEY)))
    assertEquals(
      1,
      goalPurgeExitCode(
        GoalRunnerPurgeResult(
          issueKey = PURGE_ISSUE_KEY,
          leftovers = listOf(".skill-bill/run-evidence/wf: delete failed"),
        ),
      ),
    )
    assertEquals(1, goalPurgeExitCode(GoalRunnerPurgeResult(issueKey = PURGE_ISSUE_KEY, refusalReason = "live")))
  }

  private fun we5006Seed(fixture: GoalCliFixture): GoalSeed =
    GoalSeed(
      issueKey = PURGE_ISSUE_KEY,
      repositoryIdentity = CanonicalRepositoryRoot.repositoryIdentity(fixture.tempDir),
      parentId = fixtureParentWorkflowId(fixture),
      childId = "wftr-we5006-child",
      sessionId = "session-we5006",
      parentExists = true,
    )

  private fun seedOtherGoalSpecBundle(repoRoot: Path): List<Path> {
    val bundle = repoRoot.resolve(".feature-specs/SKILL-902-other")
    Files.createDirectories(bundle)
    return listOf("spec.md", "spec_subtask_1_other.md").map { name ->
      bundle.resolve(name).also { path -> Files.writeString(path, "other goal $name") }
    }
  }

  private fun runPurge(
    fixture: GoalCliFixture,
    git: WorkflowGitOperations,
  ) = CliRuntime.run(
    listOf(
      "--db",
      fixture.dbPath.toString(),
      "goal",
      "purge",
      PURGE_ISSUE_KEY,
      "--confirm-issue-key",
      PURGE_ISSUE_KEY,
      "--repo-root",
      fixture.tempDir.toString(),
    ),
    fixture.context(launcher = GoalFixtureAgentRunLauncher(fixture), workflowGitOperations = git),
  )

  private fun runGoalCommand(
    fixture: GoalCliFixture,
    command: String,
  ) = CliRuntime.run(
    listOf(
      "--db",
      fixture.dbPath.toString(),
      "goal",
      command,
      PURGE_ISSUE_KEY,
      "--repo-root",
      fixture.tempDir.toString(),
    ),
    fixture.context(launcher = NoopGoalTestAgentRunLauncher),
  )

  private fun runPreflight(fixture: GoalCliFixture) =
    CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "preflight",
        PURGE_ISSUE_KEY,
        "--agent",
        "codex",
        "--repo-root",
        fixture.tempDir.toString(),
        "--format",
        "json",
      ),
      fixture.context(launcher = GoalFixtureAgentRunLauncher(fixture)),
    )

  private fun payloadOf(stdout: String): Map<String, Any?> =
    requireNotNull(
      JsonCodec.anyToStringAnyMap(
        JsonCodec.jsonElementToValue(requireNotNull(JsonCodec.parseObjectOrNull(stdout))),
      ),
    )

  private fun assertRestoredBundle(
    fixture: GoalCliFixture,
    manifestPath: Path,
  ) {
    assertTrue(Files.isRegularFile(fixture.parentSpec))
    fixture.subtaskSpecs.forEach { path -> assertTrue(Files.isRegularFile(path)) }
    val restored =
      loadDecompositionManifest(
        manifestPath,
        TestDecompositionManifestStore,
        testDecompositionManifestValidator,
      )
    assertEquals("pending", restored.status)
    assertEquals(1, restored.currentSubtaskIntent.subtaskId)
    assertEquals("start", restored.currentSubtaskIntent.action)
    restored.subtasks.forEach { subtask ->
      assertEquals("pending", subtask.status)
      assertNull(subtask.workflowId)
      assertNull(subtask.commitSha)
      assertNull(subtask.branch)
      assertNull(subtask.blockedReason)
      assertNull(subtask.lastResumableStep)
    }
  }

  private fun workflowCount(fixture: GoalCliFixture): Int =
    ensureTestDatabase(fixture.dbPath).use { connection ->
      connection.prepareStatement("SELECT COUNT(*) FROM feature_task_workflows").use { statement ->
        statement.executeQuery().use { rows ->
          check(rows.next())
          rows.getInt(1)
        }
      }
    }
}

private class PurgeGitOperations(
  private val trackedPrefix: String?,
) : WorkflowGitOperations by GoalTestWorkflowGitOperations {
  override fun listCheckpointRefs(
    repoRoot: Path,
    namespacePrefix: String,
  ): WorkflowGitNameListResult = WorkflowGitNameListResult.Listed(emptyList())

  override fun readHeadTrackedFile(
    repoRoot: Path,
    repoRelativePath: String,
  ): WorkflowGitOperationResult =
    if (trackedPrefix != null && repoRelativePath.startsWith(trackedPrefix)) {
      WorkflowGitOperationResult.Ok(value = "tracked at HEAD")
    } else {
      WorkflowGitOperationResult.Failed(error = "not tracked")
    }
}
