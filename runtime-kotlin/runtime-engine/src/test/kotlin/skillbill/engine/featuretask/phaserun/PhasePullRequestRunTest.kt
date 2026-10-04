package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.phaseIdFromPrompt
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.error.featuretask.PullRequestBranchRefusedError
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
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
  fun `pr commits the whole worktree before pushing and launching and a retry adds no empty commit`() {
    initRepoWithOrigin()
    git(repoRoot, "checkout", "-b", FEATURE_BRANCH)
    commitFile("Feature.kt", "class Feature\n")
    git(repoRoot, "push", "-u", "origin", FEATURE_BRANCH)
    commitFile("Feature.kt", "class Feature(val ready: Boolean)\n")
    Files.writeString(repoRoot.resolve(DIRTY_FILE), "scratch\n")
    Files.writeString(repoRoot.resolve("Feature.kt"), "staged\n")
    git(repoRoot, "add", "Feature.kt")
    Files.writeString(repoRoot.resolve("Feature.kt"), "final contents\n")
    Files.delete(repoRoot.resolve("README.md"))
    Files.writeString(repoRoot.resolve(".gitignore"), "ignored.txt\n")
    Files.writeString(repoRoot.resolve("ignored.txt"), "ignored\n")
    Files.createDirectories(repoRoot.resolve(".skill-bill"))
    Files.writeString(repoRoot.resolve(".skill-bill/private.txt"), "runtime private\n")
    val headBefore = git(repoRoot, "rev-parse", "HEAD")

    val result = entry().run(prRequest())

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertEquals(listOf("commit_push", PR), result.completedStepIds)
    assertEquals(listOf(PR), launchedPhaseIds())
    val prompt = requireNotNull(launcher.requests.single().skillRunRequest.promptOverride)
    assertTrue("for issue SKILL-903." in prompt, "the pr prompt must carry the branch's issue key")
    val committed = git(repoRoot, "rev-parse", "HEAD")
    assertEquals(headBefore, git(repoRoot, "rev-parse", "HEAD^"), "one normal commit must be added")
    assertEquals(committed, git(origin, "rev-parse", FEATURE_BRANCH))
    assertEquals("scratch", git(origin, "show", "$FEATURE_BRANCH:$DIRTY_FILE"))
    assertEquals("final contents", git(origin, "show", "$FEATURE_BRANCH:Feature.kt"))
    assertEquals("?? .skill-bill/", git(repoRoot, "status", "--porcelain"))
    assertEquals(
      emptyList(),
      git(repoRoot, "for-each-ref", "refs/skill-bill/checkpoints").lines().filter(String::isNotBlank),
    )
    assertIs<PhaseRunResult.Completed>(entry().run(prRequest()))
    assertEquals(committed, git(repoRoot, "rev-parse", "HEAD"), "retry must not add an empty commit")
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `pr on main is refused before pushing or launching`() {
    initRepoWithOrigin()
    val originMainBefore = git(origin, "rev-parse", MAIN)
    Files.writeString(repoRoot.resolve(DIRTY_FILE), "pending\n")
    val headBefore = git(repoRoot, "rev-parse", "HEAD")

    val phaseEntry = entry()
    val statusBefore = git(repoRoot, "status", "--porcelain")

    assertFailsWith<PullRequestBranchRefusedError> { phaseEntry.run(prRequest()) }

    assertEquals(headBefore, git(repoRoot, "rev-parse", "HEAD"), "nothing may be committed")
    assertEquals(statusBefore, git(repoRoot, "status", "--porcelain"))
    assertEquals(originMainBefore, git(origin, "rev-parse", MAIN), "nothing may be pushed")
    assertEquals(emptyList(), launcher.requests)
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `failed push blocks before pr and retry publishes the existing commit`() {
    initRepoWithOrigin()
    git(repoRoot, "checkout", "-b", FEATURE_BRANCH)
    Files.writeString(repoRoot.resolve(DIRTY_FILE), "pending\n")
    val hook = origin.resolve("hooks/pre-receive")
    Files.writeString(hook, "#!/bin/sh\nexit 1\n")
    hook.toFile().setExecutable(true)

    val result = assertIs<PhaseRunResult.Blocked>(entry().run(prRequest()))

    assertEquals("commit_push", result.stepId)
    assertTrue(result.reason.contains("Could not push"), result.reason)
    assertEquals(emptyList(), launcher.requests)
    val committed = git(repoRoot, "rev-parse", "HEAD")
    Files.delete(hook)
    assertIs<PhaseRunResult.Completed>(entry().run(prRequest()))
    assertEquals(committed, git(repoRoot, "rev-parse", "HEAD"))
    assertEquals(committed, git(origin, "rev-parse", FEATURE_BRANCH))
    database.assertNoDurableWorkflowState()
  }

  private fun initRepoWithOrigin() {
    git(root, "init", "--bare", "--initial-branch=$MAIN", origin.toString())
    git(root, "init", "--initial-branch=$MAIN", repoRoot.toString())
    git(repoRoot, "config", "user.email", "phase-pr@example.com")
    git(repoRoot, "config", "user.name", "Phase PR")
    git(repoRoot, "config", "commit.gpgsign", "false")
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
    val config =
      RuntimeHarnessConfig(
        seedDurableWorkflow = false,
        repoRoot = repoRoot,
        launcher = launcher,
        pullRequestIdentityLookup = PullRequestIdentityLookup { _, _ -> PullRequestIdentity.Absent },
        gitOperationsOverride = GitWorkflowGitOperations(),
      )
    val harness =
      telemetryRunnerHarness(
        runtimeConfig = config,
        databaseFactory = { database },
      )
    return phaseRunEntry(harness.strategies, config.harnessGitOperations, database, clock, harness.runLoopEntry)
  }

  private companion object {
    const val MAIN = "main"
    const val PR = "pr"
    const val FEATURE_BRANCH = "feat/SKILL-903-phase-pr"
    const val DIRTY_FILE = "scratch.txt"
  }
}
