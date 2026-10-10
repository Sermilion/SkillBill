package skillbill.review.eval

import skillbill.review.parsing.ReviewParser

internal object ReviewEvalScorer {
  fun score(
    registerText: String,
    expected: List<ReviewEvalExpectedFinding>,
  ): ReviewEvalScore {
    val (excluded, scorable) = expected.partition { it.status == ReviewEvalExpectedStatus.NEEDS_CURATION }
    val parsed = readReviewEvalRegister(registerText)
    val matched = matchReportedToExpected(parsed.located, scorable)
    return assembleReviewEvalScore(matched, scorable, excluded, parsed)
  }
}

private fun reviewEvalRatio(
  numerator: Int,
  denominator: Int,
): Double = if (denominator == 0) 0.0 else numerator.toDouble() / denominator.toDouble()

private fun parseReviewEvalLocation(location: String): Triple<String, Int, Int>? {
  val file = location.substringBeforeLast(':', "").trim()
  val range = parseLineRangeToken(location.substringAfterLast(':', "").trim())
  val start = range.first ?: return null
  if (file.isEmpty()) {
    return null
  }
  val end = range.second ?: start
  return Triple(file, minOf(start, end), maxOf(start, end))
}

private fun reviewEvalLineDistance(
  reported: ReviewEvalLocatedFinding,
  expected: ReviewEvalExpectedFinding,
): Int? {
  val expectedStart = expected.lineStart ?: return null
  val expectedEnd = expected.lineEnd ?: expectedStart
  val reportedLo = minOf(reported.lineStart, reported.lineEnd)
  val reportedHi = maxOf(reported.lineStart, reported.lineEnd)
  val expectedLo = minOf(expectedStart, expectedEnd)
  val expectedHi = maxOf(expectedStart, expectedEnd)
  return when {
    reportedHi < expectedLo -> expectedLo - reportedHi
    expectedHi < reportedLo -> reportedLo - expectedHi
    else -> 0
  }
}

private fun ensureReviewEvalIds(text: String): String {
  val hasRun = text.lineSequence().any { it.startsWith("Review run ID:") }
  val hasSession = text.lineSequence().any { it.startsWith("Review session ID:") }
  if (hasRun && hasSession) {
    return text
  }
  return buildString {
    if (!hasRun) {
      appendLine("Review run ID: $REVIEW_EVAL_PLACEHOLDER_RUN_ID")
    }
    if (!hasSession) {
      appendLine("Review session ID: $REVIEW_EVAL_PLACEHOLDER_SESSION_ID")
    }
    append(text)
  }
}

private fun readReviewEvalRegister(registerText: String): ReviewEvalRegisterRead {
  val prepared = ensureReviewEvalIds(registerText)
  val parsed =
    try {
      ReviewParser.parseReview(prepared)
    } catch (_: IllegalArgumentException) {
      return ReviewEvalRegisterRead(
        located = emptyList(),
        curationItems = listOf(ReviewEvalCurationItem(reason = "uninterpretable register")),
      )
    }
  val curation = mutableListOf<ReviewEvalCurationItem>()
  val located = mutableListOf<ReviewEvalLocatedFinding>()
  parsed.findings.forEach { finding ->
    val parsedLocation = parseReviewEvalLocation(finding.location)
    if (parsedLocation == null) {
      curation +=
        ReviewEvalCurationItem(
          reason = "missing matching metadata",
          findingId = finding.findingId,
          detail = finding.location,
        )
    } else {
      located +=
        ReviewEvalLocatedFinding(
          findingId = finding.findingId,
          file = parsedLocation.first,
          lineStart = parsedLocation.second,
          lineEnd = parsedLocation.third,
          lane = finding.laneSkillName?.takeIf { it.isNotBlank() } ?: REVIEW_EVAL_UNATTRIBUTED_LANE,
        )
    }
  }
  if (parsed.findings.isEmpty() && prepared.lineSequence().any { isUninterpretedProseLine(it.trim()) }) {
    curation += ReviewEvalCurationItem(reason = "no extractable finding locations")
  }
  return ReviewEvalRegisterRead(located = located, curationItems = curation)
}

private fun isUninterpretedProseLine(line: String): Boolean {
  if (line.isEmpty()) {
    return false
  }
  val ignoredPrefixes =
    listOf(
      "Review run ID:",
      "Review session ID:",
      "Routed to:",
      "Detected review scope:",
      "Detected stack:",
      "Execution mode:",
      "Specialist reviews:",
      "Baseline review:",
      "verdict:",
    )
  if (ignoredPrefixes.any { prefix -> line.startsWith(prefix, ignoreCase = true) }) {
    return false
  }
  if (line.startsWith("#")) {
    return false
  }
  return !line.equals("NO_FINDINGS", ignoreCase = true) && !line.equals("No findings.", ignoreCase = true)
}

