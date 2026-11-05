package dev.skillbill.desktop.feature.detail

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
import dev.skillbill.desktop.core.domain.model.WorkKind
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
  fun `fixed scenario`() {
    val fixed = (1..3).map { QaResultsFinding("QA-$it", "Title $it", fixed = true) }
    val view = qaResultsView(input(QaVerdict.Passed, QaRunDetails(assertions = assertions(Passed, 7)), fixed, fixAttempts = 2))
    assertHead(view, "Passed after fixes", QaResultsTone.Done, "QA found 3 problems and fixed them after 2 attempts. 7 checks passed.")
    assertEquals("Title 1, Title 2, Title 3", view.fixed?.preview)
    val untitled = qaResultsView(input(QaVerdict.Passed, findings = listOf(QaResultsFinding("QA-9", " ", fixed = true, observed = "it broke"))))
    assertEquals("QA-9", untitled.fixed?.preview)
    assertEquals(QaDetailRow("QA-9", "It broke"), untitled.fixed?.rows?.single())
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
    assertEquals("/srv/run", view.invocationDirectory)
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
    assertEquals("/srv/run-1", view.invocationDirectory)
  }

  @Test
  fun `duplicate finding ids collapse because rows key on the id`() {
    val view = qaResultsView(input(QaVerdict.Findings, findings = listOf(QaResultsFinding("QA-1", "a"), QaResultsFinding("QA-1", "b"))))
    assertEquals(1, view.open.size)
  }
}
