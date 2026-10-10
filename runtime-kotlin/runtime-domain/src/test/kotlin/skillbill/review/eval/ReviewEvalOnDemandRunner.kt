package skillbill.review.eval

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal object ReviewEvalOnDemandRunner {
  fun run(registerDir: Path): String {
    val casesRoot = locateReviewEvalRepositoryRoot().resolve("evals").resolve("review")
    if (!Files.isDirectory(casesRoot)) {
      return "no review eval cases under $casesRoot"
    }
    val reports =
      Files.newDirectoryStream(casesRoot).use { dirs ->
        dirs
          .filter { Files.isDirectory(it) }
          .sortedBy { it.fileName.toString() }
          .mapNotNull { caseDir -> scoreCaseIfPresent(caseDir, registerDir) }
      }
    return if (reports.isEmpty()) {
      "no case registers in $registerDir"
    } else {
      reports.joinToString("\n")
    }
  }
}

private fun locateReviewEvalRepositoryRoot(): Path {
  var current = Path.of("").toAbsolutePath().normalize()
  while (current.parent != null) {
    val hasSettings = Files.isRegularFile(current.resolve("runtime-kotlin").resolve("settings.gradle.kts"))
    val hasContracts = Files.isDirectory(current.resolve("orchestration").resolve("contracts"))
    if (hasSettings && hasContracts) {
      return current
    }
    current = current.parent
  }
  error("Could not locate skill-bill repo root from ${Path.of("").toAbsolutePath().normalize()}")
}

private fun formatReviewEvalScore(
  caseId: String,
  score: ReviewEvalScore,
): String =
  buildString {
    appendLine("case $caseId partial=${score.partial}")
    appendLaneScore(score.overall)
    score.perLane.values.sortedBy { it.lane }.forEach { laneScore ->
      appendLaneScore(laneScore)
    }
    score.curationItems.forEach { item ->
      appendLine("  curation ${item.reason} ${item.findingId.orEmpty()} ${item.detail}".trimEnd())
    }
    score.excludedNeedsCuration.forEach { expected ->
      appendLine("  needs_curation ${expected.id}")
    }
    score.unlabeledFindings.forEach { finding ->
      appendLine(
        "  unlabeled ${finding.findingId} ${finding.file}:${formatReviewEvalLineRange(finding)} lane=${finding.lane}",
      )
    }
  }.trimEnd()

private fun formatReviewEvalLineRange(finding: ReviewEvalLocatedFinding): String {
  val start = minOf(finding.lineStart, finding.lineEnd)
  val end = maxOf(finding.lineStart, finding.lineEnd)
  return if (start == end) "$start" else "$start-$end"
}

private fun StringBuilder.appendLaneScore(score: ReviewEvalLaneScore) {
  val precision = "%.2f".format(Locale.US, score.precision)
  val recall = "%.2f".format(Locale.US, score.recall)
  appendLine("  ${score.lane} precision=$precision recall=$recall")
}

private fun scoreCaseIfPresent(
  caseDir: Path,
  registerDir: Path,
): String? {
  val caseId = caseDir.fileName.toString()
  val register = registerDir.resolve("$caseId.md")
  val expectedFile = caseDir.resolve("expected-findings.yaml")
  if (!Files.isRegularFile(register) || !Files.isRegularFile(expectedFile)) {
    return null
  }
  val score =
    ReviewEvalScorer.score(
      Files.readString(register),
      loadReviewEvalExpectedFindings(Files.readString(expectedFile)),
    )
  return formatReviewEvalScore(caseId, score)
}
