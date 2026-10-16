package skillbill.engine.featuretask.phaserun.monitor

import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorResolvedTarget

enum class MonitorTargetRefusal {
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

sealed interface MonitorTargetResolution {
  data class Open(val target: MonitorResolvedTarget) : MonitorTargetResolution

  data class Merged(val pullRequest: MonitorPullRequestIdentity) : MonitorTargetResolution

  data class ClosedUnmerged(val pullRequest: MonitorPullRequestIdentity) : MonitorTargetResolution

  data object NoOpenPullRequest : MonitorTargetResolution

  data class Refused(
    val reason: MonitorTargetRefusal,
    val detail: String,
    val candidateUrls: List<String> = emptyList(),
  ) : MonitorTargetResolution

  data class Failed(val reason: String) : MonitorTargetResolution
}
