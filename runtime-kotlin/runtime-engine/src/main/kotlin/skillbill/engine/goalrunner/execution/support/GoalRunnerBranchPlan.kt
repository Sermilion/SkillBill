package skillbill.engine.goalrunner.execution.support

import skillbill.engine.goalrunner.model.GoalQaSummary
import skillbill.engine.goalrunner.model.GoalSubtaskQaSummary
import skillbill.goalrunner.GoalRunnerPlanner
import skillbill.goalrunner.model.GoalRunnerSelection
import skillbill.ports.goalrunner.runner.model.GoalPullRequestRequest
import skillbill.workflow.decomposition.branchForFinalPullRequest
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.nio.file.Path

internal data class GoalRunnerBranchPlan(
  val branch: String,
  val baseBranch: String,
  val validateBase: Boolean,
)

fun DecompositionManifest.toPullRequestRequest(
  repoRoot: Path,
  goalQaSummary: GoalQaSummary = GoalQaSummary.NotRecorded,
  qaSummary: (DecompositionSubtask) -> GoalSubtaskQaSummary? = { null },
): GoalPullRequestRequest {
  val title = "[$issueKey] ${featureName.toPullRequestTitle()}"
  val body =
    buildString {
      appendLine("# Summary")
      appendLine()
      appendLine("This pull request delivers ${featureName.toPullRequestTitle()} through the completed goal subtasks:")
      subtasks.forEach { subtask ->
        append("- ${subtask.name}")
        appendLine()
      }
      appendLine()
      appendLine("## Feature Flags")
      appendLine()
      appendLine("N/A")
      appendLine()
      appendLine("## Media")
      appendLine()
      appendLine("N/A")
      appendLine()
      appendLine("# How Has This Been Tested?")
      appendLine()
      appendLine(
        "Every subtask completed the configured review, audit, validation, and history gates before " +
          "this pull request was opened.",
      )
      val qa = subtasks.mapNotNull { subtask -> qaSummary(subtask)?.let { subtask.name to it } }
      appendLine()
      appendLine("## Mobile QA")
      appendLine()
      appendLine("- Goal: ${goalQaSummary.pullRequestText()}")
      qa.forEach { (name, summary) -> appendLine("- $name: ${summary.pullRequestText()}") }
    }
  return GoalPullRequestRequest(
    repoRoot = repoRoot,
    issueKey = issueKey,
    featureName = featureName,
    baseBranch = baseBranch,
    headBranch = featureBranch.orEmpty().ifBlank { branchForFinalPullRequest() },
    title = title,
    body = body,
  )
}

private fun String.toPullRequestTitle(): String =
  split(Regex("[-_\\s]+"))
    .filter(String::isNotBlank)
    .joinToString(" ") { word ->
      word.replaceFirstChar { character -> character.uppercase() }
    }

internal fun DecompositionManifest.goalWorkBranch(): String? {
  val branch =
    when (executionModel) {
      DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK -> featureBranch
      DecompositionExecutionModel.STACKED_BRANCHES -> {
        val selected = (GoalRunnerPlanner.selectNext(this) as? GoalRunnerSelection.Run)?.decision?.subtask?.id
        stackBranches.firstOrNull { it.subtaskId == selected }?.branch ?: featureBranch
      }
    }
  return branch?.trim()?.takeIf(String::isNotBlank)
}

internal fun DecompositionManifest.branchPlanFor(subtaskId: Int): GoalRunnerBranchPlan =
  when (executionModel) {
    DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK ->
      GoalRunnerBranchPlan(branch = featureBranch.orEmpty(), baseBranch = baseBranch, validateBase = false)
    DecompositionExecutionModel.STACKED_BRANCHES -> {
      val stackBranch = stackBranches.first { it.subtaskId == subtaskId }
      GoalRunnerBranchPlan(branch = stackBranch.branch, baseBranch = stackBranch.baseBranch, validateBase = true)
    }
  }

private fun GoalSubtaskQaSummary.pullRequestText(): String =
  when (this) {
    GoalSubtaskQaSummary.NotComposed -> "QA not composed (pre-QA workflow)"
    GoalSubtaskQaSummary.NotRecorded -> "no QA outcome recorded"
    is GoalSubtaskQaSummary.Recorded -> {
      val reason = outcome.reason?.let { " ($it)" }.orEmpty()
      val repairs =
        outcome.qaFixAttempts.takeIf { it > 0 }?.let { ", after $it qa_fix attempt" + if (it == 1) "" else "s" }
      "${outcome.outcome.wireValue}$reason${repairs.orEmpty()}"
    }
  }

private fun GoalQaSummary.pullRequestText(): String =
  when (this) {
    GoalQaSummary.RanPerSubtask -> "QA ran per subtask (pre-SKILL-431 children)"
    GoalQaSummary.NotRecorded -> "no goal-level QA outcome recorded"
    is GoalQaSummary.Recorded -> GoalSubtaskQaSummary.Recorded(outcome).pullRequestText()
    is GoalQaSummary.Warned ->
      listOfNotNull(
        outcome?.let { GoalSubtaskQaSummary.Recorded(it).pullRequestText() },
        "warning: $reason".takeUnless { outcome?.reason?.contains(reason) == true },
      ).joinToString("; ")
  }
