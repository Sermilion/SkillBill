package skillbill.review.attribution

import skillbill.review.model.NormalizedStackLabel

fun normalizeRoutedSkill(rawValue: String?): String {
  val value = rawValue?.trim().orEmpty()
  if (value.isEmpty()) return "unrouted"
  val withoutNamespace = value.substringAfter(':')
  return if (
    withoutNamespace != value &&
    plausibleSkillSlug(withoutNamespace) &&
    value.substringBefore(':').matches(Regex("^[A-Za-z][A-Za-z0-9_-]*$"))
  ) {
    withoutNamespace
  } else {
    value
  }
}

fun normalizeStackLabel(
  rawValue: String?,
  knownPlatformSlugs: Collection<String>,
): NormalizedStackLabel {
  val value = rawValue?.trim().orEmpty()
  if (value.isEmpty()) return NormalizedStackLabel(stack = "unknown")
  val slugSource =
    value
      .substringBefore("(")
      .substringBefore("->")
      .substringBefore("→")
      .substringBefore(" fallback")
      .trim()
  val slug = knownStackSlug(slugSource, knownPlatformSlugs) ?: "unknown"
  val detail = value.takeIf { it.isNotBlank() && normalizeTelemetrySlug(it) != slug }
  return NormalizedStackLabel(stack = slug, detail = detail)
}

fun normalizePlatformSlug(
  rawValue: String?,
  knownPlatformSlugs: Collection<String>,
): String = normalizeStackLabel(rawValue, knownPlatformSlugs).stack

fun normalizeTelemetrySlug(value: String): String =
  value
    .trim()
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')

fun normalizeScopeType(rawValue: String?): String {
  val normalized =
    rawValue
      ?.substringBefore("(")
      ?.trim()
      ?.lowercase()
      ?.replace(Regex("[^a-z0-9]+"), "_")
      ?.trim('_')
      ?.takeIf(String::isNotEmpty)
      ?: "unknown"
  return when (normalized) {
    "branch_diff", "branchdiff" -> "branch_diff"
    "unstaged_changes", "unstaged" -> "unstaged_changes"
    "staged_changes", "staged" -> "staged_changes"
    "file", "files" -> "files"
    "repository", "repo" -> "repo"
    else -> normalized
  }
}

private fun plausibleSkillSlug(value: String): Boolean = value.matches(Regex("^[a-z0-9][a-z0-9-]*$"))

private fun knownStackSlug(
  value: String,
  knownPlatformSlugs: Collection<String>,
): String? {
  val slug = normalizeTelemetrySlug(value).takeIf(String::isNotEmpty) ?: return null
  val tokenList = slug.split("-")
  val tokens = tokenList.toSet()
  val knownPlatformSlug = tokenList.firstOrNull { it in knownPlatformSlugs }
  return when {
    isKmpSlug(tokens) -> "kmp"
    knownPlatformSlug != null -> knownPlatformSlug
    slug.matches(Regex("^[a-z0-9][a-z0-9-]*$")) && value.trim().lowercase() == slug -> slug
    else -> null
  }
}

private fun isKmpSlug(tokens: Set<String>): Boolean = "kmp" in tokens || isKotlinMultiplatformSlug(tokens)

private fun isKotlinMultiplatformSlug(tokens: Set<String>): Boolean =
  "kotlin" in tokens && ("multiplatform" in tokens || ("multi" in tokens && "platform" in tokens))