private fun normalizeReviewEvalPath(path: String): String = path.trim().replace('\\', '/').removePrefix("./")

private fun matchReportedToExpected(
  reported: List<ReviewEvalLocatedFinding>,
  expected: List<ReviewEvalExpectedFinding>,
): ReviewEvalMatchSet {
  val remaining = expected.toMutableList()
  val matches = mutableListOf<ReviewEvalMatch>()
  val unlabeled = mutableListOf<ReviewEvalLocatedFinding>()
  reported.forEach { finding ->
    val candidate = closestExpectedMatch(finding, remaining)
    if (candidate == null) {
      unlabeled += finding
    } else {
      remaining.remove(candidate)
      matches += ReviewEvalMatch(reported = finding, expected = candidate)
    }
  }
  return ReviewEvalMatchSet(matches = matches, unlabeled = unlabeled, unmatchedExpected = remaining.toList())
}

private fun closestExpectedMatch(
  reported: ReviewEvalLocatedFinding,
  expected: List<ReviewEvalExpectedFinding>,
): ReviewEvalExpectedFinding? {
  val reportedPath = normalizeReviewEvalPath(reported.file)
  val ranked =
    expected.mapNotNull { candidate ->
      val distance = reviewEvalLineDistance(reported, candidate) ?: return@mapNotNull null
      if (reportedPath != normalizeReviewEvalPath(candidate.file) || distance > candidate.window) {
        null
      } else {
        candidate to distance
      }
    }
  return ranked.minByOrNull { it.second }?.first
}

private fun assembleReviewEvalScore(
  matched: ReviewEvalMatchSet,
  scorable: List<ReviewEvalExpectedFinding>,
  excluded: List<ReviewEvalExpectedFinding>,
  parsed: ReviewEvalRegisterRead,
): ReviewEvalScore {
  val truePositives = matched.matches.filter { it.expected.label == ReviewEvalExpectedLabel.TRUE_POSITIVE }
  val falsePositives = matched.matches.filter { it.expected.label == ReviewEvalExpectedLabel.NON_ISSUE }
  val expectedTruePositives = scorable.filter { it.label == ReviewEvalExpectedLabel.TRUE_POSITIVE }
  return ReviewEvalScore(
    truePositives = truePositives,
    missedTruePositives = matched.unmatchedExpected.filter { it.label == ReviewEvalExpectedLabel.TRUE_POSITIVE },
    falsePositives = falsePositives,
    unlabeledFindings = matched.unlabeled,
    excludedNeedsCuration = excluded,
    curationItems = parsed.curationItems,
    overall =
      laneScore(
        REVIEW_EVAL_OVERALL_LANE,
        truePositives.size,
        falsePositives.size,
        truePositives.size,
        expectedTruePositives.size,
      ),
    perLane = reviewEvalPerLaneScores(truePositives, falsePositives, matched.unlabeled, scorable),
    partial = parsed.curationItems.isNotEmpty(),
  )
}

private fun reviewEvalPerLaneScores(
  truePositives: List<ReviewEvalMatch>,
  falsePositives: List<ReviewEvalMatch>,
  unlabeled: List<ReviewEvalLocatedFinding>,
  scorable: List<ReviewEvalExpectedFinding>,
): Map<String, ReviewEvalLaneScore> {
  val precisionLanes =
    (truePositives.map { it.reported.lane } + falsePositives.map { it.reported.lane } + unlabeled.map { it.lane })
      .toSet()
  val recallLanes = scorable.map(::expectedReviewEvalLane).toSet()
  return (precisionLanes + recallLanes).sorted().associateWith { lane ->
    laneScore(
      lane,
      truePositives.count { it.reported.lane == lane },
      falsePositives.count { it.reported.lane == lane },
      truePositives.count { expectedReviewEvalLane(it.expected) == lane },
      scorable.count { expected ->
        expected.label == ReviewEvalExpectedLabel.TRUE_POSITIVE && expectedReviewEvalLane(expected) == lane
      },
    )
  }
}

private fun expectedReviewEvalLane(expected: ReviewEvalExpectedFinding): String =
  expected.lane.ifBlank { REVIEW_EVAL_UNATTRIBUTED_LANE }

private fun laneScore(
  lane: String,
  truePositiveCount: Int,
  falsePositiveCount: Int,
  matchedExpectedTruePositiveCount: Int,
  expectedTruePositiveCount: Int,
): ReviewEvalLaneScore =
  ReviewEvalLaneScore(
    lane = lane,
    truePositiveCount = truePositiveCount,
    falsePositiveCount = falsePositiveCount,
    matchedExpectedTruePositiveCount = matchedExpectedTruePositiveCount,
    expectedTruePositiveCount = expectedTruePositiveCount,
    precision = reviewEvalRatio(truePositiveCount, truePositiveCount + falsePositiveCount),
    recall = reviewEvalRatio(matchedExpectedTruePositiveCount, expectedTruePositiveCount),
  )
