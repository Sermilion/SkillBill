package skillbill.review.parsing

import skillbill.contracts.SharedPayloadKeys
import skillbill.review.model.ImportedFinding

fun requireMatch(
  pattern: Regex,
  text: String,
  errorMessage: String,
): String =
  pattern.find(text)?.groups?.get(SharedPayloadKeys.VALUE)?.value ?: throw IllegalArgumentException(errorMessage)

internal fun parseReviewFindings(text: String): List<ImportedFinding> {
  val bulletFindings = parseBulletFindings(text)
  return if (bulletFindings.isNotEmpty()) bulletFindings else parseTableFindings(text)
}

internal fun parseBulletFindings(text: String): List<ImportedFinding> {
  val seenIds = mutableSetOf<String>()
  return findingPattern.findAll(text).map { match ->
    val findingId = match.groups["findingId"]?.value.orEmpty()
    require(seenIds.add(findingId)) {
      "Review output contains duplicate finding id '$findingId'."
    }
    val rawDescription = match.groups["description"]?.value.orEmpty().trim()
    val provenance = findingProvenancePattern.find(rawDescription)
    ImportedFinding(
      findingId = findingId,
      severity = match.groups["severity"]?.value.orEmpty(),
      confidence = match.groups["confidenceLevel"]?.value.orEmpty(),
      location = match.groups["location"]?.value.orEmpty().trim(),
      description = provenance?.let { rawDescription.removeRange(it.range).trim() } ?: rawDescription,
      findingText = match.value.trim(),
      laneSkillName = match.groups["specialistSkillName"]?.value ?: provenance?.let(::parseProvenanceLane),
    )
  }.toList()
}

private fun parseProvenanceLane(provenance: MatchResult): String? =
  provenance
    .groups["provenance"]
    ?.value
    ?.let(findingSpecialistsProvenancePattern::find)
    ?.groups
    ?.get(SharedPayloadKeys.VALUE)
    ?.value
    ?.split(",")
    ?.map(String::trim)
    ?.firstOrNull(String::isNotEmpty)

private fun parseTableFindings(text: String): List<ImportedFinding> {
  val lines = text.lines()
  val headerIndex = lines.indexOfFirst(::isTableHeaderLine)
  val columnMap =
    if (headerIndex >= 0) {
      buildColumnMap(lines[headerIndex].split("|").map { it.trim() })
    } else {
      emptyMap()
    }
  return if (headerIndex < 0 || !setOf("number", "severity", "description").all(columnMap::containsKey)) {
    emptyList()
  } else {
    parseTableBody(lines.drop(headerIndex + 1), columnMap)
  }
}

internal fun extractSummaryValue(
  text: String,
  key: String,
): String? = summaryPatterns[key]?.find(text)?.groups?.get(SharedPayloadKeys.VALUE)?.value?.trim()

internal fun extractSpecialistReviews(text: String): List<String> {
  val seen = linkedSetOf<String>()
  specialistReviewsPattern.findAll(text).forEach { match ->
    match
      .groups[SharedPayloadKeys.VALUE]
      ?.value
      .orEmpty()
      .split(",")
      .map(String::trim)
      .filter(String::isNotEmpty)
      .forEach(seen::add)
  }
  return seen.toList()
}
