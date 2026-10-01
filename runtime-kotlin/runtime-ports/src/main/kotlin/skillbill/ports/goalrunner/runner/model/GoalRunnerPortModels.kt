package skillbill.ports.goalrunner.runner.model

import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Path

data class GoalRunnerSubtaskLaunchRequest(
  val invokedAgentId: String,
  val configuredAgentOverrideId: String?,
  val skillRunRequest: SkillRunRequest,
)

data class GoalRunnerOutOfBandAcceptance(
  val subtaskId: Int,
  val commitSha: String,
  val reason: String,
  val acceptedAt: String,
) {
  init {
    require(subtaskId > 0) { "subtaskId must be positive." }
    require(commitSha.isNotBlank()) { "commitSha is required." }
    require(reason.isNotBlank()) { "reason is required." }
    require(acceptedAt.isNotBlank()) { "acceptedAt is required." }
  }
}

data class GoalPullRequestRequest(
  val repoRoot: Path,
  val issueKey: String,
  val featureName: String,
  val baseBranch: String,
  val headBranch: String,
  val title: String,
  val body: String,
)

sealed interface GoalPullRequestResult {
  data class Opened(val url: String) : GoalPullRequestResult

  data class Existing(val url: String) : GoalPullRequestResult

  data class Failed(val reason: String) : GoalPullRequestResult
}
