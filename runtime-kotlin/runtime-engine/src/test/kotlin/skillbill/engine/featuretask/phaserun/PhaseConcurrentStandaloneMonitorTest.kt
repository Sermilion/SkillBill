package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.phaserun.monitor.MonitorTargetResolver
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorExecutionPreparer
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.SlotBaselineSqlite
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.phaseIdFromPrompt
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorService
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.infrastructure.workflow.monitor.GitMonitorRepairPublisher
import skillbill.infrastructure.workflow.monitor.GitMonitorResources
import skillbill.ports.agentrun.AgentRunChildDrain
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import skillbill.ports.monitor.MonitorCheckSource
import skillbill.ports.monitor.MonitorProcessInspector
import skillbill.ports.monitor.MonitorPullRequestSource
import skillbill.ports.monitor.model.MonitorBranchTip
import skillbill.ports.monitor.model.MonitorCallerRepository
import skillbill.ports.monitor.model.MonitorFailureLog
import skillbill.ports.monitor.model.MonitorPullRequest
import skillbill.ports.monitor.model.MonitorPullRequestHead
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorPullRequestListing
import skillbill.ports.monitor.model.MonitorPullRequestLookup
import skillbill.ports.monitor.model.MonitorPullRequestState
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import skillbill.ports.monitor.model.MonitorWriteEvidence
import skillbill.ports.process.ShutdownHookPort
import skillbill.ports.process.ShutdownHookRegistration
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PhaseConcurrentStandaloneMonitorTest {
  private val root: Path = Files.createTempDirectory("skillbill-concurrent-monitor").toRealPath()
  private val origin: Path = root.resolve("origin.git")
  private val caller: Path = root.resolve("caller")
  private val harnessRoot: Path = root.resolve("harness")
  private val home: Path = Files.createTempDirectory("skillbill-concurrent-monitor-home")
  private val clock: Clock = Clock.systemUTC()
  private val database = phaseRunDatabase(home, clock)
  private val heads = mutableMapOf<Int, Pull>()
  private val failingHeads = ConcurrentLinkedQueue<String>()
  private val repairCwds = ConcurrentLinkedQueue<Path>()
  private val bothRepairing = CountDownLatch(PULL_REQUESTS.size)

  init {
    git(root, "init", "--bare", "--initial-branch=main", origin.toString())
    git(root, "init", "--initial-branch=main", caller.toString())
    configureIdentity(caller)
    git(caller, "remote", "add", "origin", origin.toString())
    commit(caller, "README.md", "readme\n")
    git(caller, "push", "-q", "origin", "main")
    git(caller, "config", "url.$origin.insteadOf", BASE_URL)
    PULL_REQUESTS.forEach { (number, owner) ->
      val bare = root.resolve("$owner.git")
      git(root, "clone", "-q", "--bare", origin.toString(), bare.toString())
      git(caller, "checkout", "-q", "-B", "work-$number", "main")
      commit(caller, "Feature$number.kt", "class Feature$number\n")
      git(caller, "push", "-q", bare.toString(), "work-$number:refs/heads/$SHARED_BRANCH")
      val sha = git(caller, "rev-parse", "HEAD")
      git(caller, "config", "url.$bare.insteadOf", "https://github.com/$owner/repo.git")
      heads[number] = Pull(number, owner, bare, sha)
      failingHeads.add(sha)
    }
    git(caller, "checkout", "-q", "-B", SHARED_BRANCH, "main")
    Files.writeString(caller.resolve("README.md"), "readme\nlocal edit\n")
    Files.writeString(caller.resolve("scratch.txt"), "untracked\n")
  }

  @AfterTest
  fun cleanUp() {
    root.toFile().deleteRecursively()
    home.toFile().deleteRecursively()
  }

  @Test
  fun `two monitors with one head name from one dirty checkout repair and settle independently`() {
    val callerHead = git(caller, "rev-parse", "HEAD")
    val callerStatus = git(caller, "status", "--porcelain")
    val pool = Executors.newFixedThreadPool(PULL_REQUESTS.size)

    val results =
      try {
        PULL_REQUESTS.keys.map { number -> pool.submit<PhaseRunResult> { monitor(number) } }
          .map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
      } finally {
        pool.shutdownNow()
      }

    results.forEach { result ->
      val completed = assertIs<PhaseRunResult.Completed>(result, result.toString())
      val path = Path.of(completed.targetReport.single { it.startsWith("Worktree: ") }.removePrefix("Worktree: "))
      assertTrue(completed.targetReport.any { it.startsWith("Cleanup: released") }, completed.targetReport.toString())
      assertFalse(Files.exists(path), "standalone monitor left $path")
    }
    assertEquals(PULL_REQUESTS.size, repairCwds.toSet().size, "each pull request repairs in its own worktree")
    repairCwds.forEach { cwd ->
      assertTrue(cwd.startsWith(caller.resolve(".git/skill-bill/standalone-monitor")), "owned worktree, not $cwd")
    }
    heads.values.forEach { pull ->
      val repair = git(pull.bare, "rev-parse", "refs/heads/$SHARED_BRANCH")
      assertEquals(pull.sha, git(pull.bare, "rev-parse", "$repair^"), "one repair commit on ${pull.owner}")
      assertEquals("class Fix${pull.number}", git(pull.bare, "show", "$repair:Fix${pull.number}.kt"))
    }
    assertEquals(callerHead, git(caller, "rev-parse", "HEAD"), "the caller HEAD is untouched")
    assertEquals(callerStatus, git(caller, "status", "--porcelain"), "the caller index and files are untouched")
    assertEquals("untracked", Files.readString(caller.resolve("scratch.txt")).trim())
    val rows = SlotBaselineSqlite.rows(database.resolveDbPath(), "standalone_phase_status")
    assertEquals(
      PULL_REQUESTS.keys.map { "github.com/acme/repo#$it" }.toSet(),
      rows.map { it["monitor_pr_key"] }.toSet(),
      "each pull request owns its own status row",
    )
    assertEquals(setOf(SHARED_BRANCH), rows.map { it["branch_correlation"] }.toSet())
    assertEquals(setOf("terminal"), rows.map { it["lifecycle_state"] }.toSet(), "no row was superseded or shared")
    assertEquals(setOf("released"), rows.map { it["monitor_cleanup_disposition"] }.toSet())
    assertEquals(PULL_REQUESTS.size, rows.map { it["monitor_worktree_path"] }.toSet().size)
  }

  private fun monitor(number: Int): PhaseRunResult {
    val source = PullSource()
    val launcher =
      RuntimeRecordingLauncher { request ->
        val prompt = requireNotNull(request.skillRunRequest.promptOverride)
        val phaseId = phaseIdFromPrompt(prompt)
        if (phaseId == MONITOR_FIX) {
          val cwd = request.skillRunRequest.repoRoot
          repairCwds.add(cwd)
          Files.writeString(cwd.resolve("Fix$number.kt"), "class Fix$number\n")
          bothRepairing.countDown()
          check(bothRepairing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "the other monitor never started repairing" }
          return@RuntimeRecordingLauncher facts(FIXED_REPAIR_OUTPUT)
        }
        facts(validJsonOutput(phaseId))
      }
    val service =
      StandaloneMonitorService(source, PullChecks(source), GitMonitorRepairPublisher(), clock, sleep = {})
    val config =
      RuntimeHarnessConfig(
        seedDurableWorkflow = false,
        repoRoot = harnessRoot,
        launcher = launcher,
        gitOperationsOverride = GitWorkflowGitOperations(),
        standaloneMonitorService = service,
      )
    val harness = telemetryRunnerHarness(runtimeConfig = config, databaseFactory = { database })
    val preparer =
      StandaloneMonitorExecutionPreparer(
        MonitorTargetResolver(source),
        GitMonitorResources(LiveProcessInspector, clock),
        NoopRuntimeDiagnostics,
        IgnoredShutdownHooks,
        AgentRunChildDrain { true },
      )
    val entry =
      PhaseRunEntryFixture(
        harness.strategies,
        config.harnessGitOperations,
        database,
        clock,
        harness.runLoopEntry,
        preparer,
      ).entry()
    return entry.run(
      PhaseRunRequest(
        definitionId = SkeletonDefinition.MONITOR.id,
        repoRoot = caller,
        invokedAgentId = "claude",
        intake = "https://github.com/acme/repo/pull/$number",
      ),
    )
  }

  private data class Pull(
    val number: Int,
    val owner: String,
    val bare: Path,
    val sha: String,
  )

  private inner class PullSource : MonitorPullRequestSource {
    override fun callerRepository(callerRoot: Path) = MonitorCallerRepository.Resolved(ACME)

    override fun pullRequest(
      repository: MonitorRepositoryIdentity,
      number: Int,
    ): MonitorPullRequestLookup {
      val pull = heads[number]
      if (repository != ACME || pull == null) return MonitorPullRequestLookup.Missing
      val tip = git(pull.bare, "rev-parse", "refs/heads/$SHARED_BRANCH")
      val base = git(origin, "rev-parse", "main")
      val url = "https://github.com/${pull.owner}/repo.git"
      val headRepository = MonitorRepositoryIdentity.canonical("github.com", pull.owner, "repo")
      return MonitorPullRequestLookup.Found(
        MonitorPullRequest(
          MonitorPullRequestIdentity(ACME, number),
          MonitorPullRequestState.OPEN,
          MonitorPullRequestHead(
            MonitorBranchTip(headRepository, SHARED_BRANCH, tip),
            url,
            url,
            MonitorWriteEvidence(repositoryPush = false, maintainerCanModify = true),
          ),
          MonitorBranchTip(ACME, "main", base),
        ),
      )
    }

    override fun pullRequests(repository: MonitorRepositoryIdentity) =
      MonitorPullRequestListing.Failed("listing is not scripted")

    override fun pullRequestsForBranch(
      repository: MonitorRepositoryIdentity,
      headRepository: MonitorRepositoryIdentity,
      branch: String,
    ) = MonitorPullRequestListing.Failed("listing is not scripted")
  }

  private inner class PullChecks(private val source: PullSource) : MonitorCheckSource {
    override fun checks(pullRequest: MonitorPullRequestIdentity): PullRequestChecks {
      val found = source.pullRequest(pullRequest.repository, pullRequest.number) as MonitorPullRequestLookup.Found
      val sha = requireNotNull(found.pullRequest.head).tip.sha
      val bucket = if (sha in failingHeads) CheckBucket.FAIL else CheckBucket.PASS
      return PullRequestChecks.Reported(listOf(PullRequestCheck("build", bucket, CHECK_URL)))
    }

    override fun failureLog(
      pullRequest: MonitorPullRequestIdentity,
      check: PullRequestCheck,
    ): MonitorFailureLog = MonitorFailureLog.Available("FeatureTest > ready() FAILED")
  }

  private object LiveProcessInspector : MonitorProcessInspector {
    override fun currentProcess() =
      FeatureTaskRuntimeProcessIdentity("host-1", "boot-1", ProcessHandle.current().pid(), "birth-1")

    override fun inspect(recorded: FeatureTaskRuntimeProcessIdentity) = FeatureTaskRuntimeProcessInspection.NotRunning
  }

  private object IgnoredShutdownHooks : ShutdownHookPort {
    override fun register(action: () -> Unit): ShutdownHookRegistration = ShutdownHookRegistration { true }
  }

  private fun configureIdentity(repository: Path) {
    git(repository, "config", "user.email", "monitor@example.com")
    git(repository, "config", "user.name", "Monitor")
    git(repository, "config", "commit.gpgsign", "false")
  }

  private fun commit(
    repository: Path,
    relative: String,
    content: String,
  ) {
    Files.writeString(repository.resolve(relative), content)
    git(repository, "add", relative)
    git(repository, "commit", "-q", "-m", "Change $relative")
  }

  private fun git(
    workDir: Path,
    vararg args: String,
  ): String {
    val process =
      ProcessBuilder(listOf("git", "-C", workDir.toString()) + args.toList()).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "git ${args.joinToString(" ")} failed with $exitCode: $output" }
    return output
  }

  private companion object {
    const val SHARED_BRANCH = "feat/SKILL-427-shared"
    const val BASE_URL = "https://github.com/acme/repo.git"
    const val CHECK_URL = "https://github.com/acme/repo/actions/runs/1/job/2"
    const val MONITOR_FIX = "monitor_fix"
    const val TIMEOUT_SECONDS = 120L
    val FIXED_REPAIR_OUTPUT =
      """
      {
        "contract_version": "0.7",
        "phase_id": "monitor_fix",
        "status": "completed",
        "summary": "Monitor repair settled.",
        "produced_outputs": {"value": "The build failure was repaired in the owned worktree.\nRepair outcome: fixed"}
      }
      """.trimIndent()
    val PULL_REQUESTS: Map<Int, String> = linkedMapOf(7 to "fork-a", 8 to "fork-b")
    val ACME: MonitorRepositoryIdentity = MonitorRepositoryIdentity.canonical("github.com", "acme", "repo")
  }
}
