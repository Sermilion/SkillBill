package skillbill.engine.featuretask.phaserun.monitor

import skillbill.ports.monitor.model.MonitorBranchTip
import skillbill.ports.monitor.model.MonitorPullRequest
import skillbill.ports.monitor.model.MonitorPullRequestHead
import skillbill.ports.monitor.model.MonitorPullRequestState
import skillbill.ports.monitor.model.MonitorPullRequestSummary
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import skillbill.ports.monitor.model.MonitorResolvedTarget
import skillbill.ports.monitor.model.MonitorWritability
import java.net.URI
import java.net.URISyntaxException

internal sealed interface MonitorCandidateSelection {
  data class Unique(val summary: MonitorPullRequestSummary) : MonitorCandidateSelection

  data class Ambiguous(val candidateUrls: List<String>) : MonitorCandidateSelection

  data object Absent : MonitorCandidateSelection
}

private val SAFE_REF_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]*$")
private val OBJECT_ID = Regex("^[0-9a-f]{40}([0-9a-f]{24})?$")
private val REF_NAME_FORBIDDEN_FRAGMENTS = listOf("..", "//", "/.", "@{")
private val REF_NAME_FORBIDDEN_SUFFIXES = listOf("/", ".", ".lock")

internal fun summaryMentionsIssueKey(
  summary: MonitorPullRequestSummary,
  issueKey: String,
): Boolean {
  val token = Regex("(?i)(?<![A-Za-z0-9])${Regex.escape(issueKey)}(?![A-Za-z0-9]|\\.[0-9])")
  return listOf(summary.title, summary.body, summary.headRef).any { text -> token.containsMatchIn(text) }
}

internal fun selectIssueCandidate(
  issueKey: String,
  summaries: List<MonitorPullRequestSummary>,
): MonitorCandidateSelection {
  val matches = summaries.filter { summaryMentionsIssueKey(it, issueKey) }
  val open = matches.filter { it.state == MonitorPullRequestState.OPEN }
  return uniqueCandidate(open.ifEmpty { matches })
}

internal fun selectBranchCandidate(
  summaries: List<MonitorPullRequestSummary>,
  headRepository: MonitorRepositoryIdentity,
  branch: String,
): MonitorCandidateSelection {
  return uniqueCandidate(
    summaries.filter {
      it.state == MonitorPullRequestState.OPEN && it.headRepository == headRepository && it.headRef == branch
    },
  )
}

private fun uniqueCandidate(pool: List<MonitorPullRequestSummary>): MonitorCandidateSelection =
  when (pool.size) {
    0 -> MonitorCandidateSelection.Absent
    1 -> MonitorCandidateSelection.Unique(pool.single())
    else -> MonitorCandidateSelection.Ambiguous(pool.map { it.identity.url }.sorted())
  }

internal fun validatedOpenTarget(pullRequest: MonitorPullRequest): MonitorTargetResolution {
  val head = pullRequest.head
  val base = pullRequest.base
  val defect =
    when {
      head == null -> "the head repository or branch is missing or deleted"
      base == null -> "the base branch is missing"
      base.repository != pullRequest.identity.repository -> "the base repository differs from the target repository"
      head.tip.repository.host != base.repository.host -> "the head repository is on a different host"
      else -> tipDefect("head", head.tip) ?: tipDefect("base", base) ?: endpointDefect(head)
    }
  if (defect != null || head == null || base == null) {
    return MonitorTargetResolution.Refused(
      MonitorTargetRefusal.INCOMPLETE_TARGET,
      "Pull request ${pullRequest.identity.url} is not monitorable: ${defect ?: "target data is incomplete"}.",
    )
  }
  val label = "Pull request ${pullRequest.identity.url}"
  return when (val evidence = writabilityOf(head, base.repository)) {
    is WritabilityEvidence.Known ->
      MonitorTargetResolution.Open(
        MonitorResolvedTarget(
          pullRequest.identity,
          head,
          base,
          "https://${base.repository.host}/${base.repository.slug}.git",
          evidence.value,
        ),
      )
    is WritabilityEvidence.Missing ->
      MonitorTargetResolution.Refused(
        MonitorTargetRefusal.INCOMPLETE_TARGET,
        "$label reports no ${evidence.permission} permission data.",
      )
    WritabilityEvidence.Denied ->
      MonitorTargetResolution.Refused(
        MonitorTargetRefusal.NOT_WRITABLE,
        "$label head ${head.tip.repository.slug}:${head.tip.ref} is not writable.",
      )
  }
}

internal fun isSafeRefName(name: String): Boolean =
  SAFE_REF_NAME.matches(name) &&
    REF_NAME_FORBIDDEN_FRAGMENTS.none { it in name } &&
    REF_NAME_FORBIDDEN_SUFFIXES.none { name.endsWith(it) }

private fun tipDefect(
  label: String,
  tip: MonitorBranchTip,
): String? =
  when {
    !isSafeRefName(tip.ref) -> "the $label ref '${tip.ref}' is not a safe branch name"
    !OBJECT_ID.matches(tip.sha) -> "the $label SHA is not a full object id"
    else -> null
  }

private fun endpointDefect(head: MonitorPullRequestHead): String? =
  when {
    !endpointMatches(head.fetchUrl, head.tip.repository) -> "the head fetch endpoint is not the head repository"
    !endpointMatches(head.pushUrl, head.tip.repository) -> "the head push endpoint is not the head repository"
    else -> null
  }

internal fun endpointMatches(
  url: String,
  repository: MonitorRepositoryIdentity,
): Boolean {
  val uri =
    try {
      URI(url)
    } catch (_: URISyntaxException) {
      return false
    }
  val path = uri.rawPath.orEmpty().trim('/').removeSuffix(".git")
  return uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 &&
    uri.rawQuery == null && uri.rawFragment == null &&
    uri.host.equals(repository.host, ignoreCase = true) && path.equals(repository.slug, ignoreCase = true)
}

private sealed interface WritabilityEvidence {
  data class Known(val value: MonitorWritability) : WritabilityEvidence

  data class Missing(val permission: String) : WritabilityEvidence

  data object Denied : WritabilityEvidence
}

private fun writabilityOf(
  head: MonitorPullRequestHead,
  baseRepository: MonitorRepositoryIdentity,
): WritabilityEvidence {
  val evidence = head.writeEvidence
  val fork = head.tip.repository != baseRepository
  return when {
    evidence.repositoryPush == true -> WritabilityEvidence.Known(MonitorWritability.REPOSITORY_PUSH)
    fork && evidence.maintainerCanModify == true ->
      WritabilityEvidence.Known(MonitorWritability.MAINTAINER_CAN_MODIFY)
    evidence.repositoryPush == null -> WritabilityEvidence.Missing("push")
    fork && evidence.maintainerCanModify == null -> WritabilityEvidence.Missing("maintainer")
    else -> WritabilityEvidence.Denied
  }
}
