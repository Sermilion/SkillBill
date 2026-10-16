package skillbill.ports.monitor

import skillbill.ports.monitor.model.MonitorCallerRepository
import skillbill.ports.monitor.model.MonitorPullRequestListing
import skillbill.ports.monitor.model.MonitorPullRequestLookup
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import java.nio.file.Path

/**
 * Read-only observation of pull requests for the standalone monitor. Every lookup names its repository and
 * number explicitly; no lookup substitutes the caller's branch or pull request for a requested target.
 */
interface MonitorPullRequestSource {
  fun callerRepository(callerRoot: Path): MonitorCallerRepository

  fun pullRequest(
    repository: MonitorRepositoryIdentity,
    number: Int,
  ): MonitorPullRequestLookup

  /** Lists every pull request of [repository] in any state across all result pages, or fails as a whole. */
  fun pullRequests(repository: MonitorRepositoryIdentity): MonitorPullRequestListing

  /** Lists every pull request of [repository] whose head is [headRepository]:[branch], across all result pages. */
  fun pullRequestsForBranch(
    repository: MonitorRepositoryIdentity,
    headRepository: MonitorRepositoryIdentity,
    branch: String,
  ): MonitorPullRequestListing
}
