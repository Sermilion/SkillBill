package skillbill.engine.goalrunner.intake

import skillbill.contracts.issuekey.TRACKER_STYLE_ISSUE_KEY_PATTERN
import skillbill.contracts.issuekey.issueAndFeature
import skillbill.text.sha256HexUtf8

internal data class GoalIntake(val issueKey: String, val requirements: String) {
  companion object {
    fun parse(text: String): GoalIntake {
      val intake = text.trim()
      require(intake.isNotBlank()) { "Goal intake is required." }
      val tokens = intake.split(Regex("\\s+"))
      val first = tokens.first()
      val key =
        when {
          ISSUE_KEY.matches(first) -> first.uppercase()
          first.contains("://") ->
            first.substringAfter("://").substringBefore('?').substringBefore('#')
              .split('/').drop(1).firstOrNull(ISSUE_KEY::matches)?.uppercase()
          first.contains(".feature-specs/") ->
            first.substringAfter(".feature-specs/").substringBefore('/')
              .let { issueAndFeature(it).first }.takeIf(ISSUE_KEY::matches)
          !first.contains('/') -> issueAndFeature(first).first.takeIf(ISSUE_KEY::matches)
          else -> null
        } ?: "LOCAL-${sha256HexUtf8(intake).take(LOCAL_HASH_LENGTH).toLong(HEX_RADIX)}"
      return GoalIntake(key, intake)
    }

    private const val LOCAL_HASH_LENGTH = 12
    private const val HEX_RADIX = 16
    private val ISSUE_KEY = Regex("(?i)$TRACKER_STYLE_ISSUE_KEY_PATTERN")
  }
}
