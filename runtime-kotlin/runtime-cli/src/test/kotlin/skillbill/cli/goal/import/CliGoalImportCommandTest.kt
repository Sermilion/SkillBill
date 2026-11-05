package skillbill.cli.goal.import

import skillbill.cli.NoopGoalTestAgentRunLauncher
import skillbill.cli.core.CliRuntime
import skillbill.cli.goalFixture
import skillbill.cli.model.CliRuntimeContext
import skillbill.contracts.SharedPayloadKeys
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CliGoalImportCommandTest {
  @Test
  fun `head mismatch refused import exits one and reports refusal details`() {
    val source = goalFixture(subtaskCount = 1)
    checkout(source.tempDir, "feat/SKILL-901-goal")
    val archive = source.tempDir.resolve("goal-archive.zip")
    val sourceContext =
      source.context(launcher = NoopGoalTestAgentRunLauncher)
        .copy(workflowGitOperations = null)
    val exported =
      CliRuntime.run(
        listOf(
          "--db", source.dbPath.toString(),
          "goal", "export", "SKILL-901",
          "--output", archive.toString(),
          "--repo-root", source.tempDir.toString(),
        ),
        sourceContext,
      )
    assertEquals(0, exported.exitCode, exported.stderr + exported.stdout)
    assertEquals("feat/SKILL-901-goal", git(source.tempDir, "branch", "--show-current"))
    val commitSha = git(source.tempDir, "rev-parse", "HEAD")

    val destination = Files.createTempDirectory("goal-import-destination")
    initializeRepository(destination)
    val destinationContext =
      CliRuntimeContext(
        dbPathOverride = destination.resolve("runtime.db").toString(),
        userHome = destination,
        repositoryRoot = destination,
      )
    val arguments =
      listOf(
        "--db",
        destination.resolve("runtime.db").toString(),
        "goal",
        "import",
        archive.toString(),
        "--repo-root",
        destination.toString(),
      )

    val result = CliRuntime.run(arguments, destinationContext)

    assertEquals(1, result.exitCode, result.stderr + result.stdout)
    assertContains(result.stdout, "Current branch 'main' is not the recorded feature branch")
    assertContains(result.stdout, "'feat/SKILL-901-goal' at commit '$commitSha'; import refused.")
    val payload = assertNotNull(result.payload)
    assertEquals("refused", payload[SharedPayloadKeys.STATUS])
    assertContains(payload[GoalRunnerImportPayloadKeys.REFUSAL_REASON].toString(), "Current branch 'main'")
    assertEquals("feat/SKILL-901-goal", payload[GoalRunnerImportPayloadKeys.FEATURE_BRANCH])
    assertEquals(commitSha, payload[GoalRunnerImportPayloadKeys.COMMIT_SHA])

    assertContains(result.stdout, "Import refused for SKILL-901")
  }
}

private fun initializeRepository(root: Path) {
  git(root, "init", "--quiet", "--initial-branch=main", root.toString())
  Files.writeString(root.resolve("README.md"), "destination checkout")
  git(root, "add", "README.md")
  git(
    root,
    "-c", "user.name=CLI test",
    "-c", "user.email=cli-test@example.invalid",
    "-c", "commit.gpgsign=false",
    "commit", "--quiet", "-m", "destination checkout",
  )
}

private fun checkout(
  repository: Path,
  branch: String,
) {
  git(repository, "checkout", "--quiet", branch)
}

private fun git(
  repository: Path,
  vararg arguments: String,
): String {
  val process =
    ProcessBuilder(listOf("git", *arguments))
      .directory(repository.toFile())
      .redirectErrorStream(true)
      .start()
  val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
  check(process.waitFor() == 0) { output }
  return output
}
