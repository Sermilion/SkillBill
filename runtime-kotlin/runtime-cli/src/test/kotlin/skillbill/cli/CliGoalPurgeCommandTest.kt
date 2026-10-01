package skillbill.cli

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.decomposition.loadDecompositionManifest
import skillbill.application.testDecompositionManifestValidator
import skillbill.cli.core.CliRuntime
import skillbill.contracts.JsonCodec
import skillbill.infrastructure.sqlite.ensureTestDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
          "SKILL-901",
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
  fun `confirmed goal purge restores an unlaunched bundle and preflight reports new work`() {
    val fixture = goalFixture(subtaskCount = 2)
    val manifestPath = fixture.parentSpec.parent.resolve("decomposition-manifest.yaml")
    val result =
      CliRuntime.run(
        listOf(
          "--db",
          fixture.dbPath.toString(),
          "goal",
          "purge",
          "SKILL-901",
          "--confirm-issue-key",
          "SKILL-901",
          "--repo-root",
          fixture.tempDir.toString(),
        ),
        fixture.context(launcher = GoalFixtureAgentRunLauncher(fixture)),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertRestoredBundle(fixture, manifestPath)

    val preflight = runPreflight(fixture)
    assertEquals(0, preflight.exitCode, preflight.stdout)
    val payload =
      requireNotNull(
        JsonCodec.anyToStringAnyMap(
          JsonCodec.jsonElementToValue(requireNotNull(JsonCodec.parseObjectOrNull(preflight.stdout))),
        ),
      )
    assertEquals("new_work", payload["verdict"])
    assertNull(payload["candidate"])
    assertEquals(emptyList<Any?>(), payload["candidates"])
    assertNull(payload["goal"])
    assertTrue(payload["manifest_missing"] == false)
  }

  private fun runPreflight(fixture: GoalCliFixture) =
    CliRuntime.run(
      listOf(
        "--db",
        fixture.dbPath.toString(),
        "goal",
        "preflight",
        "SKILL-901",
        "--agent",
        "codex",
        "--repo-root",
        fixture.tempDir.toString(),
        "--format",
        "json",
      ),
      fixture.context(launcher = GoalFixtureAgentRunLauncher(fixture)),
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
