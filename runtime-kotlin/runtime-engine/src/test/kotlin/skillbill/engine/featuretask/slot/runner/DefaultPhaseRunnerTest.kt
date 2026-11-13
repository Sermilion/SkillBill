package skillbill.engine.featuretask.slot.runner

import org.junit.jupiter.api.io.TempDir
import skillbill.engine.featuretask.slot.PhaseLaunchFailureKind
import skillbill.engine.featuretask.slot.PhaseLiveChecksLaunch
import skillbill.engine.featuretask.slot.PhaseStepFacts
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.state.PhaseLaunchObservation
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.state.PhaseSettledEnvelopeRead
import skillbill.engine.featuretask.validation.check.ValidationLiveChecks
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.agentrun.model.AgentRunApprovalRequirement
import skillbill.ports.agentrun.model.AgentRunBuildObserver
import skillbill.ports.agentrun.model.AgentRunCommandEvent
import skillbill.ports.agentrun.model.AgentRunMcpStartupProbe
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.agentrun.model.AgentRunProcessSample
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.idestatus.model.LiveChecksAvailability
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowPathContentIdentitiesResult
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.fail

class DefaultPhaseRunnerTest {
  @TempDir lateinit var databaseHome: Path

  @Test
  fun `runtime phases launch outside chat approval cards`() {
    val requests = mutableListOf<SkillRunRequest>()
    val runner =
      DefaultPhaseRunner(
        GoalRunnerSubtaskLauncher { launch ->
          requests += launch.skillRunRequest
          agentRunLaunchFacts(SupportedAgent.CLAUDE)
        },
        NoopWorkflowGitOperations,
      )

    listOf("preplan", "plan", "implement", "validate", "qa", "monitor").forEach { phase ->
      runner.run(input(phase), PrepareOnlyPhaseRunState)
    }

    assertEquals(6, requests.size)
    requests.forEach { assertEquals(AgentRunApprovalRequirement.OutsideApproval, it.approval) }
  }

