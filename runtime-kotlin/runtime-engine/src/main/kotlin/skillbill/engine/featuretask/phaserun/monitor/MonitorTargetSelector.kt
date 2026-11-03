package skillbill.engine.featuretask.phaserun.monitor

import skillbill.contracts.issuekey.TRACKER_STYLE_ISSUE_KEY_PATTERN
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import java.net.URI
import java.net.URISyntaxException

public sealed interface MonitorTargetSelector {
  public data class Url(val url: String) : MonitorTargetSelector

  public data class IssueKey(val key: String) : MonitorTargetSelector

  public data class CurrentBranch(val branch: String) : MonitorTargetSelector

  public data class Branch(val name: String) : MonitorTargetSelector
}

public sealed interface MonitorIntakeParse {
  public data class Selected(val selector: MonitorTargetSelector) : MonitorIntakeParse

  public data class Refused(
    public val reason: MonitorTargetRefusal,
    public val detail: String,
  ) : MonitorIntakeParse
}

private val ISSUE_KEY_TOKEN = Regex("(?i)$TRACKER_STYLE_ISSUE_KEY_PATTERN")
public val WHITESPACE: Regex = Regex("\\s+")
internal const val MONITOR_BRANCH_PREFIX: String = "branch:"
private const val URL_SCHEME_SEPARATOR = "://"
private const val PULL_SEGMENT = "pull"
private const val PULL_URL_SEGMENTS = 4

public fun parseMonitorIntake(
  intake: String?,
  currentBranch: String?,
): MonitorIntakeParse {
  val tokens = intake?.trim().orEmpty().split(WHITESPACE).filter(String::isNotBlank)
  val branchName =
    tokens.singleOrNull()?.takeIf { it.startsWith(MONITOR_BRANCH_PREFIX) }?.removePrefix(MONITOR_BRANCH_PREFIX)
  return when {
    tokens.isEmpty() ->
      currentBranch?.trim()?.takeIf(String::isNotEmpty)
        ?.let { MonitorIntakeParse.Selected(MonitorTargetSelector.CurrentBranch(it)) }
        ?: MonitorIntakeParse.Refused(
          MonitorTargetRefusal.NO_CURRENT_BRANCH,
          "No intake was given and the caller checkout is not on a named branch.",
        )
    tokens.size > 1 ->
      MonitorIntakeParse.Refused(
        MonitorTargetRefusal.AMBIGUOUS_INTAKE,
        "Monitor intake names more than one target: ${tokens.joinToString(" ")}.",
      )
    branchName != null && isSafeRefName(branchName) ->
      MonitorIntakeParse.Selected(MonitorTargetSelector.Branch(branchName))
    branchName != null -> malformedIntake(tokens.single())
    URL_SCHEME_SEPARATOR in tokens.single() ->
      MonitorIntakeParse.Selected(MonitorTargetSelector.Url(tokens.single()))
    ISSUE_KEY_TOKEN.matches(tokens.single()) ->
      MonitorIntakeParse.Selected(MonitorTargetSelector.IssueKey(tokens.single().uppercase()))
    else -> malformedIntake(tokens.single())
  }
}

private fun malformedIntake(token: String): MonitorIntakeParse.Refused =
  MonitorIntakeParse.Refused(
    MonitorTargetRefusal.MALFORMED_INTAKE,
    "Monitor intake '$token' is not a pull request URL, an issue key, or branch:<name> with a safe branch name.",
  )

public sealed interface MonitorUrlParse {
  public data class Parsed(val identity: MonitorPullRequestIdentity) : MonitorUrlParse

  public data class Rejected(val detail: String) : MonitorUrlParse
}

public fun parseMonitorPullRequestUrl(raw: String): MonitorUrlParse {
  val uri =
    try {
      URI(raw)
    } catch (_: URISyntaxException) {
      return MonitorUrlParse.Rejected("'$raw' is not a well-formed URL.")
    }
  val host = uri.host
  val segments = uri.rawPath.orEmpty().trim('/').split('/')
  val number =
    segments.getOrNull(PULL_URL_SEGMENTS - 1)?.takeIf { it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it > 0 }
  val transportClean = uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 && !host.isNullOrBlank()
  val shapeClean =
    uri.rawQuery == null && uri.rawFragment == null && segments.size == PULL_URL_SEGMENTS &&
      segments[2] == PULL_SEGMENT && segments.take(2).none(String::isBlank)
  return if (transportClean && shapeClean && number != null) {
    MonitorUrlParse.Parsed(
      MonitorPullRequestIdentity(MonitorRepositoryIdentity.canonical(host.orEmpty(), segments[0], segments[1]), number),
    )
  } else {
    MonitorUrlParse.Rejected(
      "'$raw' is not an https pull request URL of the form https://<host>/<owner>/<repo>/pull/<number>.",
    )
  }
}
