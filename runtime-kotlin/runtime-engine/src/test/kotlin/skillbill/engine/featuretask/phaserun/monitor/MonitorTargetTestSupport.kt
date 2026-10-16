package skillbill.engine.featuretask.phaserun.monitor

import skillbill.ports.monitor.MonitorPullRequestSource
import skillbill.ports.monitor.model.MonitorBranchTip
import skillbill.ports.monitor.model.MonitorCallerRepository
import skillbill.ports.monitor.model.MonitorPullRequest
import skillbill.ports.monitor.model.MonitorPullRequestHead
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorPullRequestListing
import skillbill.ports.monitor.model.MonitorPullRequestLookup
import skillbill.ports.monitor.model.MonitorPullRequestState
import skillbill.ports.monitor.model.MonitorPullRequestSummary
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import skillbill.ports.monitor.model.MonitorWriteEvidence
import java.nio.file.Path

internal val ACME: MonitorRepositoryIdentity = MonitorRepositoryIdentity.canonical("github.com", "acme", "repo")
internal val FORK: MonitorRepositoryIdentity = MonitorRepositoryIdentity.canonical("github.com", "fork", "repo")
internal const val SHA = "0123456789abcdef0123456789abcdef01234567"

internal fun identity(number: Int) = MonitorPullRequestIdentity(ACME, number)

internal fun summary(
  number: Int,
  title: String = "",
  body: String = "",
  headRef: String = "feat/x",
  headRepository: MonitorRepositoryIdentity? = ACME,
) = MonitorPullRequestSummary(identity(number), MonitorPullRequestState.OPEN, title, body, headRef, headRepository)

internal fun head(
  repository: MonitorRepositoryIdentity = ACME,
  ref: String = "feat/x",
  sha: String = SHA,
  writeEvidence: MonitorWriteEvidence = MonitorWriteEvidence(true, false),
  url: String = "https://github.com/${repository.slug}.git",
) = MonitorPullRequestHead(
  MonitorBranchTip(repository, ref, sha),
  url,
  url,
  writeEvidence,
)

internal fun pullRequest(
  number: Int,
  state: MonitorPullRequestState = MonitorPullRequestState.OPEN,
  head: MonitorPullRequestHead? = head(),
  base: MonitorBranchTip? = MonitorBranchTip(ACME, "main", SHA),
) = MonitorPullRequest(identity(number), state, head, base)

internal class FakeMonitorPullRequestSource(
  var caller: MonitorCallerRepository = MonitorCallerRepository.Resolved(ACME),
  var lookup: (Int) -> MonitorPullRequestLookup = { MonitorPullRequestLookup.Missing },
  var listing: MonitorPullRequestListing = MonitorPullRequestListing.Complete(emptyList()),
  var branchListing: MonitorPullRequestListing = MonitorPullRequestListing.Complete(emptyList()),
) : MonitorPullRequestSource {
  val lookups = mutableListOf<Pair<MonitorRepositoryIdentity, Int>>()
  val branchQueries = mutableListOf<Triple<MonitorRepositoryIdentity, MonitorRepositoryIdentity, String>>()

  override fun callerRepository(callerRoot: Path) = caller

  override fun pullRequest(
    repository: MonitorRepositoryIdentity,
    number: Int,
  ): MonitorPullRequestLookup {
    lookups += repository to number
    return lookup(number)
  }

  override fun pullRequests(repository: MonitorRepositoryIdentity) = listing

  override fun pullRequestsForBranch(
    repository: MonitorRepositoryIdentity,
    headRepository: MonitorRepositoryIdentity,
    branch: String,
  ): MonitorPullRequestListing {
    branchQueries += Triple(repository, headRepository, branch)
    return branchListing
  }
}
