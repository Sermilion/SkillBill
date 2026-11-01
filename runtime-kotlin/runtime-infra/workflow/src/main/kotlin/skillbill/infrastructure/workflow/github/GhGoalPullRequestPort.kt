package skillbill.infrastructure.workflow.github

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
    val create = gh.run(root, createArgs(withLiveBase(root, request), head))
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

  private fun withLiveBase(
    root: Path,
    request: GoalPullRequestRequest,
  ): GoalPullRequestRequest {
    val lookup = gh.run(root, baseLookupArgs(request.baseBranch))
    val default = lookup.takeIf { it.exitCode == 0 }?.let { defaultBranchWhenBaseIsGone(it.stdout) }
    return if (default.isNullOrBlank() || default == request.baseBranch) {
      request
    } else {
      request.copy(
        baseBranch = default,
        body =
          "> Recorded base branch `${request.baseBranch}` no longer exists on the remote, so this pull request " +
            "targets `$default`.\n\n${request.body}",
      )
    }
  }

  private fun defaultBranchWhenBaseIsGone(lookup: String): String? =
    parse(lookup)
      ?.path("data")
      ?.path("repository")
      ?.takeIf { it.path("ref").isNull }
      ?.path("defaultBranchRef")
      ?.path("name")
      ?.takeIf(JsonNode::isTextual)
      ?.asText()

  private fun baseLookupArgs(baseBranch: String): List<String> =
    listOf(
      "api",
      "graphql",
      "-F",
      "owner={owner}",
      "-F",
      "name={repo}",
      "-f",
      "ref=refs/heads/$baseBranch",
      "-f",
      "query=$BASE_LOOKUP_QUERY",
    )

  private fun parse(value: String): JsonNode? =
    try {
      ObjectMapper().readTree(value)
    } catch (_: JsonProcessingException) {
      null
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

private const val BASE_LOOKUP_QUERY =
  "query(\$owner: String!, \$name: String!, \$ref: String!) { repository(owner: \$owner, name: \$name) " +
    "{ ref(qualifiedName: \$ref) { name } defaultBranchRef { name } } }"
