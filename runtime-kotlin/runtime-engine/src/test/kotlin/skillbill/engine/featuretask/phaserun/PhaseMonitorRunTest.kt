package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.SlotBaselineSqlite
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.phaseIdFromPrompt
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.slot.OpenPullRequestIdentityLookup
import skillbill.engine.featuretask.slot.PassingPullRequestChecksLookup
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.monitoring.DefaultGoalRunnerCiMonitor
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.ports.goalrunner.runner.PullRequestChecksLookup
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PhaseMonitorRunTest {
  private val root: Path = Files.createTempDirectory("skillbill-phase-monitor")
  private val origin: Path = root.resolve("origin.git")
  private val repoRoot: Path = root.resolve("repo")
  private val home: Path = Files.createTempDirectory("skillbill-phase-monitor-home")
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
  fun `monitor watches the checked-out branch's pull request without committing or launching an agent`() {
    initFeatureBranch()
    val phaseEntry = entry()
    git(repoRoot, "add", "-A")
    git(repoRoot, "commit", "-m", "Commit harness-seeded files")
    git(repoRoot, "push")
    val headBefore = git(repoRoot, "rev-parse", "HEAD")

    val result = phaseEntry.run(monitorRequest("SKILL-904"))

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertEquals(listOf(COMMIT_PUSH, MONITOR), result.completedStepIds)
    assertTrue(result.value.orEmpty().contains("CI passed on branch '$FEATURE_BRANCH'"), result.value)
    assertEquals(emptyList(), launcher.requests)
    assertEquals(headBefore, git(repoRoot, "rev-parse", "HEAD"))
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `monitor finishes and reports when the branch has no open pull request`() {
    initFeatureBranch()

    val result =
      entry(identity = { _, _ -> PullRequestIdentity.Absent }).run(monitorRequest(PULL_REQUEST_URL))

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertEquals(listOf(COMMIT_PUSH, MONITOR), result.completedStepIds)
    assertTrue(result.value.orEmpty().contains("No open pull request was found"), result.value)
    assertEquals(emptyList(), launcher.requests)
  }

  @Test
  fun `monitor blocks with the failing checks after three fix attempts`() {
    initFeatureBranch()
    val failing =
      PullRequestChecksLookup { _, _ ->
        PullRequestChecks.Reported(listOf(PullRequestCheck("validate", CheckBucket.FAIL, "https://ci.example/run")))
      }

    val result = entry(checks = failing).run(monitorRequest("SKILL-904"))

    val blocked = assertIs<PhaseRunResult.Blocked>(result, result.toString())
    assertTrue(blocked.reason.contains("after 3 fix attempt(s)"), blocked.reason)
    assertTrue(blocked.reason.contains("validate"), blocked.reason)
    assertEquals(List(3) { MONITOR_FIX }, launchedPhaseIds())
  }

  @Test
  fun `goal monitor reuses CI repair and does not publish a separate standalone execution`() {
    initFeatureBranch()
    var observations = 0
    val checks =
      PullRequestChecksLookup { _, _ ->
        observations += 1
        PullRequestChecks.Reported(
          listOf(
            PullRequestCheck(
              "validate",
              if (observations == 1) CheckBucket.FAIL else CheckBucket.PASS,
              "https://ci.example/run",
            ),
          ),
        )
      }
    val outcomes = RecordingOutcomeStore()
    val monitor = DefaultGoalRunnerCiMonitor(entry(checks = checks), outcomes, clock)
    val result =
      monitor.monitor(
        GoalRunnerManifestState(
          "goal-parent",
          database.resolveDbPath().toString(),
          manifest(1).copy(featureBranch = FEATURE_BRANCH),
        ),
        GoalRunnerRunRequest("SKILL-56", repoRoot, "claude"),
      )

    assertIs<PhaseRunResult.Completed>(result)
    assertEquals(listOf(MONITOR_FIX), launchedPhaseIds())
    assertEquals(2, observations)
    assertTrue(outcomes.progressEvents.isNotEmpty())
    assertTrue(outcomes.progressEvents.all { it.workflowId == "goal-parent" })
    assertTrue(outcomes.progressEvents.any { it.stepId == MONITOR_FIX })
    assertEquals(emptyList(), SlotBaselineSqlite.rows(database.resolveDbPath(), "standalone_phase_status"))
  }

  @Test
  fun `goal monitor cannot succeed without its published pull request or on another branch`() {
    initFeatureBranch()
    val missing =
      entry(identity = { _, _ -> PullRequestIdentity.Absent }).runForGoal(
        monitorRequest("SKILL-904"),
        "goal-parent",
        FEATURE_BRANCH,
      )
    assertIs<PhaseRunResult.Blocked>(missing)
    assertTrue(missing.reason.contains("No open pull request"))

    val wrongBranch = entry().runForGoal(monitorRequest("SKILL-904"), "goal-parent", "feat/other")
    assertIs<PhaseRunResult.Blocked>(wrongBranch)
    assertTrue(wrongBranch.reason.contains("requires branch"))
    assertEquals(emptyList(), launcher.requests)
  }

  private fun initFeatureBranch() {
    git(root, "init", "--bare", "--initial-branch=main", origin.toString())
    git(root, "init", "--initial-branch=main", repoRoot.toString())
    git(repoRoot, "config", "user.email", "phase-monitor@example.com")
    git(repoRoot, "config", "user.name", "Phase Monitor")
    git(repoRoot, "config", "commit.gpgsign", "false")
    git(repoRoot, "remote", "add", "origin", origin.toString())
    Files.writeString(repoRoot.resolve("README.md"), "readme\n")
    git(repoRoot, "add", "README.md")
    git(repoRoot, "commit", "-m", "Initial commit")
    git(repoRoot, "push", "-u", "origin", "main")
    git(repoRoot, "checkout", "-b", FEATURE_BRANCH)
    git(repoRoot, "push", "-u", "origin", FEATURE_BRANCH)
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

  private fun monitorRequest(intake: String): PhaseRunRequest =
    PhaseRunRequest(
      definitionId = SkeletonDefinition.MONITOR.id,
      repoRoot = repoRoot,
      invokedAgentId = "claude",
      intake = intake,
    )

  private fun launchedPhaseIds(): List<String> =
    launcher.requests.map { request -> phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride)) }

  private fun entry(
    identity: PullRequestIdentityLookup = OpenPullRequestIdentityLookup,
    checks: PullRequestChecksLookup = PassingPullRequestChecksLookup,
  ): PhaseRunEntry {
    val config =
      RuntimeHarnessConfig(
        seedDurableWorkflow = false,
        repoRoot = repoRoot,
        launcher = launcher,
        gitOperationsOverride = GitWorkflowGitOperations(),
        monitoredPullRequestLookup = identity,
        pullRequestChecksLookup = checks,
      )
    val harness =
      telemetryRunnerHarness(
        runtimeConfig = config,
        databaseFactory = { database },
      )
    return phaseRunEntry(harness.strategies, config.harnessGitOperations, database, clock, harness.runLoopEntry)
  }

  private companion object {
    const val COMMIT_PUSH = "commit_push"
    const val MONITOR = "monitor"
    const val MONITOR_FIX = "monitor_fix"
    const val FEATURE_BRANCH = "feat/SKILL-904-phase-monitor"
    const val PULL_REQUEST_URL = "https://github.com/example/repo/pull/7"
  }
}
