package skillbill.engine.goalrunner.assessment

import org.junit.jupiter.api.io.TempDir
import skillbill.engine.featuretask.phase.prompt.directives.MATERIALITY_RULE
import skillbill.engine.goalrunner.persist.assessment.GoalAssessmentRecordRead
import skillbill.goalrunner.assessment.model.GoalAssessmentDisposition
import skillbill.goalrunner.assessment.model.GoalAssessmentExitReason
import skillbill.goalrunner.assessment.model.GoalAssessmentFinding
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingKind
import skillbill.goalrunner.assessment.model.GoalAssessmentFindingState
import skillbill.goalrunner.assessment.model.GoalAssessmentFix
import skillbill.goalrunner.assessment.model.GoalAssessmentFixStatus
import skillbill.goalrunner.assessment.model.GoalAssessmentFixValidation
import skillbill.goalrunner.assessment.model.GoalAssessmentRecord
import skillbill.goalrunner.assessment.model.GoalAssessmentRound
import skillbill.goalrunner.assessment.model.GoalAssessmentVerification
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitIdentity
import skillbill.ports.workflow.gitops.model.WorkflowGitCommitIdentityResult
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeReviewSeverity
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoalAssessmentLoopTest {
  @TempDir lateinit var home: Path

  @Test
  fun `each finding gets three attempts and independent verification without repeating discovery`() {
    var verifications = 0
    val fixture =
      GoalAssessmentGateFixture(home, fixedWorktreeStatus = " M src/Feature.kt") { call ->
        when (call) {
          AssessmentCall.FIRST_SUBTASK ->
            MAJOR_FINDING +
              "\nFinding: Minor src/Feature.kt:20 scenario The handler drops the second call"
          AssessmentCall.VERIFIER ->
            when (++verifications) {
              1 -> "$CONFIRM_FIRST\n${verdict(2, "confirmed")}"
              2, 3 -> CONFIRM_FIRST
              4 -> REFUTE_FIRST
              5, 6 -> verdict(2, "confirmed")
              else -> verdict(2, "refuted")
            }
          AssessmentCall.PLAN -> "Repair the selected handler and its sibling production paths."
          AssessmentCall.FIX -> "fixed: src/Feature.kt handles the selected call"
          else -> NO_FINDING
        }
      }

    val record = fixture.gate().run(fixture.state(), fixture.request())

    assertEquals(GoalAssessmentExitReason.CONVERGED, record.exitReason)
    assertEquals(7, record.rounds.size)
    assertEquals(
      listOf("GA-001", "GA-001", "GA-001", "GA-002", "GA-002", "GA-002", null),
      record.rounds.map {
        it.targetRef
      },
    )
    assertEquals(6, fixture.calls.count { it == AssessmentCall.FIX })
    assertEquals(6, fixture.calls.count { it == AssessmentCall.PLAN })
    assertEquals(1, fixture.calls.count { it == AssessmentCall.FIRST_SUBTASK })
    assertEquals(1, fixture.calls.count { it == AssessmentCall.SECOND_SUBTASK })
    assertEquals(1, fixture.calls.count { it == AssessmentCall.CROSS_SUBTASK })
    assertEquals(emptyList(), record.openFindingRefs)
    assertTrue(record.rounds.flatMap { it.findings }.all { it.state == GoalAssessmentFindingState.FIXED })
    assertTrue(fixture.prompts.getValue(AssessmentCall.FIX).contains("Repair the selected handler"))
  }

  @Test
  fun `three no change repairs block and a reload preserves the exhausted budget`() {
    val fixture =
      GoalAssessmentGateFixture(home) { call ->
        when (call) {
          AssessmentCall.FIRST_SUBTASK -> MAJOR_FINDING
          AssessmentCall.VERIFIER -> CONFIRM_FIRST
          AssessmentCall.FIX -> "GA-001: not fixed: the implementation is unfinished"
          else -> NO_FINDING
        }
      }

    val record = fixture.gate().run(fixture.state(), fixture.request())
    val launches = fixture.calls.toList()
    val reloaded = fixture.gate().run(fixture.state(), fixture.request())

    assertEquals(GoalAssessmentExitReason.PERSISTENT_FINDING, record.exitReason)
    assertEquals(3, fixture.calls.count { it == AssessmentCall.FIX })
    assertEquals(4, fixture.calls.count { it == AssessmentCall.VERIFIER })
    assertEquals(3, record.rounds.flatMap { it.findings }.single().fixRoundsSurvived)
    assertEquals(record, reloaded)
    assertEquals(launches, fixture.calls)
    assertTrue(fixture.git.createCommitMessages.isEmpty())
  }

  @Test
  fun `a regression repair is independently checked and only converges when restored`() {
    var checks = 0
    val fixture =
      GoalAssessmentGateFixture(home, fixedWorktreeStatus = " M src/Feature.kt") { call ->
        when (call) {
          AssessmentCall.CROSS_SUBTASK ->
            "$NO_FINDING\nRegression TR-001: not_restored evidence src/Feature.kt:3 the legacy path still runs"
          AssessmentCall.REGRESSION_VERIFY ->
            "Regression TR-001: ${if (++checks == 1) "not_restored" else "restored"} evidence src/Feature.kt:3"
          AssessmentCall.FIX -> "TR-001: fixed: src/Feature.kt removes the legacy path"
          else -> NO_FINDING
        }
      }

    val record = fixture.gate().run(fixture.state(), fixture.request())

    assertEquals(GoalAssessmentExitReason.CONVERGED, record.exitReason)
    assertEquals(2, fixture.calls.count { it == AssessmentCall.FIX })
    assertEquals(2, checks)
    assertEquals(null, record.rounds.last().fix)
  }

  @Test
  fun `a missing verifier verdict blocks instead of accepting an unverified defect`() {
    val fixture =
      GoalAssessmentGateFixture(home) { call ->
        if (call == AssessmentCall.FIRST_SUBTASK) MAJOR_FINDING else NO_FINDING
      }

    val record = fixture.gate().run(fixture.state(), fixture.request())

    assertEquals(GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE, record.exitReason)
    assertEquals(listOf("GA-001"), record.openFindingRefs)
    assertTrue(AssessmentCall.FIX !in fixture.calls)
  }

  @Test
  fun `assessment and verifier prompts admit only material defects`() {
    val fixture =
      GoalAssessmentGateFixture(home) { call ->
        when (call) {
          AssessmentCall.FIRST_SUBTASK -> MAJOR_FINDING
          AssessmentCall.VERIFIER -> REFUTE_FIRST
          else -> NO_FINDING
        }
      }

    fixture.gate().run(fixture.state(), fixture.request())

    listOf(AssessmentCall.FIRST_SUBTASK, AssessmentCall.CROSS_SUBTASK, AssessmentCall.VERIFIER).forEach { call ->
      assertTrue(MATERIALITY_RULE in fixture.prompts.getValue(call))
    }
  }

  @Test
  fun `resume adopts a round commit that landed before its sha was recorded`() {
    val fixture = unrecordedCommitFixture(parentSha = ASSESSMENT_HEAD_SHA, changedPaths = listOf("src/Feature.kt"))

    val record = fixture.gate().run(fixture.state(), fixture.request())

    assertEquals(FIRST_FIX_SHA, record.rounds.first().fix?.commitSha)
    assertTrue(fixture.git.createCommitMessages.isEmpty())
    assertTrue(AssessmentCall.FIX !in fixture.calls)
    assertEquals(GoalAssessmentExitReason.CONVERGED, record.exitReason)
  }

  @Test
  fun `resume does not adopt a head commit that is not the round's own commit`() {
    val fixture = unrecordedCommitFixture(parentSha = ASSESSMENT_BASE_SHA, changedPaths = listOf("src/Feature.kt"))

    val record = fixture.gate().run(fixture.state(), fixture.request())

    assertEquals(null, record.rounds.first().fix?.commitSha)
    assertTrue(fixture.git.createCommitMessages.isEmpty())
    assertTrue(fixture.diagnostics.warnings.any { "not the commit this round intended" in it })
    assertEquals(GoalAssessmentExitReason.ASSESSMENT_UNAVAILABLE, record.exitReason)
    assertEquals(1, record.rounds.size)
  }

  @Test
  fun `fresh discovery can resume after branch alignment changes an unassessed head`() {
    val round =
      GoalAssessmentRound(
        round = 1,
        headSha = FIRST_FIX_SHA,
        scopeFiles = emptyList(),
        assessorRuns = emptyList(),
        findings = emptyList(),
        scenarios = emptyList(),
        regressions = emptyList(),
        fix = null,
      )
    val stored = GoalAssessmentRecordRead.Recorded(GoalAssessmentRecord("SKILL-442", listOf(round)))
    val fixture = GoalAssessmentGateFixture(home, AssessmentOutcomeStore(stored)) { NO_FINDING }

    val record = fixture.gate().run(fixture.state(), fixture.request())

    assertEquals(GoalAssessmentExitReason.CONVERGED, record.exitReason)
    assertEquals(ASSESSMENT_HEAD_SHA, record.rounds.single().headSha)
    assertEquals(1, fixture.calls.count { it == AssessmentCall.FIRST_SUBTASK })
  }

  @Test
  fun `a recorded exit skips the stage and the no change path records skipped_no_changes once`() {
    val exited = GoalAssessmentRecord("SKILL-442", exitReason = GoalAssessmentExitReason.CONVERGED)
    val recorded =
      GoalAssessmentGateFixture(home, AssessmentOutcomeStore(GoalAssessmentRecordRead.Recorded(exited))) {
        error("A recorded exit must not launch an assessor.")
      }

    assertEquals(exited, recorded.gate().run(recorded.state(), recorded.request()))
    recorded.gate().skipNoChanges(recorded.state())
    assertTrue(recorded.store.persisted.isEmpty())

    val skipped = GoalAssessmentGateFixture(home) { error("The no change path must not launch an assessor.") }
    skipped.gate().skipNoChanges(skipped.state())

    assertEquals(GoalAssessmentExitReason.SKIPPED_NO_CHANGES, skipped.store.persisted.single().exitReason)
    assertTrue(skipped.diagnostics.warnings.any { "reason=no_changes" in it }, skipped.diagnostics.warnings.toString())
  }

  private fun verdict(
    number: Int,
    word: String,
  ) = "Verify GA-00$number: $word evidence src/Feature.kt:${10 * number}"

  private fun unrecordedCommitFixture(
    parentSha: String,
    changedPaths: List<String>,
  ): GoalAssessmentGateFixture {
    val unrecorded =
      GoalAssessmentFix(
        GoalAssessmentFixStatus.COMPLETED,
        listOf("src/Feature.kt"),
        null,
        GoalAssessmentFixValidation.PASSED,
      )
    val round =
      GoalAssessmentRound(
        round = 1,
        headSha = ASSESSMENT_HEAD_SHA,
        scopeFiles = emptyList(),
        assessorRuns = emptyList(),
        findings = listOf(confirmedFinding(fixRoundsSurvived = 0)),
        scenarios = emptyList(),
        regressions = emptyList(),
        fix = unrecorded,
        targetRef = "GA-001",
        repairPlan = "Repair the handler.",
      )
    val stored = GoalAssessmentRecordRead.Recorded(GoalAssessmentRecord("SKILL-442", listOf(round)))
    val fixture =
      GoalAssessmentGateFixture(home, AssessmentOutcomeStore(stored)) { call ->
        if (call == AssessmentCall.VERIFIER) REFUTE_FIRST else NO_FINDING
      }
    fixture.git.headCommitShaValue = FIRST_FIX_SHA
    fixture.git.changedPathsBetweenCommitsValue = changedPaths
    fixture.git.commitIdentityResult =
      WorkflowGitCommitIdentityResult.Found(WorkflowGitCommitIdentity("tree", listOf(parentSha)))
    return fixture
  }

  private fun confirmedFinding(fixRoundsSurvived: Int) =
    GoalAssessmentFinding(
      findingRef = "GA-001",
      kind = GoalAssessmentFindingKind.DEFECT,
      severity = FeatureTaskRuntimeReviewSeverity.MAJOR,
      path = "src/Feature.kt",
      line = 10,
      failureScenario = "The handler drops the first call",
      guardedRef = null,
      subtaskId = 1,
      originRound = 1,
      verification = GoalAssessmentVerification.CONFIRMED,
      verificationEvidence = "src/Feature.kt:10",
      fixRoundsSurvived = fixRoundsSurvived,
      disposition = GoalAssessmentDisposition.FIX,
      state = GoalAssessmentFindingState.OPEN,
    )

  private companion object {
    const val NO_FINDING = "Finding: none"
    const val MAJOR_FINDING = "Finding: Major src/Feature.kt:10 scenario The handler drops the first call"
    const val CONFIRM_FIRST = "Verify GA-001: confirmed evidence src/Feature.kt:10 drops the first call"
    const val REFUTE_FIRST = "Verify GA-001: refuted evidence src/Feature.kt:10 handles the first call"
    const val FIRST_FIX_SHA = "e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3"
  }
}
