package skillbill.ports.goalrunner.runner.model

import java.time.Instant

sealed interface PullRequestChecks {
  data class Reported(val checks: List<PullRequestCheck>) : PullRequestChecks

  data object NoChecks : PullRequestChecks

  data class Unavailable(val reason: String) : PullRequestChecks
}

data class PullRequestCheck(
  val name: String,
  val bucket: CheckBucket,
  val link: String,
  val startedAt: Instant? = null,
)

enum class CheckBucket(val wireValue: String) {
  PASS("pass"),
  SKIPPING("skipping"),
  FAIL("fail"),
  CANCEL("cancel"),
  PENDING("pending"),
}
