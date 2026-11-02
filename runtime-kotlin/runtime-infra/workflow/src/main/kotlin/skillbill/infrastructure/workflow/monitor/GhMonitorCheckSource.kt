package skillbill.infrastructure.workflow.monitor

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.workflow.github.GhCommandRunner
import skillbill.infrastructure.workflow.github.ProcessGhCommandRunner
import skillbill.infrastructure.workflow.github.describeFailure
import skillbill.infrastructure.workflow.github.parsePullRequestChecks
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.model.PullRequestCheck
import skillbill.ports.goalrunner.runner.model.PullRequestChecks
import skillbill.ports.monitor.MonitorCheckSource
import skillbill.ports.monitor.model.MonitorFailureLog
import skillbill.ports.monitor.model.MonitorPullRequestIdentity
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path

private const val LOG_OUTPUT_BYTES: Long = 8L * 1024 * 1024
private const val LOG_DEADLINE_SECONDS: Long = 120
private const val LOG_TAIL_CHARACTERS = 24_000
private val RUN_LINK = Regex("/actions/runs/(\\d+)(?:/job/(\\d+))?")

class GhMonitorCheckSource internal constructor(
  private val gh: GhCommandRunner,
  private val diagnostics: RuntimeDiagnostics,
) : MonitorCheckSource {
  @Inject
  constructor(diagnostics: RuntimeDiagnostics) : this(
    ProcessGhCommandRunner(maxOutputBytes = LOG_OUTPUT_BYTES, deadlineSeconds = LOG_DEADLINE_SECONDS),
    diagnostics,
  )

  override fun checks(pullRequest: MonitorPullRequestIdentity): PullRequestChecks {
    val result =
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
    if (result is PullRequestChecks.Unavailable) {
      diagnostics.warning(
        "monitor poll checks unavailable for ${pullRequest.repository.slug}#${pullRequest.number}: ${result.reason}",
      )
    }
    return result
  }

  override fun commitChecks(
    repository: MonitorRepositoryIdentity,
    sha: String,
  ): PullRequestChecks {
    val checkRuns = gh.run(Path.of("."), commitArgs(repository, sha, "check-runs"))
    val statuses =
      if (checkRuns.exitCode == 0) gh.run(Path.of("."), commitArgs(repository, sha, "status")) else checkRuns
    val result =
      if (statuses.exitCode != 0) {
        PullRequestChecks.Unavailable(statuses.describeFailure())
      } else {
        parseMonitorCommitChecks(checkRuns.stdout, statuses.stdout)
      }
    if (result is PullRequestChecks.Unavailable) {
      diagnostics.warning("monitor poll commit checks unavailable for ${repository.slug}@$sha: ${result.reason}")
    }
    return result
  }

  private fun commitArgs(
    repository: MonitorRepositoryIdentity,
    sha: String,
    resource: String,
  ): List<String> =
    listOf(
      "api",
      "--hostname",
      repository.host,
      "--method",
      "GET",
      "--paginate",
      "-f",
      "filter=latest",
      "-f",
      "per_page=100",
      "repos/${repository.slug}/commits/$sha/$resource",
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
      val reason = result.describeFailure()
      diagnostics.warning("monitor poll failure log unavailable for $repository#${pullRequest.number}: $reason")
      MonitorFailureLog.Unavailable(reason)
    }
  }

  private fun runLinkOf(
    pullRequest: MonitorPullRequestIdentity,
    link: String,
  ): MatchResult? {
    val uri =
      try {
        URI(link)
      } catch (_: URISyntaxException) {
        return null
      }
    val path = uri.path.orEmpty()
    val ownPrefix = "/${pullRequest.repository.slug}/actions/runs/"
    return RUN_LINK.find(path)?.takeIf {
      uri.host.equals(pullRequest.repository.host, ignoreCase = true) && path.startsWith(ownPrefix)
    }
  }

  private fun repositoryArgument(pullRequest: MonitorPullRequestIdentity): String =
    "${pullRequest.repository.host}/${pullRequest.repository.slug}"
}
