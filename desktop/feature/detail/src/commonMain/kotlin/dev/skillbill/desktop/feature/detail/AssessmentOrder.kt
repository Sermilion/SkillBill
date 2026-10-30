package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.GoalAssessmentFinding
import dev.skillbill.desktop.core.domain.model.GoalAssessmentFindingState

/** How the Goal assessment section lists its findings: as the runtime reported them, by priority, or by status. */
internal enum class AssessmentOrder(val label: String) {
  Default("Default"),
  Priority("Priority"),
  Status("Status"),
}

/** Severity words from most to least urgent; anything else sorts after them. */
private val SEVERITY_RANK = listOf("blocker", "critical", "high", "major", "medium", "minor", "low", "nit", "info")

private fun GoalAssessmentFinding.priorityRank(): Int =
  SEVERITY_RANK.indexOf(severity.trim().lowercase()).let { if (it < 0) SEVERITY_RANK.size else it }

/** Open findings still need work, refuted ones need a look, fixed ones are done. */
private fun GoalAssessmentFinding.statusRank(): Int = when (state) {
  GoalAssessmentFindingState.Open -> 0
  GoalAssessmentFindingState.Refuted -> 1
  GoalAssessmentFindingState.Fixed -> 2
}

/**
 * [findings] in [order]. Priority breaks ties by status and status by priority; equal findings keep the runtime's order, since
 * the sort is stable.
 */
internal fun orderedFindings(findings: List<GoalAssessmentFinding>, order: AssessmentOrder): List<GoalAssessmentFinding> =
  when (order) {
    AssessmentOrder.Default -> findings
    AssessmentOrder.Priority -> findings.sortedWith(compareBy({ it.priorityRank() }, { it.statusRank() }))
    AssessmentOrder.Status -> findings.sortedWith(compareBy({ it.statusRank() }, { it.priorityRank() }))
  }
