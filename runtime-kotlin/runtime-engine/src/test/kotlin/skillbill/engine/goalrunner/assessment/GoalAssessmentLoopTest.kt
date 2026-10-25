package skillbill.engine.goalrunner.assessment

import org.junit.jupiter.api.io.TempDir
import skillbill.engine.goalrunner.persist.assessment.GoalAssessmentRecordRead
import skillbill.engine.goalrunner.qa.GoalPostChangeValidationResult
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
  fun `a Major that survives two committed fixes exits as persistent and is scoped to the fix files`() {
    val fixture =
      GoalAssessmentGateFixture(home, fixedWorktreeStatus = " M src/Feature.kt") { call ->
        when (call) {
          AssessmentCall.FIRST_SUBTASK -> MAJOR_FINDING
          AssessmentCall.VERIFIER -> CONFIRM_FIRST
          AssessmentCall.FIX -> "GA-001: fixed: src/Feature.kt guards the first call"
          else -> NO_FINDING
        }
      }

    val record = fixture.gate().run(fixture.state(), fixture.request())

    assertEquals(GoalAssessmentExitReason.PERSISTENT_FINDING, record.exitReason)
    assertEquals(listOf("GA-001"), record.openFindingRefs)
    assertEquals(listOf("GA-001"), record.rounds.flatMap { it.findings }.map { it.findingRef })
    assertEquals(2, record.rounds.flatMap { it.findings }.single().fixRoundsSurvived)
    assertEquals(
      listOf("SKILL-442: goal assessment round 1", "SKILL-442: goal assessment round 2"),
      fixture.git.createCommitMessages,
    )
    val scoped = listOf("src/Feature.kt")
    assertEquals(listOf(emptyList(), scoped, scoped), record.rounds.map { it.scopeFiles })
    val expectedCalls =
      listOf(
        AssessmentCall.FIRST_SUBTASK,
        AssessmentCall.SECOND_SUBTASK,
        AssessmentCall.CROSS_SUBTASK,
        AssessmentCall.VERIFIER,
        AssessmentCall.FIX,
        AssessmentCall.FIRST_SUBTASK,
        AssessmentCall.SECOND_SUBTASK,
        AssessmentCall.VERIFIER,
        AssessmentCall.FIX,
        AssessmentCall.FIRST_SUBTASK,
        AssessmentCall.SECOND_SUBTASK,
        AssessmentCall.VERIFIER,
      )
    assertEquals(expectedCalls, fixture.calls)
  }

  @Test
  fun `a new Major every round ends at the round cap after three commits`() {
    var firstSubtaskCalls = 0
    var verifierCalls = 0
    val fixture =
      GoalAssessmentGateFixture(home, fixedWorktreeStatus = " M src/Feature.kt") { call ->
        when (call) {
          AssessmentCall.FIRST_SUBTASK -> {
            firstSubtaskCalls++
            val line = 10 * firstSubtaskCalls
            "Finding: Major src/Feature.kt:$line scenario The handler drops call $firstSubtaskCalls"
          }
          AssessmentCall.VERIFIER -> {
            verifierCalls++
            when (verifierCalls) {
              1 -> CONFIRM_FIRST
              2 -> "${verdict(1, "refuted")}\n${verdict(2, "confirmed")}"
              else -> "${verdict(2, "refuted")}\n${verdict(3, "confirmed")}"
            }
          }
          AssessmentCall.FIX -> "GA-00$firstSubtaskCalls: fixed: src/Feature.kt"
          else -> NO_FINDING
        }
      }

    val record = fixture.gate().run(fixture.state(), fixture.request())

    assertEquals(GoalAssessmentExitReason.ROUND_CAP, record.exitReason)
    assertEquals(3, record.rounds.size)
    assertEquals((1..3).map { "SKILL-442: goal assessment round $it" }, fixture.git.createCommitMessages)
    assertEquals(listOf("GA-003"), record.openFindingRefs)
    val states = record.rounds.flatMap { it.findings }.associate { it.findingRef to it.state }
    assertEquals(GoalAssessmentFindingState.FIXED, states.getValue("GA-001"))
    assertEquals(GoalAssessmentFindingState.FIXED, states.getValue("GA-002"))
  }

  @Test
  fun `a regression only round runs one fix with the regression and converges`() {
    val fixture =
      GoalAssessmentGateFixture(home, fixedWorktreeStatus = " M src/Feature.kt") { call ->
        when (call) {
          AssessmentCall.CROSS_SUBTASK ->
            "$NO_FINDING\nRegression TR-001: not_restored evidence src/Feature.kt:3 the legacy path still runs"
          AssessmentCall.FIX -> "TR-001: fixed: src/Feature.kt removes the legacy path"
          else -> NO_FINDING
        }
      }

    val record = fixture.gate().run(fixture.state(), fixture.request())

    assertEquals(GoalAssessmentExitReason.CONVERGED, record.exitReason)
    assertEquals(1, fixture.calls.count { it == AssessmentCall.FIX })
    assertTrue("TR-001" in fixture.prompts.getValue(AssessmentCall.FIX))
    assertEquals(listOf("SKILL-442: goal assessment round 1"), fixture.git.createCommitMessages)
  }

  @Test
  fun `a failed validation is only a warning, the round commit stands and only the fixed subtask is reassessed`() {
    var verifierCalls = 0
    val fixture =
      GoalAssessmentGateFixture(home, fixedWorktreeStatus = " M src/A.kt\n M src/B.kt") { call ->
        when (call) {
          AssessmentCall.FIRST_SUBTASK -> MAJOR_FINDING
          AssessmentCall.VERIFIER -> if (++verifierCalls == 1) CONFIRM_FIRST else REFUTE_FIRST
          AssessmentCall.FIX -> "GA-001: fixed: src/A.kt and src/B.kt"
          else -> NO_FINDING
        }
      }
    fixture.validationResult = GoalPostChangeValidationResult.Stopped("lint", "ktlint failed")

    val record = fixture.gate().run(fixture.state(), fixture.request())

    val fix = assertNotNullFix(record.rounds.first())
    assertEquals(GoalAssessmentFixValidation.FAILED, fix.validation)
    assertEquals(1, fixture.validations)
    assertTrue(fixture.diagnostics.warnings.any { "ktlint failed" in it }, fixture.diagnostics.warnings.toString())
    assertEquals(listOf("SKILL-442: goal assessment round 1"), fixture.git.createCommitMessages)
    assertEquals(listOf("src/A.kt", "src/B.kt"), fixture.git.stagePathsCalls)
    assertEquals(listOf("src/A.kt", "src/B.kt"), record.rounds[1].scopeFiles)
    assertEquals(
      listOf(AssessmentCall.VERIFIER, AssessmentCall.FIX, AssessmentCall.FIRST_SUBTASK, AssessmentCall.VERIFIER),
      fixture.calls.drop(3),
    )
    assertEquals(GoalAssessmentFindingState.FIXED, record.rounds.flatMap { it.findings }.single().state)
    assertEquals(GoalAssessmentExitReason.CONVERGED, record.exitReason)
    assertEquals(emptyList(), record.openFindingRefs)
  }

  @Test
  fun `a fix that changes nothing makes no commit and later rounds only re-verify the carried finding`() {
    val fixture =
      GoalAssessmentGateFixture(home) { call ->
        when (call) {
          AssessmentCall.FIRST_SUBTASK -> MAJOR_FINDING
          AssessmentCall.VERIFIER -> CONFIRM_FIRST
          AssessmentCall.FIX -> "GA-001: not fixed: the cause is outside this repository"
          else -> NO_FINDING
        }
      }

    val record = fixture.gate().run(fixture.state(), fixture.request())

    assertEquals(GoalAssessmentFixStatus.NO_CHANGE, assertNotNullFix(record.rounds.first()).status)
    assertTrue(fixture.git.createCommitMessages.isEmpty())
    assertEquals(3, fixture.calls.count { it == AssessmentCall.VERIFIER })
    assertEquals(1, fixture.calls.count { it == AssessmentCall.FIRST_SUBTASK })
    assertEquals(GoalAssessmentExitReason.PERSISTENT_FINDING, record.exitReason)
  }

  @Test
  fun `resume continues after the last committed fix without a second commit or re-running the fix`() {
    val store = AssessmentOutcomeStore(GoalAssessmentRecordRead.Recorded(committedTwoRoundRecord()))
    val fixture =
      GoalAssessmentGateFixture(home, store) { call ->
        when (call) {
          AssessmentCall.FIRST_SUBTASK -> MAJOR_FINDING
          AssessmentCall.VERIFIER -> CONFIRM_FIRST
          else -> NO_FINDING
        }
      }
    fixture.git.headCommitShaValue = SECOND_FIX_SHA

    val record = fixture.gate().run(fixture.state(), fixture.request())

    assertEquals(GoalAssessmentExitReason.PERSISTENT_FINDING, record.exitReason)
    assertEquals(3, record.rounds.size)
    assertEquals(listOf("src/Feature.kt"), record.rounds[2].scopeFiles)
    assertEquals(listOf(FIRST_FIX_SHA, SECOND_FIX_SHA), record.rounds.take(2).map { it.fix?.commitSha })
    assertTrue(fixture.git.createCommitMessages.isEmpty())
    assertTrue(AssessmentCall.FIX !in fixture.calls)
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
    assertEquals(emptyList(), record.rounds[1].scopeFiles)
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

  private fun assertNotNullFix(round: GoalAssessmentRound): GoalAssessmentFix =
    checkNotNull(round.fix) { "Round ${round.round} recorded no fix." }

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

  private fun committedTwoRoundRecord(): GoalAssessmentRecord {
    val finding = confirmedFinding(fixRoundsSurvived = 1)

    fun committed(sha: String) =
      GoalAssessmentFix(
        GoalAssessmentFixStatus.COMPLETED,
        listOf("src/Feature.kt"),
        sha,
        GoalAssessmentFixValidation.PASSED,
      )
    val first =
      GoalAssessmentRound(
        round = 1,
        headSha = ASSESSMENT_HEAD_SHA,
        scopeFiles = emptyList(),
        assessorRuns = emptyList(),
        findings = listOf(finding),
        scenarios = emptyList(),
        regressions = emptyList(),
        fix = committed(FIRST_FIX_SHA),
      )
    val second =
      first.copy(
        round = 2,
        headSha = FIRST_FIX_SHA,
        scopeFiles = listOf("src/Feature.kt"),
        findings = emptyList(),
        fix = committed(SECOND_FIX_SHA),
      )
    return GoalAssessmentRecord("SKILL-442", listOf(first, second), openFindingRefs = listOf("GA-001"))
  }

  private companion object {
    const val NO_FINDING = "Finding: none"
    const val MAJOR_FINDING = "Finding: Major src/Feature.kt:10 scenario The handler drops the first call"
    const val CONFIRM_FIRST = "Verify GA-001: confirmed evidence src/Feature.kt:10 drops the first call"
    const val REFUTE_FIRST = "Verify GA-001: refuted evidence src/Feature.kt:10 handles the first call"
    const val FIRST_FIX_SHA = "e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3e3"
    const val SECOND_FIX_SHA = "f4f4f4f4f4f4f4f4f4f4f4f4f4f4f4f4f4f4f4f4"
  }
}
