package skillbill.engine.featuretask.phaserun

import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoop
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.runner.committedRepoBranchSetup
import skillbill.engine.featuretask.runner.kotlinPackWithValidationGate
import skillbill.engine.featuretask.runner.packBuildGateRunLoopEntryForTests
import skillbill.engine.featuretask.runner.telemetryRunnerHarness
import skillbill.engine.featuretask.slot.testPhaseStrategies
import skillbill.engine.featuretask.validation.repoLocalConfig
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalQualityInterruptedRerunTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-interrupted-rerun-repo")
  private val home: Path = Files.createTempDirectory("skillbill-interrupted-rerun-home")
  private val started = Instant.parse("2026-11-14T09:12:41.118Z")
  private val clock: Clock = Clock.fixed(started.plusSeconds(600), ZoneOffset.UTC)
  private val database = phaseRunDatabase(home, clock)
  private val parent = "goal-parent"
  private val invocation = "phr-quality"
  private val namespace = "$parent:$invocation"
  private val qaBranch = "feat/existing-runtime-branch"
  private val pendingDiscovery =
    "build:initial_discovery:runtime-kotlin::./gradlew\u0000compileKotlin || desktop::./gradlew\u0000compileKotlin"
  private val branchSetup =
    committedRepoBranchSetup().also {
      it.gitOperations.repositoryFingerprintValue = "build-checkpoint"
      it.gitOperations.trackedPathsValue = listOf("src/Foo.kt")
    }

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    home.toFile().deleteRecursively()
  }

  @Test
  fun `a hard-killed initial build discovery reruns as a new attempt`() {
    val gateRequests = mutableListOf<ValidationGateRunRequest>()
    var latest = killedBuild(attempt = 1, priorReruns = 0)
    val fixture = buildFixture(gateRequests)
    val request = buildRequest(latest) { latest = it }
    val plan = fixture.plans().resolveGoalQualityExecutionPlan(request, parent)

    val result = fixture.entry().runForGoalQuality(request, parent, invocation, currentBranch(), plan)

    assertIs<PhaseRunResult.Completed>(result, result.toString())
    assertTrue(gateRequests.isNotEmpty())
    val build = latest.phaseRecords.getValue("build")
    assertEquals(2, build.attemptCount)
    assertEquals(WorkflowStepStatus.COMPLETED, build.status)
    val settled = latest.phaseLedger.single { it.action == FeatureTaskRuntimePhaseLedgerAction.PAUSED }
    assertEquals(1, settled.attemptCount)
    assertTrue(GoalQualityInterruptedRerun.isRerunReason(settled.blockedReason))
    assertContains(settled.blockedReason.orEmpty(), pendingDiscovery)
    assertEquals(gateKey(2), latest.buildGateProgressKey)
    assertNull(latest.buildGateProgress?.pendingExternalOperation)
    assertNull(latest.buildGateProgress?.pendingExternalCheckpoint)
  }

  @Test
  fun `the third no-outcome interruption of a step blocks with its concrete cause`() {
    val retained = killedBuild(attempt = 3, priorReruns = 2)
    val fixture = buildFixture(mutableListOf())
    val request = buildRequest(retained) {}
    val plan = fixture.plans().resolveGoalQualityExecutionPlan(request, parent)

    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        fixture.entry().runForGoalQuality(request, parent, invocation, currentBranch(), plan)
      }

    assertEquals(WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION, failure.code)
    val message = failure.message.orEmpty()
    assertContains(message, "Goal-quality step 'build' was interrupted without a usable outcome 3 times")
    assertContains(message, "pending '$pendingDiscovery' at checkpoint 'build-checkpoint'")
    assertContains(message, "automatic reruns exhausted")
    assertFalse("fresh-quality" in message, message)
  }

  @Test
  fun `a hard-killed QA fix reruns as a new attempt`() {
    val start =
      FeatureTaskRuntimePhaseLedgerEntry(FeatureTaskRuntimePhaseLedgerAction.START, 1, started, "qa_fix", 1, "codex")
    val retained =
      GoalQualityPhaseProgress(
        phaseRecords =
          mapOf(
            "qa_fix" to
              FeatureTaskRuntimePhaseRecord(
                "qa_fix",
                WorkflowStepStatus.RUNNING,
                1,
                started,
                resolvedAgentId = "codex",
              ),
          ),
        phaseLedger = listOf(start),
        revision = 3,
      )
    var dispatchedNextAttempt: Int? = null
    var dispatchedLedger = emptyList<FeatureTaskRuntimePhaseLedgerEntry>()
    val git = RecordingWorkflowGitOperations()
    val strategies = testPhaseStrategies(GoalRunnerSubtaskLauncher { error("Unexpected agent launch") }, git)
    val continuation =
      object : TestFeatureTaskRuntimeRunLoopEntry(gitOperations = git) {
        override fun run(
          context: FeatureTaskRuntimeRunLoopContext,
          beforeDrive: (FeatureTaskRuntimeRunLoop) -> Unit,
        ): FeatureTaskRuntimeRunReport {
          dispatchedNextAttempt = context.state.phase("qa_fix").nextIteration
          dispatchedLedger = context.recorder.loadPhaseLedger(invocation).orEmpty()
          return FeatureTaskRuntimeRunReport.Blocked(
            "SKILL-1",
            invocation,
            "small",
            "qa_fix",
            "stop",
            emptyList(),
            qaBranch,
          )
        }
      }
    val fixture = PhaseRunEntryFixture(strategies, git, database, clock, continuation)
    var latest = retained
    val request =
      PhaseRunRequest(
        SkeletonDefinition.GOAL_QA.id,
        home,
        "codex",
        acceptedGoalQualityAgentId = "codex",
        goalQualityPhaseProgress = retained,
        persistGoalQualityPhaseProgress = { progress, _ ->
          latest = progress
          true
        },
        latestGoalQualityPhaseProgress = { latest },
        goalQualityInvocationId = invocation,
      )
    val plan = fixture.plans().resolveGoalQualityExecutionPlan(request, parent)

    fixture.entry().runForGoalQuality(request, parent, invocation, qaBranch, plan)

    assertEquals(2, dispatchedNextAttempt)
    val settled = dispatchedLedger.single { it.action == FeatureTaskRuntimePhaseLedgerAction.PAUSED }
    assertEquals("qa_fix", settled.phaseId)
    assertEquals(1, settled.attemptCount)
    assertContains(settled.blockedReason.orEmpty(), "it recorded no accepted output")
  }

  private fun killedBuild(
    attempt: Int,
    priorReruns: Int,
  ): GoalQualityPhaseProgress {
    val priorEntries =
      (1..priorReruns).flatMap { prior ->
        val start = buildStart(sequence = prior * 2 - 1, attempt = prior)
        val reason =
          GoalQualityInterruptedRerun.reason(
            GoalQualityInterruptedAttempt(
              FeatureTaskRuntimePhaseRecord(
                "build",
                WorkflowStepStatus.RUNNING,
                prior,
                started,
                resolvedAgentId = "claude",
              ),
              "pending '$pendingDiscovery' at checkpoint 'build-checkpoint'",
            ),
          )
        listOf(
          start,
          start.copy(
            action = FeatureTaskRuntimePhaseLedgerAction.PAUSED,
            sequenceNumber = prior * 2,
            blockedReason = reason,
          ),
        )
      }
    val firstStart = buildStart(sequence = priorReruns * 2 + 1, attempt = attempt)
    val secondStart =
      firstStart.copy(
        sequenceNumber = firstStart.sequenceNumber + 1,
        timestamp = started.plusMillis(507),
      )
    return GoalQualityPhaseProgress(
      phaseRecords =
        mapOf(
          "build" to
            FeatureTaskRuntimePhaseRecord(
              "build",
              WorkflowStepStatus.RUNNING,
              attempt,
              started,
              resolvedAgentId = "claude",
            ),
        ),
      phaseLedger = priorEntries + firstStart + secondStart,
      buildGateProgress =
        FeatureTaskRuntimeValidationGateProgress(
          gateRunCount = 0,
          gateRuns = emptyList(),
          pendingExternalOperation = pendingDiscovery,
          pendingExternalCheckpoint = "build-checkpoint",
        ),
      buildGateProgressKey = gateKey(attempt),
      revision = 7,
    )
  }

  private fun buildStart(
    sequence: Int,
    attempt: Int,
  ) = FeatureTaskRuntimePhaseLedgerEntry(
    FeatureTaskRuntimePhaseLedgerAction.START,
    sequence,
    started,
    "build",
    attempt,
    "claude",
  )

  private fun gateKey(attempt: Int) = "$namespace:${SkeletonDefinition.GOAL_BUILD.id}:build:$attempt:build"

  private fun currentBranch(): String = branchSetup.gitOperations.currentBranchValue

  private fun buildRequest(
    retained: GoalQualityPhaseProgress,
    persist: (GoalQualityPhaseProgress) -> Unit,
  ): PhaseRunRequest {
    var latest = retained
    return PhaseRunRequest(
      definitionId = SkeletonDefinition.GOAL_BUILD.id,
      repoRoot = repoRoot,
      invokedAgentId = "claude",
      acceptedGoalQualityAgentId = "claude",
      goalQualityPhaseProgress = retained,
      persistGoalQualityPhaseProgress = { progress, _ ->
        latest = progress
        persist(progress)
        true
      },
      latestGoalQualityPhaseProgress = { latest },
      terminalGoalQualityContentFingerprint = { "build-checkpoint" },
      acceptedGoalQualitySourceFingerprint = { "build-checkpoint" },
      goalQualityInvocationId = invocation,
    )
  }

  private fun buildFixture(gateRequests: MutableList<ValidationGateRunRequest>): PhaseRunEntryFixture {
    val pack =
      kotlinPackWithValidationGate().let {
        it.copy(
          validationGate =
            requireNotNull(it.validationGate).copy(
              buildCommand = listOf("./gradlew", "compileKotlin"),
              cacheBypassingBuildCommand = listOf("./gradlew", "compileKotlin", "--rerun-tasks"),
            ),
        )
      }
    val gateRunner =
      object : ValidationGateRunner {
        override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
          gateRequests += request
          return ValidationGateRunResult(
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
      }
    val config =
      RuntimeHarnessConfig(
        seedDurableWorkflow = false,
        branchSetup =
          branchSetup.also {
            it.gitOperations.ownedPathsResult = WorkflowGitNameListResult.Listed(listOf("src/Foo.kt"))
          },
        repoRoot = repoRoot,
        launcher = RuntimeRecordingLauncher { error("The build gate must not launch an agent when it passes.") },
        validationGatePlatformManifests = listOf(pack),
        gateRepoLocalConfig = repoLocalConfig("./gradlew"),
        validationGateRunner = gateRunner,
      )
    val runLoop =
      packBuildGateRunLoopEntryForTests(
        config.harnessGitOperations,
        listOf(pack),
        gateRunner,
        repoLocalConfig("./gradlew"),
      )
    val harness = telemetryRunnerHarness(runtimeConfig = config, databaseFactory = { database })
    return PhaseRunEntryFixture(
      harness.strategies,
      config.harnessGitOperations,
      database,
      clock,
      runLoop,
    )
  }
}
