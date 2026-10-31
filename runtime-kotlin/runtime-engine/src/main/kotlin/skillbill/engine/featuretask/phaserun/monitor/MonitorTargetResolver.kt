package skillbill.engine.featuretask.phaserun.monitor

import me.tatarka.inject.annotations.Inject
import skillbill.ports.monitor.MonitorPullRequestSource
import skillbill.ports.monitor.model.MonitorCallerRepository
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorPullRequestListing
import skillbill.ports.monitor.model.MonitorPullRequestLookup
import skillbill.ports.monitor.model.MonitorPullRequestState
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import java.nio.file.Path

@Inject
public class MonitorTargetResolver(
  private val source: MonitorPullRequestSource,
) {
  internal fun resolve(
    callerRoot: Path,
    selector: MonitorTargetSelector,
  ): MonitorTargetResolution =
    when (val caller = source.callerRepository(callerRoot)) {
      is MonitorCallerRepository.Unavailable ->
        MonitorTargetResolution.Refused(MonitorTargetRefusal.CALLER_REPOSITORY_UNAVAILABLE, caller.reason)
      is MonitorCallerRepository.Resolved ->
        when (selector) {
          is MonitorTargetSelector.Url -> resolveUrl(caller.repository, selector.url)
          is MonitorTargetSelector.IssueKey -> resolveIssueKey(caller.repository, selector.key)
          is MonitorTargetSelector.CurrentBranch -> resolveBranch(caller.repository, selector.branch)
        }
    }

  private fun resolveUrl(
    caller: MonitorRepositoryIdentity,
    url: String,
  ): MonitorTargetResolution =
    when (val parsed = parseMonitorPullRequestUrl(url)) {
      is MonitorUrlParse.Rejected ->
        MonitorTargetResolution.Refused(MonitorTargetRefusal.UNSUPPORTED_URL, parsed.detail)
      is MonitorUrlParse.Parsed ->
        if (parsed.identity.repository == caller) {
          resolveIdentity(parsed.identity)
        } else {
          MonitorTargetResolution.Refused(
            MonitorTargetRefusal.REPOSITORY_MISMATCH,
            "Pull request ${parsed.identity.url} belongs to ${parsed.identity.repository.host}/" +
              "${parsed.identity.repository.slug}, not the caller repository ${caller.host}/${caller.slug}.",
          )
        }
    }

  private fun resolveIssueKey(
    caller: MonitorRepositoryIdentity,
    issueKey: String,
  ): MonitorTargetResolution =
    when (val listing = source.pullRequests(caller)) {
      is MonitorPullRequestListing.Failed -> MonitorTargetResolution.Failed(listing.reason)
      is MonitorPullRequestListing.Complete ->
        when (val selection = selectIssueCandidate(issueKey, listing.pullRequests)) {
          is MonitorCandidateSelection.Unique -> resolveIdentity(selection.summary.identity)
          is MonitorCandidateSelection.Ambiguous ->
            MonitorTargetResolution.Refused(
              MonitorTargetRefusal.AMBIGUOUS_TARGET,
              "Issue $issueKey matches more than one pull request.",
              selection.candidateUrls,
            )
          MonitorCandidateSelection.Absent ->
            MonitorTargetResolution.Refused(
              MonitorTargetRefusal.TARGET_NOT_FOUND,
              "No pull request of ${caller.slug} names issue $issueKey in its title, body, or head branch.",
            )
        }
    }

  private fun resolveBranch(
    caller: MonitorRepositoryIdentity,
    branch: String,
  ): MonitorTargetResolution =
    when (val listing = source.pullRequestsForBranch(caller, caller, branch)) {
      is MonitorPullRequestListing.Failed -> MonitorTargetResolution.Failed(listing.reason)
      is MonitorPullRequestListing.Complete ->
        when (val selection = selectBranchCandidate(listing.pullRequests, caller, branch)) {
          is MonitorCandidateSelection.Unique -> resolveIdentity(selection.summary.identity)
          is MonitorCandidateSelection.Ambiguous ->
            MonitorTargetResolution.Refused(
              MonitorTargetRefusal.AMBIGUOUS_TARGET,
              "Branch $branch has more than one open pull request.",
              selection.candidateUrls,
            )
          MonitorCandidateSelection.Absent -> MonitorTargetResolution.NoOpenPullRequest
        }
    }

  private fun resolveIdentity(identity: MonitorPullRequestIdentity): MonitorTargetResolution =
    when (val lookup = source.pullRequest(identity.repository, identity.number)) {
      MonitorPullRequestLookup.Missing ->
        MonitorTargetResolution.Refused(
          MonitorTargetRefusal.TARGET_NOT_FOUND,
          "Pull request ${identity.url} does not exist.",
        )
      is MonitorPullRequestLookup.Failed -> MonitorTargetResolution.Failed(lookup.reason)
      is MonitorPullRequestLookup.Found ->
        when {
          lookup.pullRequest.identity != identity ->
            MonitorTargetResolution.Refused(
              MonitorTargetRefusal.INCOMPLETE_TARGET,
              "The provider answered for ${lookup.pullRequest.identity.url} instead of ${identity.url}.",
            )
          lookup.pullRequest.state == MonitorPullRequestState.MERGED -> MonitorTargetResolution.Merged(identity)
          lookup.pullRequest.state == MonitorPullRequestState.CLOSED ->
            MonitorTargetResolution.ClosedUnmerged(identity)
          else -> validatedOpenTarget(lookup.pullRequest)
        }
    }
}
