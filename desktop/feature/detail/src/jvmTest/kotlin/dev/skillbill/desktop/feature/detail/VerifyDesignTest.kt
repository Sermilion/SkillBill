package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.rememberUiTicker
import dev.skillbill.desktop.core.designsystem.detail.rememberSectionCollapse
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.QaAssertionOutcome
import dev.skillbill.desktop.core.domain.model.QaRunAssertion
import dev.skillbill.desktop.core.domain.model.QaRunDetails
import dev.skillbill.desktop.core.domain.model.QaRunFinding
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.VerifyCompletenessAudit
import dev.skillbill.desktop.core.domain.model.VerifyCriterionGrade
import dev.skillbill.desktop.core.domain.model.VerifyFlagAudit
import dev.skillbill.desktop.core.domain.model.VerifyFlagCheck
import dev.skillbill.desktop.core.domain.model.VerifyReviewFinding
import dev.skillbill.desktop.core.domain.model.VerifyReviewSection
import dev.skillbill.desktop.core.domain.model.VerifyRunCriterion
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails
import dev.skillbill.desktop.core.domain.model.VerifyRunStep
import dev.skillbill.desktop.core.domain.model.VerifyRunVerdict
import dev.skillbill.desktop.core.domain.model.VerifyTestValueRow
import dev.skillbill.desktop.core.domain.model.VerifyUnitTestValue
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.testing.workItem
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class VerifyDesignTest {
  @Test
  fun `criterion grades refresh and audit prose remains readable at narrow widths`() = runComposeUiTest {
    val evidence = "SyncTest.kt:322 proves the local edit survives. The pending change remains until the next write confirms it."
    var grade by mutableStateOf<VerifyCriterionGrade?>(null)
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Column(Modifier.width(330.dp)) {
          VerifyCriterionRow(VerifyCriteriaViewRow(1, "Local edits survive.", grade), open = true)
        }
      }
    }
    onNodeWithText("NOT AUDITED").assertExists()
    runOnIdle { grade = VerifyCriterionGrade(1, "PASS", "Local edits survive.", listOf(evidence), null, null, emptyList()) }
    onNodeWithText("NOT AUDITED").assertDoesNotExist()
    onNodeWithText("PASS").assertExists()
    val layouts = mutableListOf<TextLayoutResult>()
    onNodeWithText(evidence).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    kotlin.test.assertTrue(layouts.single().lineCount > 1)
    kotlin.test.assertFalse(layouts.single().hasVisualOverflow)
  }

  @Test
  fun `criteria summary never treats an empty or incomplete audit as all met`() {
    val run = verifyDesignFixture().copy(workflowStatus = "completed")
    fun grade(number: Int) = VerifyCriterionGrade(number, "PASS", "criterion", emptyList(), null, null, emptyList())
    val cases = listOf(
      emptyList<VerifyCriterionGrade>() to "2 ungraded",
      listOf(grade(1)) to "1 ungraded",
      listOf(grade(1), grade(99)) to "1 ungraded",
      listOf(grade(1), grade(2)) to "all met",
      listOf(grade(1), grade(2), grade(99).copy(status = "FAIL")) to "all met",
    )
    cases.forEach { (grades, expected) ->
      val audited = run.copy(completenessAudit = VerifyCompletenessAudit("all_pass", emptyList(), grades))
      val cell = verifyOutcomeView(audited).cells.first { it.title == "Criteria" }
      assertEquals(expected, cell.note)
      val rows = verifyCriteriaView(audited, VerifyCriteriaFilter.All).rows
      assertEquals(grades.filter { it.number in 1..2 }, rows.mapNotNull { it.grade })
      val step = VerifyRunStep("completeness_audit", "completed", 1)
      assertEquals(if (expected == "all met") VerifyScoreTone.Green else VerifyScoreTone.Amber, verifyStepTone(audited, step))
      assertEquals(rows.count { it.grade == null }, verifyStepSummaryParts(audited, step).firstOrNull { it.text.endsWith("ungraded") }?.text?.substringBefore(' ')?.toInt() ?: 0)
    }
    assertEquals("0 / 2 met", verifyOutcomeView(run.copy(completenessAudit = VerifyCompletenessAudit("all_pass", emptyList(), emptyList()))).cells.first().value)
    assertEquals("not audited", verifyScoreNotes(run)["Criteria"])
  }

  @Test
  fun `context disclosures preserve full text and long work summaries stay within three lines`() = runComposeUiTest {
    val summary = "Preserves edits while a sync write is in flight. ".repeat(20)
    val run = verifyDesignFixture().copy(workflowStatus = "completed", verdict = VerifyRunVerdict("REQUEST CHANGES", emptyList(), summary))
    var narrow by mutableStateOf(false)
    var jump = ""
    setContent {
      SkillBillTheme(reducedMotion = true) {
        val list = rememberLazyListState()
        val collapse = rememberSectionCollapse()
        LazyColumn(Modifier.width(if (narrow) 330.dp else 700.dp).background(SkillBillTheme.colors.bgPanel).padding(16.dp), state = list) {
          item { VerifyWorkSummary(run, summary) { jump = it } }
          verifyContextSection(collapse, "scope", VerifyContextKind.NonGoals, listOf("Changing the retention window.", "Replacing the sync protocol."))
          verifyContextSection(collapse, "constraints", VerifyContextKind.Constraints, listOf("Preserve records inside the retention window.", "Keep pending edits until the next write confirms them."))
          verifyContextSection(collapse, "rollout", VerifyContextKind.Rollout, listOf("1. Enable for a small workspace.\n2. Check pending edits before expanding."))
        }
      }
    }
    val layouts = mutableListOf<TextLayoutResult>()
    onNodeWithText(summary.trim()).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    assertEquals(3, layouts.single().lineCount)
    onNodeWithText("Full summary in Verdict step").performClick()
    assertEquals(verifyStepKey("verdict"), jump)
    onNodeWithText("Replacing the sync protocol.").assertDoesNotExist()
    val directory = File("build/reports/verify-design").apply { mkdirs() }
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "context-collapsed.png"))
    runOnIdle { narrow = true }
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "context-collapsed-narrow.png"))
    listOf("Non-goals", "Technical constraints", "Rollout").forEach { label ->
      onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
      onNodeWithText(label).performClick()
    }
    onNodeWithText("Check pending edits before expanding.").assertExists()
    onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Non-goals"))
    onNodeWithText("Replacing the sync protocol.").assertExists()
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "context-expanded-narrow.png"))
  }

  @Test
  fun `long verdict findings stay in the expandable step instead of the overview`() = runComposeUiTest {
    val report = "## Acceptance criteria\n\n| Criterion | Status | Gap |\n| --- | --- | --- |\n" + "| 1 | PARTIAL | Missing race coverage |\n".repeat(40)
    val warning = "Warning: QA could not exercise the offline flow."
    val run = verifyDesignFixture().copy(
      workflowStatus = "completed",
      steps = listOf(VerifyRunStep("verdict", "completed", 1)),
      verdict = VerifyRunVerdict("REQUEST CHANGES", listOf(report, warning), "Preserves edits during sync."),
    )
    setContent {
      SkillBillTheme(reducedMotion = true) {
        val list = rememberLazyListState()
        val collapse = rememberSectionCollapse()
        LazyColumn(Modifier.width(700.dp), state = list) {
          item { VerifyOutcomeCard(verifyOutcomeView(run), {}) }
          DetailSections(this, collapse, list).verifyStepsTimeline(run, verifyDesignState(run), emptyList(), {}, {})
        }
      }
    }

    onNodeWithText("Verification finished. See Steps for findings and evidence.").assertIsDisplayed()
    onNodeWithText(report).assertDoesNotExist()
    onNodeWithText(warning).assertDoesNotExist()
    onNodeWithContentDescription("Toggle step verdict").performClick()
    onNodeWithText("Acceptance criteria").assertExists()
    onAllNodesWithText("Missing race coverage").onFirst().assertExists()
    onNodeWithText(warning).assertExists()
    assertEquals("REQUEST CHANGES\n\nPreserves edits during sync.\n\n$report\n$warning", verifyReportText(run))
  }

  @Test
  fun `lifecycle cards distinguish waiting running stopped superseded and verdicts`() {
    val run = verifyDesignFixture()
    val cases = listOf(
      run.copy(workflowStatus = "pending", currentStepId = "collect_inputs", steps = run.steps.map { it.copy(status = "pending", attemptCount = 0) }) to ("Waiting to start" to VerifyScoreTone.Faint),
      run to ("Code review" to VerifyScoreTone.Active),
      run.copy(workflowStatus = "pending", currentStepId = "extract_criteria") to ("Confirm the criteria" to VerifyScoreTone.Amber),
      run.copy(workflowStatus = "pending", currentStepId = "extract_criteria", criteria = emptyList()) to ("No criteria to audit" to VerifyScoreTone.Amber),
      run.copy(workflowStatus = "failed") to ("Code review" to VerifyScoreTone.Red),
      run.copy(workflowStatus = "abandoned", supersededBy = "newer") to ("Extract criteria" to VerifyScoreTone.Faint),
    ) + listOf("APPROVE" to VerifyScoreTone.Green, "APPROVE WITH FIXES" to VerifyScoreTone.Amber, "REQUEST CHANGES" to VerifyScoreTone.Red).map { (verdict, tone) ->
      run.copy(workflowStatus = "completed", verdict = VerifyRunVerdict(verdict, emptyList(), "Work summary")) to (verdict to tone)
    }
    cases.forEach { (fixture, expected) ->
      val view = verifyOutcomeView(fixture)
      assertEquals(expected.first, view.headline)
      assertEquals(expected.second, view.tone)
      assertEquals(if (verifyRunState(fixture) in setOf(VerifyRunState.Parked, VerifyRunState.Superseded)) 0 else 4, view.cells.size)
    }
    assertNull(verifySummary(cases.first().first))
    assertEquals(VerifyScoreTone.Amber, verifyStepTone(run.copy(completenessAudit = VerifyCompletenessAudit("had_gaps", emptyList(), listOf(VerifyCriterionGrade(1, "PARTIAL", "criterion", emptyList(), "gap", null, emptyList())))), VerifyRunStep("completeness_audit", "completed", 1)))
  }

  @Test
  fun `criterion disclosures keep evidence hidden until opened and raw text readable`() = runComposeUiTest {
    val run = verifyDesignFixture().copy(workflowStatus = "completed", completenessAudit = VerifyCompletenessAudit("had_gaps", emptyList(), listOf(VerifyCriterionGrade(1, "PARTIAL", "criterion", listOf("Cleanup.kt:12"), "The last row remains", null, emptyList()))))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        val list = rememberLazyListState()
        val collapse = rememberSectionCollapse()
        val state = verifyDesignState(run)
        LazyColumn(Modifier.width(700.dp).background(SkillBillTheme.colors.bgPanel)) {
          DetailSections(this, collapse, list).verifyCriteriaSection(verifyCriteriaView(run, VerifyCriteriaFilter.All), run, state, verifyFixItems(run, emptyMap()), {})
        }
      }
    }
    onNodeWithText("The last row remains").assertDoesNotExist()
    onNodeWithText("Retention cleanup deletes every old row.").performClick()
    onNodeWithText("The last row remains").assertExists()
    onNodeWithText("Cleanup.kt:12").assertExists()
    onNodeWithText("Not a code gap here?").assertExists()
    onNodeWithText("Retention cleanup deletes every old row.").performClick()
    onNodeWithText("The last row remains").assertDoesNotExist()
  }

  @Test
  fun `design states render readable summaries and score navigation at narrow widths`() = runComposeUiTest {
    var run by mutableStateOf(verifyDesignFixture())
    var narrow by mutableStateOf(false)
    var captureList: LazyListState? = null
    var jump = ""
    var expectedColor = androidx.compose.ui.graphics.Color.Unspecified
    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalUiTicker provides rememberUiTicker(object : Clock { override fun now() = Instant.parse("2026-11-07T09:35:00Z") })) {
          expectedColor = SkillBillTheme.colors.fg
          val list = rememberLazyListState()
          captureList = list
          val collapse = rememberSectionCollapse()
          val state = verifyDesignState(run)
          LazyColumn(Modifier.width(if (narrow) 330.dp else 760.dp).background(SkillBillTheme.colors.bgPanel).padding(24.dp), state = list) {
            item { VerifyHeader(run, {}) { VerifyHeaderActions(state, run, runActionsView(state), remember { FocusRequester() }, {}, {}) } }
            DetailSections(this, collapse, list).verifyDocument(state, run, {}, { jump = it })
        }
        }
      }
    }
    val layouts = mutableListOf<TextLayoutResult>()
    onAllNodesWithText("Retention cleanup deletes every old row.").onFirst().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    assertEquals(expectedColor, layouts.single().layoutInput.style.color)
    val completed = run.copy(
      nonGoals = listOf("Changing the retention policy."), technicalConstraints = listOf("Preserve records inside the retention window."), rolloutExpectation = "Enable for a small workspace first.",
      workflowStatus = "completed", finishedAt = Instant.parse("2026-11-07T09:36:00Z"), steps = run.steps.map { it.copy(status = "completed", attemptCount = 1) },
      completenessAudit = VerifyCompletenessAudit("had_gaps", emptyList(), listOf(
        VerifyCriterionGrade(1, "PARTIAL", run.criteria[0].text, listOf("Cleanup.kt:12"), "The last row remains", null, listOf("Cleanup.kt:12")),
        VerifyCriterionGrade(2, "PASS", run.criteria[1].text, listOf("Links.kt:42"), null, null, emptyList()),
      )),
      reviewFindings = VerifyReviewSection("changes_requested", listOf("F-001"), listOf(VerifyReviewFinding("F-001", "Major", "High", "data/Cleanup.kt:12", "The last old row is retained.", emptyList(), listOf("Cleanup.kt:12")))),
      flagAudit = VerifyFlagAudit("pass", emptyList(), null, "cleanup_retention", "Boolean", "cleanup", listOf(VerifyFlagCheck(true, "Flag is declared"))),
      unitTestValue = VerifyUnitTestValue("completed", emptyList(), null, "Weak", "Test cleanup with populated data.", listOf(VerifyTestValueRow("Retention cleanup", "Weak", "High", listOf("CleanupTest.kt"), "The empty-store test misses the retention boundary.", "Rewrite", emptyList())), emptyList(), listOf("Use populated data."), emptyList(), emptyList()),
    )
    val cases = linkedMapOf(
      "running" to run,
      "pending" to run.copy(criteria = emptyList(), workflowStatus = "pending", currentStepId = "collect_inputs", steps = run.steps.map { it.copy(status = "pending", attemptCount = 0) }),
      "confirm" to run.copy(workflowStatus = "pending", currentStepId = "extract_criteria", steps = run.steps.mapIndexed { index, step -> step.copy(status = if (index < 2) "completed" else "pending", attemptCount = if (index < 2) 1 else 0) }),
      "no-criteria" to run.copy(workflowStatus = "pending", currentStepId = "extract_criteria", criteria = emptyList(), criteriaRaw = "No numbered criteria were found.", technicalConstraints = listOf("Keep user data.")),
      "stopped" to run.copy(workflowStatus = "abandoned", finishedAt = Instant.parse("2026-11-07T09:36:00Z")),
      "failed" to run.copy(workflowStatus = "failed", finishedAt = Instant.parse("2026-11-07T09:36:00Z")),
      "stalled" to run.copy(staleReason = "No progress"),
      "superseded" to run.copy(workflowStatus = "abandoned", supersededBy = "newer"),
      "approved" to completed.copy( verdict = VerifyRunVerdict("APPROVE", emptyList(), "Deletes records before the retention window.")),
      "request-changes" to completed.copy( verdict = VerifyRunVerdict("REQUEST CHANGES", listOf("Old records remain."), "Deletes records before the retention window.")),
      "qa-fixes" to completed.copy(qa = QaRunDetails(assertions = listOf(QaRunAssertion(QaAssertionOutcome.Passed, "New records remain", "The current record is retained.", listOf("retained.png")), QaRunAssertion(QaAssertionOutcome.NotExercised, "Remote config floor", "The config service rejected this value.")), findings = listOf(QaRunFinding("QA-1", "Old records remain visible", "Old records remain after cleanup.", "Only records in the retention window remain.", "Run cleanup against populated data.", listOf("cleanup.png")))), qaSelection = "on", qaVerdict = "findings_open", qaReportText = "## Isolation\nUsed a scratch database.\n\n## Cleanup\nRemoved temporary records.", verdict = VerifyRunVerdict("APPROVE WITH FIXES", listOf("Warning: QA findings"), "Deletes records before the retention window.")),
    )
    val directory = File("build/reports/verify-design").apply { mkdirs() }
    cases.forEach { (name, fixture) ->
      runOnIdle { run = fixture }
      waitForIdle()
      ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
    }
    runOnIdle { run = cases.getValue("qa-fixes") }
    cases.getValue("qa-fixes").steps.filter { verifyStepHasReceipt(cases.getValue("qa-fixes"), it) }.forEach { step ->
      val title = verifyStepName(step.stepId)
      onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Toggle step ${step.stepId}"))
      onNodeWithContentDescription("Toggle step ${step.stepId}").performClick()
      waitForIdle()
      var headerIndex = 0
      runOnIdle { headerIndex = captureList!!.layoutInfo.visibleItemsInfo.first { it.key == if (step.stepId == "qa") VERIFY_QA_KEY else verifyStepKey(step.stepId) }.index }
      onNode(hasScrollToIndexAction()).performScrollToIndex(headerIndex)
      ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "expanded-${title.lowercase().replace(' ', '-')}.png"))
    }
    listOf("QA-1 Old records remain visible", "Passed · 1", "Full report").forEachIndexed { index, label ->
      val matcher = if (index == 0) hasText("Old records remain visible", substring = true) else hasText(label)
      onNode(hasScrollToIndexAction()).performScrollToNode(matcher)
      onNode(matcher).performClick()
      waitForIdle()
      ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "expanded-qa-$index.png"))
    }
    onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Removed temporary records."))
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "expanded-qa-report.png"))
    runOnIdle { run = cases.getValue("request-changes") }
    listOf("Non-goals", "Technical constraints", "Rollout").forEach { label ->
      onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
      onNodeWithText(label).performClick()
    }
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "expanded-context.png"))
    onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Criteria", substring = false))
    onNodeWithText("Retention cleanup deletes every old row.").performClick()
    onNodeWithText("The last row remains").assertExists()
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "graded-criteria.png"))
    runOnIdle { run = cases.getValue("running"); narrow = true }
    onNode(hasScrollToIndexAction()).performScrollToIndex(0)
    onNodeWithContentDescription("Criteria: 2 extracted. Go to Criteria").performClick()
    assertEquals(VERIFY_CRITERIA_KEY, jump)
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "running-narrow.png"))
  }

  @Test
  fun `selected fix actions stay visible after scrolling the verify document`() = runComposeUiTest {
    val run = verifyDesignFixture().copy(workflowStatus = "completed", verdict = VerifyRunVerdict("REQUEST CHANGES", emptyList(), "Keeps recent records."), completenessAudit = VerifyCompletenessAudit("had_gaps", emptyList(), listOf(VerifyCriterionGrade(1, "PARTIAL", "Retention cleanup", listOf("Cleanup.kt:12"), "The last row remains", null, emptyList()))))
    var state by mutableStateOf(verifyDesignState(run).copy(fixSelection = mapOf("c:1" to FixRoute.Chat)))
    val actions = mutableListOf<DetailAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Column(Modifier.width(760.dp).fillMaxSize().background(SkillBillTheme.colors.bgPanel)) {
          DetailScreen(state, DetailScreenHost(false, "Ctrl I", {}), actions::add)
        }
      }
    }
    onNodeWithText("Fix 1 in chat").assertIsDisplayed()
    onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Activity"))
    onNodeWithText("Fix 1 in chat").assertIsDisplayed()
    onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Criteria"))
    onNodeWithText("Retention cleanup deletes every old row.").performClick()
    onNodeWithText("Chat").assertIsDisplayed()
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/reports/verify-design/selected-criterion.png"))
    runOnIdle { state = state.copy(fixSelection = emptyMap(), criterionMarks = mapOf(1 to dev.skillbill.desktop.core.domain.settings.CriterionMark.NotInRepo)) }
    onNodeWithText("Clear mark").assertIsDisplayed()
    onNodeWithText("Clear mark").performClick()
    assertEquals(DetailAction.MarkCriterion(1, null), actions.last())
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File("build/reports/verify-design/marked-criterion.png"))
  }

}

