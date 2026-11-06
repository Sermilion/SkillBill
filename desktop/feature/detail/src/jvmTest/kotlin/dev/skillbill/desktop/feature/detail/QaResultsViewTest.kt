package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.rememberSectionCollapse
import dev.skillbill.desktop.core.domain.model.ExecutionInstance
import dev.skillbill.desktop.core.domain.model.GoalQaFinding
import dev.skillbill.desktop.core.domain.model.GoalQaResult
import dev.skillbill.desktop.core.domain.model.QaAssertionOutcome.Failed
import dev.skillbill.desktop.core.domain.model.QaAssertionOutcome.NotExercised
import dev.skillbill.desktop.core.domain.model.QaAssertionOutcome.Passed
import dev.skillbill.desktop.core.domain.model.QaAssertionOutcome
import dev.skillbill.desktop.core.domain.model.QaChecklist
import dev.skillbill.desktop.core.domain.model.QaChecklistFinding
import dev.skillbill.desktop.core.domain.model.QaRunAssertion
import dev.skillbill.desktop.core.domain.model.QaRunCoverage
import dev.skillbill.desktop.core.domain.model.QaRunDetails
import dev.skillbill.desktop.core.domain.model.QaRunFinding
import dev.skillbill.desktop.core.domain.model.QaRunRetainedResource
import dev.skillbill.desktop.core.domain.model.QaRunTemporaryEdit
import dev.skillbill.desktop.core.domain.model.QaVerdict
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.model.isFileUnderDirectory
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QaResultsViewTest {
  private val report = "Verdict: x\n\nNotes."

  private fun assertions(outcome: QaAssertionOutcome, count: Int) = (1..count).map { QaRunAssertion(outcome, "$outcome $it", "detail") }

  private fun input(
    outcome: QaVerdict?,
    details: QaRunDetails? = null,
    findings: List<QaResultsFinding> = emptyList(),
    reportText: String? = report,
    running: Boolean = false,
    fixLoop: Boolean = true,
    fixAttempts: Int = 0,
    reason: String? = null,
  ) = QaResultsInput("run", outcome, running, fixLoop, fixAttempts, reason, null, reportText, findings, details)

  private val desktopTested = QaRunCoverage("desktop", "passed")
  private val notInScope = listOf("android", "backend", "web").map { QaRunCoverage(it, "skipped", "empty_scope") }

  private fun assertHead(view: QaResultsView, word: String, tone: QaResultsTone, sentence: String) {
    assertEquals(word, view.word)
    assertEquals(tone, view.tone)
    assertEquals(sentence, view.sentence)
  }

  @Test
  fun `open scenario`() {
    val coverage = listOf(QaRunCoverage("desktop", "incomplete", "capability_absent")) + notInScope
    val view = qaResultsView(
      input(
        QaVerdict.Attempted,
        QaRunDetails(assertions = assertions(Passed, 3) + assertions(NotExercised, 2) + assertions(Failed, 1), coverage = coverage),
        listOf(QaResultsFinding("QA-1", "Times out", observed = "it exited")),
      ),
    )
    assertHead(view, "Needs your review", QaResultsTone.Failed, "QA could not fix 1 problem and could not test 2 things. 3 checks passed.")
    assertEquals("It exited", view.open.single().observed)
    assertEquals("Not fixed", view.open.single().state)
    assertEquals(2, view.notTested.size)
    assertEquals(3, view.passed?.rows?.size)
    assertEquals("Desktop incomplete, capability missing · Android, backend, web not in scope", view.targets)
  }

  @Test
  fun `gaps scenario`() {
    val view = qaResultsView(input(QaVerdict.Incomplete, QaRunDetails(assertions = assertions(Passed, 3) + assertions(NotExercised, 2))))
    assertHead(view, "Incomplete", QaResultsTone.Blocked, "QA found no problems, but could not test 2 things. 3 checks passed.")
  }

  @Test
  fun `unblock parses fields and keeps heading lines inside the fenced snippet`() {
    val report = """Verdict: incomplete

## Unblock
#### display
- Target: desktop
- Tried: Started /tmp/run/xvfb.log
- Evidence: /tmp/run/xvfb.log, , /tmp/run/app.log
- Remedy: install xvfb
```markdown
# not a heading
### 1 not a finding
Start Xvfb before launching.
```
#### app_startup_gate
- Target: desktop
- Tried:
- Evidence:
- Remedy: set SKILL_BILL_RUNTIME_DIR
~~~
Set SKILL_BILL_RUNTIME_DIR before launch.
~~~

## Assertions
must stay out
"""
    val view = qaResultsView(input(QaVerdict.Incomplete, reportText = report).copy(fallbackInvocationDirectory = "/tmp/run"))

    assertEquals(2, view.unblock.size)
    assertEquals(
      QaUnblockEntry(
        "display", "desktop", "Started xvfb.log", "/tmp/run", listOf("/tmp/run/xvfb.log", "/tmp/run/app.log"), "install xvfb",
        "# not a heading\n### 1 not a finding\nStart Xvfb before launching.",
      ),
      view.unblock[0],
    )
    assertEquals("app_startup_gate", view.unblock[1].capability)
    assertNull(view.unblock[1].tried)
    assertEquals(emptyList(), view.unblock[1].evidencePaths)
    assertEquals("Set SKILL_BILL_RUNTIME_DIR before launch.", view.unblock[1].snippet)
  }

  @Test
  fun `unblock keeps shorter fences and tagged fence lines inside snippets`() {
    for (fence in listOf("````", "~~~~")) {
      val marker = fence.take(3)
      val snippet = "Run this setup:\n${marker}sh\nexport DISPLAY=:99\n$marker\n## Keep inside the snippet\n${fence}markdown\n- Remedy: keep this literal"
      val report = "## Unblock\n#### display\n- Remedy: install Xvfb\n${fence}markdown\n$snippet\n$fence\n#### input\n- Remedy: install a driver\n## Assertions\n#### must_stay_out"

      val entries = qaResultsView(input(QaVerdict.Incomplete, reportText = report)).unblock

      assertEquals(listOf("display", "input"), entries.map { it.capability })
      assertEquals(snippet, entries[0].snippet)
      assertEquals("install Xvfb", entries[0].remedy)
      assertEquals("install a driver", entries[1].remedy)
      assertNull(entries[1].snippet)
    }
  }

  @Test
  fun `unblock stays empty for passed missing and malformed sections`() {
    val section = "## Unblock\n#### display\n- Remedy: install xvfb\n```text\n# in snippet\n```."
    assertTrue(qaResultsView(input(QaVerdict.Passed, reportText = section)).unblock.isEmpty())
    assertTrue(qaResultsView(input(QaVerdict.Incomplete, reportText = "Verdict: incomplete")).unblock.isEmpty())
    assertTrue(qaResultsView(input(QaVerdict.Skipped, reportText = "## Unblock\n- Remedy: x\n####\n```\nunclosed")).unblock.isEmpty())
  }

  @Test
  fun `verify qa keeps the selected skipped receipt report for unblock parsing`() {
    val report = "## Unblock\n#### display\n- Remedy: install Xvfb\n```markdown\nStart Xvfb.\n```"
    val run = VerifyRunDetails(
      workflowId = "verify-1", issueKey = null, workflowStatus = "completed", currentStepId = null,
      startedAt = null, finishedAt = null, intake = null, target = null, repoRoot = "/repo", baseRevision = null,
      headRevision = null, reviewMode = null, qaSelection = "on", specPath = null, steps = emptyList(), criteria = emptyList(),
      criteriaRaw = null, nonGoals = emptyList(), technicalConstraints = emptyList(), rolloutExpectation = null,
      completenessAudit = null, reviewFindings = null, flagAudit = null, unitTestValue = null, changedFiles = emptyList(),
      verdict = null, qa = null, qaVerdict = "skipped", qaOutcome = "skipped", qaFindingLocations = emptyMap(),
      supersededBy = null, staleReason = null, qaReportText = report,
    )

    val input = requireNotNull(verifyQaInput(run))
    assertEquals(report, input.reportText)
    assertEquals(1, qaResultsView(input).unblock.size)
  }

  @OptIn(ExperimentalTestApi::class)
  @Test
  fun `unblock chips copy the snippet verbatim and ask to open qa md`() = runComposeUiTest {
    val snippet = "Set SKILL_BILL_RUNTIME_DIR.\nKeep this line."
    val report = "## Unblock\n#### app_startup_gate\n```markdown\n$snippet\n```"
    val view = qaResultsView(input(QaVerdict.Incomplete, reportText = report))
    val actions = mutableListOf<DetailAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        QaResultsContent(view, rememberSectionCollapse(), { actions += it })
      }
    }

    onNodeWithText("Copy for qa.md").performClick()
    onNodeWithText("Open qa.md").performClick()

    assertEquals(listOf(DetailAction.Copy(snippet), DetailAction.OpenQaPhaseInstructions), actions)
  }

  @Test
  fun `fixed scenario`() {
    val fixed = (1..3).map { QaResultsFinding("QA-$it", "Title $it", fixed = true) }
    val view = qaResultsView(input(QaVerdict.Passed, QaRunDetails(assertions = assertions(Passed, 7)), fixed, fixAttempts = 2))
    assertHead(view, "Passed after fixes", QaResultsTone.Done, "QA found 3 problems and fixed them after 2 attempts. 7 checks passed.")
    assertEquals("Title 1, Title 2, Title 3", view.fixed?.preview)
    val untitled = qaResultsView(input(QaVerdict.Passed, findings = listOf(QaResultsFinding("QA-9", " ", fixed = true, observed = "it broke"))))
    assertEquals("QA-9", untitled.fixed?.preview)
    assertEquals(QaDetailRow("QA-9", "It broke", evidenceDirectory = null), untitled.fixed?.rows?.single())
    assertTrue(view.open.isEmpty())
  }

  @Test
  fun `clean scenario`() {
    val view = qaResultsView(input(QaVerdict.Passed, QaRunDetails(assertions = assertions(Passed, 7), coverage = listOf(desktopTested) + notInScope)))
    assertHead(view, "Passed", QaResultsTone.Done, "All 7 checks passed.")
    assertEquals("Desktop tested · Android, backend, web not in scope", view.targets)
    assertEquals("The 1 check passed.", qaResultsView(input(QaVerdict.Passed, QaRunDetails(assertions = assertions(Passed, 1)))).sentence)
  }

  @Test
  fun `left scenario copies the full value and shows the short one`() {
    val details = QaRunDetails(
      assertions = assertions(Passed, 4),
      retainedResources = listOf(QaRunRetainedResource("process", "48213")),
      temporaryEdits = listOf(QaRunTemporaryEdit("/home/me/gradle.properties", "set jvmargs", true), QaRunTemporaryEdit("restored.txt", "x", false)),
    )
    val view = qaResultsView(input(QaVerdict.Passed, details))
    assertHead(view, "Passed, cleanup needed", QaResultsTone.Blocked, "All 4 checks passed. QA left 1 process running and 1 temporary edit.")
    assertEquals(listOf("Process still running", "Temporary edit"), view.left.map { it.title })
    assertEquals("pid 48213. Release reported it as retained.", view.left[0].detail)
    assertEquals("`gradle.properties`: set jvmargs", view.left[1].detail)
    assertEquals("/home/me/gradle.properties", view.left[1].copyText)
    val untagged = qaResultsView(input(QaVerdict.Passed, QaRunDetails(retainedResources = listOf(QaRunRetainedResource("worktree", "/home/me/wt/")))))
    assertEquals("QA passed, but left 1 worktree.", untagged.sentence)
    assertEquals("wt", untagged.left.single().detail)
  }

  @Test
  fun `skipped scenario is grey for empty scope and orange with the reason otherwise`() {
    val empty = qaResultsView(input(QaVerdict.Skipped, reportText = "Verdict: skipped\nReason: empty_scope\n\nDocs only."))
    assertHead(empty, "Skipped", QaResultsTone.Faint, "QA skipped this change. No acceptance criterion describes behavior it can exercise.")
    assertNull(empty.report?.firstParagraph)

    val docker = qaResultsView(input(QaVerdict.Skipped, QaRunDetails(skipReason = "docker_unavailable", coverage = listOf(QaRunCoverage("backend", "skipped", "docker_unavailable"))), reportText = null))
    assertHead(docker, "Skipped", QaResultsTone.Blocked, "QA skipped this change because Docker is not available.")
    assertEquals(listOf("Backend, Docker missing"), docker.notTested.map { it.title })
    assertEquals("Backend skipped, Docker missing", docker.targets)
  }

  @Test
  fun `skip tokens never crash and never come from prose`() {
    assertEquals("QA skipped this change because some new thing.", qaResultsView(input(QaVerdict.Skipped, QaRunDetails(skipReason = "some_new_thing"))).sentence)
    assertEquals("QA skipped this change because the target is offline.", qaResultsView(input(QaVerdict.Skipped, reason = "offline_target · no adb; warning")).sentence)
    val missing = "QA skipped this change and did not say what was missing."
    assertEquals(missing, qaResultsView(input(QaVerdict.Skipped, reason = "could not start the run")).sentence)
    assertEquals(missing, qaResultsView(input(QaVerdict.Skipped, reportText = "Reason: Docker seemed absent")).sentence)
  }

  @Test
  fun `older skipped rows yield a known token followed by detail`() {
    val docker = "QA skipped this change because Docker is not available."
    val environment = "QA skipped this change because an isolated environment could not be set up."
    val unsupported = "QA skipped this change because the target is not supported."
    val emptyScope = "QA skipped this change. No acceptance criterion describes behavior it can exercise."
    fun reported(text: String) = qaResultsView(input(QaVerdict.Skipped, reportText = text))
    assertHead(reported("Verdict: skipped\nReason: empty_scope. Only docs changed."), "Skipped", QaResultsTone.Faint, emptyScope)
    assertHead(reported("Reason: docker_unavailable, no daemon"), "Skipped", QaResultsTone.Blocked, docker)
    assertHead(reported("Reason: environment_unavailable. The host disk is full"), "Skipped", QaResultsTone.Blocked, environment)
    assertHead(reported("Reason: unsupported_target — no artifact"), "Skipped", QaResultsTone.Blocked, unsupported)
    val summary = "QA verdict: skipped: empty_scope; coverage: backend skipped (empty_scope)"
    assertHead(qaResultsView(input(QaVerdict.Skipped, reason = summary)), "Skipped", QaResultsTone.Faint, emptyScope)
  }

  @Test
  fun `an older standalone row reads its token with no run details`() {
    val run = ExecutionInstance(
      "run-1", "run-1", "completed", epoch, epoch, epoch, "QA verdict: skipped", null, QaVerdict.Skipped,
      "Verdict: skipped\nReason: empty_scope. The tree under test changes only docs.", null,
    )
    assertHead(
      qaResultsView(standaloneQaInput(workItem("QA", kind = WorkKind.Phase), run)),
      "Skipped",
      QaResultsTone.Faint,
      "QA skipped this change. No acceptance criterion describes behavior it can exercise.",
    )
  }

  @Test
  fun `text that only resembles a skip token still says nothing was named`() {
    val missing = "QA skipped this change and did not say what was missing."
    listOf("Reason: empty scope", "Reason: the host disk is full. Retry.", "Reason: unknown_thing. detail", "Reason: empty_scope2").forEach { text ->
      assertEquals(missing, qaResultsView(input(QaVerdict.Skipped, reportText = text)).sentence, text)
    }
    val perTargetOnly = "QA verdict: skipped; coverage: backend skipped (empty_scope)"
    assertEquals(missing, qaResultsView(input(QaVerdict.Skipped, reason = perTargetOnly)).sentence)
  }

  @Test
  fun `failed scenario keeps the reason and has no report or targets`() {
    val view = qaResultsView(input(QaVerdict.Incomplete, reportText = null, reason = "Agent exited with status 137."))
    assertHead(view, "Did not finish", QaResultsTone.Failed, "QA stopped before it wrote a report, so this change has no QA result.")
    assertEquals("Agent exited with status 137.", view.runDetails)
    assertNull(view.report)
    assertNull(view.targets)
  }

  @Test
  fun `legacy goal row finds its directory from the report path and claims no checks`() {
    val qa = GoalQaResult(
      "attempted", null, 0, epoch,
      listOf(GoalQaFinding("QA-1", "Times out", false, listOf("/srv/run/benchmark.log"), "/srv/run/report.md")),
      "Verdict: findings\nReason: open\nFirst prose line\nsecond line\n\n## Coverage", null, null,
    )
    val view = qaResultsView(goalQaInput(qa, running = false))
    assertHead(view, "Needs your review", QaResultsTone.Failed, "QA could not fix 1 problem.")
    assertEquals("/srv/run", view.open.single().evidenceDirectory)
    assertEquals("/srv/run/report.md", view.reportPath)
    assertEquals("First prose line second line", view.report?.firstParagraph)
    assertEquals("No tagged checks in this run", view.report?.preview)
    assertNull(view.passed)
  }

  @Test
  fun `an untagged pass is never green`() {
    assertHead(qaResultsView(input(QaVerdict.Passed)), "Passed", QaResultsTone.Faint, "QA passed. This run has no tagged checks.")
    val fixed = qaResultsView(input(QaVerdict.Passed, findings = listOf(QaResultsFinding("QA-1", "t", fixed = true))))
    assertHead(fixed, "Passed after fixes", QaResultsTone.Faint, "QA found 1 problem and fixed it. This run has no tagged checks.")
  }

  @Test
  fun `partial coverage or a failed assertion with no open finding is never green`() {
    val partial = qaResultsView(input(QaVerdict.Passed, QaRunDetails(assertions = assertions(Passed, 1), coverage = listOf(QaRunCoverage("web", "incomplete", null)))))
    assertEquals("Incomplete", partial.word)
    assertEquals(listOf("Web, incomplete"), partial.notTested.map { it.title })
    val failed = qaResultsView(input(QaVerdict.Passed, QaRunDetails(assertions = assertions(Failed, 1))))
    assertEquals("Incomplete", failed.word)
    assertEquals(1, failed.notTested.size)
  }

  @Test
  fun `a failed assertion with an open finding has no row`() {
    val view = qaResultsView(input(QaVerdict.Findings, QaRunDetails(assertions = assertions(Failed, 1)), listOf(QaResultsFinding("QA-1", "t"))))
    assertTrue(view.notTested.isEmpty())
  }

  @Test
  fun `attempted never overstates`() {
    val running = qaResultsView(input(QaVerdict.Attempted, running = true, findings = listOf(QaResultsFinding("QA-1", "t"))))
    assertHead(running, "Running", QaResultsTone.Faint, "QA is still running. It has 1 open problem so far.")
    val settled = qaResultsView(input(QaVerdict.Attempted))
    assertHead(settled, "Incomplete", QaResultsTone.Blocked, "QA did not finish testing this change.")
    assertNotEquals(QaResultsTone.Done, settled.tone)
    assertEquals("QA reported problems but listed none of them. Open the full report.", qaResultsView(input(QaVerdict.Findings)).sentence)
  }

  @Test
  fun `a checklist finding on a stopped standalone run stays under needs you`() {
    val run = ExecutionInstance(
      "run-1", "run-1", "canceled", epoch, epoch, epoch, "stopped", null, null, null, null, null,
      QaChecklist(invocationDirectory = "/srv/run-1", findings = listOf(QaChecklistFinding("QA-2", "Recorded"))),
      listOf(QaChecklistFinding("QA-3", "Declared")),
      QaRunDetails(findings = listOf(QaRunFinding("QA-3", "Declared", "seen", "wanted", "`do`", listOf("a.log")))),
    )
    val view = qaResultsView(standaloneQaInput(workItem("QA", kind = WorkKind.Phase), run))
    assertEquals(setOf("QA-2", "QA-3"), view.open.map { it.id }.toSet())
    assertHead(view, "Needs your review", QaResultsTone.Failed, "QA found 2 problems.")
    assertTrue(view.open.all { it.state == "Open" })
    assertFalse(view.sentence.contains("could not fix"))
    val enriched = assertNotNull(view.open.firstOrNull { it.id == "QA-3" })
    assertEquals("do", enriched.copySteps)
    assertEquals("/srv/run-1", enriched.evidenceDirectory)
  }

  @Test
  fun `duplicate finding ids collapse because rows key on the id`() {
    val view = qaResultsView(input(QaVerdict.Findings, findings = listOf(QaResultsFinding("QA-1", "a"), QaResultsFinding("QA-1", "b"))))
    assertEquals(1, view.open.size)
  }

  private val qaA = "/srv/qa-A"
  private val qaB = "/srv/qa-B"

  private fun carriedFinding(id: String, fixed: Boolean, directory: String = qaA) =
    GoalQaFinding(id, "$id title $directory/note.txt", fixed, listOf("$directory/$id.log"), "$directory/report.md", "seen $directory/out.log")

  private fun goalQa(findings: List<GoalQaFinding>, details: QaRunDetails?, outcome: String = "findings") =
    qaResultsView(goalQaInput(GoalQaResult(outcome, null, 1, epoch, findings, report, null, details), running = false))

  private fun assertOpens(path: String, directory: String?) {
    assertEquals(qaA, directory)
    assertTrue(isFileUnderDirectory(path, assertNotNull(directory)))
  }

  @Test
  fun `a finding fixed in a later invocation opens evidence against its own report directory`() {
    val view = goalQa(listOf(carriedFinding("QA-1", fixed = true)), QaRunDetails(invocationDirectory = qaB, reportPath = "$qaB/report.md"), "passed")
    val row = assertNotNull(view.fixed).rows.single()
    assertOpens(row.evidencePaths.single(), row.evidenceDirectory)
    assertFalse(row.title.contains(qaA))
    assertFalse(row.detail.contains(qaA))
    assertFalse(assertNotNull(view.fixed).preview.contains(qaA))
  }

  @Test
  fun `an open finding carried past a later report opens evidence against its own report directory`() {
    val view = goalQa(listOf(carriedFinding("QA-1", fixed = false)), QaRunDetails(invocationDirectory = qaB))
    val row = view.open.single()
    assertOpens(row.evidencePaths.single(), row.evidenceDirectory)
    assertFalse(row.title.contains(qaA))
    assertFalse(assertNotNull(row.observed).contains(qaA))
  }

  @Test
  fun `a carried finding keeps its directory when the latest visit wrote no report`() {
    val view = goalQa(listOf(carriedFinding("QA-1", fixed = false, qaB), carriedFinding("QA-2", fixed = false)), QaRunDetails())
    assertEquals(listOf(qaB, qaA), view.open.map { it.evidenceDirectory })
  }

  @Test
  fun `findings from different invocations in one run each get their own directory`() {
    val view = goalQa(listOf(carriedFinding("QA-1", fixed = false), carriedFinding("QA-2", fixed = false, qaB)), QaRunDetails(invocationDirectory = qaB))
    assertEquals(listOf(qaA, qaB), view.open.map { it.evidenceDirectory })
  }

  @Test
  fun `a finding with no report path falls back to the details directory`() {
    val view = goalQa(listOf(GoalQaFinding("QA-1", "t", false, listOf("$qaB/x.log"))), QaRunDetails(invocationDirectory = qaB))
    assertEquals(qaB, view.open.single().evidenceDirectory)
  }

  @Test
  fun `a finding without a report path does not inherit another finding's directory`() {
    val view = goalQa(
      listOf(
        carriedFinding("QA-1", fixed = false),
        GoalQaFinding("QA-2", "QA-2 current", false, listOf("$qaB/current.log")),
      ),
      QaRunDetails(reportPath = "$qaB/report.md"),
    )

    assertEquals(listOf(qaA, qaB), view.open.map { it.evidenceDirectory })
  }

  @Test
  fun `passed and not tested rows use the details directory`() {
    val assertionsWithEvidence = listOf(
      QaRunAssertion(Passed, "Passed one", "ok", listOf("$qaB/p.log")),
      QaRunAssertion(NotExercised, "Skipped one", "no", listOf("$qaB/n.log")),
    )
    val view = goalQa(listOf(carriedFinding("QA-1", fixed = true)), QaRunDetails(invocationDirectory = qaB, assertions = assertionsWithEvidence), "passed")
    assertEquals(qaB, assertNotNull(view.passed).rows.single().evidenceDirectory)
    assertEquals(qaB, view.notTested.single().evidenceDirectory)
  }

  @Test
  fun `a partial coverage row has no evidence directory`() {
    val view = goalQa(emptyList(), QaRunDetails(invocationDirectory = qaB, coverage = listOf(QaRunCoverage("web", "incomplete"))), "passed")
    assertNull(view.notTested.single().evidenceDirectory)
  }
}
