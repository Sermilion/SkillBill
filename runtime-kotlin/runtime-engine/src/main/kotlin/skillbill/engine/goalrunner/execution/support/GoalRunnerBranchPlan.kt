package skillbill.engine.goalrunner.execution.support

import skillbill.ports.goalrunner.runner.model.GoalPullRequestRequest
import skillbill.workflow.decomposition.branchForFinalPullRequest
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import java.nio.file.Path

internal data class GoalRunnerBranchPlan(
  val branch: String,
  val baseBranch: String,
  val validateBase: Boolean,
)

fun DecompositionManifest.toPullRequestRequest(repoRoot: Path): GoalPullRequestRequest {
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

internal fun DecompositionManifest.branchPlanFor(subtaskId: Int): GoalRunnerBranchPlan =
  when (executionModel) {
    DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK ->
      GoalRunnerBranchPlan(branch = featureBranch.orEmpty(), baseBranch = baseBranch, validateBase = false)
    DecompositionExecutionModel.STACKED_BRANCHES -> {
      val stackBranch = stackBranches.first { it.subtaskId == subtaskId }
      GoalRunnerBranchPlan(branch = stackBranch.branch, baseBranch = stackBranch.baseBranch, validateBase = true)
    }
  }
