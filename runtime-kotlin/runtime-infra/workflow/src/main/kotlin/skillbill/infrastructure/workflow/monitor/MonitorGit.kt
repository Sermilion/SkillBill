package skillbill.infrastructure.workflow.monitor

import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.workflow.process.runGitProcess
import skillbill.ports.monitor.model.MonitorRepositoryIdentity
import java.nio.file.Path

internal val NO_PROMPT_ENVIRONMENT = mapOf("GIT_TERMINAL_PROMPT" to "0")

private val REMOTE_URL =
  Regex("^(?:https://(?:[^/@]+@)?|ssh://git@|git@)([^/:@]+)(?::\\d+)?[/:]([^/]+)/([^/]+?)(?:\\.git)?/?$")

private const val ORIGIN_URL_KEY = "remote.origin.url"

internal data class GitRun(
  val ok: Boolean,
  val exitCode: Int,
  val output: String,
)

internal fun monitorGit(
  root: Path,
  vararg args: String,
): GitRun {
  val result = runGitProcess(root, args.toList(), extraEnvironment = NO_PROMPT_ENVIRONMENT)
  val ok = !result.timedOut && result.readFailure == null && result.exitCode == 0
  val detail = result.readFailure?.message ?: if (result.timedOut) "git timed out" else result.output
  return GitRun(ok, result.exitCode, if (ok) result.output else detail)
}

internal fun monitorRepositoryOf(url: String): MonitorRepositoryIdentity? =
  REMOTE_URL.matchEntire(url.trim())?.destructured?.let { (host, owner, name) ->
    MonitorRepositoryIdentity.canonical(host, owner, name)
  }

internal fun monitorTransportUrl(
  root: Path,
  endpoint: String,
): String {
  val repository = monitorRepositoryOf(endpoint) ?: return endpoint
  val remotes = monitorGit(root, "config", "--get-regexp", "^remote\\..*\\.url$")
  if (!remotes.ok) return endpoint
  return remotes.output
    .lines()
    .mapNotNull { line -> line.split(' ', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].trim() } }
    .sortedByDescending { (key, _) -> key == ORIGIN_URL_KEY }
    .map { (_, url) -> url }
    .firstOrNull { url -> !url.hasHttpsCredentials() && monitorRepositoryOf(url) == repository }
    ?: endpoint
}

private fun String.hasHttpsCredentials(): Boolean =
  startsWith("https://") && '@' in removePrefix("https://").substringBefore('/')

internal fun monitorFetchBranch(
  primary: Path,
  url: String,
  sourceRef: String,
  destination: String,
): GitRun =
  monitorGit(
    primary,
    "fetch",
    "--no-tags",
    "--no-write-fetch-head",
    monitorTransportUrl(primary, url),
    "+refs/heads/$sourceRef:$destination",
  )

internal fun GitRun.valueOrThrow(description: String): String =
  if (ok) {
    output
  } else {
    throw SkillBillRuntimeException(
      StandaloneMonitorFailureCode.GIT_COMMAND_FAILED,
      "Standalone monitor could not $description: $output",
    )
  }