internal fun verifyDesignFixture() = VerifyRunDetails(
  workflowId = "verify-design", issueKey = "WE-5246", workflowStatus = "running", currentStepId = "code_review",
  startedAt = Instant.parse("2026-11-07T09:31:00Z"), finishedAt = null, intake = "https://linear.app/team/issue/WE-5246/cleanup", target = "HEAD", repoRoot = "/repo/capmo-android", baseRevision = "a12c54f", headRevision = "3878091", reviewMode = "inline", qaSelection = "off", specPath = null,
  steps = VERIFY_STEP_NAMES.keys.mapIndexed { index, id -> VerifyRunStep(id, if (index < 4) "completed" else if (index == 4) "running" else "pending", if (index <= 4) 1 else 0) },
  criteria = listOf(VerifyRunCriterion(1, "Retention cleanup deletes every old row."), VerifyRunCriterion(2, "Pending links keep the correct sync status.")), criteriaRaw = null,
  nonGoals = emptyList(), technicalConstraints = emptyList(), rolloutExpectation = null, completenessAudit = null, reviewFindings = null, flagAudit = null, unitTestValue = null, changedFiles = listOf("data/Cleanup.kt", "data/CleanupTest.kt"), verdict = null,
  qa = null, qaVerdict = null, qaOutcome = null, qaFindingLocations = emptyMap(), supersededBy = null, staleReason = null,
)

internal fun verifyDesignState(run: VerifyRunDetails) = DetailUiState(
  workItem("verify-design", lifecycle = if (run.workflowStatus == "running") Lifecycle.Active else if (run.workflowStatus == "completed") Lifecycle.Terminal else Lifecycle.Blocked).copy(kind = WorkKind.Operation), SectionsUi.Loaded(emptyDetail.copy(verifyRun = run), RunOptions(true, true, "Resume", "codex", listOf("codex"), null), emptyMap(), emptySet()), false, epoch,
)
