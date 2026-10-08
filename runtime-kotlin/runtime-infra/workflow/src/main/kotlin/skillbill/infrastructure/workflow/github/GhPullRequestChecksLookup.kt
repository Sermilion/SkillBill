package skillbill.infrastructure.workflow.github

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.runner.PullRequestChecksLookup
import skillbill.ports.goalrunner.runner.model.CheckBucket
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import java.nio.file.Path

class GhPullRequestChecksLookup internal constructor(
  private val gh: GhCommandRunner,
) : PullRequestChecksLookup {
  @Inject
  constructor() : this(ProcessGhCommandRunner())

  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  override fun lookup(
    repoRoot: Path,
    prNumber: Int,
  ): PullRequestChecks {
    val root = repoRoot.toAbsolutePath().normalize()
    val result = gh.run(root, listOf("pr", "checks", prNumber.toString(), "--json", "name,state,bucket,link"))
    return parseChecks(result)
  }

  private fun parseChecks(result: GhCommandResult): PullRequestChecks {
    val entries = runCatching { mapper.readTree(result.stdout) }.getOrNull()?.takeIf(JsonNode::isArray)
    return when {
      entries != null -> checksFromEntries(entries)
      result.stdout.contains(NO_CHECKS_MARKER, ignoreCase = true) -> PullRequestChecks.NoChecks
      result.stdout.contains(UNKNOWN_FLAG_MARKER, ignoreCase = true) ->
        PullRequestChecks.Unavailable(UNSUPPORTED_JSON_REASON)
      else -> PullRequestChecks.Unavailable(result.describeFailure())
    }
  }

  private fun checksFromEntries(entries: JsonNode): PullRequestChecks {
    if (entries.size() == 0) return PullRequestChecks.NoChecks
    val checks = entries.map(::checkFrom)
    return if (checks.all { it != null }) {
      PullRequestChecks.Reported(checks.filterNotNull())
    } else {
      PullRequestChecks.Unavailable(UNRECOGNISED_CHECK_REASON)
    }
  }

  private fun checkFrom(entry: JsonNode): PullRequestCheck? {
    val name = entry.path("name").takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank) ?: return null
    val bucket = entry.path("bucket").takeIf(JsonNode::isTextual)?.asText()?.let(::bucketFrom) ?: return null
    val link = entry.path("link").takeIf(JsonNode::isTextual)?.asText().orEmpty()
    return PullRequestCheck(name = name, bucket = bucket, link = link)
  }

  private fun bucketFrom(wireValue: String): CheckBucket? =
    CheckBucket.entries.firstOrNull { it.wireValue == wireValue }
}

private const val NO_CHECKS_MARKER: String = "no checks reported"
private const val UNKNOWN_FLAG_MARKER: String = "unknown flag"
private const val UNSUPPORTED_JSON_REASON: String = "gh pr checks --json unsupported; upgrade gh"
private const val UNRECOGNISED_CHECK_REASON: String = "GitHub CLI returned a check with an unrecognised bucket or name."
