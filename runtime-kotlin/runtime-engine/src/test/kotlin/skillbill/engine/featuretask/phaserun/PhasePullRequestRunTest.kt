package skillbill.engine.featuretask.phaserun

import skillbill.engine.RuntimeHarnessConfig
import skillbill.engine.RuntimeRecordingLauncher
import skillbill.engine.facts
import skillbill.engine.phaseIdFromPrompt
import skillbill.engine.telemetryRunnerHarness
import skillbill.engine.validJsonOutput
import skillbill.error.featuretask.PullRequestBranchRefusedError
import skillbill.infrastructure.workflow.git.workflow.GitWorkflowGitOperations
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PhasePullRequestRunTest {
  private val root: Path = Files.createTempDirectory("skillbill-phase-pr")
  private val origin: Path = root.resolve("origin.git")
  private val repoRoot: Path = root.resolve("repo")
  private val home: Path = Files.createTempDirectory("skillbill-phase-pr-home")
  private val clock: Clock = Clock.systemUTC()
  private val database = phaseRunDatabase(home, clock)
  private val launcher =
    RuntimeRecordingLauncher { request ->
      facts(validJsonOutput(phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))))
    }

  @AfterTest
  fun cleanUp() {
    root.toFile().deleteRecursively()
    home.toFile().deleteRecursively()
  }

  @Test
  fun `pr pushes a feature branch that is ahead of origin and leaves the dirty worktree uncommitted`() {
    initRepoWithOrigin()
    git(repoRoot, "checkout", "-b", FEATURE_BRANCH)
    commitFile("Feature.kt", "class Feature\n")
    git(repoRoot, "push", "-u", "origin", FEATURE_BRANCH)
    commitFile("Feature.kt", "class Feature(val ready: Boolean)\n")
    Files.writeString(repoRoot.resolve(DIRTY_FILE), "scratch\n")
    val headBefore = git(repoRoot, "rev-parse", "HEAD")

    val result = entry().run(prRequest())

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertEquals(listOf(PR), launchedPhaseIds())
    val prompt = requireNotNull(launcher.requests.single().skillRunRequest.promptOverride)
    assertTrue("for issue SKILL-903." in prompt, "the pr prompt must carry the branch's issue key")
    assertEquals(headBefore, git(repoRoot, "rev-parse", "HEAD"), "HEAD must not move")
    assertEquals(headBefore, git(origin, "rev-parse", FEATURE_BRANCH), "origin must hold the local HEAD")
    assertTrue("?? $DIRTY_FILE" in git(repoRoot, "status", "--porcelain"), "the dirty file must stay uncommitted")
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `pr on main is refused before pushing or launching`() {
    initRepoWithOrigin()
    val originMainBefore = git(origin, "rev-parse", MAIN)
    commitFile("Feature.kt", "class Feature\n")

    assertFailsWith<PullRequestBranchRefusedError> { entry().run(prRequest()) }

    assertEquals(originMainBefore, git(origin, "rev-parse", MAIN), "nothing may be pushed")
    assertEquals(emptyList(), launcher.requests)
    database.assertNoDurableWorkflowState()
  }

  private fun initRepoWithOrigin() {
    git(root, "init", "--bare", "--initial-branch=$MAIN", origin.toString())
    git(root, "init", "--initial-branch=$MAIN", repoRoot.toString())
    git(repoRoot, "config", "user.email", "phase-pr@example.com")
    git(repoRoot, "config", "user.name", "Phase PR")
    git(repoRoot, "remote", "add", "origin", origin.toString())
    commitFile("README.md", "readme\n")
    git(repoRoot, "push", "-u", "origin", MAIN)
  }

  private fun commitFile(
    relative: String,
    content: String,
  ) {
    Files.writeString(repoRoot.resolve(relative), content)
    git(repoRoot, "add", relative)
    git(repoRoot, "commit", "-m", "Change $relative")
  }

  private fun git(
    workDir: Path,
    vararg args: String,
  ): String {
    val process =
      ProcessBuilder(listOf("git", "-C", workDir.toString()) + args.toList())
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "git ${args.joinToString(" ")} failed with $exitCode: $output" }
    return output
  }

  private fun prRequest(): PhaseRunRequest =
    PhaseRunRequest(definitionId = SkeletonDefinition.PR.id, repoRoot = repoRoot, invokedAgentId = "claude")

  private fun launchedPhaseIds(): List<String> =
    launcher.requests.map { request -> phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride)) }

  private fun entry(): PhaseRunEntry {
    val runner =
      telemetryRunnerHarness(
        runtimeConfig =
          RuntimeHarnessConfig(
            repoRoot = repoRoot,
            launcher = launcher,
            pullRequestIdentityLookup = PullRequestIdentityLookup { _, _ -> PullRequestIdentity.Absent },
            gitOperationsOverride = GitWorkflowGitOperations(),
          ),
        databaseFactory = { database },
      ).runner
    return phaseRunEntry(runner, database, clock)
  }

  private companion object {
    const val MAIN = "main"
    const val PR = "pr"
    const val FEATURE_BRANCH = "feat/SKILL-903-phase-pr"
    const val DIRTY_FILE = "scratch.txt"
  }
}
