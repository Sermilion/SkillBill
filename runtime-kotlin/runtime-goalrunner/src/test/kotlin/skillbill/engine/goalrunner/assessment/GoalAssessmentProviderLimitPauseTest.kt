package skillbill.engine.goalrunner.assessment

import org.junit.jupiter.api.io.TempDir
import skillbill.engine.goalrunner.qa.GoalPostChangeValidationResult
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentFixStatus
import skillbill.goalrunner.assessment.model.GoalAssessmentFixValidation
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeProviderLimitSignal
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalAssessmentProviderLimitPauseTest {
  @TempDir lateinit var home: Path

  @Test
  fun `assessor usage limit pauses the stage without assessment unavailable`() {
    val fixture =
      GoalAssessmentGateFixture(home, agentLaunch = { call ->
        if (call == AssessmentCall.FIRST_SUBTASK) codexProviderLimitLaunchFacts() else null
      }) { NO_FINDING }

    val stage = fixture.gate().run(fixture.state(), fixture.request())

    val paused = assertIs<GoalAssessmentStageResult.ProviderPaused>(stage)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT, paused.lastResumableStep)
    assertTrue(FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(paused.reason))
    assertTrue(paused.reason.contains("Access resets Nov 5th, 2026 2:05 AM"))
    assertNull(paused.record.exitReason)
    assertTrue(fixture.diagnostics.warnings.none { "assessment unavailable" in it })
    assertTrue(fixture.store.persisted.none { it.exitReason == GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE })
  }

  @Test
  fun `fix agent usage limit pauses at goal assessment fix without recording a failed fix`() {
    val fixture =
      GoalAssessmentGateFixture(home, agentLaunch = { call ->
        if (call == AssessmentCall.FIX) codexProviderLimitLaunchFacts() else null
      }) { call ->
        when (call) {
          AssessmentCall.FIRST_SUBTASK -> MAJOR_FINDING
          AssessmentCall.VERIFIER -> CONFIRM_FIRST
          else -> NO_FINDING
        }
      }

    val stage = fixture.gate().run(fixture.state(), fixture.request())

    val paused = assertIs<GoalAssessmentStageResult.ProviderPaused>(stage)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT_FIX, paused.lastResumableStep)
    assertTrue(FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(paused.reason))
    assertNull(paused.record.rounds.first().fix)
    assertNull(paused.record.exitReason)
    assertTrue(fixture.diagnostics.warnings.none { "the fix agent stopped at" in it })
  }

  @Test
  fun `post-fix validation usage limit pauses without marking validation failed`() {
    val pauseReason =
      FeatureTaskRuntimeProviderLimitPause.reason(
        phaseLabel = "Feature-task-runtime phase 'validate'",
        agentId = "claude",
        resumePhaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT_FIX,
        signal =
          FeatureTaskRuntimeProviderLimitSignal(
            evidence = "usage_limit_exceeded",
            resetHint = "Nov 5th, 2026 2:05 AM",
          ),
      )
    val fixture =
      GoalAssessmentGateFixture(home, fixedWorktreeStatus = " M src/Feature.kt") { call ->
        when (call) {
          AssessmentCall.FIRST_SUBTASK -> MAJOR_FINDING
          AssessmentCall.VERIFIER -> CONFIRM_FIRST
          AssessmentCall.PLAN -> "Repair the selected handler and its sibling production paths."
          AssessmentCall.FIX -> "fixed: src/Feature.kt handles the selected call"
          else -> NO_FINDING
        }
      }
    fixture.validationResult = GoalPostChangeValidationResult.Stopped("validate", pauseReason)

    val stage = fixture.gate().run(fixture.state(), fixture.request())

    val paused = assertIs<GoalAssessmentStageResult.ProviderPaused>(stage)
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_GOAL_ASSESSMENT_FIX, paused.lastResumableStep)
    assertTrue(FeatureTaskRuntimeProviderLimitPause.isProviderLimitPauseReason(paused.reason))
    val fix = paused.record.rounds.first().fix
    assertEquals(GoalAssessmentFixStatus.COMPLETED, fix?.status)
    assertEquals(GoalAssessmentFixValidation.NOT_RUN, fix?.validation)
    assertNull(paused.record.exitReason)
    assertTrue(fixture.diagnostics.warnings.none { "validation after the fix stopped at" in it })
  }

  @Test
  fun `ordinary assessor blocked stderr still records assessment unavailable`() {
    val fixture =
      GoalAssessmentGateFixture(home, agentLaunch = { call ->
        if (call == AssessmentCall.FIRST_SUBTASK) {
          codexProviderLimitLaunchFacts().copy(
            stderr = "Feature-task-runtime phase 'goal_assessment' agent exited with non-zero status 1.",
          )
        } else {
          null
        }
      }) { NO_FINDING }

    val stage = fixture.gate().runRecord(fixture.state(), fixture.request())

    assertEquals(GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE, stage.exitReason)
  }

  private companion object {
    const val NO_FINDING = "Finding: none"
    const val MAJOR_FINDING = "Finding: Major src/Feature.kt:10 scenario The handler drops the first call"
    const val CONFIRM_FIRST = "Verify GA-001: confirmed evidence src/Feature.kt:10 drops the first call"
  }
}
