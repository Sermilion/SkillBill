package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.phaserun.monitor.MonitorTargetResolver
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorExecutionPreparer
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorLedger
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoop
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopPlanningBranch
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.phaseIdFromPrompt
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorService
import skillbill.engine.featuretask.slot.monitor.standalone.StandaloneMonitorStrategy
import skillbill.engine.featuretask.slot.state.PhaseCommitStepBinding
import skillbill.engine.featuretask.slot.state.PhaseStandaloneMonitorOperations
import skillbill.engine.featuretask.slot.state.PhaseStandaloneMonitorStepBinding
import skillbill.engine.featuretask.slot.state.PhaseStandalonePublicationStepBinding
import skillbill.engine.featuretask.slot.state.StandaloneRepairWorktree
import skillbill.engine.featuretask.slot.state.step
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.error.core.SkillBillRuntimeException
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
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PhaseStandaloneMonitorExecutionTest {
  private val root: Path = Files.createTempDirectory("skillbill-standalone-monitor-run").toRealPath()
  private val origin: Path = root.resolve("origin.git")
  private val fork: Path = root.resolve("fork.git")
  private val caller: Path = root.resolve("caller")
  private val home: Path = Files.createTempDirectory("skillbill-standalone-monitor-run-home")
  private val clock: Clock = Clock.systemUTC()
  private val database = phaseRunDatabase(home, clock)
  private val failingHeads = mutableSetOf<String>()
  private val repairCwds = mutableListOf<Path>()
  private val repairPrompts = mutableListOf<String>()
  private var duringRepair: (Path) -> Unit = {}
  private var reportedBase: String? = null
  private var repairCount = 0
  private var repairOutcome = FIXED
  private var pullRequestTitle = "title 0"
  private var repairEdits: ((Int) -> String)? = null
  private val launcher =
    RuntimeRecordingLauncher { request ->
      val phaseId = phaseIdFromPrompt(requireNotNull(request.skillRunRequest.promptOverride))
      if (phaseId == MONITOR_FIX) {
        val cwd = request.skillRunRequest.repoRoot
        repairCwds.add(cwd)
        repairPrompts.add(requireNotNull(request.skillRunRequest.promptOverride))
        repairCount += 1
        val editTitle = repairEdits
        if (editTitle != null) {
          pullRequestTitle = editTitle(repairCount)
        } else {
          Files.writeString(cwd.resolve("Fix.kt"), "class Fix$repairCount\n")
        }
        duringRepair(cwd)
        return@RuntimeRecordingLauncher facts(repairOutput(repairOutcome))
      }
      facts(validJsonOutput(phaseId))
    }
  private val baseSha: String
  private val headSha: String

  init {
    git(root, "init", "--bare", "--initial-branch=main", origin.toString())
    git(root, "init", "--initial-branch=main", caller.toString())
    configureIdentity(caller)
    git(caller, "remote", "add", "origin", origin.toString())
    commit(caller, "README.md", "readme\n")
    git(caller, "push", "-q", "origin", "main")
    baseSha = git(caller, "rev-parse", "HEAD")
    git(root, "clone", "-q", "--bare", origin.toString(), fork.toString())
    git(caller, "checkout", "-q", "-b", FEATURE_BRANCH)
    commit(caller, "Feature.kt", "class Feature\n")
    git(caller, "push", "-q", fork.toString(), FEATURE_BRANCH)
    headSha = git(caller, "rev-parse", "HEAD")
    git(caller, "checkout", "-q", "main")
    git(caller, "config", "url.$origin.insteadOf", BASE_URL)
    git(caller, "config", "url.$fork.insteadOf", FORK_URL)
  }

  @AfterTest
  fun cleanUp() {
    root.toFile().deleteRecursively()
    home.toFile().deleteRecursively()
  }

  @Test
  fun `a failing head is repaired in the owned worktree and the repair lands on the fork head only`() {
    failingHeads += headSha
    val callerHead = git(caller, "rev-parse", "HEAD")

    val result = run()

    val completed = assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertOwnedWorktreeRemoved(completed.targetReport)
    assertEquals(COMMIT_PUSH, completed.completedStepIds.first(), "the publication step runs first as a no-op")
    val repair = forkHead()
    assertEquals(headSha, git(fork, "rev-parse", "$repair^"), "exactly one normal repair commit on the fork head")
    assertEquals("class Fix1", git(fork, "show", "$repair:Fix.kt"))
    assertEquals(listOf("refs/heads/$FEATURE_BRANCH", "refs/heads/main"), refs(fork), "no temporary remote branch")
    assertEquals(listOf("refs/heads/main"), refs(origin), "the base repository is never pushed to")
    assertEquals(baseSha, git(origin, "rev-parse", "main"))
    val cwd = repairCwds.single()
    assertTrue(
      cwd.startsWith(caller.resolve(".git/skill-bill/standalone-monitor")),
      "the repair must run in the owned worktree, not $cwd",
    )
    assertEquals(callerHead, git(caller, "rev-parse", "HEAD"), "the caller checkout is untouched")
    assertEquals("main", git(caller, "branch", "--show-current"))
    assertEquals("", git(caller, "status", "--porcelain", "--untracked-files=no"))
    val prompt = repairPrompts.single()
    listOf(PULL_REQUEST_URL, headSha, baseSha, cwd.toString(), "build", "assertion failed in FeatureTest").forEach {
      assertTrue(it in prompt, "the repair brief must carry $it")
    }
  }

  @Test
  fun `a repair the agent reports unresolved is never committed or published and its edit is retained`() {
    assertUnpublishedRepair(UNRESOLVED)
  }

  @Test
  fun `a repair whose prose names no outcome fails closed and is never published`() {
    assertUnpublishedRepair("Local checks pass.")
  }

  private fun assertUnpublishedRepair(outcome: String) {
    failingHeads += headSha
    repairOutcome = outcome

    val result = run()

    val blocked = assertIs<PhaseRunResult.Blocked>(result, result.toString())
    assertEquals(MONITOR_FIX, blocked.stepId)
    assertTrue("did not report a fixed repair" in blocked.reason, blocked.reason)
    assertTrue(repairCwds.single().toString() in blocked.reason, "the preserved work must be named: ${blocked.reason}")
    assertEquals(headSha, forkHead(), "an unresolved repair must not reach the pull request head")
    val worktree = repairCwds.single()
    assertEquals(headSha, git(worktree, "rev-parse", "HEAD"), "an unresolved repair must not be committed")
    assertEquals("class Fix1", Files.readString(worktree.resolve("Fix.kt")).trim(), "the attempted edit is retained")
    assertTrue("Pull request: $PULL_REQUEST_URL" in blocked.targetReport, blocked.targetReport.toString())
    assertTrue("Worktree: $worktree" in blocked.targetReport, blocked.targetReport.toString())
    val cleanup = blocked.targetReport.single { it.startsWith("Cleanup:") }
    assertTrue(cleanup.startsWith("Cleanup: retained (untracked_files)") && worktree.toString() in cleanup, cleanup)
  }

  @Test
  fun `a retained repair blocks retry with a persisted attempt on the pull request branch before launching an agent`() {
    failingHeads += headSha
    repairOutcome = UNRESOLVED
    assertIs<PhaseRunResult.Blocked>(run())
    val launches = launcher.requests.size

    val result = assertIs<PhaseRunResult.Blocked>(run())

    assertTrue("retained" in result.reason, result.reason)
    assertEquals(launches, launcher.requests.size)
    val record = assertNotNull(database.read { it.standalonePhaseStatuses.readByInvocation(result.invocationId) })
    assertEquals("blocked", record.lifecycleState)
    assertEquals("monitor", record.currentStep)
    assertEquals(result.reason, record.terminalResult)
    assertEquals(FEATURE_BRANCH, record.branchCorrelation)
    assertEquals("main", git(caller, "branch", "--show-current"))
    val target = assertNotNull(record.monitorTarget)
    assertEquals(PULL_REQUEST_URL, target.pullRequestUrl)
    assertNull(target.worktreePath)
    assertTrue(Files.exists(repairCwds.single()))
  }

  @Test
  fun `the initial publication visit leaves the fork untouched when CI already passes`() {
    val result = run()

    val completed = assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertOwnedWorktreeRemoved(completed.targetReport)
    assertEquals(headSha, forkHead())
    assertEquals(emptyList(), repairCwds)
    assertEquals(listOf("refs/heads/$FEATURE_BRANCH", "refs/heads/main"), refs(fork))
  }

  @Test
  fun `a teammate push during the repair blocks publication and the concurrent commit survives`() {
    failingHeads += headSha
    val teammate = root.resolve("teammate")
    git(root, "clone", "-q", "-b", FEATURE_BRANCH, fork.toString(), teammate.toString())
    configureIdentity(teammate)
    duringRepair = {
      commit(teammate, "Teammate.kt", "class Teammate\n")
      git(teammate, "push", "-q", "origin", FEATURE_BRANCH)
    }

    val result = run()

    val blocked = assertIs<PhaseRunResult.Blocked>(result, result.toString())
    val teammateHead = git(teammate, "rev-parse", "HEAD")
    assertEquals(teammateHead, forkHead(), "the concurrent commit must not be overwritten")
    val worktree = repairCwds.single()
    assertTrue(teammateHead in blocked.reason, blocked.reason)
    assertTrue(worktree.toString() in blocked.reason, "the preserved work must be named: ${blocked.reason}")
    assertEquals("class Fix1", Files.readString(worktree.resolve("Fix.kt")).trim(), "the repair is retained")
  }

  @Test
  fun `a conflict is repaired by merging the fetched base tip and the merge lands on the fork head`() {
    git(caller, "checkout", "-q", "main")
    commit(caller, "Base.kt", "class Base\n")
    git(caller, "push", "-q", "origin", "main")
    val pinnedBase = git(caller, "rev-parse", "HEAD")
    duringRepair = { cwd ->
      git(cwd, "fetch", "-q", BASE_URL, "refs/heads/main")
      git(cwd, "merge", "-q", "--no-commit", "--no-ff", "FETCH_HEAD")
    }

    val result = run()

    val completed = assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertOwnedWorktreeRemoved(completed.targetReport)
    val merge = forkHead()
    assertEquals(listOf(headSha, pinnedBase), git(fork, "rev-list", "--parents", "-n", "1", merge).split(" ").drop(1))
    assertEquals("class Base", git(fork, "show", "$merge:Base.kt"))
    assertTrue("Merge the fetched base tip (`FETCH_HEAD`)" in repairPrompts.single(), repairPrompts.single())
    assertEquals(pinnedBase, git(origin, "rev-parse", "main"), "the base repository is never pushed to")
  }

  @Test
  fun `a conflict merges the fetched base tip when the pull request reports an older base commit`() {
    git(caller, "checkout", "-q", "main")
    reportedBase = git(caller, "rev-parse", "HEAD")
    commit(caller, "Base.kt", "class Base\n")
    git(caller, "push", "-q", "origin", "main")
    val baseTip = git(caller, "rev-parse", "HEAD")
    duringRepair = { cwd ->
      git(cwd, "fetch", "-q", BASE_URL, "refs/heads/main")
      git(cwd, "merge", "-q", "--no-commit", "--no-ff", "FETCH_HEAD")
    }

    val result = run()

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertTrue(reportedBase.orEmpty() !in repairPrompts.single(), "the stale base.sha must not be named")
    assertEquals(listOf(headSha, baseTip), git(fork, "rev-list", "--parents", "-n", "1", forkHead()).split(" ").drop(1))
  }

  @Test
  fun `a base that moves during a merge repair publishes the merge and repairs again against the newer base`() {
    git(caller, "checkout", "-q", "main")
    commit(caller, "Base.kt", "class Base\n")
    git(caller, "push", "-q", "origin", "main")
    val firstBase = git(caller, "rev-parse", "HEAD")
    duringRepair = { cwd ->
      git(cwd, "fetch", "-q", BASE_URL, "refs/heads/main")
      git(cwd, "merge", "-q", "--no-commit", "--no-ff", "FETCH_HEAD")
      if (repairCount == 1) {
        commit(caller, "Later.kt", "class Later\n")
        git(caller, "push", "-q", "origin", "main")
      }
    }

    val result = run()

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    val secondBase = git(origin, "rev-parse", "main")
    assertEquals(2, repairPrompts.size, "the newer base starts a second merge repair")
    val secondMerge = forkHead()
    val firstMerge = git(fork, "rev-parse", "$secondMerge^1")
    assertEquals(
      listOf(headSha, firstBase),
      git(fork, "rev-list", "--parents", "-n", "1", firstMerge).split(" ").drop(1),
    )
    assertEquals(secondBase, git(fork, "rev-parse", "$secondMerge^2"))
  }

  @Test
  fun `every published repair that still fails CI counts toward the three-attempt cap`() {
    val result = run(checks = { failing() })

    val blocked = assertIs<PhaseRunResult.Blocked>(result, result.toString())
    assertOwnedWorktreeRemoved(blocked.targetReport)
    assertEquals(MAX_REPAIRS, repairCwds.size, "no fourth repair may launch")
    assertTrue("after 3 CI-confirmed" in blocked.reason, blocked.reason)
    var commit = forkHead()
    repeat(MAX_REPAIRS) { commit = git(fork, "rev-parse", "$commit^") }
    assertEquals(headSha, commit, "each attempt published exactly one commit on the fork head")
  }

  @Test
  fun `a repair that only fixes the pull request title pushes nothing and monitoring follows the refreshed checks`() {
    repairEdits = { "fix: title" }

    val result = run(checks = { if (pullRequestTitle.startsWith("fix:")) passing() else failing() })

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertEquals(1, repairCwds.size)
    assertEquals(headSha, forkHead(), "a pull-request-only repair publishes no commit")
  }

  @Test
  fun `pull request edits that keep failing CI count toward the cap even when a title repeats`() {
    repairEdits = { attempt -> "title ${attempt % 2}" }
    val freshFailure = {
      PullRequestChecks.Reported(listOf(PullRequestCheck("build", CheckBucket.FAIL, CHECK_URL, Instant.now())))
    }

    val result = run(checks = { freshFailure() })

    val blocked = assertIs<PhaseRunResult.Blocked>(result, result.toString())
    assertEquals(MAX_REPAIRS, repairCwds.size, "no fourth repair may launch")
    assertTrue("after 3 CI-confirmed" in blocked.reason, blocked.reason)
    assertTrue("failed published pull request edit" in blocked.reason, blocked.reason)
    assertEquals(headSha, forkHead())
  }

  @Test
  fun `a teammate push on a published repair is adopted and its failure does not spend the repair budget`() {
    var teammateHead: String? = null
    val checks = { sha: String ->
      if (repairCount == 2 && teammateHead == null) {
        val teammate = root.resolve("teammate")
        git(root, "clone", "-q", "-b", FEATURE_BRANCH, fork.toString(), teammate.toString())
        configureIdentity(teammate)
        commit(teammate, "Teammate.kt", "class Teammate\n")
        git(teammate, "push", "-q", "origin", FEATURE_BRANCH)
        teammateHead = git(teammate, "rev-parse", "HEAD")
        PullRequestChecks.Reported(listOf(PullRequestCheck("build", CheckBucket.PENDING, CHECK_URL)))
      } else {
        failing()
      }
    }

    val result = run(checks = checks)

    val blocked = assertIs<PhaseRunResult.Blocked>(result, result.toString())
    val adopted = assertNotNull(teammateHead)
    assertEquals(MAX_REPAIRS + 1, repairCwds.size, "the teammate commit's failure must not count as a failed repair")
    assertFalse(adopted in blocked.reason, "the teammate commit is not a failed repair: ${blocked.reason}")
    assertTrue(isAncestor(adopted, forkHead()), "later repairs must build on the teammate commit")
    assertOwnedWorktreeRemoved(blocked.targetReport)
  }

  @Test
  fun `standalone bindings refuse foreign steps, foreign strategies and expired lifetimes before any effect`() {
    val operations = CountingOperations()
    val inspection =
      object : TestFeatureTaskRuntimeRunLoopEntry() {
        override fun run(
          context: FeatureTaskRuntimeRunLoopContext,
          beforeDrive: (FeatureTaskRuntimeRunLoop) -> Unit,
        ): FeatureTaskRuntimeRunReport {
          val run =
            FeatureTaskRuntimeRunLoopPlanningBranch.buildPhaseRun(
              context,
              COMMIT_PUSH,
              context.request,
              context.specSource,
              null,
            )
          context.runState.stepBinding.authorizeCoordinatorDispatch(run)
          val binding = context.runState.step(run)
          assertFalse(binding is PhaseCommitStepBinding, "publication must not receive the general commit capability")
          assertFalse(binding is PhaseStandaloneMonitorStepBinding)
          val publication = assertIs<PhaseStandalonePublicationStepBinding>(binding)
          assertFails { publication.runStandalonePublication(run.copy(phaseId = MONITOR), operations) }
          val foreignRequest = object : FeatureTaskRuntimeRunFacts by run.request {}
          assertFails { publication.runStandalonePublication(run.copy(request = foreignRequest), operations) }
          assertFails { publication.requireAcceptedStep(run, StandaloneMonitorStrategy.ID) }
          binding.finishStepExecution()
          context.runState.stepBinding.releaseCoordinatorDispatch()
          assertFails { publication.runStandalonePublication(run, operations) }
          throw InspectionFinished()
        }
      }

    val failure = assertFailsWith<SkillBillRuntimeException> { run(runLoopEntry = inspection) }
    assertIs<InspectionFinished>(failure.cause)
    assertTrue("Cleanup: released" in failure.message.orEmpty(), failure.message)

    assertEquals(0, operations.calls, "no refused operation may observe or publish")
    assertEquals(headSha, forkHead())
  }

  @Test
  fun `an interrupted monitor preserves its cancellation and releases its clean owned worktree`() {
    val cancellation = CancellationException("operator stop")
    lateinit var worktree: Path
    val interruptedRun =
      object : TestFeatureTaskRuntimeRunLoopEntry() {
        override fun run(
          context: FeatureTaskRuntimeRunLoopContext,
          beforeDrive: (FeatureTaskRuntimeRunLoop) -> Unit,
        ): FeatureTaskRuntimeRunReport {
          worktree = context.request.repoRoot
          Thread.currentThread().interrupt()
          throw cancellation
        }
      }

    try {
      assertSame(cancellation, assertFailsWith<CancellationException> { run(runLoopEntry = interruptedRun) })
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }

    assertFalse(Files.exists(worktree), "cancellation must still settle the clean owned worktree")
    assertEquals("main", git(caller, "branch", "--show-current"))
    assertEquals(headSha, forkHead())
  }

  private fun assertOwnedWorktreeRemoved(report: List<String>) {
    val cleanup = report.single { it.startsWith("Cleanup: ") }
    assertTrue(cleanup.startsWith("Cleanup: released"), cleanup)
    val path = Path.of(report.single { it.startsWith("Worktree: ") }.removePrefix("Worktree: "))
    assertFalse(Files.exists(path), "standalone monitor left $path")
    assertFalse(path.toString() in git(caller, "worktree", "list", "--porcelain"), "git still lists $path")
  }

  private fun run(
    checks: (String) -> PullRequestChecks = ::scriptedChecks,
    runLoopEntry: FeatureTaskRuntimeRunLoopEntry? = null,
  ): PhaseRunResult {
    val source = ForkPullRequestSource()
    val service =
      StandaloneMonitorService(source, ScriptedChecks(source, checks), GitMonitorRepairPublisher(), clock, sleep = {})
    val config =
      RuntimeHarnessConfig(
        seedDurableWorkflow = false,
        repoRoot = caller,
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
        runLoopEntry ?: harness.runLoopEntry,
        preparer,
      ).entry()
    return entry.runStandaloneMonitor(
      PhaseRunRequest(
        definitionId = SkeletonDefinition.MONITOR.id,
        repoRoot = caller,
        invokedAgentId = "claude",
        intake = PULL_REQUEST_URL,
      ),
    )
  }

  private fun scriptedChecks(sha: String): PullRequestChecks = if (sha in failingHeads) failing() else passing()

  private fun failing() = PullRequestChecks.Reported(listOf(PullRequestCheck("build", CheckBucket.FAIL, CHECK_URL)))

  private fun passing() = PullRequestChecks.Reported(listOf(PullRequestCheck("build", CheckBucket.PASS, CHECK_URL)))

  private fun forkHead(): String = git(fork, "rev-parse", "refs/heads/$FEATURE_BRANCH")

  private fun refs(repository: Path): List<String> =
    git(repository, "for-each-ref", "--format=%(refname)", "refs/heads").lines().filter(String::isNotBlank).sorted()

  private inner class ForkPullRequestSource : MonitorPullRequestSource {
    override fun callerRepository(callerRoot: Path) = MonitorCallerRepository.Resolved(ACME)

    override fun pullRequest(
      repository: MonitorRepositoryIdentity,
      number: Int,
    ): MonitorPullRequestLookup =
      if (repository != ACME || number != PR_NUMBER) {
        MonitorPullRequestLookup.Missing
      } else {
        MonitorPullRequestLookup.Found(
          MonitorPullRequest(
            MonitorPullRequestIdentity(ACME, PR_NUMBER),
            MonitorPullRequestState.OPEN,
            MonitorPullRequestHead(
              MonitorBranchTip(FORK, FEATURE_BRANCH, forkHead()),
              FORK_URL,
              FORK_URL,
              MonitorWriteEvidence(repositoryPush = false, maintainerCanModify = true),
            ),
            MonitorBranchTip(ACME, "main", reportedBase ?: git(origin, "rev-parse", "main")),
            conflicting = !isAncestor(git(origin, "rev-parse", "main"), forkHead()),
            editableFieldsDigest = pullRequestTitle,
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

  private inner class ScriptedChecks(
    private val source: ForkPullRequestSource,
    private val bySha: (String) -> PullRequestChecks,
  ) : MonitorCheckSource {
    override fun checks(pullRequest: MonitorPullRequestIdentity): PullRequestChecks {
      val found = source.pullRequest(pullRequest.repository, pullRequest.number)
      return bySha(requireNotNull((found as MonitorPullRequestLookup.Found).pullRequest.head).tip.sha)
    }

    override fun failureLog(
      pullRequest: MonitorPullRequestIdentity,
      check: PullRequestCheck,
    ): MonitorFailureLog = MonitorFailureLog.Available("FeatureTest > ready() FAILED: assertion failed in FeatureTest")
  }

  private class CountingOperations : PhaseStandaloneMonitorOperations {
    var calls = 0

    override fun observe(
      ledger: StandaloneMonitorLedger,
      onCiRunning: (String, List<PullRequestCheck>) -> Unit,
    ) = refused()

    override fun failureEvidence(
      ledger: StandaloneMonitorLedger,
      failing: List<PullRequestCheck>,
    ) = refused()

    override fun conflictEvidence(ledger: StandaloneMonitorLedger) = refused()

    override fun guardRepairLaunch(
      ledger: StandaloneMonitorLedger,
      worktree: StandaloneRepairWorktree,
    ) = refused()

    override fun publish(
      ledger: StandaloneMonitorLedger,
      worktree: StandaloneRepairWorktree,
    ) = refused()

    private fun refused(): Nothing {
      calls += 1
      error("a refused binding must not reach the standalone operations")
    }
  }

  private class InspectionFinished : RuntimeException()

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

  private fun isAncestor(
    ancestor: String,
    descendant: String,
  ): Boolean =
    ProcessBuilder("git", "-C", fork.toString(), "merge-base", "--is-ancestor", ancestor, descendant)
      .redirectErrorStream(true)
      .start()
      .waitFor() == 0

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
    const val FEATURE_BRANCH = "feat/SKILL-427-fork"
    const val PR_NUMBER = 7
    const val PULL_REQUEST_URL = "https://github.com/acme/repo/pull/7"
    const val BASE_URL = "https://github.com/acme/repo.git"
    const val FORK_URL = "https://github.com/fork/repo.git"
    const val CHECK_URL = "https://github.com/acme/repo/actions/runs/1/job/2"
    const val COMMIT_PUSH = "commit_push"
    const val MONITOR = "monitor"
    const val MONITOR_FIX = "monitor_fix"
    const val MAX_REPAIRS = 3
    const val FIXED = "Repair outcome: fixed"
    const val UNRESOLVED = "Repair outcome: unresolved"

    fun repairOutput(outcome: String): String =
      """
      {
        "contract_version": "0.7",
        "phase_id": "monitor_fix",
        "status": "completed",
        "summary": "Monitor repair settled.",
        "produced_outputs": {"value": "The build failure was investigated in the owned worktree.\n$outcome"}
      }
      """.trimIndent()

    val ACME: MonitorRepositoryIdentity = MonitorRepositoryIdentity.canonical("github.com", "acme", "repo")
    val FORK: MonitorRepositoryIdentity = MonitorRepositoryIdentity.canonical("github.com", "fork", "repo")
  }
}
