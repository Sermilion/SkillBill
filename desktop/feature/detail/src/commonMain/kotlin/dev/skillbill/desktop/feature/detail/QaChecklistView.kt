package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.ExecutionInstance
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.QaChecklistStep
import dev.skillbill.desktop.core.domain.model.QaChecklistTarget
import dev.skillbill.desktop.core.domain.model.QaStepState
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlin.time.Instant

internal enum class QaStepDisplay { Pending, Running, Passed, Failed, Stopped, NotRun }
internal data class QaCaseStatus(val label: String)
internal data class QaStepView(
  val number: Int,
  val text: String,
  val display: QaStepDisplay,
  val note: String?,
  val findingId: String?,
  val evidence: List<String>,
  val updatedAt: Instant?,
)
internal data class QaCaseView(
  val key: QaCaseKey,
  val id: String,
  val title: String,
  val status: QaCaseStatus,
  val done: Int,
  val total: Int,
  val open: Boolean,
  val steps: List<QaStepView>,
)
internal data class QaProgressView(
  val done: Int,
  val total: Int,
  val passed: Int,
  val failed: Int,
  val running: Int,
  val stopped: Int,
  val remaining: Int,
  val description: String,
  val summary: String,
)
internal sealed interface QaNowTesting {
  data class Step(val caseId: String, val caseTitle: String, val stepNumber: Int, val stepCount: Int, val stepText: String) : QaNowTesting
  data class Idle(val text: String) : QaNowTesting
}
internal data class QaChecklistView(
  val invocationId: String?,
  val live: Boolean,
  val planText: String?,
  val cases: List<QaCaseView>,
  val stepCount: Int,
  val progress: QaProgressView?,
  val nowTesting: QaNowTesting,
  val targets: List<QaChecklistTarget>,
  val allOpen: Boolean,
  val toggleLabel: String,
  val planRegisteredAt: Instant?,
  val lastStepUpdateAt: Instant?,
  val invocationDirectory: String?,
  val reportPath: String?,
  val testedCommit: String?,
  val criteriaSource: String?,
)

internal fun qaDisplayedRun(item: WorkItem, detail: Detail, selectedRun: String?): ExecutionInstance? =
  selectedRun?.let(detail::execution)
    ?: currentExecution(item, detail)
    ?: detail.executionHistory.maxByOrNull { it.startedAt }

internal fun qaRunLive(item: WorkItem, run: ExecutionInstance): Boolean =
  item.running && item.lifecycle == Lifecycle.Active && run.finishedAt == null && run.invocationId == item.control.invocationId

