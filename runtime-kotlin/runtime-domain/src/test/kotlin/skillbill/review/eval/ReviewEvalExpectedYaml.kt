package skillbill.review.eval

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper

internal fun loadReviewEvalExpectedFindings(yaml: String): List<ReviewEvalExpectedFinding> {
  val root = YAMLMapper().readTree(yaml)
  val findings = root.path("findings")
  if (!findings.isArray) {
    return emptyList()
  }
  return findings.map(::readExpectedFinding)
}

private fun readExpectedFinding(node: JsonNode): ReviewEvalExpectedFinding {
  val range = readExpectedLineRange(node)
  return ReviewEvalExpectedFinding(
    id = textOrEmpty(node, "id"),
    file = textOrEmpty(node, "file"),
    lineStart = range.first,
    lineEnd = range.second,
    lane = textOrEmpty(node, "lane"),
    label = readExpectedLabel(textOrEmpty(node, "label")),
    status = readExpectedStatus(textOrEmpty(node, "status")),
    window = node.path("window").takeIf { it.isNumber }?.intValue() ?: REVIEW_EVAL_DEFAULT_LINE_WINDOW,
  )
}

private fun readExpectedLabel(raw: String): ReviewEvalExpectedLabel =
  when (raw.trim()) {
    "true_positive" -> ReviewEvalExpectedLabel.TRUE_POSITIVE
    "non_issue" -> ReviewEvalExpectedLabel.NON_ISSUE
    else -> error("expected-findings label must be true_positive or non_issue, got '$raw'")
  }

private fun readExpectedStatus(raw: String): ReviewEvalExpectedStatus =
  if (raw.trim() == "needs_curation") {
    ReviewEvalExpectedStatus.NEEDS_CURATION
  } else {
    ReviewEvalExpectedStatus.READY
  }

private fun readExpectedLineRange(node: JsonNode): Pair<Int?, Int?> {
  val startField = intOrNull(node, "line_start")
  val endField = intOrNull(node, "line_end")
  val lineNode = node.get("line")
  return when {
    startField != null -> startField to (endField ?: startField)
    lineNode == null || lineNode.isNull -> null to null
    lineNode.isNumber -> lineNode.intValue().let { line -> line to (endField ?: line) }
    else -> parseLineRangeToken(lineNode.asText())
  }
}

internal fun parseLineRangeToken(raw: String): Pair<Int?, Int?> {
  val token = raw.trim()
  if (token.isEmpty()) {
    return null to null
  }
  val start = token.substringBefore('-').toIntOrNull()
  val end = token.substringAfter('-', missingDelimiterValue = token).toIntOrNull()
  return start to (end ?: start)
}

private fun textOrEmpty(
  node: JsonNode,
  field: String,
): String = node.path(field).asText("").trim()

private fun intOrNull(
  node: JsonNode,
  field: String,
): Int? = node.get(field)?.takeIf { it.isNumber }?.intValue()
