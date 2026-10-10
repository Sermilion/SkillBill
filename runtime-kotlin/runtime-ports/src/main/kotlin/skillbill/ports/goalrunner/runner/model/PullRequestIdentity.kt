package skillbill.ports.goalrunner.runner.model

sealed interface PullRequestIdentity {
  data class Found(
    val url: String,
    val number: Int,
    val title: String = "",
  ) : PullRequestIdentity

  data class Merged(
    val url: String,
    val number: Int,
  ) : PullRequestIdentity

  data object Absent : PullRequestIdentity

  data class Unavailable(val reason: String) : PullRequestIdentity
}
