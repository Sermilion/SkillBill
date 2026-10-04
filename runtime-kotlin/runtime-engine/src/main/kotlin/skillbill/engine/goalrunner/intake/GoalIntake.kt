package skillbill.engine.goalrunner.intake

import skillbill.contracts.issuekey.TRACKER_STYLE_ISSUE_KEY_PATTERN
import skillbill.contracts.issuekey.issueAndFeature
import skillbill.error.core.InvalidFeatureSpecPreparationRequestError

internal data class GoalIntake(
  val issueKey: String,
  val requirements: String,
  val featureName: String?,
  val hasRequirements: Boolean,
) {
  companion object {
    fun parse(text: String): GoalIntake =
      parseOrNull(text)
        ?: invalid(
          "issue_key",
          "supply a tracker issue key or link. A local workflow identity is not assigned.",
        )

    fun parseOrNull(text: String): GoalIntake? {
      val intake = text.trim()
      if (intake.isBlank()) {
        return null
      }
      val first = intake.split(Regex("\\s+")).first()
      val (key, fromReference) = reference(first) ?: return null
      val contentLine = firstContentLine(intake, first)
      return GoalIntake(key, intake, fromReference ?: slug(contentLine), contentLine != null)
    }

    private fun reference(first: String): Pair<String, String?>? =
      when {
        ISSUE_KEY.matches(first) -> first.uppercase() to null
        first.contains("://") -> {
          val segments =
            first.substringAfter("://").substringBefore('?').substringBefore('#').split('/')
          val index = segments.indexOfFirst(ISSUE_KEY::matches)
          if (index < 0) {
            null
          } else {
            segments[index].uppercase() to segments.getOrNull(index + 1)?.let(::slug)
          }
        }
        first.contains(".feature-specs/") -> {
          val directory = first.substringAfter(".feature-specs/").substringBefore('/')
          namedDirectory(directory)
        }
        !first.contains('/') -> namedDirectory(first)
        else -> null
      }

    private fun namedDirectory(directory: String): Pair<String, String?>? {
      val (key, feature) = issueAndFeature(directory)
      if (!ISSUE_KEY.matches(key)) {
        return null
      }
      val fromDirectory = slug(feature).takeUnless { ISSUE_KEY.matches(directory) }
      return key.uppercase() to fromDirectory
    }

    private fun firstContentLine(
      intake: String,
      first: String,
    ): String? {
      val rest = intake.substringAfter(first, missingDelimiterValue = "").trim()
      return rest.lineSequence()
        .map { it.trim().trimStart('#').trim() }
        .firstOrNull { it.isNotEmpty() }
    }

    private fun slug(raw: String?): String? =
      raw
        ?.trim()
        ?.lowercase()
        ?.replace(Regex("[^a-z0-9]+"), "-")
        ?.trim('-')
        ?.takeIf { it.isNotBlank() }

    private fun invalid(
      field: String,
      reason: String,
    ): Nothing = throw InvalidFeatureSpecPreparationRequestError(fieldPath = field, reason = reason)

    private val ISSUE_KEY = Regex("(?i)$TRACKER_STYLE_ISSUE_KEY_PATTERN")
  }
}
