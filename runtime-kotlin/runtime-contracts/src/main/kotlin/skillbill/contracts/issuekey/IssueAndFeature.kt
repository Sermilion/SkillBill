package skillbill.contracts.issuekey

import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.contracts.projectstore.ProjectStoreLayout

const val TRACKER_STYLE_ISSUE_KEY_PATTERN: String = "[A-Z0-9]+-\\d+(?:\\.\\d+)?"

private val ISSUE_AND_FEATURE_DIRECTORY: Regex = Regex("^(?i)($TRACKER_STYLE_ISSUE_KEY_PATTERN)-(.+)$")
private val TRACKER_STYLE_ISSUE_KEY_PREFIX: Regex = Regex("^(?i)$TRACKER_STYLE_ISSUE_KEY_PATTERN")
private val ABSOLUTE_STORE_SPECS_PATH: Regex = Regex("^/.*/projects/[^/]+/${ProjectStoreLayout.SPECS_PREFIX}")
const val GOAL_INTAKE_URL_MARKER: String = "://"
private const val GOAL_INTAKE_SPEC_FILE_SUFFIX: String = ".md"

fun issueAndFeature(directoryName: String): Pair<String, String> {
  val match = ISSUE_AND_FEATURE_DIRECTORY.matchEntire(directoryName)
  if (match != null) {
    return match.groupValues[1].uppercase() to match.groupValues[2]
  }
  val parts = directoryName.split("-", limit = 2)
  return parts.first() to parts.getOrElse(1) { "decomposition" }
}

fun goalIntakeSpecBundleDirectory(token: String): String? {
  val bundlePath =
    when {
      token.contains(LegacyProjectStatePaths.FEATURE_SPECS_PREFIX) ->
        token.substringAfter(LegacyProjectStatePaths.FEATURE_SPECS_PREFIX)
      token.startsWith(ProjectStoreLayout.SPECS_PREFIX) -> token.removePrefix(ProjectStoreLayout.SPECS_PREFIX)
      else -> ABSOLUTE_STORE_SPECS_PATH.find(token)?.let { token.substring(it.range.last + 1) } ?: return null
    }
  return bundlePath.substringBefore('/').takeIf(String::isNotBlank)
}

fun looksLikeGoalIntakeToken(token: String): Boolean =
  TRACKER_STYLE_ISSUE_KEY_PREFIX.containsMatchIn(token) ||
    token.contains(GOAL_INTAKE_URL_MARKER) ||
    goalIntakeSpecBundleDirectory(token) != null ||
    token.endsWith(GOAL_INTAKE_SPEC_FILE_SUFFIX)
