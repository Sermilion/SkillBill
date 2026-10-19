package skillbill.infrastructure.workflow.github

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import java.nio.file.Path
import java.security.MessageDigest

class GhPullRequestIdentityLookup internal constructor(
  private val includeMergeState: Boolean = false,
  private val gh: GhCommandRunner,
) : PullRequestIdentityLookup {
  @Inject
  constructor() : this(gh = ProcessGhCommandRunner())

  constructor(includeMergeState: Boolean) : this(includeMergeState, ProcessGhCommandRunner())

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
    val result =
      gh.run(
        root,
        listOf(
          "pr",
          "list",
          "--head",
          head,
          "--state",
          "all",
          "--json",
          if (includeMergeState) "$IDENTITY_FIELDS,$MERGE_STATE_FIELDS" else IDENTITY_FIELDS,
          "--limit",
          "1",
        ),
      )
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
    if (url == null || number == null) return null
    return when (first.path("state").asText()) {
      "OPEN" ->
        PullRequestIdentity.Found(
          url,
          number,
          title,
          conflicting = conflicting(first),
          headSha = first.path("headRefOid").asText().takeIf(String::isNotBlank),
          editableFieldsDigest = editableFieldsDigest(title, first),
        )
      "MERGED" -> PullRequestIdentity.Merged(url, number)
      "CLOSED" -> PullRequestIdentity.Absent
      else -> null
    }
  }

  private fun editableFieldsDigest(
    title: String,
    entry: JsonNode,
  ): String {
    val body = entry.path("body").takeIf(JsonNode::isTextual)?.asText().orEmpty()
    val labels =
      entry.path("labels").mapNotNull { label -> label.path("name").takeIf(JsonNode::isTextual)?.asText() }.sorted()
    val fields = mapper.writeValueAsString(listOf(title, body, labels))
    return MessageDigest.getInstance("SHA-256")
      .digest(fields.encodeToByteArray())
      .joinToString("") { "%02x".format(it) }
  }

  private fun conflicting(entry: JsonNode): Boolean {
    val mergeable = entry.path("mergeable").asText()
    val mergeState = entry.path("mergeStateStatus").asText()
    return mergeable == "CONFLICTING" || mergeState == "DIRTY"
  }

  private companion object {
    const val IDENTITY_FIELDS = "url,number,title,body,labels,state,headRefOid"
    const val MERGE_STATE_FIELDS = "mergeable,mergeStateStatus"
  }
}
