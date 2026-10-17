package skillbill.engine.featuretask.phaserun

import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.phaserun.monitor.MonitorTargetResolver
import skillbill.engine.featuretask.phaserun.monitor.StandaloneMonitorExecutionPreparer
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.ports.agentrun.AgentRunChildDrain
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.monitor.MonitorPullRequestSource
import skillbill.ports.monitor.model.MonitorCallerRepository
import skillbill.ports.monitor.model.MonitorPullRequestListing
import skillbill.ports.monitor.model.MonitorPullRequestLookup
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import skillbill.ports.process.ShutdownHookPort
import skillbill.ports.process.ShutdownHookRegistration
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PhaseStandaloneMonitorRunTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-standalone-monitor-repo")
  private val home: Path = Files.createTempDirectory("skillbill-standalone-monitor-home")
  private val clock: Clock = Clock.systemUTC()
  private val database = phaseRunDatabase(home, clock)
  private val git = RecordingWorkflowGitOperations()
  private val launcher = RuntimeRecordingLauncher { _ -> facts(validJsonOutput("monitor")) }

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    home.toFile().deleteRecursively()
  }

  @Test
  fun `a refused preparation blocks before any status, telemetry, agent launch or checkout happens`() {
    val config =
      RuntimeHarnessConfig(
        seedDurableWorkflow = false,
        repoRoot = repoRoot,
        launcher = launcher,
        gitOperationsOverride = git,
      )
    val harness = telemetryRunnerHarness(runtimeConfig = config, databaseFactory = { database })
    val entry = phaseRunEntry(harness.strategies, git, database, clock, harness.runLoopEntry)

    val result = entry.runStandaloneMonitor(request())

    val blocked = assertIs<PhaseRunResult.Blocked>(result, result.toString())
    assertEquals(SkeletonDefinition.MONITOR.id, blocked.stepId)
    assertTrue(blocked.reason.startsWith("Monitor target refused"), blocked.reason)
    assertEquals(listOf("Selector: https://github.com/example/repo/pull/7"), blocked.targetReport)
    assertEquals(emptyList(), launcher.requests)
    assertEquals(emptyList(), git.checkoutCalls)
    database.assertNoDurableWorkflowState()
    database.assertOnlyOutboxEvents(emptySet())
  }

  @Test
  fun `goal monitoring keeps its branch guard and never reaches standalone target preparation`() {
    git.currentBranchValue = "feat/actual"
    val config =
      RuntimeHarnessConfig(
        seedDurableWorkflow = false,
        repoRoot = repoRoot,
        launcher = launcher,
        gitOperationsOverride = git,
      )
    val harness = telemetryRunnerHarness(runtimeConfig = config, databaseFactory = { database })
    val entry =
      PhaseRunEntryFixture(
        harness.strategies,
        git,
        database,
        clock,
        harness.runLoopEntry,
        StandaloneMonitorExecutionPreparer(
          MonitorTargetResolver(UntouchableMonitorSource),
          UnreachableMonitorResources(),
          NoopRuntimeDiagnostics,
          object : ShutdownHookPort {
            override fun register(action: () -> Unit): ShutdownHookRegistration = error("goal runs register no hook")
          },
          AgentRunChildDrain { true },
        ),
      ).entry()

    val result = entry.runForGoal(request().copy(intake = null), "goal-parent", "feat/expected")

    val blocked = assertIs<PhaseRunResult.Blocked>(result, result.toString())
    assertTrue("requires branch" in blocked.reason, blocked.reason)
    assertEquals(emptyList(), launcher.requests)
    assertEquals(emptyList(), git.checkoutCalls)
    database.assertNoDurableWorkflowState()
  }

  private object UntouchableMonitorSource : MonitorPullRequestSource {
    override fun callerRepository(callerRoot: Path): MonitorCallerRepository = untouched()

    override fun pullRequest(
      repository: MonitorRepositoryIdentity,
      number: Int,
    ): MonitorPullRequestLookup = untouched()

    override fun pullRequests(repository: MonitorRepositoryIdentity): MonitorPullRequestListing = untouched()

    override fun pullRequestsForBranch(
      repository: MonitorRepositoryIdentity,
      headRepository: MonitorRepositoryIdentity,
      branch: String,
    ): MonitorPullRequestListing = untouched()

    private fun untouched(): Nothing = error("goal monitoring must not resolve a standalone monitor target")
  }

  private fun request() =
    PhaseRunRequest(
      definitionId = SkeletonDefinition.MONITOR.id,
      repoRoot = repoRoot,
      invokedAgentId = "claude",
      intake = "https://github.com/example/repo/pull/7",
    )
}
