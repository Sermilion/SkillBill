package skillbill.contracts.issuekey

const val TRACKER_STYLE_ISSUE_KEY_PATTERN: String = "[A-Z0-9]+-\\d+(?:\\.\\d+)?"

private val ISSUE_AND_FEATURE_DIRECTORY: Regex = Regex("^(?i)($TRACKER_STYLE_ISSUE_KEY_PATTERN)-(.+)$")
private val TRACKER_STYLE_ISSUE_KEY_PREFIX: Regex = Regex("^(?i)$TRACKER_STYLE_ISSUE_KEY_PATTERN")
const val GOAL_INTAKE_URL_MARKER: String = "://"
const val GOAL_INTAKE_FEATURE_SPECS_MARKER: String = ".feature-specs/"
private const val GOAL_INTAKE_SPEC_FILE_SUFFIX: String = ".md"

fun issueAndFeature(directoryName: String): Pair<String, String> {
  val match = ISSUE_AND_FEATURE_DIRECTORY.matchEntire(directoryName)
  if (match != null) {
    return match.groupValues[1].uppercase() to match.groupValues[2]
  }
  val parts = directoryName.split("-", limit = 2)
  return parts.first() to parts.getOrElse(1) { "decomposition" }
}

fun looksLikeGoalIntakeToken(token: String): Boolean =
  TRACKER_STYLE_ISSUE_KEY_PREFIX.containsMatchIn(token) ||
    token.contains(GOAL_INTAKE_URL_MARKER) ||
    token.contains(GOAL_INTAKE_FEATURE_SPECS_MARKER) ||
    token.endsWith(GOAL_INTAKE_SPEC_FILE_SUFFIX)
