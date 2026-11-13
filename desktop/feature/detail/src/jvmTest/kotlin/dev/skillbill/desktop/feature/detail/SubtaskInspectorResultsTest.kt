package dev.skillbill.desktop.feature.detail

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.AuditAttempt
import dev.skillbill.desktop.core.domain.model.AuditCriterion
import dev.skillbill.desktop.core.domain.model.AuditOutcome
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.PhaseConclusion
import dev.skillbill.desktop.core.domain.model.PhaseCard
import dev.skillbill.desktop.core.domain.model.PhaseCardState
import dev.skillbill.desktop.core.domain.model.ReviewFindingStatus
import dev.skillbill.desktop.core.domain.model.ReviewPass
import dev.skillbill.desktop.core.domain.model.ReviewReportFinding
import dev.skillbill.desktop.core.domain.model.RunningReviewPass
import dev.skillbill.desktop.core.domain.model.SubtaskAuditResult
import dev.skillbill.desktop.core.domain.model.SubtaskPhaseResults
import dev.skillbill.desktop.core.domain.model.WorkSubtask
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkControlIdentity
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SubtaskInspectorResultsTest {
  private val item = WorkItem(
    id = "goal-1", kind = WorkKind.Goal, issueKey = "GOAL-1", title = "Goal", summary = "", lifecycle = Lifecycle.Active,
    planningPrepared = true, stalledSince = null, pauseRequested = false, fresh = false, stepLabel = "implement", phaseExecution = null,
    phaseExecutionLabel = null, progress = null, planningProgress = null, activeDurationMs = null, activeDurationAsOf = null,
    startedAt = null, finishedAt = null, model = null, effort = null, agentId = null, branch = null, repositoryName = "repo",
    repositoryRoot = "/repo", liveSection = null, displayGroup = null, displayPriority = 0, requiresAttention = false,
    controlEligible = false, pauseEligible = false, running = false, recentlyFinishedPhase = false, doneWithinDay = false,
    freshnessDeadline = null, membershipDeadline = null, exactPauseReason = null, awaitsOperatorDecision = false, currentSubtaskId = null,
    reason = null, control = WorkControlIdentity(1, null, null, "repo", null, null, null, null, null, epoch, null, null, null, null, null),
  )

  private fun subtask(status: String) = WorkSubtask(1, "Build the view", status, null, null, emptyList())

  private fun finding(ref: String, status: ReviewFindingStatus) =
    ReviewReportFinding(ref, "major", "high", "bill-kotlin-code-review-persistence", "src/a/File.kt:84", "Finding $ref", status)

  /**
   * Renders subtask 1 with [open] chosen; results live only in their owning card, so a fixture without that card gains a recorded
   * one. Pill clicks store an explicit Open choice, as the view model does for a pill that is not already open.
   */
  private fun ComposeUiTest.show(status: String, detail: Detail, open: String? = defaultOpen(detail)) {
    val shown = detail.copy(
      subtasks = listOf(subtask(status)),
      phaseResults = detail.phaseResults.map { results ->
        if (results.subtaskId != 1 || open == null || results.phaseCards.any { it.phaseId == open }) results
        else results.copy(phaseCards = results.phaseCards + phaseCard(open))
      },
    )
    val choices = mutableStateOf(open?.let { mapOf(PhaseChoiceKey(item.id, SubtaskKey.Subtask(1)) to PhaseDisclosure.Open(it)) }.orEmpty())
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          SubtaskInspector(item, shown, subtask(status), choices.value, dev.skillbill.desktop.core.domain.model.ActivityResult.Loading, renderedAt = epoch, phaseResultsPagePending = false) { action ->
            if (action is DetailAction.SelectPhase) choices.value = choices.value + (PhaseChoiceKey(action.itemId, action.key) to PhaseDisclosure.Open(action.phaseId))
          }
        }
      }
    }
  }

  private fun defaultOpen(detail: Detail): String? {
    val results = detail.phaseResults.firstOrNull { it.subtaskId == 1 }
    return when {
      results?.auditResult != null -> "audit"
      results?.reviewPasses?.isNotEmpty() == true || results?.runningReviewPass != null -> "review"
      else -> null
    }
  }

  private fun results(vararg passes: ReviewPass) = SubtaskPhaseResults(
    1, passes.toList(), null, null,
    phaseCards = if (passes.isEmpty()) emptyList() else listOf(phaseCard("review")),
  )

  private fun phaseCard(id: String, state: PhaseCardState = PhaseCardState.Done) = PhaseCard(
    id, state, null, null, null, null, null, null, null, null, emptyList(), null, null, null, null, null, null, emptyList(), emptyList(), null, null,
  )

  @Test
  fun `completed passes show the count, a clean pass line, its lane and a finding's meta`() = runComposeUiTest {
    val first = ReviewPass(1, "changes_requested", 0, listOf(finding("F-001", ReviewFindingStatus.Fixed)), listOf("bill-kotlin-code-review-persistence"))
    val second = ReviewPass(2, "approved", 0, emptyList(), emptyList())
    show("complete", emptyDetail.copy(phaseResults = listOf(results(first, second))))

    onNodeWithText("2 passes").assertExists()
    onNodeWithText("Pass 2").assertExists()
    onNodeWithText("· no findings").assertExists()
    onNodeWithText("Lanes").assertExists()
    onNodeWithText("F-001").assertExists()
    onNodeWithText("File.kt:84").assertExists()
    onNodeWithText("persistence · high confidence").assertExists()
  }

  @Test
  fun `review passes render once in their card and an audit without a card stays a notice`() = runComposeUiTest {
    val pass = ReviewPass(1, "approved", 0, emptyList(), emptyList())
    val phaseResults = results(pass).copy(auditResult = SubtaskAuditResult(AuditOutcome.Passed, 1, 1, listOf(AuditCriterion("AC-1", "Works", true, null)), emptyList(), 1))
    show("complete", emptyDetail.copy(stepPipeline = listOf("review"), phaseResults = listOf(phaseResults)), open = "review")

    onAllNodesWithText("Pass 1").assertCountEquals(1)
    onNodeWithText("Phases").assertExists()
    onNodeWithText("Audit").assertDoesNotExist()
    onNodeWithText("Works").assertDoesNotExist()
    onNodeWithText("Audit results are available, but no audit phase card was recorded.").assertExists()
  }

  @Test
  fun `IS-003 review stays in its card while switching subtasks and returning to the goal`() = runComposeUiTest {
    val selectedSubtask = mutableStateOf(1)
    val goalId = mutableStateOf(item.id)
    val choices = mutableStateOf<Map<PhaseChoiceKey, PhaseDisclosure>>(emptyMap())
    val first = subtask("complete")
    val second = first.copy(id = 2, status = "in_progress")
    val review = PhaseCard("review", PhaseCardState.Done, null, null, null, null, null, null, null, null, emptyList(), null, null, null, null, null, null, emptyList(), emptyList(), null, null)
    val detail = emptyDetail.copy(
      subtasks = listOf(first, second), stepPipeline = listOf("review", "validate"),
      phaseResults = listOf(
        results(ReviewPass(1, "approved", 0, emptyList(), emptyList())).copy(phaseCards = listOf(review, review.copy(phaseId = "validate"))),
        SubtaskPhaseResults(2, emptyList(), null, null, runningReviewPass = RunningReviewPass(2, emptyList()), phaseCards = listOf(review.copy(state = PhaseCardState.Running))),
      ),
    )
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides ShellInput()) {
          key(goalId.value, selectedSubtask.value) {
            SubtaskInspector(item.copy(id = goalId.value), detail, if (selectedSubtask.value == 1) first else second, choices.value, dev.skillbill.desktop.core.domain.model.ActivityResult.Loading, epoch, phaseResultsPagePending = false) { action ->
              if (action is DetailAction.SelectPhase) choices.value = choices.value + (PhaseChoiceKey(action.itemId, action.key) to PhaseDisclosure.Open(action.phaseId))
            }
          }
        }
      }
    }
    onNode(hasText("Validate") and hasClickAction()).performClick()
    onNode(hasText("Review") and hasClickAction()).performClick()
    onAllNodesWithText("Pass 1").assertCountEquals(1)
    onNode(hasText("Pass 1") and hasAnyAncestor(hasContentDescription("Review"))).assertExists()
    runOnIdle { selectedSubtask.value = 2 }
    onNode(hasText("Review") and hasClickAction()).assertIsSelected()
    onNodeWithText("pass 2 running").assertExists()
    runOnIdle { goalId.value = "another-goal" }
    runOnIdle { goalId.value = item.id; selectedSubtask.value = 1 }
    onNode(hasText("Review") and hasClickAction()).assertIsSelected()
    onAllNodesWithText("Pass 1").assertCountEquals(1)
    onNode(hasText("Pass 1") and hasAnyAncestor(hasContentDescription("Review"))).assertExists()
  }

  @Test
  fun `an open finding reads after the fixed one and both show their state`() = runComposeUiTest {
    val pass = ReviewPass(
      1, "changes_requested", 1,
      listOf(finding("F-001", ReviewFindingStatus.Open), finding("F-002", ReviewFindingStatus.Fixed)),
      emptyList(),
    )
    show("blocked", emptyDetail.copy(phaseResults = listOf(results(pass))))

    onNodeWithText("· 2 findings · 1 fixed · 1 open").assertExists()
    onNodeWithText("Open").assertExists()
    onNodeWithText("Fixed").assertExists()
  }

  @Test
  fun `a pending subtask says when review and audit run`() = runComposeUiTest {
    show("pending", emptyDetail.copy(stepPipeline = listOf("implement", "review")))

    onNodeWithText("Runs after implementation").assertExists()
    onNodeWithText("No phase has run yet.").assertDoesNotExist()
    onNodeWithText("Implement").assertIsNotEnabled()
    onNodeWithText("Review").assertIsNotEnabled()
  }

  @Test
  fun `a reliable empty read with no cards says no phase has run`() = runComposeUiTest {
    show("complete", emptyDetail.copy(phaseResults = listOf(SubtaskPhaseResults(1, emptyList(), null, null))), open = null)

    onNodeWithText("No phase has run yet.").assertExists()
    onNodeWithText("Select a phase to see what it did.").assertDoesNotExist()
    onNodeWithText("Runs after implementation").assertDoesNotExist()
  }

  @Test
  fun `folded cards keep a degraded callout and never claim no phase ran`() = runComposeUiTest {
    val cards = listOf(phaseCard("audit"), phaseCard("review"))
    val entry = SubtaskPhaseResults(1, emptyList(), null, null, "subtask_review_findings", phaseCards = cards)
    show("complete", emptyDetail.copy(phaseResults = listOf(entry)), open = null)

    onNodeWithText("degraded at subtask_review_findings", substring = true).assertExists()
    onNodeWithText("Refresh").assertExists()
    onNodeWithText("Select a phase to see what it did.").assertExists()
    onNodeWithText("No phase has run yet.").assertDoesNotExist()
    onNodeWithText("Passed").assertDoesNotExist()
  }

  @Test
  fun `an unreadable read names its source and shows no verdict`() = runComposeUiTest {
    val audit = PhaseConclusion("audit_verdict", null, null, 1)
    val entry = SubtaskPhaseResults(1, emptyList(), audit, null, "subtask_review_findings")
    show("complete", emptyDetail.copy(stepPipeline = listOf("implement", "review"), phaseResults = listOf(entry)), open = null)

    onNodeWithText("degraded at subtask_review_findings", substring = true).assertExists()
    onNodeWithText("Refresh").assertExists()
    onNodeWithText("· audit_verdict").assertDoesNotExist()
    onNodeWithText("No phase has run yet.").assertDoesNotExist()
    onNodeWithText("Implement").assertIsNotEnabled()
    onNodeWithText("Review").assertIsNotEnabled()
    onNodeWithText("Audit").assertDoesNotExist()
  }

  @Test
  fun `a loading subtask shows captions and no pending copy`() = runComposeUiTest {
    show("complete", emptyDetail)

    onNodeWithText("Loading phase results.").assertExists()
    onNodeWithText("Refresh").assertExists()
    onNodeWithText("Runs after", substring = true).assertDoesNotExist()
    onNodeWithText("No phase has run yet.").assertDoesNotExist()
  }

  @Test
  fun `missing historical discovery scope keeps current audit criteria and review results reachable`() = runComposeUiTest {
    val audit = SubtaskAuditResult(
      AuditOutcome.Passed, 1, 1,
      listOf(AuditCriterion("AC-001", "Retains the saved state", true, null)),
      listOf(
        AuditAttempt(1, null, null, null, "Discovery scope was not retained for this historical audit."),
        AuditAttempt(2, true, 0, null),
      ),
      2,
    )
    val retained = results(ReviewPass(1, "approved", 0, emptyList(), emptyList())).copy(
      degradedReadSource = "subtask_audit_history",
      auditResult = audit,
    )
    show("complete", emptyDetail.copy(phaseResults = listOf(retained)))

    onNodeWithText("Passed · 1 of 1 criteria met").assertExists()
    onNodeWithText("Retains the saved state").assertExists()
    onNodeWithText("Attempt 1 · Historical result unavailable").assertExists()
    onNodeWithText("Some historical audit results are unavailable because their discovery scope was not retained.").assertExists()
    onNodeWithText("Review and audit ran, but their results could not be read.", substring = true).assertDoesNotExist()
    onNode(hasText("Review") and hasClickAction()).performClick()
    onNodeWithText("Pass 1").assertExists()
    onNodeWithText("Retains the saved state").assertDoesNotExist()
  }

  @Test
  fun `a passed audit shows criteria fixed in a later attempt and prior gaps`() = runComposeUiTest {
    val results = SubtaskPhaseResults(
      1,
      emptyList(),
      PhaseConclusion("satisfied", "All criteria are met.", null, 2),
      null,
      auditResult = SubtaskAuditResult(
        AuditOutcome.Passed,
        1,
        1,
        listOf(AuditCriterion("AC-001", "Retains the saved state", true, 2)),
        listOf(AuditAttempt(1, false, 1, "audit_plan_fix"), AuditAttempt(2, true, 0, null)),
        2,
      ),
    )
    show("complete", emptyDetail.copy(phaseResults = listOf(results)))

    onNodeWithText("Passed · 1 of 1 criteria met").assertExists()
    onNodeWithText("AC-001").assertExists()
    onNodeWithText("fixed in attempt 2").assertExists()
    onNodeWithText("Attempt 1 · Gaps found · 1 criterion uncovered → audit_plan_fix").assertExists()
    onNodeWithText("Attempt 2 · Passed").assertDoesNotExist()
  }

  @Test
  fun `a failed audit expands met criteria in place`() = runComposeUiTest {
    val results = SubtaskPhaseResults(
      1,
      emptyList(),
      PhaseConclusion("advance", null, null, 1),
      null,
      auditResult = SubtaskAuditResult(
        AuditOutcome.Failed,
        1,
        2,
        listOf(
          AuditCriterion("AC-001", "Retains the saved state", true, null),
          AuditCriterion("AC-002", "Rejects expired sessions", false, null),
        ),
        listOf(AuditAttempt(1, false, 1, null)),
        1,
      ),
    )
    show("blocked", emptyDetail.copy(phaseResults = listOf(results)))

    onNodeWithText("Failed · 1 of 2 criteria met").assertExists()
    onNodeWithText("AC-002").assertExists()
    onNodeWithText("not met").assertExists()
    onNodeWithText("1 criterion met").assertExists()
    onNodeWithText("Retains the saved state").assertDoesNotExist()
    onNodeWithText("Show").performClick()
    onNodeWithText("Retains the saved state").assertExists()
    onNodeWithText("Hide").assertExists()
  }

  @Test
  fun `a running review pass keeps the settled audit visible`() = runComposeUiTest {
    val passed = SubtaskAuditResult(AuditOutcome.Passed, 1, 1, emptyList(), emptyList(), 1)
    val running = SubtaskPhaseResults(
      1,
      emptyList(),
      PhaseConclusion("satisfied", null, null, 1),
      null,
      runningReviewPass = RunningReviewPass(2, emptyList()),
      auditResult = passed,
    )
    show("in_progress", emptyDetail.copy(phaseResults = listOf(running)))
    onNodeWithText("Passed · 1 of 1 criteria met").assertExists()
    onNodeWithText("1 attempt").assertExists()
    onNodeWithText("Runs after", substring = true).assertDoesNotExist()
  }

  @Test
  fun `a started review with no audit record omits the audit section`() = runComposeUiTest {
    val running = SubtaskPhaseResults(1, emptyList(), null, null, runningReviewPass = RunningReviewPass(1, emptyList()))
    show("in_progress", emptyDetail.copy(phaseResults = listOf(running)))
    onNodeWithText("Audit").assertDoesNotExist()
    onNodeWithText("Runs after", substring = true).assertDoesNotExist()
  }

  @Test
  fun `a completed subtask without an audit omits the section`() = runComposeUiTest {
    show("complete", emptyDetail.copy(phaseResults = listOf(results())))
    onNodeWithText("Audit").assertDoesNotExist()
  }

  @Test
  fun `unknown audit attempt count is not displayed as zero`() = runComposeUiTest {
    val audit = SubtaskAuditResult(AuditOutcome.Passed, null, null, emptyList(), emptyList(), null)
    show("complete", emptyDetail.copy(phaseResults = listOf(results().copy(auditResult = audit))))

    onNodeWithText("Passed").assertExists()
    onNodeWithText("0 attempts").assertDoesNotExist()
  }
}
