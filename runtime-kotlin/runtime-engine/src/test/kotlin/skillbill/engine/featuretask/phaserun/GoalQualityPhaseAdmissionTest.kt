package skillbill.engine.featuretask.phaserun

import org.junit.jupiter.api.io.TempDir
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoop
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runner.TestFeatureTaskRuntimeRunLoopEntry
import skillbill.engine.featuretask.slot.testPhaseStrategies
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRepairOutput
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class GoalQualityPhaseAdmissionTest {
  @TempDir lateinit var home: Path
  private val now = Instant.parse("2026-11-07T00:00:00Z")
  private val clock = Clock.fixed(now, ZoneOffset.UTC)
  private val parent = "goal-parent"
  private val invocation = "phr-quality-admission"
  private val branch = "feat/existing-runtime-branch"
  private val pause = FeatureTaskRuntimeProviderLimitPause.MARKER
  private val nestedKey = "$parent:$invocation:goal-qa:build:1:build"
  private var dispatches = 0

  @Test
  fun `provider paused QA nested build reaches continuation without an outer terminal source callback`() {
    val retained = pausedBuild()
    val fixture = fixture()
    val request = request(SkeletonDefinition.GOAL_QA, retained)
    val encoded = fixture.plans().resolveGoalQualityExecutionPlan(request, parent)
    val plan = fixture.plans().validateGoalQualityExecutionPlan(encoded, SkeletonDefinition.GOAL_QA.id, "codex")
    assertContains(plan.selectedStepIds, "build")

    val result = fixture.entry().runForGoalQuality(request, parent, invocation, branch, encoded)

    assertEquals(1, dispatches)
    assertEquals("build", assertIs<PhaseRunResult.Blocked>(result).stepId)
    assertEquals(pause, result.reason)
    assertEquals(retained, request.latestGoalQualityPhaseProgress?.invoke())
    assertEquals(1, retained.buildGateProgress?.repairsUsed)
    assertEquals(listOf("accepted repair"), retained.buildGateProgress?.acceptedRepairOutputs?.map { it.output })
  }

  @Test
  fun `initial build and final validation still reject a missing terminal source callback before dispatch`() {
    val fixture = fixture()
    listOf(SkeletonDefinition.GOAL_BUILD, SkeletonDefinition.VALIDATION).forEach { definition ->
      val request = request(definition, GoalQualityPhaseProgress())
      val encoded = fixture.plans().resolveGoalQualityExecutionPlan(request, parent)
      val failure =
        assertFailsWith<SkillBillRuntimeException> {
          fixture.entry().runForGoalQuality(request, parent, invocation, branch, encoded)
        }
      assertEquals(WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION, failure.code)
    }
    assertEquals(0, dispatches)
  }

  @Test
  fun `QA pending external repair remains unknown before continuation dispatch`() {
    val paused = pausedBuild()
    val retained =
      paused.copy(
        buildGateProgress =
          requireNotNull(paused.buildGateProgress).copy(
            pendingExternalOperation = "build:repair:build:2",
            pendingExternalCheckpoint = "checkpoint",
            terminalOutcome = null,
            terminalReason = null,
          ),
      )
    val fixture = fixture()
    val request = request(SkeletonDefinition.GOAL_QA, retained)
    val encoded = fixture.plans().resolveGoalQualityExecutionPlan(request, parent)

    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        fixture.entry().runForGoalQuality(request, parent, invocation, branch, encoded)
      }

    assertContains(failure.message.orEmpty(), "outcome of goal-quality step 'build#1' is unknown")
    assertEquals(0, dispatches)
    assertEquals(retained, request.latestGoalQualityPhaseProgress?.invoke())
  }

  private fun request(
    definition: SkeletonDefinition,
    retained: GoalQualityPhaseProgress,
  ): PhaseRunRequest {
    var latest = retained
    return PhaseRunRequest(
      definition.id,
      home,
      "codex",
      acceptedGoalQualityAgentId = "codex",
      goalQualityInvocationId = invocation,
      goalQualityPhaseProgress = retained,
      persistGoalQualityPhaseProgress = { progress, _ ->
        latest = progress
        true
      },
      latestGoalQualityPhaseProgress = { latest },
    )
  }

  private fun fixture(): PhaseRunEntryFixture {
    val git = RecordingWorkflowGitOperations()
    val strategies = testPhaseStrategies(GoalRunnerSubtaskLauncher { error("Unexpected agent launch") }, git)
    val continuation =
      object : TestFeatureTaskRuntimeRunLoopEntry(gitOperations = git) {
        override fun run(
          context: FeatureTaskRuntimeRunLoopContext,
          beforeDrive: (FeatureTaskRuntimeRunLoop) -> Unit,
        ): FeatureTaskRuntimeRunReport {
          dispatches++
          assertEquals(1, context.recorder.loadBuildGateProgress(nestedKey)?.repairsUsed)
          return FeatureTaskRuntimeRunReport.Paused(
            "SKILL-514",
            invocation,
            "small",
            "build",
            pause,
            "build",
            emptyList(),
            branch,
          )
        }
      }
    return PhaseRunEntryFixture(strategies, git, phaseRunDatabase(home, clock), clock, continuation)
  }

  private fun pausedBuild(): GoalQualityPhaseProgress {
    val start =
      FeatureTaskRuntimePhaseLedgerEntry(FeatureTaskRuntimePhaseLedgerAction.START, 1, now, "build", 1, "codex")
    return GoalQualityPhaseProgress(
      phaseRecords =
        mapOf(
          "build" to
            FeatureTaskRuntimePhaseRecord(
              "build",
              WorkflowStepStatus.PAUSED,
              1,
              now,
              resolvedAgentId = "codex",
              blockedReason = pause,
            ),
        ),
      phaseLedger =
        listOf(
          start,
          start.copy(action = FeatureTaskRuntimePhaseLedgerAction.PAUSED, sequenceNumber = 2, blockedReason = pause),
        ),
      buildGateProgress =
        FeatureTaskRuntimeValidationGateProgress(
          gateRunCount = 1,
          gateRuns =
            listOf(
              FeatureTaskRuntimeValidationGateRunRecord(
                durationMs = 0,
                outcome = "passed",
                cacheMode = "cache_eligible",
                executedWorkUnits = 1,
                command = "compile",
                exitCode = 0,
                repositoryCheckpoint = "checkpoint",
              ),
            ),
          repairsUsed = 1,
          acceptedRepairOutputs = listOf(FeatureTaskRuntimeValidationGateRepairOutput(1, "accepted repair")),
          terminalOutcome = "paused",
          terminalReason = pause,
        ),
      buildGateProgressKey = nestedKey,
      revision = 4,
    )
  }
}