  @Test
  fun `unobserved launches still receive the build observer`() {
    val marker =
      object : AgentRunBuildObserver {
        override fun processes(
          observedAt: Instant,
          samples: List<AgentRunProcessSample>,
        ) = Unit

        override fun command(event: AgentRunCommandEvent) = Unit

        override fun finished(observedAt: Instant) = Unit
      }
    val request = mutableListOf<SkillRunRequest>()
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
              buildObserver = marker,
            )
          "settlementTarget" -> null
          "recordTokenUsage" -> Unit
          "settledEnvelope" -> PhaseSettledEnvelopeRead.None
          "toString" -> "BuildObserverPhaseRunState"
          "hashCode" -> 0
          "equals" -> false
          else -> fail("unexpected phase state method ${method.name}")
        }
      } as PhaseLaunchState
    val runner =
      DefaultPhaseRunner(
        GoalRunnerSubtaskLauncher { launch ->
          request += launch.skillRunRequest
          agentRunLaunchFacts(SupportedAgent.CLAUDE, stdout = "done")
        },
        NoopWorkflowGitOperations,
      )

    runner.run(input("validate"), state)

    assertSame(marker, request.single().buildObserver)
  }

  @Test
  fun `captured planning launches request provider output while uncaptured launches do not`() {
    val requests = mutableListOf<GoalRunnerSubtaskLaunchRequest>()
    val runner =
      DefaultPhaseRunner(
        GoalRunnerSubtaskLauncher { request ->
          requests += request
          agentRunLaunchFacts(SupportedAgent.CLAUDE)
        },
        NoopWorkflowGitOperations,
      )
    val outputSink = AgentRunOutputSink { _, _ -> }

    listOf("preplan", "plan").forEach { stepName ->
      runner.run(input(stepName), PrepareOnlyPhaseRunState, outputSink, true)
      runner.run(input(stepName), PrepareOnlyPhaseRunState, outputSink, false)
      runner.run(
        input(stepName).let { it.copy(facts = it.facts.copy(streamOutputForLiveness = true)) },
        PrepareOnlyPhaseRunState,
        outputSink,
        false,
      )
    }

    assertEquals(
      listOf(true, false, false, true, false, false),
      requests.map { it.skillRunRequest.streamProviderOutput },
    )
    assertEquals(
      listOf(true, false, false, true, false, false),
      requests.map { it.skillRunRequest.captureDisplayOutput },
    )
    assertEquals(
      listOf(true, false, true, true, false, true),
      requests.map { it.skillRunRequest.streamOutputForLiveness },
    )
  }

  @Test
  fun `a step captures its stdout as process output without reading an envelope from it`() {
    val stdout =
      """
      Tried to publish the release notes.
      {"status": "blocked", "value": "Release notes need an approved changelog.", "verdict": "needs_changelog", "failure_disposition": "operator_action"}
      """.trimIndent()
    val runner = DefaultPhaseRunner(launcher(stdout), NoopWorkflowGitOperations)

    val output = runner.run(input("release_notes"), PrepareOnlyPhaseRunState)

    assertEquals(stdout, output.stdout.text)
    assertEquals(PhaseSettledEnvelopeRead.None, output.settledEnvelope)
  }

  @Test
  fun `manifest capture failures report the git cause without losing launched output`() {
    val boundaries =
      listOf("before-file manifest", "before commit", "after-file manifest", "after commit", "committed file changes")
    boundaries.forEachIndexed { index, boundary ->
      var calls = 0
      var launches = 0
      val cause = "git command failed with exit code 128: repository unavailable"
      val git =
        object : WorkflowGitOperations by NoopWorkflowGitOperations {
          override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult = capture()

          override fun runtimePhaseHeadCommit(repoRoot: Path): WorkflowGitOperationResult = capture()

          override fun runtimePhaseChangedPathsBetweenCommits(
            repoRoot: Path,
            beforeCommit: String,
            afterCommit: String,
          ): WorkflowGitNameListResult =
            if (++calls == index + 1) {
              WorkflowGitNameListResult.Failed(
                cause,
              )
            } else {
              WorkflowGitNameListResult.Listed(emptyList())
            }

          private fun capture(): WorkflowGitOperationResult =
            if (++calls == index + 1) {
              WorkflowGitOperationResult.Failed(
                error = cause,
              )
            } else {
              WorkflowGitOperationResult.Ok("head")
            }
        }
      val runner =
        DefaultPhaseRunner(
          GoalRunnerSubtaskLauncher {
            launches++
            agentRunLaunchFacts(SupportedAgent.CLAUDE, stdout = "Merge conflicts resolved.")
          },
          git,
        )
      val input = input("monitor_fix").let { it.copy(facts = it.facts.copy(attempt = 1)) }

      val output = runner.run(input, TrackedPhaseRunState)

      assertEquals(
        "Feature-task-runtime phase 'monitor_fix' could not capture its $boundary: $cause",
        output.launchFailure?.reason,
      )
      assertEquals(
        if (index < 2) PhaseLaunchFailureKind.BEFORE_CAPTURE_FAILED else PhaseLaunchFailureKind.AFTER_CAPTURE_FAILED,
        output.launchFailure?.kind,
      )
      assertEquals(if (index < 2) 0 else 1, launches)
      assertEquals(if (index < 2) "" else "Merge conflicts resolved.", output.stdout.text)
      assertNull(output.fileManifest)
    }
  }

  @Test
  fun `a pre-dirty source path is attributed only when the launched repair changes its content or presence`() {
    for (after in listOf("before", "after", null)) {
      var reads = 0
      val git =
        object : WorkflowGitOperations by NoopWorkflowGitOperations {
          override fun worktreeStatus(repoRoot: Path) = WorkflowGitOperationResult.Ok(" M source.kt")

          override fun pathContentIdentities(
            repoRoot: Path,
            paths: List<String>,
          ): WorkflowPathContentIdentitiesResult {
            assertEquals(listOf("source.kt"), paths)
            val identity = if (reads++ == 0) "before" else after
            return WorkflowPathContentIdentitiesResult.Resolved(identity?.let { mapOf("source.kt" to it) }.orEmpty())
          }
        }
      val input =
        input("monitor_fix").let {
          it.copy(facts = it.facts.copy(attempt = 1), policy = it.policy.copy(mutating = true))
        }

      val output = DefaultPhaseRunner(launcher("Repair complete."), git).run(input, TrackedPhaseRunState)

      assertNull(output.launchFailure)
      assertEquals(if (after == "before") emptyList() else listOf("source.kt"), output.fileManifest?.reeditedPaths)
    }
  }

  @Test
  fun `unreadable content identities block attribution before or after the repair without losing its output`() {
    for (failedRead in listOf(1, 2)) {
      var reads = 0
      var launches = 0
      val git =
        object : WorkflowGitOperations by NoopWorkflowGitOperations {
          override fun worktreeStatus(repoRoot: Path) = WorkflowGitOperationResult.Ok(" M source.kt")

          override fun pathContentIdentities(
            repoRoot: Path,
            paths: List<String>,
          ): WorkflowPathContentIdentitiesResult =
            if (++reads == failedRead) {
              WorkflowPathContentIdentitiesResult.Failed("object database unreadable")
            } else {
              WorkflowPathContentIdentitiesResult.Resolved(mapOf("source.kt" to "before"))
            }
        }
      val input =
        input("monitor_fix").let {
          it.copy(facts = it.facts.copy(attempt = 1), policy = it.policy.copy(mutating = true))
        }
      val runner =
        DefaultPhaseRunner(
          GoalRunnerSubtaskLauncher {
            launches++
            agentRunLaunchFacts(SupportedAgent.CLAUDE, stdout = "Repair output.")
          },
          git,
        )

      val output = runner.run(input, TrackedPhaseRunState)

      assertEquals(
        if (failedRead == 1) {
          PhaseLaunchFailureKind.BEFORE_CAPTURE_FAILED
        } else {
          PhaseLaunchFailureKind.AFTER_CAPTURE_FAILED
        },
        output.launchFailure?.kind,
      )
      assertEquals(failedRead - 1, launches)
      assertEquals(if (failedRead == 1) "" else "Repair output.", output.stdout.text)
      assertNull(output.fileManifest)
    }
  }

  @Test
  fun `one shared runner carries no failure or launch state from one invocation to the next`() {
    var statusReads = 0
    var launches = 0
    val git =
      object : WorkflowGitOperations by NoopWorkflowGitOperations {
        override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult =
          if (++statusReads == 1) {
            WorkflowGitOperationResult.Failed(error = "repository unavailable")
          } else {
            WorkflowGitOperationResult.Ok("")
          }
      }
    val runner =
      DefaultPhaseRunner(
        GoalRunnerSubtaskLauncher { agentRunLaunchFacts(SupportedAgent.CLAUDE, stdout = "launch ${++launches}") },
        git,
      )
    val tracked = input("monitor_fix").let { it.copy(facts = it.facts.copy(attempt = 1)) }

    val first = runner.run(tracked, TrackedPhaseRunState)
    val second = runner.run(tracked, TrackedPhaseRunState)

    assertEquals(PhaseLaunchFailureKind.BEFORE_CAPTURE_FAILED, first.launchFailure?.kind)
    assertEquals("", first.stdout.text)
    assertNull(second.launchFailure)
    assertEquals("launch 1", second.stdout.text)
  }

  @Test
  fun `a standalone validation launch gates the live-checks tool on the agent capability`() {
    val database =
      sqliteDatabaseSessionFactory(
        databaseHome,
        environment = mapOf("TEST" to "1"),
        diagnostics = NoopRuntimeDiagnostics,
      )
    val requests = mutableListOf<SkillRunRequest>()
    val runner =
      DefaultPhaseRunner(
        GoalRunnerSubtaskLauncher { request ->
          requests += request.skillRunRequest
          agentRunLaunchFacts(SupportedAgent.CLAUDE, stdout = "Checks ran.")
        },
        NoopWorkflowGitOperations,
        ValidationLiveChecks(database, NoopRuntimeDiagnostics),
      )

    fun launch(liveChecks: PhaseLiveChecksLaunch?): SkillRunRequest {
      liveChecks?.let { registerValidationRow(database, it.invocationId) }
      val input = input("validate").let { it.copy(facts = it.facts.copy(liveChecks = liveChecks)) }
      runner.run(input, PrepareOnlyPhaseRunState)
      return requests.last()
    }

    fun liveChecks(invocationId: String) =
      database.read { it.standalonePhaseStatuses.readByInvocation(invocationId)?.liveChecks }

    val capable = launch(PhaseLiveChecksLaunch("phr-claude", agentCapable = true))
    assertEquals("phr-claude", capable.validationCheckInvocationId)
    assertNotSame(AgentRunMcpStartupProbe.NONE, capable.mcpStartupProbe)
    assertEquals(LiveChecksAvailability.UNAVAILABLE, liveChecks("phr-claude"))

    val incapable = launch(PhaseLiveChecksLaunch("phr-incapable", agentCapable = false))
    assertNull(incapable.validationCheckInvocationId)
    assertSame(AgentRunMcpStartupProbe.NONE, incapable.mcpStartupProbe)
    assertEquals(LiveChecksAvailability.UNAVAILABLE, liveChecks("phr-incapable"))

    val other = launch(null)
    assertNull(other.validationCheckInvocationId)
    assertSame(AgentRunMcpStartupProbe.NONE, other.mcpStartupProbe)
  }

  private fun registerValidationRow(
    database: DatabaseSessionFactory,
    invocationId: String,
  ) {
    database.transaction {
      it.standalonePhaseStatuses.register(
        StandalonePhaseStatusRegistration(
          repositoryIdentity = "repo", branchCorrelation = "main", issueKey = null, workflowId = null,
          invocationId = invocationId, phaseId = SkeletonDefinition.VALIDATION.id,
          executionId = "execution-$invocationId", lifecycleState = "active",
          currentStep = SkeletonDefinition.VALIDATION.id, startedAt = Instant.EPOCH, leaseOwner = "owner",
          leaseGeneration = 1, leaseExpiresAt = Instant.EPOCH.plusSeconds(3600),
        ),
      )
    }
  }

  private fun launcher(stdout: String) =
    GoalRunnerSubtaskLauncher { agentRunLaunchFacts(SupportedAgent.CLAUDE, stdout = stdout) }

  private fun input(stepName: String) =
    PhaseStepInput(
      stepName = stepName,
      directive = "Publish the release notes.",
      priorValues = emptyMap(),
      operatorInstructions = null,
      facts =
        PhaseStepFacts(
          issueKey = "SKILL-380",
          repoRoot = Path.of("."),
          timeout = null,
          invokedAgentId = "claude",
          configuredAgentOverrideId = null,
          modelOverride = null,
          effortOverride = null,
          compaction = null,
          attempt = null,
          observeLaunch = false,
          briefingText = "",
        ),
      policy = PhaseStepPolicy(false, false, false, false, false),
    )
}

