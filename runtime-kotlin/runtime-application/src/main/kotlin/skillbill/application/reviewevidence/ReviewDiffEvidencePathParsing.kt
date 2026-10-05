package skillbill.application.reviewevidence

internal const val REVIEW_DIFF_OLD_START_GROUP = 1
internal const val REVIEW_DIFF_OLD_COUNT_GROUP = 2
internal const val REVIEW_DIFF_NEW_START_GROUP = 3
internal const val REVIEW_DIFF_NEW_COUNT_GROUP = 4

private const val REVIEW_DIFF_OLD_PREFIX = "a/"
private const val REVIEW_DIFF_NEW_PREFIX = "b/"

private val REVIEW_DIFF_HEADER_PATH = Regex("(?m)^\\+\\+\\+ (.+)$")
private val REVIEW_DIFF_OLD_HEADER_PATH = Regex("(?m)^--- (.+)$")
private val REVIEW_DIFF_RENAME_FROM = Regex("(?m)^rename from (.+)$")
private val REVIEW_DIFF_RENAME_TO = Regex("(?m)^rename to (.+)$")
private val REVIEW_DIFF_COPY_FROM = Regex("(?m)^copy from (.+)$")
private val REVIEW_DIFF_COPY_TO = Regex("(?m)^copy to (.+)$")

internal data class ReviewDiffRecordPaths(val old: String?, val new: String?, val authoritative: String)

internal fun reviewDiffRecordPaths(record: String): ReviewDiffParseOutcome<ReviewDiffRecordPaths> {
  val oldHeaderValue = REVIEW_DIFF_OLD_HEADER_PATH.find(record)?.groupValues?.get(1)
  val newHeaderValue = REVIEW_DIFF_HEADER_PATH.find(record)?.groupValues?.get(1)
  val oldAbsent = oldHeaderValue?.trim() == "/dev/null"
  val newAbsent = newHeaderValue?.trim() == "/dev/null"
  val oldSources = mutableListOf<String>()
  val newSources = mutableListOf<String>()
  val sources =
    listOf(
      Triple(oldHeaderValue, REVIEW_DIFF_OLD_PREFIX, oldSources),
      Triple(REVIEW_DIFF_RENAME_FROM.find(record)?.groupValues?.get(1), null, oldSources),
      Triple(REVIEW_DIFF_COPY_FROM.find(record)?.groupValues?.get(1), null, oldSources),
      Triple(newHeaderValue, REVIEW_DIFF_NEW_PREFIX, newSources),
      Triple(REVIEW_DIFF_RENAME_TO.find(record)?.groupValues?.get(1), null, newSources),
      Triple(REVIEW_DIFF_COPY_TO.find(record)?.groupValues?.get(1), null, newSources),
    )
  for ((raw, prefix, target) in sources) {
    if (raw == null) continue
    when (val path = reviewDiffRepositoryPath(raw, prefix)) {
      is ReviewDiffParseOutcome.Accepted -> path.value?.let(target::add)
      is ReviewDiffParseOutcome.Rejected -> return path
    }
  }
  val headerPaths =
    when (val result = parseReviewDiffHeader(record.lineSequence().first(), oldSources, newSources)) {
      is ReviewDiffParseOutcome.Accepted -> result.value
      is ReviewDiffParseOutcome.Rejected -> return result
    }
  if (oldAbsent && newAbsent) return ReviewDiffParseOutcome.Rejected("Git diff record cannot have /dev/null on both sides.")
  val old =
    if (oldAbsent) {
      null
    } else {
      when (val result = agreeReviewDiffPaths("old", oldSources + listOfNotNull(headerPaths?.first))) {
        is ReviewDiffParseOutcome.Accepted -> result.value
        is ReviewDiffParseOutcome.Rejected -> return result
      }
    }
  val new =
    if (newAbsent) {
      null
    } else {
      when (val result = agreeReviewDiffPaths("new", newSources + listOfNotNull(headerPaths?.second))) {
        is ReviewDiffParseOutcome.Accepted -> result.value
        is ReviewDiffParseOutcome.Rejected -> return result
      }
    }
  val authoritative =
    new ?: old ?: return ReviewDiffParseOutcome.Rejected("Malformed Git diff record has no attributable repository path.")
  return ReviewDiffParseOutcome.Accepted(ReviewDiffRecordPaths(old, new, authoritative))
}

private fun parseReviewDiffHeader(
  line: String,
  corroboratedOld: List<String>,
  corroboratedNew: List<String>,
): ReviewDiffParseOutcome<Pair<String, String>?> {
  val body = line.removePrefix("diff --git ").takeIf { it != line } ?: return ReviewDiffParseOutcome.Accepted(null)
  val tokens = when (val result = parseReviewDiffGitTokens(body)) {
    is ReviewDiffParseOutcome.Accepted -> result.value
    is ReviewDiffParseOutcome.Rejected -> return result
  }
  if (tokens.size == 2) {
    val old =
      when (val result = reviewDiffRepositoryPath(tokens[0], REVIEW_DIFF_OLD_PREFIX)) {
        is ReviewDiffParseOutcome.Accepted -> result.value ?: return ReviewDiffParseOutcome.Rejected("Required value was null.")
        is ReviewDiffParseOutcome.Rejected -> return result
      }
    val new =
      when (val result = reviewDiffRepositoryPath(tokens[1], REVIEW_DIFF_NEW_PREFIX)) {
        is ReviewDiffParseOutcome.Accepted -> result.value ?: return ReviewDiffParseOutcome.Rejected("Required value was null.")
        is ReviewDiffParseOutcome.Rejected -> return result
      }
    return ReviewDiffParseOutcome.Accepted(old to new)
  }
  val candidates = mutableListOf<Pair<String, String>>()
  for (boundary in Regex(" b/").findAll(body)) {
    val old = reviewDiffRepositoryPath(body.substring(0, boundary.range.first), REVIEW_DIFF_OLD_PREFIX)
    val new = reviewDiffRepositoryPath(body.substring(boundary.range.first + 1), REVIEW_DIFF_NEW_PREFIX)
    if (
      old is ReviewDiffParseOutcome.Accepted && new is ReviewDiffParseOutcome.Accepted &&
      old.value != null && new.value != null
    ) {
      candidates += old.value to new.value
    }
  }
  val candidatesMatchingRecord =
    candidates.filter { (old, new) ->
      (corroboratedOld.isEmpty() || old in corroboratedOld) &&
        (corroboratedNew.isEmpty() || new in corroboratedNew) &&
        (corroboratedOld.isNotEmpty() || corroboratedNew.isNotEmpty() || old == new)
    }.distinct()
  if (candidatesMatchingRecord.size != 1) {
    return ReviewDiffParseOutcome.Rejected("Ambiguous Git diff header cannot establish repository path ownership.")
  }
  return ReviewDiffParseOutcome.Accepted(candidatesMatchingRecord.single())
}

private fun agreeReviewDiffPaths(
  side: String,
  paths: List<String>,
): ReviewDiffParseOutcome<String?> {
  val distinct = paths.distinct()
  if (distinct.size > 1) {
    return ReviewDiffParseOutcome.Rejected("Git diff $side path sources disagree: ${distinct.joinToString()}")
  }
  return ReviewDiffParseOutcome.Accepted(distinct.singleOrNull())
}
