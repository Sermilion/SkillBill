package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.ExecutionInstance
import dev.skillbill.desktop.core.domain.model.QaChecklist
import dev.skillbill.desktop.core.domain.model.QaChecklistCase
import dev.skillbill.desktop.core.domain.model.QaChecklistFinding
import dev.skillbill.desktop.core.domain.model.QaChecklistStep
import dev.skillbill.desktop.core.domain.model.QaVerdict
import dev.skillbill.desktop.core.domain.model.QaStepState
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QaChecklistViewTest {
  private fun run(
    id: String,
    start: Long,
    checklist: QaChecklist? = null,
    finished: Long? = null,
    lifecycle: String = if (finished == null) "active" else "canceled",
    findings: List<QaChecklistFinding> = emptyList(),
  ) = ExecutionInstance(id, id, lifecycle, instant(start), instant(finished ?: start), finished?.let(::instant), null, null, null, null, null, null, checklist, findings)

  @Test
  fun `legacy run has no invented plan and live progress uses recorded steps`() {
    val legacy = run("run-1", 1)
    val item = workItem("QA", kind = WorkKind.Phase).copy(control = workItem("QA").control.copy(invocationId = "run-1"))
    val empty = qaChecklistView(item, emptyDetail.copy(executionHistory = listOf(legacy)), null, emptyMap())
    assertEquals("QA registered no plan.", empty.planText)
    assertTrue(empty.cases.isEmpty())
    assertNull(empty.progress)
    assertTrue(empty.targets.isEmpty())
    val emptyChecklist = qaChecklistView(item, emptyDetail.copy(executionHistory = listOf(legacy.copy(qaChecklist = QaChecklist()))), null, emptyMap())
    assertTrue(emptyChecklist.cases.isEmpty())
    assertNull(emptyChecklist.progress)

    val checklist = QaChecklist(
      invocationDirectory = "/repo/run-1", reportPath = "/repo/run-1/report.md", testedCommit = null, criteriaSource = null,
      targets = emptyList(), planRegisteredAt = instant(2), cases = listOf(
        QaChecklistCase("C1", "Live case", listOf(
          QaChecklistStep("passed", QaStepState.Passed, null, null, emptyList(), instant(3)),
          QaChecklistStep("failed", QaStepState.Failed, null, "F-1", emptyList(), instant(4)),
          QaChecklistStep("running", QaStepState.Running, null, null, emptyList(), instant(5)),
          QaChecklistStep("pending", QaStepState.Pending, null, null, emptyList(), null),
        )),
      ), findings = emptyList(), lastStepUpdateAt = instant(5),
    )
    val liveRun = run("run-1", 1, checklist)
    val live = qaChecklistView(item, emptyDetail.copy(executionHistory = listOf(liveRun)), null, emptyMap())
    assertEquals("2 of 4 steps done: 1 passed, 1 failed", live.progress?.description)
    assertEquals("1 case running", live.progress?.summary)
    assertEquals(QaNowTesting.Step("C1", "Live case", 3, 4, "running"), live.nowTesting)
    assertTrue(live.cases.single().open)
    val collapsed = qaChecklistView(item, emptyDetail.copy(executionHistory = listOf(liveRun)), null, mapOf(live.cases.single().key to false))
    assertFalse(collapsed.cases.single().open)
  }

  @Test
  fun `finished and canceled steps never remain live and pager runs oldest first`() {
    val checklist = QaChecklist(null, null, null, null, emptyList(), null, listOf(
      QaChecklistCase("C", "Case", listOf(QaChecklistStep("stuck", QaStepState.Running, null, null, emptyList(), instant(2)), QaChecklistStep("todo", QaStepState.Pending, null, null, emptyList(), null))),
    ), emptyList(), null)
    val old = run("old", 1, checklist, 4, "canceled")
    val newest = run("new", 8, finished = null)
    val item = workItem("QA", kind = WorkKind.Phase).copy(control = workItem("QA").control.copy(invocationId = "new"))
    val detail = emptyDetail.copy(executionHistory = listOf(newest, old))
    val selected = qaChecklistView(item, detail, "old", emptyMap())
    assertEquals(QaStepDisplay.Stopped, selected.cases.single().steps[0].display)
    assertEquals(QaStepDisplay.NotRun, selected.cases.single().steps[1].display)
    assertEquals(0, selected.progress?.running)
    assertEquals(QaNowTesting.Idle("Not running"), selected.nowTesting)
    assertEquals(QaRunPager(0, 2, null, "new"), qaRunPager(item, detail, "old"))
    assertTrue(qaActivityLines(item, detail).any { it.action == "canceled" })

    val passedChecklist = checklist.copy(cases = listOf(QaChecklistCase("P", "Passed", listOf(
      QaChecklistStep("done", QaStepState.Passed, null, null, emptyList(), instant(7)),
    ))))
    val finished = run("finished", 10, passedChecklist, 12, "completed").copy(qaVerdict = QaVerdict.Passed)
    val finishedItem = item.copy(running = false, lifecycle = dev.skillbill.desktop.core.domain.model.Lifecycle.Terminal)
    val finishedView = qaChecklistView(finishedItem, emptyDetail.copy(executionHistory = listOf(finished)), null, emptyMap())
    assertEquals(QaStepDisplay.Passed, finishedView.cases.single().steps.single().display)
    assertTrue(finishedView.cases.none { it.status.label == "Running" })
  }

  @Test
  fun `a finished run tells skipped steps apart from steps never reported`() {
    val checklist = QaChecklist(null, null, null, null, emptyList(), null, listOf(
      QaChecklistCase("S1", "Partly skipped", listOf(
        QaChecklistStep("ran", QaStepState.Passed, null, null, emptyList(), instant(2)),
        QaChecklistStep("no web", QaStepState.Skipped, "no web session", null, emptyList(), instant(3)),
      )),
      QaChecklistCase("S5", "Skipped", listOf(QaChecklistStep("cache", QaStepState.Skipped, "out of scope", null, emptyList(), instant(4)))),
      QaChecklistCase("S6", "Never reported", listOf(QaChecklistStep("airplane", QaStepState.Pending, null, null, emptyList(), null))),
    ), emptyList(), null)
    val item = workItem("QA", kind = WorkKind.Phase).copy(running = false, lifecycle = dev.skillbill.desktop.core.domain.model.Lifecycle.Terminal)

    val view = qaChecklistView(item, emptyDetail.copy(executionHistory = listOf(run("done", 1, checklist, 5, "completed"))), null, emptyMap())

    assertEquals(listOf("Partly skipped", "Skipped", "Not run"), view.cases.map { it.status.label })
    assertEquals(QaStepDisplay.Skipped, view.cases[0].steps[1].display)
    assertEquals(QaStepDisplay.NotRun, view.cases[2].steps.single().display)
    assertEquals("3 of 4 steps done: 1 passed, 0 failed, 2 skipped", view.progress?.description)
    assertEquals(1, view.progress?.remaining)
  }

  @Test
  fun `selected QA execution supplies checklist and case state outside history page`() {
    val checklist = QaChecklist(null, null, null, null, emptyList(), null, listOf(
      QaChecklistCase("C1", "Selected case", listOf(QaChecklistStep("stopped", QaStepState.Running, null, null, emptyList(), instant(2)))),
    ), emptyList(), null)
    val selected = run("old", 1, checklist, 4, "canceled")
    val item = workItem("QA", kind = WorkKind.Phase).copy(control = workItem("QA").control.copy(invocationId = "old"))
    val detail = emptyDetail.copy(executionHistory = listOf(run("new", 8)), selectedExecution = selected)

    val view = qaChecklistView(item, detail, "old", emptyMap())

    assertEquals("old", view.invocationId)
    assertEquals("Selected case", view.cases.single().title)
    assertEquals(false, qaCaseIsOpen(item, detail, view.cases.single().key, emptyMap()))
    assertEquals(true, qaCaseIsOpen(item, detail, view.cases.single().key, mapOf(view.cases.single().key to true)))
  }

  @Test
  fun `findings merge report order and recorded timestamps while feed is newest first`() {
    val checklist = QaChecklist(null, null, null, null, emptyList(), null, emptyList(), listOf(
      QaChecklistFinding("F-2", "Recorded title", instant(3)), QaChecklistFinding("F-1", "Only recorded", instant(4)),
    ), null)
    val first = run("one", 1, checklist, 5, "completed", listOf(QaChecklistFinding("F-2", "Report title", null)))
      .copy(qaVerdict = QaVerdict.Findings)
    val second = run("two", 7, finished = 9, lifecycle = "completed")
    val merged = qaFindings(first)
    assertEquals(listOf("F-2", "F-1"), merged.map { it.id })
    assertEquals("Report title", merged.first().title)
    val item = workItem("QA", kind = WorkKind.Phase)
    val lines = qaActivityLines(item, emptyDetail.copy(executionHistory = listOf(first, second)))
    assertEquals("completed", lines.first().action)
    assertTrue(lines.any { it.target == "F-2 Report title" })
    assertTrue(lines.any { it.action == "QA verdict: findings_open" })
    assertEquals("Run 1", lines.last().actor)
  }
}

private fun instant(value: Long) = kotlin.time.Instant.fromEpochMilliseconds(value)