internal fun qaChecklistView(
  item: WorkItem,
  detail: Detail,
  selectedRun: String?,
  overrides: Map<QaCaseKey, Boolean>,
): QaChecklistView {
  val run = qaDisplayedRun(item, detail, selectedRun)
  val checklist = run?.qaChecklist
  val live = run != null && qaRunLive(item, run)
  val cases = if (checklist == null) emptyList() else checklist.cases.map { qaCase ->
    val steps = qaCase.steps.mapIndexed { index, step ->
      QaStepView(index + 1, step.text, step.display(live), step.note, step.findingId, step.evidence, step.updatedAt)
    }
    val status = caseStatus(qaCase.steps, live)
    val key = QaCaseKey(run!!.invocationId, qaCase.id)
    val open = overrides[key] ?: (status.label == "Running" || status.label == "Failed")
    QaCaseView(
      key, qaCase.id, qaCase.title, status,
      qaCase.steps.count { it.state == QaStepState.Passed || it.state == QaStepState.Failed }, qaCase.steps.size, open, steps,
    )
  }
  val allSteps = checklist?.cases.orEmpty().flatMap { qaCase ->
    qaCase.steps.mapIndexed { index, step -> Triple(qaCase, index + 1, step) }
  }
  val hasCases = checklist?.cases?.isNotEmpty() == true
  val progress = if (!hasCases) null else {
    val passed = allSteps.count { it.third.state == QaStepState.Passed }
    val failed = allSteps.count { it.third.state == QaStepState.Failed }
    val running = allSteps.count { it.third.display(live) == QaStepDisplay.Running }
    val stopped = allSteps.count { it.third.state == QaStepState.Running && !live }
    val total = allSteps.size
    val groups = listOf(
      "failed" to cases.count { it.status.label == "Failed" },
      "passed" to cases.count { it.status.label == "Passed" },
      "running" to cases.count { it.status.label == "Running" },
      "queued" to cases.count { it.status.label == "Queued" },
      "stopped" to cases.count { it.status.label == "Stopped" },
      "not run" to cases.count { it.status.label == "Not run" },
    )
      .filter { it.second > 0 }
    val caseWord = if (groups.firstOrNull()?.second == 1) "case" else "cases"
    val summary = groups.mapIndexed { index, (label, count) ->
      "${if (index == 0) "$count $caseWord" else count} $label"
    }.joinToString(", ")
    QaProgressView(
      passed + failed, total, passed, failed, running, stopped, total - passed - failed - running,
      "${passed + failed} of $total steps done: $passed passed, $failed failed", summary,
    )
  }
  val now = if (live) {
    allSteps.filter { it.third.display(live) == QaStepDisplay.Running }
      .maxByOrNull { it.third.updatedAt ?: Instant.DISTANT_PAST }
      ?.let { (qaCase, number, step) -> QaNowTesting.Step(qaCase.id, qaCase.title, number, qaCase.steps.size, step.text) }
      ?: QaNowTesting.Idle("Waiting for the next step")
  } else {
    QaNowTesting.Idle("Not running")
  }
  val allOpen = cases.isNotEmpty() && cases.all { it.open }
  return QaChecklistView(
    run?.invocationId, live, if (!hasCases) "QA registered no plan." else null, cases, allSteps.size, progress, now,
    checklist?.targets.orEmpty(),
    allOpen, if (allOpen) "Collapse all" else "Expand all", checklist?.planRegisteredAt, checklist?.lastStepUpdateAt,
    checklist?.invocationDirectory, checklist?.reportPath, checklist?.testedCommit, checklist?.criteriaSource,
  )
}

internal fun qaCaseIsOpen(item: WorkItem, detail: Detail, key: QaCaseKey, overrides: Map<QaCaseKey, Boolean>): Boolean? {
  val run = detail.execution(key.invocationId) ?: return null
  val qaCase = run.qaChecklist?.cases?.firstOrNull { it.id == key.caseId } ?: return null
  return overrides[key] ?: (caseStatus(qaCase.steps, qaRunLive(item, run)).label in setOf("Running", "Failed"))
}

private fun QaChecklistStep.display(live: Boolean): QaStepDisplay = when (state) {
  QaStepState.Pending -> if (live) QaStepDisplay.Pending else QaStepDisplay.NotRun
  QaStepState.Running -> if (live) QaStepDisplay.Running else QaStepDisplay.Stopped
  QaStepState.Passed -> QaStepDisplay.Passed
  QaStepState.Failed -> QaStepDisplay.Failed
}

private fun caseStatus(steps: List<QaChecklistStep>, live: Boolean): QaCaseStatus {
  val status = if (live) when {
    steps.any { it.state == QaStepState.Running } -> "Running"
    steps.any { it.state == QaStepState.Failed } -> "Failed"
    steps.isNotEmpty() && steps.all { it.state == QaStepState.Passed } -> "Passed"
    steps.any { it.state == QaStepState.Passed || it.state == QaStepState.Failed } -> "Running"
    else -> "Queued"
  } else when {
    steps.any { it.state == QaStepState.Failed } -> "Failed"
    steps.isNotEmpty() && steps.all { it.state == QaStepState.Passed } -> "Passed"
    steps.any { it.state == QaStepState.Passed || it.state == QaStepState.Running } -> "Stopped"
    else -> "Not run"
  }
  return QaCaseStatus(status)
}
