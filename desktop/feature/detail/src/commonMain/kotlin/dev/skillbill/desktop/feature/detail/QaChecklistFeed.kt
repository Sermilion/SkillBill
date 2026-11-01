package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailList
import dev.skillbill.desktop.core.domain.model.ExecutionInstance
import dev.skillbill.desktop.core.domain.model.QaChecklistFinding
import dev.skillbill.desktop.core.domain.model.QaVerdict
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.qaExecutionsChronological
import dev.skillbill.desktop.core.domain.model.qaActivityNewestFirst
import dev.skillbill.desktop.core.designsystem.status.StatusKind

internal data class QaRunPager(
  val index: Int,
  val total: Int,
  val previousInvocationId: String?,
  val nextInvocationId: String?,
)

internal fun qaRunPager(item: WorkItem, detail: Detail, selectedRun: String?): QaRunPager? {
  val runs = qaExecutionsChronological(detail.executionHistory)
  if (runs.isEmpty()) return null
  val index = runs.indexOfFirst { it.invocationId == qaDisplayedRun(item, detail, selectedRun)?.invocationId }
  if (index < 0) return null
  return QaRunPager(index, runs.size, runs.getOrNull(index - 1)?.invocationId, runs.getOrNull(index + 1)?.invocationId)
}

internal fun qaRunPreviousAction(pager: QaRunPager, moreLists: Set<DetailList>): DetailAction =
  pager.previousInvocationId?.let(DetailAction::SelectQaRun)
    ?: if (DetailList.Executions in moreLists) DetailAction.LoadMore(DetailList.Executions) else DetailAction.SelectQaRun(null)

internal fun qaRunNextAction(pager: QaRunPager): DetailAction = DetailAction.SelectQaRun(pager.nextInvocationId)

internal fun qaFindings(run: ExecutionInstance): List<QaChecklistFinding> {
  val recorded = run.qaChecklist?.findings.orEmpty()
  val report = run.qaFindings
  val recordedById = recorded.associateBy { it.id }
  val reportIds = report.mapTo(HashSet()) { it.id }
  return report.map { finding -> finding.copy(recordedAt = recordedById[finding.id]?.recordedAt) } +
    recorded.filter { it.id !in reportIds }
}

internal fun qaActivityLines(item: WorkItem, detail: Detail): List<ActivityLine> = qaActivityNewestFirst(buildList {
  qaExecutionsChronological(detail.executionHistory).forEachIndexed { index, run ->
    val actor = "Run ${index + 1}"
    add(ActivityLine(ActivityLead.Status(StatusKind.Active, 0.12f), actor, "started", at = run.startedAt))
    run.qaChecklist?.let { checklist ->
      checklist.planRegisteredAt?.let { at ->
        val caseCount = checklist.cases.size
        val stepCount = checklist.cases.sumOf { it.steps.size }
        add(
          ActivityLine(
            ActivityLead.Status(StatusKind.Active, null), actor, "plan registered",
            "$caseCount ${if (caseCount == 1) "case" else "cases"}, $stepCount ${if (stepCount == 1) "step" else "steps"}", at = at,
          ),
        )
      }
      qaFindings(run).forEach { finding ->
        finding.recordedAt?.let { at ->
          add(ActivityLine(ActivityLead.Status(StatusKind.Failed, null), actor, "finding", "${finding.id} ${finding.title}", at = at))
        }
      }
    }
    run.finishedAt?.let { at ->
      val action = run.qaVerdict?.wire()?.let { "QA verdict: $it" } ?: run.lifecycleState.lowercase()
      add(ActivityLine(ActivityLead.Status(executionStatus(run.lifecycleState), 1f), actor, action, at = at))
    }
  }
})

private fun QaVerdict.wire(): String? = when (this) {
  QaVerdict.Attempted -> null
  QaVerdict.Passed -> "passed"
  QaVerdict.Findings -> "findings_open"
  QaVerdict.Skipped -> "skipped"
  QaVerdict.Incomplete -> "incomplete"
}
