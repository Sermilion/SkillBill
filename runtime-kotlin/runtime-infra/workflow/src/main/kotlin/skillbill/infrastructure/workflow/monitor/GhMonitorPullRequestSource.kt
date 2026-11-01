package skillbill.infrastructure.workflow.monitor

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.workflow.github.GhCommandRunner
import skillbill.infrastructure.workflow.github.ProcessGhCommandRunner
import skillbill.infrastructure.workflow.github.describeFailure
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.monitor.MonitorPullRequestSource
import skillbill.ports.monitor.model.MonitorCallerRepository
import skillbill.ports.monitor.model.MonitorPullRequest
import skillbill.ports.monitor.model.MonitorPullRequestListing
import skillbill.ports.monitor.model.MonitorPullRequestLookup
import skillbill.ports.monitor.model.MonitorPullRequestSummary
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.io.IOException
import java.nio.file.Path

private const val LISTING_OUTPUT_BYTES: Long = 256L * 1024 * 1024
private const val LISTING_DEADLINE_SECONDS: Long = 300
private const val PAGE_SIZE = "100"
private const val NOT_FOUND_MARKER = "HTTP 404"
private val REMOTE_URL =
  Regex("^(?:https://(?:[^/@]+@)?|ssh://git@|git@)([^/:@]+)(?::\\d+)?[/:]([^/]+)/([^/]+?)(?:\\.git)?/?$")

class GhMonitorPullRequestSource internal constructor(
  private val gh: GhCommandRunner,
  private val diagnostics: RuntimeDiagnostics,
) : MonitorPullRequestSource {
  @Inject
  constructor(diagnostics: RuntimeDiagnostics) : this(
    ProcessGhCommandRunner(maxOutputBytes = LISTING_OUTPUT_BYTES, deadlineSeconds = LISTING_DEADLINE_SECONDS),
    diagnostics,
  )

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
      result.exitCode != 0 -> {
        val reason = result.describeFailure()
        diagnostics.warning("monitor poll pull request lookup failed for ${repository.slug}#$number: $reason")
        MonitorPullRequestLookup.Failed(reason)
      }
      else -> {
        val json = readTreeOrNull(result.stdout, "pull request lookup")
        val pullRequest =
          json
            ?.takeIf(JsonNode::isObject)
            ?.let { parseMonitorPullRequest(it, repository) }
        if (pullRequest != null) {
          MonitorPullRequestLookup.Found(withRepositoryPush(pullRequest))
        } else {
          val reason = "GitHub returned an unparsable or incomplete pull request."
          if (json != null) {
            diagnostics.warning("monitor poll pull request lookup failed for ${repository.slug}#$number: $reason")
          }
          MonitorPullRequestLookup.Failed(reason)
        }
      }
    }
  }

  private fun readTreeOrNull(
    stdout: String,
    operation: String,
  ): JsonNode? =
    try {
      val json = mapper.readTree(stdout)
      if (json == null) diagnostics.warning("monitor poll $operation returned empty GitHub output")
      json
    } catch (error: JsonProcessingException) {
      diagnostics.warning("monitor poll $operation returned unparsable GitHub output", error)
      null
    }

  private fun withRepositoryPush(pullRequest: MonitorPullRequest): MonitorPullRequest {
    val head = pullRequest.head ?: return pullRequest
    if (head.writeEvidence.repositoryPush != null) return pullRequest
    val push = repositoryPush(head.tip.repository) ?: return pullRequest
    return pullRequest.copy(head = head.copy(writeEvidence = head.writeEvidence.copy(repositoryPush = push)))
  }

  private fun repositoryPush(repository: MonitorRepositoryIdentity): Boolean? {
    val result = gh.run(Path.of("."), getArgs(repository.host, "repos/${repository.slug}"))
    if (result.exitCode != 0) {
      diagnostics.warning(
        "monitor poll repository permissions failed for ${repository.slug}: ${result.describeFailure()}",
      )
      return null
    }
    return readTreeOrNull(result.stdout, "repository permissions")
      ?.path("permissions")
      ?.path("push")
      ?.takeIf(JsonNode::isBoolean)
      ?.asBoolean()
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
    if (result.exitCode != 0) {
      val reason = result.describeFailure()
      diagnostics.warning("monitor poll pull request list failed for ${repository.slug}: $reason")
      return MonitorPullRequestListing.Failed(reason)
    }
    val summaries =
      try {
        readPages(result.stdout, repository)
      } catch (error: IOException) {
        diagnostics.warning(
          "monitor poll pull request list returned unparsable GitHub output for ${repository.slug}",
          error,
        )
        return MonitorPullRequestListing.Failed(
          "GitHub returned an unparsable, truncated, or incomplete pull request list.",
        )
      }
    if (summaries != null) return MonitorPullRequestListing.Complete(summaries)
    val reason = "GitHub returned an unparsable, truncated, or incomplete pull request list."
    diagnostics.warning("monitor poll pull request list failed for ${repository.slug}: $reason")
    return MonitorPullRequestListing.Failed(reason)
  }

  private fun readPages(
    stdout: String,
    repository: MonitorRepositoryIdentity,
  ): List<MonitorPullRequestSummary>? {
    val summaries = mutableListOf<MonitorPullRequestSummary>()
    mapper.factory.createParser(stdout).use { parser ->
      while (parser.nextToken() != null) {
        val page = mapper.readTree<JsonNode>(parser).takeIf(JsonNode::isArray) ?: return null
        for (entry in page) summaries += parseMonitorPullRequestSummary(entry, repository) ?: return null
      }
    }
    return summaries
  }

  private fun apiArgs(
    repository: MonitorRepositoryIdentity,
    resource: String,
    options: List<String> = emptyList(),
  ): List<String> = getArgs(repository.host, "repos/${repository.slug}/$resource", options)

  private fun getArgs(
    host: String,
    path: String,
    options: List<String> = emptyList(),
  ): List<String> = listOf("api", "--hostname", host, "--method", "GET") + options + path
}
