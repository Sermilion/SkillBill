package skillbill.engine.featuretask.phaserun

import skillbill.contracts.issuekey.TRACKER_STYLE_ISSUE_KEY_PATTERN
import skillbill.contracts.issuekey.goalIntakeSpecBundleDirectory
import skillbill.contracts.issuekey.issueAndFeature
import java.nio.file.Path

object IntakeIssueKeys {
  private val WHITESPACE = Regex("\\s+")
  private val ISSUE_KEY = Regex("(?i)$TRACKER_STYLE_ISSUE_KEY_PATTERN")
  private const val SPEC_EXTENSION = ".md"
  private const val URL_SCHEME_SEPARATOR = "://"

  fun tokens(intake: String?): List<String> = intake?.trim().orEmpty().split(WHITESPACE).filter(String::isNotBlank)

  fun specPathToken(tokens: List<String>): String? = tokens.firstOrNull { it.endsWith(SPEC_EXTENSION) }

  fun ofIntake(intake: String?): String? {
    val tokens = tokens(intake)
    return tokens.firstOrNull(ISSUE_KEY::matches)?.uppercase()
      ?: tokens.firstNotNullOfOrNull(::ofUrl)
      ?: specPathToken(tokens)?.let { ofSpecPath(Path.of(it)) }
  }

  fun ofSpecPath(specPath: Path): String? =
    goalIntakeSpecBundleDirectory(specPath.normalize().toString())
      ?.let { directory -> issueAndFeature(directory).first.takeIf(ISSUE_KEY::matches) }

  fun ofBranch(branch: String): String? {
    val leaf = branch.substringAfterLast('/')
    return ISSUE_KEY.matchEntire(leaf)?.value?.uppercase()
      ?: issueAndFeature(leaf).first.takeIf(ISSUE_KEY::matches)
  }

  private fun ofUrl(token: String): String? =
    token.takeIf { it.contains(URL_SCHEME_SEPARATOR) }
      ?.substringAfter(URL_SCHEME_SEPARATOR)
      ?.substringBefore('?')
      ?.substringBefore('#')
      ?.split('/')
      ?.drop(1)
      ?.firstOrNull(ISSUE_KEY::matches)
      ?.uppercase()
}
