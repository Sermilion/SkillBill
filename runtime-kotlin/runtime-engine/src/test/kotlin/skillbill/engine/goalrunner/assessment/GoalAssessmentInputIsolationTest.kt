package skillbill.engine.goalrunner.assessment

import org.junit.jupiter.api.io.TempDir
import skillbill.engine.featuretask.phase.prompt.compose.PhaseProjectInstructions
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoalAssessmentInputIsolationTest {
  @TempDir lateinit var home: Path

  @Test
  fun `every assessor role receives the specs and ranges and none receives a pipeline verdict`() {
    val phasesDirectory = Files.createDirectories(home.resolve(PhaseProjectInstructions.PHASES_DIR))
    Files.writeString(phasesDirectory.resolve("goal_assessment.md"), PROJECT_INSTRUCTIONS_MARKER)
    val store = AssessmentOutcomeStore(goalQaOutcome = GoalAssessmentGateFixture.qaOutcome(QA_SENTINEL))
    val fixture =
      GoalAssessmentGateFixture(
        home,
        store,
        { id -> GoalAssessmentGateFixture.committedSubtask(id, BLOCKED_SENTINEL, RESUMABLE_SENTINEL) },
      ) { call ->
        when (call) {
          AssessmentCall.FIRST_SUBTASK -> "Finding: Major src/Feature.kt:10 scenario The handler drops the first call"
          AssessmentCall.VERIFIER -> "Verify GA-001: confirmed evidence src/Feature.kt:10 drops the first call"
          else -> "Finding: none"
        }
      }

    assertIs<GoalAssessmentRoundResult.Assessed>(fixture.gate().runRound(fixture.state(), fixture.request(), 1))

    assertEquals(
      setOf(
        AssessmentCall.FIRST_SUBTASK,
        AssessmentCall.SECOND_SUBTASK,
        AssessmentCall.CROSS_SUBTASK,
        AssessmentCall.VERIFIER,
      ),
      fixture.prompts.keys,
    )
    fixture.prompts.forEach { (call, prompt) ->
      REQUIRED.forEach { required -> assertTrue(required in prompt, "$call prompt lacks '$required'") }
      SENTINELS.forEach { sentinel -> assertFalse(sentinel in prompt, "$call prompt carries '$sentinel'") }
    }
  }

  private companion object {
    const val PROJECT_INSTRUCTIONS_MARKER = "PROJECT-INSTRUCTIONS-MARKER-7g8h"
    const val QA_SENTINEL = "SENTINEL-QA-VERDICT-1a2b"
    const val BLOCKED_SENTINEL = "SENTINEL-REVIEW-FINDING-3c4d"
    const val RESUMABLE_SENTINEL = "SENTINEL-AUDIT-RESULT-5e6f"
    val SENTINELS = listOf(QA_SENTINEL, BLOCKED_SENTINEL, RESUMABLE_SENTINEL)
    val REQUIRED =
      listOf(
        PROJECT_INSTRUCTIONS_MARKER,
        PARENT_SPEC_MARKER,
        FIRST_SUBTASK_SPEC_MARKER,
        SECOND_SUBTASK_SPEC_MARKER,
        "IS-001",
        "TR-001",
        "$ASSESSMENT_BASE_SHA..$ASSESSMENT_FIRST_SHA",
        "$ASSESSMENT_FIRST_SHA..$ASSESSMENT_HEAD_SHA",
      )
  }
}
