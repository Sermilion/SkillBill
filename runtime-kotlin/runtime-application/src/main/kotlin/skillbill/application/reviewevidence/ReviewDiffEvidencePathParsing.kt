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
  return parseReviewDiffHeader(record.lineSequence().first(), oldSources, newSources).flatMap { header ->
    resolveReviewDiffRecordPaths(
      oldAbsent,
      newAbsent,
      oldSources + listOfNotNull(header?.first),
      newSources + listOfNotNull(header?.second),
    )
  }
}

private fun resolveReviewDiffRecordPaths(
  oldAbsent: Boolean,
  newAbsent: Boolean,
  oldSources: List<String>,
  newSources: List<String>,
): ReviewDiffParseOutcome<ReviewDiffRecordPaths> {
  if (oldAbsent && newAbsent) {
    return ReviewDiffParseOutcome.Rejected("Git diff record cannot have /dev/null on both sides.")
  }
  val old = if (oldAbsent) ReviewDiffParseOutcome.Accepted(null) else agreeReviewDiffPaths("old", oldSources)
  val new = if (newAbsent) ReviewDiffParseOutcome.Accepted(null) else agreeReviewDiffPaths("new", newSources)
  return old.flatMap { oldPath ->
    new.flatMap { newPath ->
      val authoritative = newPath ?: oldPath
      if (authoritative == null) {
        ReviewDiffParseOutcome.Rejected("Malformed Git diff record has no attributable repository path.")
      } else {
        ReviewDiffParseOutcome.Accepted(ReviewDiffRecordPaths(oldPath, newPath, authoritative))
      }
    }
  }
}

private fun parseReviewDiffHeader(
  line: String,
  corroboratedOld: List<String>,
  corroboratedNew: List<String>,
): ReviewDiffParseOutcome<Pair<String, String>?> {
  val body = line.removePrefix("diff --git ").takeIf { it != line } ?: return ReviewDiffParseOutcome.Accepted(null)
  return parseReviewDiffGitTokens(body).flatMap { tokens ->
    if (tokens.size == 2) {
      reviewDiffHeaderPair(tokens[0], tokens[1])
    } else {
      ambiguousReviewDiffHeader(body, corroboratedOld, corroboratedNew)
    }
  }
}

private fun reviewDiffHeaderPair(
  oldRaw: String,
  newRaw: String,
): ReviewDiffParseOutcome<Pair<String, String>> =
  reviewDiffRepositoryPath(oldRaw, REVIEW_DIFF_OLD_PREFIX).flatMap { old ->
    if (old == null) {
      ReviewDiffParseOutcome.Rejected("Required value was null.")
    } else {
      reviewDiffRepositoryPath(newRaw, REVIEW_DIFF_NEW_PREFIX).flatMap { new ->
        if (new == null) {
          ReviewDiffParseOutcome.Rejected("Required value was null.")
        } else {
          ReviewDiffParseOutcome.Accepted(old to new)
        }
      }
    }
  }

private fun ambiguousReviewDiffHeader(
  body: String,
  corroboratedOld: List<String>,
  corroboratedNew: List<String>,
): ReviewDiffParseOutcome<Pair<String, String>> {
  val candidates =
    Regex(" b/").findAll(body).mapNotNull { boundary ->
      val result =
        reviewDiffHeaderPair(
          body.substring(0, boundary.range.first),
          body.substring(boundary.range.first + 1),
        )
      (result as? ReviewDiffParseOutcome.Accepted)?.value
    }
  val matching =
    candidates.filter { (old, new) ->
      pathCorroborated(old, corroboratedOld) && pathCorroborated(new, corroboratedNew) &&
        (corroboratedOld.isNotEmpty() || corroboratedNew.isNotEmpty() || old == new)
    }.distinct().toList()
  return if (matching.size != 1) {
    ReviewDiffParseOutcome.Rejected("Ambiguous Git diff header cannot establish repository path ownership.")
  } else {
    ReviewDiffParseOutcome.Accepted(matching.single())
  }
}

private fun pathCorroborated(
  path: String,
  sources: List<String>,
): Boolean = sources.isEmpty() || path in sources

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
