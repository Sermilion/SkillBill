package skillbill.review.attribution

import skillbill.contracts.SharedPayloadKeys
import skillbill.review.model.ImportedFinding
import skillbill.review.model.ReviewIssueCategory

private val explicitCategoryAliases: Map<String, ReviewIssueCategory> =
  ReviewIssueCategory.entries.associateBy { it.wireValue } +
    mapOf(
      "behavior" to ReviewIssueCategory.BEHAVIOR_CORRECTNESS,
      "correctness" to ReviewIssueCategory.BEHAVIOR_CORRECTNESS,
      "persistence" to ReviewIssueCategory.DATA_PERSISTENCE,
      "database" to ReviewIssueCategory.DATA_PERSISTENCE,
      "db" to ReviewIssueCategory.DATA_PERSISTENCE,
      "concurrency" to ReviewIssueCategory.CONCURRENCY_LIFECYCLE,
      "lifecycle" to ReviewIssueCategory.CONCURRENCY_LIFECYCLE,
      "ux" to ReviewIssueCategory.UX_ACCESSIBILITY,
      "ui" to ReviewIssueCategory.UX_ACCESSIBILITY,
      "accessibility" to ReviewIssueCategory.UX_ACCESSIBILITY,
      "testing" to ReviewIssueCategory.TESTING_QUALITY_GATE,
      "tests" to ReviewIssueCategory.TESTING_QUALITY_GATE,
      "quality" to ReviewIssueCategory.TESTING_QUALITY_GATE,
      "security" to ReviewIssueCategory.SECURITY_PRIVACY,
      "privacy" to ReviewIssueCategory.SECURITY_PRIVACY,
      "docs" to ReviewIssueCategory.DOCS_CONTRACT,
      "contract" to ReviewIssueCategory.DOCS_CONTRACT,
      "api" to ReviewIssueCategory.DOCS_CONTRACT,
    )

private val bulletCategoryPattern =
  Regex(
    """(?:^|\b)(?:issue\s+category|category)\s*[:=]\s*(?<value>[A-Za-z0-9 _/-]+)""",
    RegexOption.IGNORE_CASE,
  )

private val routedCategoryRules: List<Pair<List<String>, ReviewIssueCategory>> =
  listOf(
    listOf("code-quality") to ReviewIssueCategory.CODE_QUALITY,
    listOf("persistence") to ReviewIssueCategory.DATA_PERSISTENCE,
    listOf("platform-correctness", "concurrency", "lifecycle") to ReviewIssueCategory.CONCURRENCY_LIFECYCLE,
    listOf("ux-accessibility", "accessibility", "-ui", " ui") to ReviewIssueCategory.UX_ACCESSIBILITY,
    listOf("testing", "quality-check") to ReviewIssueCategory.TESTING_QUALITY_GATE,
    listOf("security", "privacy") to ReviewIssueCategory.SECURITY_PRIVACY,
    listOf("api-contracts", "contract", "docs") to ReviewIssueCategory.DOCS_CONTRACT,
    listOf("architecture", "correctness") to ReviewIssueCategory.BEHAVIOR_CORRECTNESS,
  )

internal fun normalizeReviewIssueCategory(rawValue: String?): String =
  resolveExplicitCategory(rawValue)?.wireValue ?: ReviewIssueCategory.OTHER.wireValue

internal fun resolveReviewIssueCategory(
  explicitCategory: String?,
  routedSkill: String?,
  specialistReviews: List<String>,
  finding: ImportedFinding,
): String =
  (
    resolveExplicitCategory(explicitCategory)
      ?: resolveExplicitCategory(extractBulletCategory(finding.findingText))
      ?: finding.laneSkillName?.let(::categoryFromRoutedLabel)?.takeIf { it == ReviewIssueCategory.CODE_QUALITY }
      ?: resolveRoutedCategory(
        routedSkill,
        specialistReviews.filterNot { categoryFromRoutedLabel(it) == ReviewIssueCategory.CODE_QUALITY },
      )
      ?: classifyFindingCategory(finding)
      ?: ReviewIssueCategory.OTHER
  ).wireValue

private fun resolveExplicitCategory(rawValue: String?): ReviewIssueCategory? =
  rawValue
    ?.trim()
    ?.lowercase()
    ?.replace(Regex("[^a-z0-9]+"), "_")
    ?.trim('_')
    ?.takeIf(String::isNotEmpty)
    ?.let(explicitCategoryAliases::get)

private fun extractBulletCategory(findingText: String): String? =
  bulletCategoryPattern.find(findingText)?.groups?.get(SharedPayloadKeys.VALUE)?.value

private fun resolveRoutedCategory(
  routedSkill: String?,
  specialistReviews: List<String>,
): ReviewIssueCategory? {
  val labels = listOfNotNull(routedSkill) + specialistReviews
  return labels.asSequence().mapNotNull(::categoryFromRoutedLabel).firstOrNull()
}

private fun categoryFromRoutedLabel(label: String): ReviewIssueCategory? {
  val normalized = label.lowercase()
  return routedCategoryRules.firstOrNull { (needles) -> normalized.containsAny(needles) }?.second
}

private fun classifyFindingCategory(finding: ImportedFinding): ReviewIssueCategory? {
  val text = "${finding.location} ${finding.description} ${finding.findingText}".lowercase()
  return when {
    text.containsAny("database", "sqlite", "transaction", "migration", "persist", "query", "row", "table") ->
      ReviewIssueCategory.DATA_PERSISTENCE
    text.containsAny("coroutine", "thread", "race", "lifecycle", "dispatcher", "timeout", "deadlock", "concurrent") ->
      ReviewIssueCategory.CONCURRENCY_LIFECYCLE
    text.containsAny(
      "accessibility",
      "screen reader",
      "content description",
      "focus",
      "label",
      "keyboard",
      "ui",
      "ux",
    ) ->
      ReviewIssueCategory.UX_ACCESSIBILITY
    text.containsAny("test", "assert", "fixture", "quality gate", "lint", "gradle check", "golden") ->
      ReviewIssueCategory.TESTING_QUALITY_GATE
    text.containsAny("auth", "token", "secret", "credential", "password", "privacy", "encrypt", "sensitive") ->
      ReviewIssueCategory.SECURITY_PRIVACY
    text.containsAny("contract", "schema", "api", "status code", "documentation", "docs", "backward compatible") ->
      ReviewIssueCategory.DOCS_CONTRACT
    text.containsAny("incorrect", "wrong", "bug", "regression", "state", "validation", "invariant") ->
      ReviewIssueCategory.BEHAVIOR_CORRECTNESS
    else -> null
  }
}

private fun String.containsAny(vararg needles: String): Boolean = needles.any { contains(it) }

private fun String.containsAny(needles: List<String>): Boolean = needles.any { contains(it) }
