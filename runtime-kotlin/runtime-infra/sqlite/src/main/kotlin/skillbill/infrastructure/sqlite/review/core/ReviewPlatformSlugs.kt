package skillbill.infrastructure.sqlite.review.core

import skillbill.review.attribution.normalizePlatformSlug
import skillbill.review.attribution.normalizeTelemetrySlug

internal fun platformSlugFromRoutedSkill(
  routedSkill: String?,
  routedSkillPlatformSlugs: Map<String, String> = emptyMap(),
): String {
  val normalized = routedSkill?.let(::normalizeTelemetrySlug).orEmpty()
  if (normalized == "unknown") {
    return "unknown"
  }
  return normalizedRoutedSkillPlatformSlugs(routedSkillPlatformSlugs)
    .firstNotNullOfOrNull { (skillName, platformSlug) ->
      platformSlug.takeIf { normalized == skillName }
    }
    ?: "unknown"
}

internal fun reviewPlatformSlug(
  detectedStack: String?,
  routedSkill: String?,
  routedSkillPlatformSlugs: Map<String, String> = emptyMap(),
): String {
  val normalizedDetectedStack = normalizePlatformSlug(detectedStack, routedSkillPlatformSlugs.values)
  val routedPlatformSlug = platformSlugFromRoutedSkill(routedSkill, routedSkillPlatformSlugs)
  return when {
    routedPlatformSlug != "unknown" && detectedStack.isDescriptiveStackLabel(normalizedDetectedStack) ->
      routedPlatformSlug
    normalizedDetectedStack != "unknown" -> normalizedDetectedStack
    else -> routedPlatformSlug
  }
}

private fun normalizedRoutedSkillPlatformSlugs(
  routedSkillPlatformSlugs: Map<String, String>,
): List<Pair<String, String>> =
  routedSkillPlatformSlugs
    .mapNotNull { (skillName, platformSlug) ->
      val normalizedSkillName = normalizeTelemetrySlug(skillName)
      val normalizedPlatformSlug = normalizePlatformSlug(platformSlug, routedSkillPlatformSlugs.values)
      if (normalizedSkillName == "unknown" || normalizedPlatformSlug == "unknown") {
        null
      } else {
        normalizedSkillName to normalizedPlatformSlug
      }
    }
    .sortedWith(compareByDescending<Pair<String, String>> { it.first.length }.thenBy { it.first })

private fun String?.isDescriptiveStackLabel(normalizedStack: String): Boolean {
  if (normalizedStack == "unknown") return false
  val normalizedRaw = this?.let(::normalizeTelemetrySlug).orEmpty()
  return normalizedRaw != normalizedStack
}
