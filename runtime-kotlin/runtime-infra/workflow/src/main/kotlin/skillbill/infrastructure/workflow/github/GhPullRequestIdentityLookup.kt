package skillbill.infrastructure.workflow.github

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import java.nio.file.Path

class GhPullRequestIdentityLookup internal constructor(
  private val gh: GhCommandRunner,
) : PullRequestIdentityLookup {
  @Inject
  constructor() : this(ProcessGhCommandRunner())

  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  override fun lookup(
    repoRoot: Path,
    branch: String,
  ): PullRequestIdentity =
    branch.takeIf(String::isNotBlank)
      ?.let { head -> lookupHead(repoRoot.toAbsolutePath().normalize(), head) }
      ?: PullRequestIdentity.Unavailable("A head branch is required to look up the pull request.")

  private fun lookupHead(
    root: Path,
    head: String,
  ): PullRequestIdentity {
    val result = gh.run(root, listOf("pr", "list", "--head", head, "--json", "url,number,title", "--limit", "1"))
    return if (result.exitCode == 0) {
      parseIdentity(result.stdout)
    } else {
      PullRequestIdentity.Unavailable(result.describeFailure())
    }
  }

  private fun parseIdentity(stdout: String): PullRequestIdentity =
    runCatching { mapper.readTree(stdout) }.getOrNull()
      ?.takeIf(JsonNode::isArray)
      ?.let(::identityFromEntries)
      ?: PullRequestIdentity.Unavailable("GitHub CLI returned unparsable pull request output.")

  private fun identityFromEntries(entries: JsonNode): PullRequestIdentity? {
    val first = entries.firstOrNull() ?: return PullRequestIdentity.Absent
    val url = first.path("url").takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank)
    val number = first.path("number").takeIf(JsonNode::isInt)?.asInt()
    val title = first.path("title").takeIf(JsonNode::isTextual)?.asText().orEmpty()
    return if (url != null && number != null) PullRequestIdentity.Found(url, number, title) else null
  }
}
