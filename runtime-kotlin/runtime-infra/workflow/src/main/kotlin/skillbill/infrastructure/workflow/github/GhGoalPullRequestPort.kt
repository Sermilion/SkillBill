package skillbill.infrastructure.workflow.github

import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.runner.GoalPullRequestPort
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.runner.model.GoalPullRequestResult
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import java.nio.file.Path

class GhGoalPullRequestPort internal constructor(
  private val gh: GhCommandRunner,
  private val identityLookup: PullRequestIdentityLookup,
) : GoalPullRequestPort {
  @Inject
  constructor(identityLookup: PullRequestIdentityLookup) : this(ProcessGhCommandRunner(), identityLookup)

  override fun open(request: GoalPullRequestRequest): GoalPullRequestResult =
    request.headBranch.takeIf(String::isNotBlank)
      ?.let { head -> openWithHead(request, head) }
      ?: GoalPullRequestResult.Failed("A head branch is required before creating the goal pull request.")

  private fun openWithHead(
    request: GoalPullRequestRequest,
    head: String,
  ): GoalPullRequestResult {
    val root = request.repoRoot.toAbsolutePath().normalize()
    return when (val existing = identityLookup.lookup(root, head)) {
      is PullRequestIdentity.Found -> GoalPullRequestResult.Existing(existing.url)
      is PullRequestIdentity.Merged -> GoalPullRequestResult.Existing(existing.url)
      is PullRequestIdentity.Unavailable -> GoalPullRequestResult.Failed(existing.reason)
      PullRequestIdentity.Absent -> createPullRequest(root, request, head)
    }
  }

  private fun createPullRequest(
    root: Path,
    request: GoalPullRequestRequest,
    head: String,
  ): GoalPullRequestResult {
    val create = gh.run(root, createArgs(request, head))
    return if (create.exitCode == 0) {
      create.stdout.lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
        ?.let(GoalPullRequestResult::Opened)
        ?: GoalPullRequestResult.Failed("Goal pull request was created but no PR URL was returned.")
    } else {
      GoalPullRequestResult.Failed(create.describeFailure())
    }
  }

  private fun createArgs(
    request: GoalPullRequestRequest,
    head: String,
  ): List<String> =
    listOf(
      "pr",
      "create",
      "--head",
      head,
      "--base",
      request.baseBranch,
      "--draft",
      "--title",
      request.title,
      "--body",
      request.body,
    )
}
