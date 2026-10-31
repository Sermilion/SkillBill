package skillbill.engine.featuretask.phaserun.monitor

import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorPullRequestSummary
import skillbill.ports.monitor.model.MonitorResolvedTarget

public enum class MonitorTargetRefusal {
  MALFORMED_INTAKE,
  AMBIGUOUS_INTAKE,
  NO_CURRENT_BRANCH,
  UNSUPPORTED_URL,
  REPOSITORY_MISMATCH,
  CALLER_REPOSITORY_UNAVAILABLE,
  TARGET_NOT_FOUND,
  AMBIGUOUS_TARGET,
  INCOMPLETE_TARGET,
  NOT_WRITABLE,
}

public sealed interface MonitorTargetResolution {
  public data class Open(val target: MonitorResolvedTarget) : MonitorTargetResolution

  public data class Merged(val pullRequest: MonitorPullRequestIdentity) : MonitorTargetResolution

  public data class ClosedUnmerged(val pullRequest: MonitorPullRequestIdentity) : MonitorTargetResolution

  public data object NoOpenPullRequest : MonitorTargetResolution

  public data class Refused(
    public val reason: MonitorTargetRefusal,
    public val detail: String,
    public val candidateUrls: List<String> = emptyList(),
  ) : MonitorTargetResolution

  public data class Failed(val reason: String) : MonitorTargetResolution
}

public sealed interface MonitorCandidateSelection {
  public data class Unique(val summary: MonitorPullRequestSummary) : MonitorCandidateSelection

  public data class Ambiguous(val candidateUrls: List<String>) : MonitorCandidateSelection

  public data object Absent : MonitorCandidateSelection
}
