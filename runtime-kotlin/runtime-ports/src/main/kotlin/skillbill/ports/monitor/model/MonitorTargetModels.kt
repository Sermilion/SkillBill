package skillbill.ports.monitor.model

import java.util.Locale

data class MonitorRepositoryIdentity(
  val host: String,
  val owner: String,
  val name: String,
) {
  init {
    require(host.isNotBlank() && owner.isNotBlank() && name.isNotBlank()) {
      "A repository identity needs a host, owner, and name."
    }
  }

  val slug: String get() = "$owner/$name"

  companion object {
    fun canonical(
      host: String,
      owner: String,
      name: String,
    ): MonitorRepositoryIdentity =
      MonitorRepositoryIdentity(
        host.trim().lowercase(Locale.ROOT),
        owner.trim().lowercase(Locale.ROOT),
        name.trim().lowercase(Locale.ROOT),
      )
  }
}

data class MonitorPullRequestIdentity(
  val repository: MonitorRepositoryIdentity,
  val number: Int,
) {
  init {
    require(number > 0) { "A pull request number must be positive." }
  }

  val targetKey: String get() = "${repository.host}/${repository.slug}#$number"

  val url: String get() = "https://${repository.host}/${repository.slug}/pull/$number"
}

enum class MonitorPullRequestState { OPEN, MERGED, CLOSED }

data class MonitorBranchTip(
  val repository: MonitorRepositoryIdentity,
  val ref: String,
  val sha: String,
)

data class MonitorWriteEvidence(
  val repositoryPush: Boolean?,
  val maintainerCanModify: Boolean?,
)

data class MonitorPullRequestHead(
  val tip: MonitorBranchTip,
  val fetchUrl: String,
  val pushUrl: String,
  val writeEvidence: MonitorWriteEvidence,
)

data class MonitorPullRequest(
  val identity: MonitorPullRequestIdentity,
  val state: MonitorPullRequestState,
  val head: MonitorPullRequestHead?,
  val base: MonitorBranchTip?,
  val conflicting: Boolean = false,
)

data class MonitorPullRequestSummary(
  val identity: MonitorPullRequestIdentity,
  val state: MonitorPullRequestState,
  val title: String,
  val body: String,
  val headRef: String,
  val headRepository: MonitorRepositoryIdentity?,
)

enum class MonitorWritability { REPOSITORY_PUSH, MAINTAINER_CAN_MODIFY }

data class MonitorResolvedTarget(
  val pullRequest: MonitorPullRequestIdentity,
  val head: MonitorPullRequestHead,
  val base: MonitorBranchTip,
  val baseFetchUrl: String,
  val writability: MonitorWritability,
) {
  val state: MonitorPullRequestState get() = MonitorPullRequestState.OPEN
}

sealed interface MonitorCallerRepository {
  data class Resolved(val repository: MonitorRepositoryIdentity) : MonitorCallerRepository

  data class Unavailable(val reason: String) : MonitorCallerRepository
}

sealed interface MonitorPullRequestLookup {
  data class Found(val pullRequest: MonitorPullRequest) : MonitorPullRequestLookup

  data object Missing : MonitorPullRequestLookup

  data class Failed(val reason: String) : MonitorPullRequestLookup
}

sealed interface MonitorPullRequestListing {
  data class Complete(val pullRequests: List<MonitorPullRequestSummary>) : MonitorPullRequestListing

  data class Failed(val reason: String) : MonitorPullRequestListing
}
