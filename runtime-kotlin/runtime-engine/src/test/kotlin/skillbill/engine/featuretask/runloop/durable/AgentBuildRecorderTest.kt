package skillbill.engine.featuretask.runloop.durable

import org.junit.jupiter.api.io.TempDir
import skillbill.agent.model.BuildRepositoryFiles
import skillbill.engine.featuretask.slot.PhaseStepFacts
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.runner.DefaultPhaseRunner
import skillbill.engine.featuretask.slot.state.PhaseLaunchObservation
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.infrastructure.sqlite.idestatus.SessionAgentBuildRunRepository
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.install.model.SupportedAgent
import skillbill.model.EnvironmentContext
import skillbill.model.FileLocation
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.agentrun.model.AgentRunBuildObserver
import skillbill.ports.agentrun.model.AgentRunCommandEvent
import skillbill.ports.agentrun.model.AgentRunCommandEventKind
import skillbill.ports.agentrun.model.AgentRunProcessSample
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.idestatus.AgentBuildRunRepository
import skillbill.ports.idestatus.model.AgentBuildOutcome
import skillbill.ports.idestatus.model.AgentBuildRunStart
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.scaffold.install.InstalledPlatformPackCatalogPort
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.scaffold.model.BuildToolDeclaration
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentBuildRecorderTest {
  @TempDir lateinit var databaseHome: Path

  @Test
  fun `recorder stores attribution, abandons stale rows and settles the observed build`() {
    val database =
      sqliteDatabaseSessionFactory(
        databaseHome,
        environment = emptyMap(),
        diagnostics = NoopRuntimeDiagnostics,
      )
    database.transaction { seedStaleBuilds(it.agentBuildRuns) }
    val observer = recorder(SessionAgentBuildRunRepository(database))
    val startedAt = Instant.parse("2026-11-07T11:59:58Z")
    val endedAt = Instant.parse("2026-11-07T12:00:01Z")
    runImplement(observer) { buildObserver ->
      buildObserver.command(
        AgentRunCommandEvent("call-1", "cargo build --release", AgentRunCommandEventKind.STARTED, null, startedAt),
      )
      buildObserver.processes(
        startedAt,
        listOf(AgentRunProcessSample(20, null, "cargo", listOf("build", "--release"), startedAt)),
      )
      buildObserver.processes(endedAt, emptyList())
      buildObserver.command(
        AgentRunCommandEvent("call-1", "cargo build --release", AgentRunCommandEventKind.FAILED, 101, endedAt),
      )
    }

    val recorded = assertNotNull(database.read { it.agentBuildRuns.latestForRun(setOf("run-1")) })
    val parentRecorded = assertNotNull(database.read { it.agentBuildRuns.latestForRun(setOf("goal-1")) })
    val abandoned = assertNotNull(database.read { it.agentBuildRuns.latestForRun(setOf("abandoned-only-run")) })
    val history =
      database.read {
        it.agentBuildRuns.history(
          "primary",
          "cargo build --release",
          endedAt.plusSeconds(1),
        )
      }
    assertEquals(recorded, parentRecorded)
    assertEquals(startedAt, recorded.startedAt)
    assertEquals("run-1", recorded.runId)
    assertEquals("goal-1", recorded.parentRunId)
    assertEquals("primary", recorded.repositoryIdentity)
    assertEquals(AgentBuildOutcome.FAILED, recorded.outcome)
    assertEquals(endedAt, recorded.endedAt)
    assertEquals(101, recorded.exitCode)
    assertEquals("abandoned-only", abandoned.buildId)
    assertEquals(AgentBuildOutcome.UNKNOWN, abandoned.outcome)
    assertNull(abandoned.endedAt)
    assertEquals(AgentBuildOutcome.FAILED, history.previous?.outcome)
    assertEquals(endedAt, history.previous?.endedAt)
    assertEquals(3_000L, history.previous?.durationMs)
  }

  @Test
  fun `reused pids settle both durable rows without leaving an open build`() {
    val database =
      sqliteDatabaseSessionFactory(
        databaseHome,
        environment = emptyMap(),
        diagnostics = NoopRuntimeDiagnostics,
      )
    val store = SessionAgentBuildRunRepository(database)
    val observer = recorder(store)
    val firstStart = Instant.parse("2026-11-07T12:00:00Z")
    val secondStart = firstStart.plusSeconds(2)
    observer.processes(
      firstStart,
      listOf(AgentRunProcessSample(20, null, "cargo", listOf("build"), firstStart)),
    )
    observer.processes(firstStart.plusSeconds(1), emptyList())
    observer.processes(
      secondStart,
      listOf(AgentRunProcessSample(20, null, "cargo", listOf("build"), secondStart)),
    )
    observer.finished(secondStart.plusSeconds(1))

    val latest = assertNotNull(store.latestForRun(setOf("run-1")))
    assertEquals(secondStart, latest.startedAt)
    assertEquals(secondStart.plusSeconds(1), latest.endedAt)
    assertEquals(1_000L, store.history("primary", "cargo build", secondStart.plusSeconds(2)).usualMs)
  }

  private fun seedStaleBuilds(store: AgentBuildRunRepository) {
    store.start(
      AgentBuildRunStart(
        buildId = "stale",
        runId = "run-1",
        parentRunId = null,
        repositoryIdentity = "primary",
        command = "old command",
        shortCommand = "old",
        tool = "cargo",
        projectKind = "Rust",
        pid = 10,
        startedAt = Instant.EPOCH,
      ),
    )
    store.start(
      AgentBuildRunStart(
        buildId = "abandoned-only",
        runId = "abandoned-only-run",
        parentRunId = null,
        repositoryIdentity = "primary",
        command = "old command",
        shortCommand = "old",
        tool = "cargo",
        projectKind = "Rust",
        pid = 11,
        startedAt = Instant.EPOCH.plusSeconds(1),
      ),
    )
    store.abandonOpen("abandoned-only-run")
  }

  private fun runImplement(
    observer: AgentRunBuildObserver,
    command: (AgentRunBuildObserver) -> Unit,
  ) {
    val state =
      Proxy.newProxyInstance(
        PhaseLaunchState::class.java.classLoader,
        arrayOf(PhaseLaunchState::class.java),
      ) { _, method, args ->
        when (method.name) {
          "prepareLaunch" -> args?.single()
          "launchObservation" ->
            PhaseLaunchObservation(
              AgentRunActivityStampSink.NONE,
              AgentRunWorktreeEditObserver.NONE,
              buildObserver = observer,
            )
          else -> error("unexpected phase state method ${method.name}")
        }
      } as PhaseLaunchState
    val runner =
      DefaultPhaseRunner(
        GoalRunnerSubtaskLauncher { request ->
          assertEquals("codex", request.invokedAgentId)
          assertEquals(3, request.skillRunRequest.subtaskId)
          command(request.skillRunRequest.buildObserver)
          agentRunLaunchFacts(SupportedAgent.CODEX, stdout = "Implementation complete.")
        },
        NoopWorkflowGitOperations,
      )
    runner.run(
      PhaseStepInput(
        stepName = "implement",
        directive = "Run cargo build --release.",
        priorValues = emptyMap(),
        operatorInstructions = null,
        facts =
          PhaseStepFacts(
            issueKey = "SKILL-522",
            repoRoot = Path.of("/worktree"),
            timeout = null,
            invokedAgentId = "codex",
            configuredAgentOverrideId = null,
            modelOverride = null,
            effortOverride = null,
            compaction = null,
            attempt = null,
            observeLaunch = true,
            briefingText = "",
            subtaskId = 3,
            approvalRunId = "approval-run",
          ),
        policy = PhaseStepPolicy(false, false, false, false, false),
      ),
      state,
    )
  }

  private fun recorder(store: AgentBuildRunRepository): AgentRunBuildObserver {
    val clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC)
    val repoRoot = Path.of("/worktree")
    val roots =
      object : RepositoryEnclosingRootPort {
        override fun enclosingRepositoryRoot(start: Path) = repoRoot

        override fun canonicalPath(path: Path) = path

        override fun optionalRealPath(path: Path): Path? = path

        override fun repositoryIdentity(repoRoot: Path) = "worktree"

        override fun primaryRepositoryIdentity(repoRoot: Path) = "primary"

        override fun primaryRepositoryRoot(repoRoot: Path) = Path.of("/primary")
      }
    val manifest =
      PlatformManifest(
        slug = "rust",
        packRoot = FileLocation("/packs/rust"),
        contractVersion = "1.8",
        routingSignals = RoutingSignals(strong = emptyList(), tieBreakers = emptyList()),
        declaredCodeReviewAreas = emptyList(),
        declaredFiles = DeclaredFiles(null, emptyMap()),
        areaMetadata = emptyMap(),
        buildTools =
          listOf(
            BuildToolDeclaration(
              executable = "cargo",
              displayExecutable = "cargo",
              tool = "cargo",
              projectKind = "Rust",
              shortSubcommand = true,
            ),
          ),
      )
    val factory =
      AgentBuildRecorderFactory(
        store = store,
        repositoryRoots = roots,
        catalog = InstalledPlatformPackCatalogPort { listOf(manifest) },
        files =
          object : BuildRepositoryFiles {
            override fun exists(path: String) = false

            override fun readText(path: String): String? = null
          },
        environment = EnvironmentContext(),
        clock = clock,
        diagnostics = NoopRuntimeDiagnostics,
      )
    return factory.observer(repoRoot, { "run-1" }, { "goal-1" })
  }
}
