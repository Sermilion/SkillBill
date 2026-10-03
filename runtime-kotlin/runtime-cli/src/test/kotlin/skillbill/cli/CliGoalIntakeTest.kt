package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.contracts.issuekey.issueAndFeature
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.AgentRunLauncher
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunLaunchRequest
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
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
    startNewGoal("https://linear.app/capmo/issue/WE-5018/update\n\nRefresh the board filters.", "WE-5018", "update")
    startNewGoal(
      "https://team.atlassian.net/browse/APP-123\n\n# Board cache",
      "APP-123",
      "board-cache",
    )
  }

  @Test
  fun `raw requirements do not mint a local workflow key`() {
    refuseNewGoal(
      "Allow export.\n\n## Acceptance criteria\n\n- [ ] Export preserves Czech characters.",
      "To start new work, add a tracker issue key or link",
    )
    refuseNewGoal("https://tracker.example/tasks/opaque-id", "To start new work, add a tracker issue key or link")
  }

  @Test
  fun `a tracker key or link without requirements does not start new work`() {
    refuseNewGoal("APP-123", "To start new work on APP-123, add the requirements")
    refuseNewGoal("https://team.atlassian.net/browse/APP-123", "To start new work on APP-123, add the requirements")
    refuseNewGoal("https://linear.app/capmo/issue/WE-5018/update", "To start new work on WE-5018, add the requirements")
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

  @Test
  fun `bare spec intake prepares beside its unchanged parent and design assets`() {
    for (referenceKind in listOf(
      "absolute",
      "relative",
      "directory",
      "directory-with-instructions",
      "bundle-key",
      "key",
    )) {
      val fixture = goalFixture(subtaskCount = 0, seedWorkflow = false)
      val root = fixture.tempDir
      val parent = fixture.parentSpec
      val original = Files.readString(parent) + "\n\nDesign reference: [layout](design/layout.html)\n"
      Files.writeString(parent, original)
      val design = parent.parent.resolve("design/layout.html")
      Files.createDirectories(design.parent)
      Files.writeString(design, "<html>Existing design</html>")
      val unrelated = root.resolve(".feature-specs/SKILL-800-legacy/decomposition-manifest.yaml")
      Files.createDirectories(unrelated.parent)
      Files.writeString(unrelated, "contract_version: '0.4'\n")
      val reference =
        when (referenceKind) {
          "absolute" -> parent.toString()
          "relative" -> root.relativize(parent).toString()
          "directory" -> parent.parent.toString()
          "directory-with-instructions" -> "${parent.parent} use current branch as base"
          "bundle-key" -> "SKILL-901-goal"
          else -> "SKILL-901"
        }
      val launcher = StoppedPlanningLauncher(fixture.dbPath)
      val git =
        object : WorkflowGitOperations by GoalTestWorkflowGitOperations {
          override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
            WorkflowGitOperationResult.Ok(value = "feat/SKILL-900-foundation")
        }
      val context = fixture.context(launcher = launcher, workflowGitOperations = git).copy(repositoryRoot = root)
      val command =
        listOf("--db", fixture.dbPath.toString(), reference, "--agent", "codex", "--repo-root", root.toString())
      try {
        repeat(2) {
          val result = CliRuntime.run(command, context)
          assertEquals(3, result.exitCode, result.stderr + result.stdout)
          assertEquals(original, Files.readString(parent))
          assertEquals("<html>Existing design</html>", Files.readString(design))
          assertFalse(Files.exists(root.resolve(".feature-specs/SKILL-901-intake")))
          val manifest = Files.readString(parent.resolveSibling("decomposition-manifest.yaml"))
          assertContains(manifest, ".feature-specs/SKILL-901-goal/spec.md")
          assertContains(manifest, "feat/SKILL-900-foundation")
          val subtask = parent.resolveSibling("spec_subtask_1_implement-the-requested-change.md")
          assertContains(Files.readString(subtask), "The decomposed goal completes every governed subtask.")
          assertContains(Files.readString(subtask), "[layout](design/layout.html)")
          assertEquals(1, parentCount(fixture.dbPath))
          assertEquals("contract_version: '0.4'\n", Files.readString(unrelated))
        }
        assertTrue(launcher.prompts.isNotEmpty())
      } finally {
        root.toFile().deleteRecursively()
      }
    }
  }

  @Test
  fun `a missing explicit spec path does not become raw requirements`() {
    val fixture = goalFixture(subtaskCount = 0, seedWorkflow = false)
    val root = fixture.tempDir
    val missing = root.resolve(".feature-specs/SKILL-902-missing/spec.md")
    val launcher = StoppedPlanningLauncher(fixture.dbPath)
    try {
      val result =
        CliRuntime.run(
          listOf(
            "--db",
            fixture.dbPath.toString(),
            missing.toString(),
            "--agent",
            "codex",
            "--repo-root",
            root.toString(),
          ),
          fixture.context(launcher = launcher).copy(repositoryRoot = root),
        )
      assertEquals(1, result.exitCode, result.stderr + result.stdout)
      assertContains(result.stderr, "supplied spec path")
      assertFalse(Files.exists(root.resolve(".feature-specs/SKILL-902-intake")))
      assertTrue(launcher.prompts.isEmpty())
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun startNewGoal(
    text: String,
    expectedKey: String,
    expectedFeature: String,
  ) {
    val root = Files.createTempDirectory("goal-intake")
    val db = root.resolve("metrics.db")
    val fixture = GoalCliFixture(root, db, root.resolve("unused.md"), emptyList())
    val launcher = StoppedPlanningLauncher(db)
    val command = listOf("--db", db.toString(), text, "--agent", "codex", "--repo-root", root.toString())
    try {
      val first = CliRuntime.run(command, fixture.context(launcher = launcher).copy(repositoryRoot = root))
      assertEquals(3, first.exitCode, first.stderr + first.stdout)
      val folder =
        Files.list(root.resolve(".feature-specs")).use { paths ->
          paths.findFirst().orElseThrow().fileName.toString()
        }
      val (key, feature) = issueAndFeature(folder)
      assertEquals(expectedKey, key)
      assertEquals(expectedFeature, feature)
      assertContains(first.stdout, "goal $key:")
      assertFalse(first.stdout.contains("No decomposed parent workflow"))
      assertTrue(launcher.prompts.isNotEmpty())
      assertTrue(launcher.prompts.all { it.contains("Phase: preplan") })
      val spec = root.resolve(".feature-specs/$folder/spec.md")
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

  private fun refuseNewGoal(
    text: String,
    message: String,
  ) {
    val root = Files.createTempDirectory("goal-intake")
    val db = root.resolve("metrics.db")
    val fixture = GoalCliFixture(root, db, root.resolve("unused.md"), emptyList())
    val launcher = StoppedPlanningLauncher(db)
    val command = listOf("--db", db.toString(), text, "--agent", "codex", "--repo-root", root.toString())
    try {
      val result = CliRuntime.run(command, fixture.context(launcher = launcher).copy(repositoryRoot = root))
      assertEquals(1, result.exitCode, result.stderr + result.stdout)
      assertContains(result.stderr, message)
      assertFalse(result.stderr.contains("invalid", ignoreCase = true), result.stderr)
      assertFalse(result.stdout.contains("launched runtime"), result.stdout)
      val specs = root.resolve(".feature-specs")
      assertTrue(!Files.exists(specs) || Files.list(specs).use { it.count() } == 0L)
      assertTrue(launcher.prompts.isEmpty())
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