private val PrepareOnlyPhaseRunState: PhaseLaunchState =
  Proxy.newProxyInstance(
    PhaseLaunchState::class.java.classLoader,
    arrayOf(PhaseLaunchState::class.java),
  ) { _, method, args ->
    when (method.name) {
      "prepareLaunch" -> args?.single()
      "launchObservation" -> PhaseLaunchObservation(AgentRunActivityStampSink.NONE, AgentRunWorktreeEditObserver.NONE)
      "toString" -> "PrepareOnlyPhaseRunState"
      "hashCode" -> 0
      "equals" -> false
      else -> fail("an untracked launch reached PhaseLaunchState.${method.name}")
    }
  } as PhaseLaunchState

private val TrackedPhaseRunState: PhaseLaunchState =
  Proxy.newProxyInstance(
    PhaseLaunchState::class.java.classLoader,
    arrayOf(PhaseLaunchState::class.java),
  ) { _, method, args ->
    when (method.name) {
      "prepareLaunch" -> args?.single()
      "launchObservation" -> PhaseLaunchObservation(AgentRunActivityStampSink.NONE, AgentRunWorktreeEditObserver.NONE)
      "settlementTarget", "recordTokenUsage" -> null
      else -> fail("a tracked launch reached PhaseLaunchState.${method.name}")
    }
  } as PhaseLaunchState
