package skillbill.infrastructure.workflow.monitor

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.workflow.github.GhCommandRunner
import skillbill.infrastructure.workflow.github.ProcessGhCommandRunner
import skillbill.infrastructure.workflow.github.describeFailure
import skillbill.infrastructure.workflow.github.parsePullRequestChecks
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import skillbill.ports.monitor.MonitorCheckSource
import skillbill.ports.monitor.model.MonitorFailureLog
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import java.net.URI
import java.nio.file.Path

private const val LOG_OUTPUT_BYTES: Long = 8L * 1024 * 1024
private const val LOG_DEADLINE_SECONDS: Long = 120
private const val LOG_TAIL_CHARACTERS = 24_000
private val RUN_LINK = Regex("/actions/runs/(\\d+)(?:/job/(\\d+))?")

class GhMonitorCheckSource internal constructor(
  private val gh: GhCommandRunner,
) : MonitorCheckSource {
  @Inject
  constructor() : this(
    ProcessGhCommandRunner(maxOutputBytes = LOG_OUTPUT_BYTES, deadlineSeconds = LOG_DEADLINE_SECONDS),
  )

  override fun checks(pullRequest: MonitorPullRequestIdentity): PullRequestChecks =
    parsePullRequestChecks(
      gh.run(
        Path.of("."),
        listOf(
          "pr",
          "checks",
          pullRequest.number.toString(),
          "--repo",
          repositoryArgument(pullRequest),
          "--json",
          "name,state,bucket,link,startedAt",
        ),
      ),
    )

  override fun failureLog(
    pullRequest: MonitorPullRequestIdentity,
    check: PullRequestCheck,
  ): MonitorFailureLog {
    val repository = repositoryArgument(pullRequest)
    val match =
      runLinkOf(pullRequest, check.link)
        ?: return MonitorFailureLog.Unavailable("The check link is not a workflow run of $repository.")
    val (runId, jobId) = match.destructured
    val arguments =
      listOf("run", "view", runId) +
        (if (jobId.isBlank()) emptyList() else listOf("--job", jobId)) +
        listOf("--log-failed", "--repo", repository)
    val result = gh.run(Path.of("."), arguments)
    return if (result.exitCode == 0) {
      MonitorFailureLog.Available(result.stdout.takeLast(LOG_TAIL_CHARACTERS))
    } else {
      MonitorFailureLog.Unavailable(result.describeFailure())
    }
  }

  private fun runLinkOf(
    pullRequest: MonitorPullRequestIdentity,
    link: String,
  ): MatchResult? {
    val uri = runCatching { URI(link) }.getOrNull() ?: return null
    val path = uri.path.orEmpty()
    val ownPrefix = "/${pullRequest.repository.slug}/actions/runs/"
    return RUN_LINK.find(path)?.takeIf {
      uri.host.equals(pullRequest.repository.host, ignoreCase = true) && path.startsWith(ownPrefix)
    }
  }

  private fun repositoryArgument(pullRequest: MonitorPullRequestIdentity): String =
    "${pullRequest.repository.host}/${pullRequest.repository.slug}"
}
