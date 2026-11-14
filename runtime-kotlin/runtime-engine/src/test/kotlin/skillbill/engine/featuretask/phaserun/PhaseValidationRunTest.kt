package skillbill.engine.featuretask.phaserun

import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.committedRepoBranchSetup
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.kotlinPackWithValidationGate
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.engine.featuretask.validation.repoLocalConfig
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.install.model.SupportedAgent
import skillbill.ports.agentrun.agentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.scaffold.model.PlatformManifest
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PhaseValidationRunTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-phase-validation-repo")
  private val home: Path = Files.createTempDirectory("skillbill-phase-validation-home")
  private val clock: Clock = Clock.systemUTC()
  private val database = phaseRunDatabase(home, clock)
  private val branchSetup =
    committedRepoBranchSetup().also {
      it.gitOperations.repositoryFingerprintValue = "validation-checkpoint"
      it.gitOperations.trackedPathsValue = listOf("src/Foo.kt")
    }
  private val headBefore = branchSetup.gitOperations.headCommitShaValue

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    home.toFile().deleteRecursively()
  }

  @Test
  fun `standalone validation discovers checks without installed packs`() {
    val gateRequests = mutableListOf<ValidationGateRunRequest>()
    val launcher = launcher { validJsonOutput(PHASE_VALIDATE) }
    val result =
      assertIs<PhaseRunResult.Completed>(
        entry(launcher, gateRequests, emptyList(), manifests = emptyList()).run(validationRequest()),
      )

    assertEquals(listOf(PHASE_VALIDATE), result.completedStepIds)
    val prompt = requireNotNull(launcher.requests.single().skillRunRequest.promptOverride)
    assertContains(prompt, "admitted saved validation")
    assertContains(prompt, "do not rediscover checks")
    assertContains(prompt, "print a warning")
    assertContains(prompt, "then settle completed and allow the workflow to advance")
    assertEquals(emptyList(), gateRequests)
    database.assertNoDurableWorkflowState()
    branchSetup.gitOperations.assertNoCommitOrCheckpointRef(headBefore)
  }

  @Test
  fun `only a standalone validation launch hands its invocation id to the runtime MCP server`() {
    val launcher = launcher { validJsonOutput(PHASE_VALIDATE) }
    val entry = entry(launcher, mutableListOf(), emptyList(), manifests = emptyList())

    val standalone = assertIs<PhaseRunResult.Completed>(entry.run(validationRequest()))
    entry.runForGoal(validationRequest(), "goal-parent", branchSetup.gitOperations.currentBranchValue)

    assertEquals(
      listOf(standalone.invocationId, null),
      launcher.requests.map { it.skillRunRequest.validationCheckInvocationId },
    )
  }

  @Test
  fun `no discovered checks completes validation and retains the warning`() {
    val requests = mutableListOf<ValidationGateRunRequest>()
    val warning =
      "Warning: No applicable validation commands were found after inspecting repository instructions, " +
        "build files, scripts, and CI."
    val launcher = launcher { warning }

    val result =
      assertIs<PhaseRunResult.Completed>(
        entry(launcher, requests, emptyList(), manifests = emptyList()).run(validationRequest()),
      )

    assertContains(requireNotNull(result.value), warning)
    assertEquals(listOf(PHASE_VALIDATE), result.completedStepIds)
    assertEquals(emptyList(), requests)
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `accepted standalone output remains exact beyond the display summary limit`() {
    val full = "Validation findings. ".repeat(50) + "Final required action."
    val launcher = launcher { full }
    val result =
      assertIs<PhaseRunResult.Completed>(
        entry(launcher, mutableListOf(), emptyList(), manifests = emptyList()).run(validationRequest()),
      )
    val stored = database.read { it.standalonePhaseStatuses.readByInvocation(result.invocationId) }

    assertEquals(full, result.acceptedOutput)
    assertEquals(full, stored?.acceptedResult)
    database.assertNoDurableWorkflowState()
  }

  @Test
  fun `repeated validation reuses admitted saved commands without rediscovery`() {
    val gateRequests = mutableListOf<ValidationGateRunRequest>()
    val launcher = launcher { validJsonOutput(PHASE_VALIDATE) }
    val entry = entry(launcher, gateRequests, emptyList())

    val first = assertIs<PhaseRunResult.Completed>(entry.run(validationRequest()))
    val second = assertIs<PhaseRunResult.Completed>(entry.run(validationRequest()))

    assertNotEquals(first.invocationId, second.invocationId)
    assertEquals(2, launcher.requests.size)
    assertEquals(emptyList(), gateRequests)
    database.assertNoDurableWorkflowState()
    branchSetup.gitOperations.assertNoCommitOrCheckpointRef(headBefore)
  }

  @Test
  fun `a completed standalone invocation keeps its branch worktree until finalize`() {
    val entry =
      entry(launcher { validJsonOutput(PHASE_VALIDATE) }, mutableListOf(), emptyList(), manifests = emptyList())

    val result = assertIs<PhaseRunResult.Completed>(entry.runInvocation(validationRequest()))

    assertTrue(result.targetReport.none { repoRoot.toString() in it && "dirty" in it }, result.targetReport.toString())
  }

  @Test
  fun `validation relaunches after a failed attempt and completes once the build is green`() {
    var attempt = 0
    val launcher =
      RuntimeRecordingLauncher {
        if (++attempt < 3) failedValidationLaunch() else facts(validJsonOutput(PHASE_VALIDATE))
      }
    val result = assertIs<PhaseRunResult.Completed>(goalEntry(launcher))

    assertEquals(listOf(PHASE_VALIDATE), result.completedStepIds)
    assertEquals(3, launcher.requests.size)
  }

  @Test
  fun `validation blocks only after three failed attempts`() {
    val launcher = RuntimeRecordingLauncher { failedValidationLaunch() }
    val result = assertIs<PhaseRunResult.Blocked>(goalEntry(launcher))

    assertEquals(3, launcher.requests.size)
    assertContains(result.reason, "after 3 attempts")
  }

  @Test
  fun `goal validation interrupted mid attempt runs again on resume`() {
    val started = Instant.parse("2026-11-07T21:53:10.032Z")
    val start =
      FeatureTaskRuntimePhaseLedgerEntry(
        FeatureTaskRuntimePhaseLedgerAction.START,
        1,
        started,
        PHASE_VALIDATE,
        1,
        "claude",
      )
    var latest =
      GoalQualityPhaseProgress(
        phaseRecords =
          mapOf(
            PHASE_VALIDATE to
              FeatureTaskRuntimePhaseRecord(
                PHASE_VALIDATE,
                WorkflowStepStatus.RUNNING,
                1,
                started,
                resolvedAgentId = "claude",
              ),
          ),
        phaseLedger = listOf(start, start.copy(sequenceNumber = 2, timestamp = started.plusMillis(722))),
        revision = 2,
      )
    val launcher = launcher { validJsonOutput(PHASE_VALIDATE) }
    val fixture = fixture(launcher, mutableListOf(), emptyList(), manifests = emptyList())
    val request =
      validationRequest().copy(
        acceptedGoalQualityAgentId = "claude",
        goalQualityPhaseProgress = latest,
        persistGoalQualityPhaseProgress = { progress, _ ->
          latest = progress
          true
        },
        latestGoalQualityPhaseProgress = { latest },
        terminalGoalQualityContentFingerprint = { "validation-checkpoint" },
        acceptedGoalQualitySourceFingerprint = { "validation-checkpoint" },
      )
    val plan = fixture.plans().resolveGoalQualityExecutionPlan(request, "goal-parent")

    val result =
      fixture.entry().runForGoalQuality(
        request,
        "goal-parent",
        "phr-quality",
        branchSetup.gitOperations.currentBranchValue,
        plan,
      )

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertEquals(1, launcher.requests.size)
  }

  private fun goalEntry(launcher: RuntimeRecordingLauncher): PhaseRunResult =
    entry(launcher, mutableListOf(), emptyList(), manifests = emptyList())
      .runForGoal(validationRequest(), "goal-parent", branchSetup.gitOperations.currentBranchValue)

  private fun failedValidationLaunch() =
    agentRunLaunchFacts(agent = SupportedAgent.CLAUDE, termination = AgentRunTermination.Exited(1))

  private fun validationPack(): PlatformManifest =
    kotlinPackWithValidationGate().let { pack ->
      pack.copy(
        validationGate =
          requireNotNull(pack.validationGate).copy(
            buildCommand = listOf("./gradlew", "build-discovery"),
            cacheBypassingBuildCommand = listOf("./gradlew", "build-verification"),
            collectAllFullGateCommand = listOf("./gradlew", "validation-discovery"),
            cacheBypassingCollectAllFullGateCommand = listOf("./gradlew", "validation-verification"),
          ),
      )
    }

  private fun validationRequest(): PhaseRunRequest =
    PhaseRunRequest(
      definitionId = SkeletonDefinition.VALIDATION.id,
      repoRoot = repoRoot,
      invokedAgentId = "claude",
    )

  private fun launcher(output: (Int) -> String): RuntimeRecordingLauncher {
    var attempt = 0
    return RuntimeRecordingLauncher { facts(output(++attempt)) }
  }

  private fun entry(
    launcher: RuntimeRecordingLauncher,
    gateRequests: MutableList<ValidationGateRunRequest>,
    results: List<ValidationGateRunResult>,
    manifests: List<PlatformManifest> = listOf(validationPack()),
    runLoopEntry: FeatureTaskRuntimeRunLoopEntry = TestFeatureTaskRuntimeRunLoopEntry(),
  ): PhaseRunEntry = fixture(launcher, gateRequests, results, manifests, runLoopEntry).entry()

  private fun fixture(
    launcher: RuntimeRecordingLauncher,
    gateRequests: MutableList<ValidationGateRunRequest>,
    results: List<ValidationGateRunResult>,
    manifests: List<PlatformManifest> = listOf(validationPack()),
    runLoopEntry: FeatureTaskRuntimeRunLoopEntry = TestFeatureTaskRuntimeRunLoopEntry(),
  ): PhaseRunEntryFixture {
    var resultIndex = 0
    val config =
      RuntimeHarnessConfig(
        seedDurableWorkflow = false,
        branchSetup =
          branchSetup.also {
            it.gitOperations.ownedPathsResult =
              WorkflowGitNameListResult.Listed(listOf("src/Foo.kt"))
          },
        repoRoot = repoRoot,
        launcher = launcher,
        validationGatePlatformManifests = manifests,
        gateRepoLocalConfig = repoLocalConfig("./tools/gradlew"),
        validationGateRunner =
          object : ValidationGateRunner {
            override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
              gateRequests += request
              return results.getOrNull(resultIndex++)?.copy(command = request.argv.joinToString(" "))
                ?: ValidationGateRunResult(
                  exitCode = 0,
                  durationMs = 1,
                  outcome = ValidationGateRunOutcome.PASSED,
                  cacheMode = request.cacheMode,
                  executedWorkUnits = 1,
                  executedCheckIdentities = emptyList(),
                  findings = emptyList(),
                  command = request.argv.joinToString(" "),
                )
            }
          },
      )
    val harness =
      telemetryRunnerHarness(
        runtimeConfig = config,
        databaseFactory = { database },
      )
    return PhaseRunEntryFixture(
      harness.strategies,
      config.harnessGitOperations,
      database,
      clock,
      harness.runLoopEntry.delegateTo(runLoopEntry),
    )
  }
}
