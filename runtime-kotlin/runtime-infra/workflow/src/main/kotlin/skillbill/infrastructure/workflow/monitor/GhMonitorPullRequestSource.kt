package skillbill.infrastructure.workflow.monitor

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.workflow.github.GhCommandRunner
import skillbill.infrastructure.workflow.github.ProcessGhCommandRunner
import skillbill.infrastructure.workflow.github.describeFailure
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.monitor.MonitorPullRequestSource
import skillbill.ports.monitor.model.MonitorCallerRepository
import skillbill.ports.monitor.model.MonitorPullRequestListing
import skillbill.ports.monitor.model.MonitorPullRequestLookup
import skillbill.ports.monitor.model.MonitorPullRequestSummary
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

private const val LISTING_OUTPUT_BYTES: Long = 256L * 1024 * 1024
private const val LISTING_DEADLINE_SECONDS: Long = 300
private const val PAGE_SIZE = "100"
private const val NOT_FOUND_MARKER = "HTTP 404"
private val REMOTE_URL = Regex("^(?:https://|ssh://git@|git@)([^/:@]+)[/:]([^/]+)/([^/]+?)(?:\\.git)?/?$")

class GhMonitorPullRequestSource internal constructor(
  private val gh: GhCommandRunner,
) : MonitorPullRequestSource {
  @Inject
  constructor() : this(ProcessGhCommandRunner(LISTING_OUTPUT_BYTES, LISTING_DEADLINE_SECONDS))

  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  override fun callerRepository(callerRoot: Path): MonitorCallerRepository =
    when (val remote = runGitCommand(callerRoot, NO_PROMPT_ENVIRONMENT, "remote", "get-url", "origin")) {
      is WorkflowGitOperationResult.Failed ->
        MonitorCallerRepository.Unavailable("The caller checkout has no origin remote: ${remote.error}")
      is WorkflowGitOperationResult.Ok ->
        REMOTE_URL.matchEntire(remote.value.trim())
          ?.destructured
          ?.let { (host, owner, name) ->
            MonitorCallerRepository.Resolved(MonitorRepositoryIdentity.canonical(host, owner, name))
          }
          ?: MonitorCallerRepository.Unavailable("The origin remote is not a recognizable repository URL.")
    }

  override fun pullRequest(
    repository: MonitorRepositoryIdentity,
    number: Int,
  ): MonitorPullRequestLookup {
    val result = gh.run(Path.of("."), apiArgs(repository, "pulls/$number"))
    return when {
      result.exitCode != 0 && NOT_FOUND_MARKER in result.stdout -> MonitorPullRequestLookup.Missing
      result.exitCode != 0 -> MonitorPullRequestLookup.Failed(result.describeFailure())
      else ->
        runCatching { mapper.readTree(result.stdout) }.getOrNull()
          ?.takeIf(JsonNode::isObject)
          ?.let { parseMonitorPullRequest(it, repository) }
          ?.let { MonitorPullRequestLookup.Found(it) }
          ?: MonitorPullRequestLookup.Failed("GitHub returned an unparsable or incomplete pull request.")
    }
  }

  override fun pullRequests(repository: MonitorRepositoryIdentity): MonitorPullRequestListing =
    list(repository, emptyList())

  override fun pullRequestsForBranch(
    repository: MonitorRepositoryIdentity,
    headRepository: MonitorRepositoryIdentity,
    branch: String,
  ): MonitorPullRequestListing = list(repository, listOf("-f", "head=${headRepository.owner}:$branch"))

  private fun list(
    repository: MonitorRepositoryIdentity,
    filters: List<String>,
  ): MonitorPullRequestListing {
    val options = listOf("--paginate", "-f", "state=all", "-f", "per_page=$PAGE_SIZE") + filters
    val result = gh.run(Path.of("."), apiArgs(repository, "pulls", options))
    if (result.exitCode != 0) return MonitorPullRequestListing.Failed(result.describeFailure())
    val summaries = runCatching { readPages(result.stdout, repository) }.getOrNull()
    return summaries?.let { MonitorPullRequestListing.Complete(it) }
      ?: MonitorPullRequestListing.Failed("GitHub returned an unparsable, truncated, or incomplete pull request list.")
  }

  private fun readPages(
    stdout: String,
    repository: MonitorRepositoryIdentity,
  ): List<MonitorPullRequestSummary>? {
    val summaries = mutableListOf<MonitorPullRequestSummary>()
    mapper.readerFor(JsonNode::class.java).readValues<JsonNode>(stdout).use { pages ->
      while (pages.hasNextValue()) {
        val page = pages.nextValue().takeIf(JsonNode::isArray) ?: return null
        for (entry in page) summaries += parseMonitorPullRequestSummary(entry, repository) ?: return null
      }
    }
    return summaries
  }

  private fun apiArgs(
    repository: MonitorRepositoryIdentity,
    resource: String,
    options: List<String> = emptyList(),
  ): List<String> =
    listOf("api", "--hostname", repository.host, "--method", "GET") + options + "repos/${repository.slug}/$resource"
}
