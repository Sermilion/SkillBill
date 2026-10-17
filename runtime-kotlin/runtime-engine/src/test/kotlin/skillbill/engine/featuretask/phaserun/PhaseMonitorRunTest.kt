package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.SlotBaselineSqlite
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.phaseIdFromPrompt
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
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
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
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
  private var editSourceOnRepair = true
  private val launcher =
    RuntimeRecordingLauncher { request ->
      val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
      if (phaseId == MONITOR_FIX && editSourceOnRepair) {
        val readme = repoRoot.resolve("README.md")
        Files.writeString(readme, Files.readString(readme) + "repair\n")
      }
      facts(validJsonOutput(phaseId))
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

    val result = phaseEntry.runMonitor("SKILL-904")

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertEquals(listOf(COMMIT_PUSH, MONITOR), result.completedStepIds)
    assertTrue(result.value.orEmpty().contains("CI passed on branch '$FEATURE_BRANCH'"), result.value)
    assertEquals(emptyList(), launcher.requests)
    assertEquals(headBefore, git(repoRoot, "rev-parse", "HEAD"))
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `monitor observes three published repair failures before blocking`() {
    initFeatureBranch()
    val failing =
      PullRequestChecksLookup { _, _ ->
        PullRequestChecks.Reported(
          listOf(
            PullRequestCheck("validate", CheckBucket.FAIL, "https://ci.example/run"),
            PullRequestCheck("integration", CheckBucket.PENDING, "https://ci.example/integration"),
          ),
        )
      }

    val result = entry(checks = failing).runMonitor("SKILL-904")

    val blocked = assertIs<PhaseRunResult.Blocked>(result, result.toString())
    assertTrue(blocked.reason.contains("after 3 CI-confirmed failed repair commit(s)"), blocked.reason)
    assertTrue(blocked.reason.contains("validate"), blocked.reason)
    assertTrue(blocked.reason.contains("https://ci.example/run"), blocked.reason)
    assertTrue(blocked.reason.contains("CI confirmed a failed published repair commit"), blocked.reason)
    assertEquals(List(3) { MONITOR_FIX }, launchedPhaseIds())
  }

  @Test
  fun `a completed repair without a source change blocks without claiming a failed repair commit`() {
    initFeatureBranch()
    editSourceOnRepair = false
    val result =
      entry(checks = { _, _ ->
        PullRequestChecks.Reported(listOf(PullRequestCheck("validate", CheckBucket.FAIL, "https://ci.example/run")))
      }).runMonitor("SKILL-904")

    val blocked = assertIs<PhaseRunResult.Blocked>(result)
    assertTrue("did not publish a source or merge change" in blocked.reason, blocked.reason)
    assertTrue("CI-confirmed failed repair" !in blocked.reason, blocked.reason)
    assertEquals(listOf(MONITOR_FIX), launchedPhaseIds())
  }

  @Test
  fun `a merge conflict starts repair without spending a CI failure attempt`() {
    initFeatureBranch()
    var identityCalls = 0

    val result =
      entry(
        identity = { _, _ ->
          identityCalls += 1
          PullRequestIdentity.Found(
            PULL_REQUEST_URL,
            7,
            conflicting = identityCalls <= 2,
            headSha = git(repoRoot, "rev-parse", "HEAD"),
          )
        },
        checks = { _, _ -> PullRequestChecks.Reported(listOf(PullRequestCheck("validate", CheckBucket.PASS, ""))) },
      ).runMonitor("SKILL-904")

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertEquals(listOf(MONITOR_FIX), launchedPhaseIds())
    assertTrue(
      launcher.requests.all { request ->
        "conflicts with 'main'" in request.skillRunRequest.promptOverride.orEmpty()
      },
    )
  }

  @Test
  fun `a conflict after the third repair remains unjudged until the merged repair fails CI`() {
    initFeatureBranch()
    var checkCalls = 0
    var identityCalls = 0
    val checks =
      PullRequestChecksLookup { _, _ ->
        checkCalls += 1
        PullRequestChecks.Reported(listOf(PullRequestCheck("validate", CheckBucket.FAIL, "https://ci.example/run")))
      }
    val identity: PullRequestIdentityLookup = { _, _ ->
      identityCalls += 1
      val conflicting = checkCalls == 3 && identityCalls in 7..8
      PullRequestIdentity.Found(
        PULL_REQUEST_URL,
        7,
        conflicting = conflicting,
        headSha = git(repoRoot, "rev-parse", "HEAD"),
      )
    }

    val result = entry(identity = identity, checks = checks).runMonitor("SKILL-904")

    val blocked = assertIs<PhaseRunResult.Blocked>(result, result.toString())
    assertTrue(blocked.reason.contains("after 3 CI-confirmed failed repair commit(s)"), blocked.reason)
    assertTrue(blocked.reason.contains("https://ci.example/run"), blocked.reason)
    assertTrue(!blocked.reason.contains("conflicts with 'main'"), blocked.reason)
    assertEquals(4, checkCalls)
    assertEquals(List(4) { MONITOR_FIX }, launchedPhaseIds())
  }

  @Test
  fun `repair prompt requires CI investigation and blocks unresolved local passes`() {
    initFeatureBranch()
    val failing =
      PullRequestChecksLookup { _, _ ->
        PullRequestChecks.Reported(
          listOf(PullRequestCheck("validate", CheckBucket.FAIL, "https://ci.example/run")),
        )
      }

    entry(checks = failing).runMonitor("SKILL-904")

    val prompt = launcher.requests.first().skillRunRequest.promptOverride.orEmpty()
    assertTrue("Run the full project validation" in prompt, prompt)
    assertTrue("https://ci.example/run" in prompt, prompt)
    assertTrue("read that failing job's logs first" in prompt, prompt)
    assertTrue("--job <job-id> --log-failed" in prompt, prompt)
    assertTrue("Identify the failed command, assertion or error" in prompt, prompt)
    assertTrue("A passing local rerun does not resolve the recorded CI failure" in prompt, prompt)
    assertTrue("investigate timing, concurrency, isolation, and environment differences" in prompt, prompt)
    assertTrue("Do not settle completed with an unchanged tree" in prompt, prompt)
    assertTrue("If investigation cannot establish a repository fix, settle blocked" in prompt, prompt)
    assertTrue("Local validation is not a claim that CI passed" in prompt, prompt)
    assertTrue("The runtime commits and pushes a completed repair" in prompt, prompt)
    assertTrue(
      prompt.indexOf("Monitor repair completion requires") > prompt.indexOf("Run the full project validation"),
      prompt,
    )
    assertTrue("block only for an external obstacle" !in prompt, prompt)
    assertTrue("## Validation ownership" !in prompt, prompt)
  }

  @Test
  fun `goal monitor publishes repairs before rechecking CI without reopening completed phases`() {
    initFeatureBranch()
    val repairLauncher = repairLauncher { Files.writeString(repoRoot.resolve("ci-fix.txt"), "Fix CI\n") }
    var observations = 0
    val checks =
      PullRequestChecksLookup { _, _ ->
        observations += 1
        if (observations > 1) {
          assertEquals(listOf(MONITOR_FIX), launchedPhaseIds(repairLauncher))
          assertEquals("Fix CI", git(origin, "show", "$FEATURE_BRANCH:ci-fix.txt"))
          assertEquals(git(repoRoot, "rev-parse", "HEAD"), git(origin, "rev-parse", FEATURE_BRANCH))
        }
        PullRequestChecks.Reported(
          listOf(
            PullRequestCheck(
              "validate",
              if (observations == 1) CheckBucket.FAIL else CheckBucket.PASS,
              "https://ci.example/run",
            ),
            PullRequestCheck(
              "integration",
              if (observations == 1) CheckBucket.PENDING else CheckBucket.PASS,
              "https://ci.example/integration",
            ),
          ),
        )
      }
    val outcomes = RecordingOutcomeStore()
    val monitor = DefaultGoalRunnerCiMonitor(entry(checks = checks, agentLauncher = repairLauncher), outcomes, clock)
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
    assertEquals(listOf(MONITOR_FIX), launchedPhaseIds(repairLauncher))
    assertEquals(2, observations)
    assertTrue(outcomes.progressEvents.isNotEmpty())
    assertTrue(outcomes.progressEvents.all { it.workflowId == "goal-parent" })
    assertTrue(outcomes.progressEvents.any { it.stepId == MONITOR_FIX })
    assertEquals(
      1,
      outcomes.progressEvents.count {
        it.stepId == COMMIT_PUSH && it.eventKind == GoalProgressEventKind.PHASE_STARTED
      },
    )
    assertEquals(emptyList(), SlotBaselineSqlite.rows(database.resolveDbPath(), "standalone_phase_status"))
  }

  @Test
  fun `a rejected repair push blocks before CI can report success`() {
    initFeatureBranch()
    var publishedHead: String? = null
    val repairLauncher =
      repairLauncher {
        publishedHead = git(origin, "rev-parse", FEATURE_BRANCH)
        Files.writeString(repoRoot.resolve("ci-fix.txt"), "Fix CI\n")
        val hook = origin.resolve("hooks/pre-receive")
        Files.writeString(hook, "#!/bin/sh\nexit 1\n")
        assertTrue(hook.toFile().setExecutable(true))
      }
    var observations = 0
    val checks =
      PullRequestChecksLookup { _, _ ->
        observations += 1
        PullRequestChecks.Reported(listOf(PullRequestCheck("validate", CheckBucket.FAIL, "https://ci.example/run")))
      }

    val result = entry(checks = checks, agentLauncher = repairLauncher).runMonitor("SKILL-904")

    val blocked = assertIs<PhaseRunResult.Blocked>(result, result.toString())
    assertTrue(blocked.reason.contains("Could not push branch"), blocked.reason)
    assertEquals(1, observations)
    assertEquals(listOf(MONITOR_FIX), launchedPhaseIds(repairLauncher))
    assertEquals(publishedHead, git(origin, "rev-parse", FEATURE_BRANCH))
  }

  @Test
  fun `goal monitoring completes for a merged pull request without repairing failing CI`() {
    initFeatureBranch()
    val result =
      entry(
        identity = { _, _ -> PullRequestIdentity.Merged(PULL_REQUEST_URL, 7) },
        checks = { _, _ -> error("Merged pull requests need no CI observation.") },
      ).runForGoal(monitorRequest("SKILL-904"), "goal-parent", FEATURE_BRANCH)

    assertIs<PhaseRunResult.Completed>(result)
    assertTrue(result.value.orEmpty().contains("was merged"), result.value)
    assertEquals(emptyList(), launcher.requests)
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

  private fun PhaseRunEntry.runMonitor(intake: String): PhaseRunResult =
    runForGoal(monitorRequest(intake), "goal-parent", FEATURE_BRANCH)

  private fun repairLauncher(repair: () -> Unit): RuntimeRecordingLauncher =
    RuntimeRecordingLauncher { request ->
      val phase = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
      if (phase == MONITOR_FIX) repair()
      facts(validJsonOutput(phase))
    }

  private fun launchedPhaseIds(agentLauncher: RuntimeRecordingLauncher = launcher): List<String> =
    agentLauncher.requests.map { request -> phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride)) }

  private fun entry(
    identity: PullRequestIdentityLookup = { _, _ ->
      PullRequestIdentity.Found(PULL_REQUEST_URL, 7, headSha = git(repoRoot, "rev-parse", "HEAD"))
    },
    checks: PullRequestChecksLookup = PassingPullRequestChecksLookup,
    agentLauncher: RuntimeRecordingLauncher = launcher,
  ): PhaseRunEntry {
    val config =
      RuntimeHarnessConfig(
        seedDurableWorkflow = false,
        repoRoot = repoRoot,
        launcher = agentLauncher,
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
