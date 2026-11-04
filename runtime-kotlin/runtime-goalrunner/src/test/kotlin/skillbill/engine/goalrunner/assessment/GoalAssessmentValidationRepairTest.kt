package skillbill.engine.goalrunner.assessment

import org.junit.jupiter.api.io.TempDir
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentFixValidation
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalAssessmentValidationRepairTest {
  @TempDir lateinit var home: Path

  @Test
  fun `validation repairs on additional paths are recorded and committed`() {
    val fixture = repairFixture()
    fixture.onValidation = { fixture.git.worktreeStatusValue = " M src/Feature.kt\n M src/ValidationFix.kt" }

    val record = fixture.gate().run(fixture.state(), fixture.request())

    assertEquals(GoalAssessmentExitReason.CONVERGED, record.exitReason)
    assertEquals(listOf("src/Feature.kt", "src/ValidationFix.kt"), record.rounds.first().fix?.changedFiles)
    assertEquals(listOf("src/Feature.kt", "src/ValidationFix.kt"), fixture.git.stagePathsCalls)
    assertEquals(GoalAssessmentFixValidation.PASSED, record.rounds.first().fix?.validation)
  }

  @Test
  fun `a head change during validation blocks the repair commit`() {
    val fixture = repairFixture()
    fixture.onValidation = { fixture.git.headCommitShaValue = ASSESSMENT_BASE_SHA }

    val record = fixture.gate().run(fixture.state(), fixture.request())

    assertEquals(GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE, record.exitReason)
    assertEquals(GoalAssessmentFixValidation.FAILED, record.rounds.first().fix?.validation)
    assertTrue(fixture.git.createCommitMessages.isEmpty())
  }

  private fun repairFixture(): GoalAssessmentGateFixture {
    var verifications = 0
    return GoalAssessmentGateFixture(home, fixedWorktreeStatus = " M src/Feature.kt") { call ->
      when (call) {
        AssessmentCall.FIRST_SUBTASK -> "Finding: Major src/Feature.kt:10 scenario The handler drops the first call"
        AssessmentCall.VERIFIER ->
          if (verifications++ == 0) {
            "Verify GA-001: confirmed evidence src/Feature.kt:10 drops the first call"
          } else {
            "Verify GA-001: refuted evidence src/Feature.kt:10 handles the first call"
          }
        AssessmentCall.FIX -> "GA-001: fixed: src/Feature.kt handles the call"
        else -> "Finding: none"
      }
    }
  }
}
