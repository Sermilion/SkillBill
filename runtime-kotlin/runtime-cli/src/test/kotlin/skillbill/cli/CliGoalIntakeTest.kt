package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunTermination
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliGoalIntakeTest {
  @Test
  fun `a tracker URL starts durable planning without a prepared workflow`() {
    startNewGoal("https://linear.app/capmo/issue/WE-5018/update", "WE-5018")
    startNewGoal("https://team.atlassian.net/browse/APP-123", "APP-123")
    startNewGoal("https://tracker.example/tasks/opaque-id", null)
  }

  @Test
  fun `raw requirements survive startup and reuse the same goal identity`() {
    startNewGoal("Allow export.\n\n## Acceptance criteria\n\n- [ ] Export preserves Czech characters.", null)
  }

  @Test
  fun `an existing spec key resumes without creating a second bundle`() {
    val fixture = goalFixture(subtaskCount = 1)
    try {
      val result =
        CliRuntime.run(
          fixture.goalCommand().filterNot { it == "goal" },
          fixture.context(launcher = GoalFixtureAgentRunLauncher(fixture)),
        )
      assertEquals(0, result.exitCode, result.stderr + result.stdout)
      assertFalse(Files.exists(fixture.tempDir.resolve(".feature-specs/SKILL-901-intake")))
    } finally {
      fixture.tempDir.toFile().deleteRecursively()
    }
  }

  private fun startNewGoal(
    text: String,
    expectedKey: String?,
  ) {
    val root = Files.createTempDirectory("goal-intake")
    val db = root.resolve("metrics.db")
    val fixture = GoalCliFixture(root, db, root.resolve("unused.md"), emptyList())
    val launcher = StoppedPlanningLauncher(db)
    val command = listOf("--db", db.toString(), text, "--agent", "codex", "--repo-root", root.toString())
    try {
      val first = CliRuntime.run(command, fixture.context(launcher = launcher).copy(repositoryRoot = root))
      assertEquals(3, first.exitCode, first.stderr + first.stdout)
      val key =
        Files.list(root.resolve(".feature-specs")).use { paths ->
          paths.findFirst().orElseThrow().fileName.toString().removeSuffix("-intake")
        }
      expectedKey?.let { assertEquals(it, key) }
      assertContains(first.stdout, "goal $key:")
      assertFalse(first.stdout.contains("No decomposed parent workflow"))
      assertTrue(launcher.prompts.isNotEmpty())
      assertTrue(launcher.prompts.all { it.contains("Phase: preplan") })
      val spec = root.resolve(".feature-specs/$key-intake/spec.md")
      assertContains(Files.readString(spec), text)
      val before = Files.readString(spec)
      val second = CliRuntime.run(command, fixture.context(launcher = launcher).copy(repositoryRoot = root))
      assertEquals(3, second.exitCode, second.stderr + second.stdout)
      assertEquals(before, Files.readString(spec))
      assertEquals(1, parentCount(db))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private class StoppedPlanningLauncher(private val db: Path) : AgentRunLauncher {
    val prompts = mutableListOf<String>()

    override fun launch(request: AgentRunLaunchRequest): AgentRunLaunchOutcome {
      assertEquals(1, parentCount(db))
      prompts += request.skillRunRequest.promptOverride.orEmpty()
      return agentRunLaunchFacts(
        SupportedAgent.CODEX,
        termination = AgentRunTermination.SpawnFailed,
        stderr = "Fixture refuses planning so implementation cannot run.",
      )
    }
  }

  companion object {
    private fun parentCount(db: Path): Int =
      DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
        connection.createStatement().use { statement ->
          statement.executeQuery("SELECT COUNT(*) FROM goal_runner_controls").use { rows ->
            assertTrue(rows.next())
            rows.getInt(1)
          }
        }
      }
  }
}
