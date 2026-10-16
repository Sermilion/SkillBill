package skillbill.infrastructure.workflow.monitor

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.workflow.process.GIT_HOOKED_COMMAND_TIMEOUT_SECONDS
import skillbill.infrastructure.workflow.process.invokeGitProcess
import skillbill.ports.monitor.MonitorRepairPublisher
import skillbill.ports.monitor.model.MonitorPublicationRefusal
import skillbill.ports.monitor.model.MonitorPublicationRequest
import skillbill.ports.monitor.model.MonitorPublicationResult
import java.nio.file.Files
import java.nio.file.Path

@Inject
class GitMonitorRepairPublisher : MonitorRepairPublisher {
  override fun publish(request: MonitorPublicationRequest): MonitorPublicationResult {
    val worktree = request.worktree.path
    val localRef = "refs/heads/${request.worktree.branch}"
    val destination = "refs/heads/${request.head.tip.ref}"
    val localTip = monitorGit(worktree, "rev-parse", "--verify", "$localRef^{commit}")
    val original = originalHook(worktree)
    return when {
      !localTip.ok || localTip.output != request.repairSha ->
        MonitorPublicationResult.Rejected(
          MonitorPublicationRefusal.LOCAL_STATE_MISMATCH,
          "The owned branch ${request.worktree.branch} is at ${localTip.output}, not the repair ${request.repairSha}.",
        )
      original == null ->
        MonitorPublicationResult.Rejected(
          MonitorPublicationRefusal.GUARD_REFUSED,
          "The applicable pre-push hook could not be determined, so the push fails closed.",
        )
      else -> push(request, localRef, destination, original)
    }
  }

  private fun push(
    request: MonitorPublicationRequest,
    localRef: String,
    destination: String,
    original: String,
  ): MonitorPublicationResult {
    val worktree = request.worktree.path
    val hookDirectory = writeMonitorPrePushGuard()
    try {
      val environment =
        NO_PROMPT_ENVIRONMENT +
          mapOf(
            GUARD_EXPECT_URL to request.head.pushUrl,
            GUARD_EXPECT_REF to destination,
            GUARD_EXPECT_LOCAL_SHA to request.repairSha,
            GUARD_EXPECT_REMOTE_SHA to request.expectedRemoteSha,
            GUARD_ORIGINAL_HOOK to original,
          )
      val result =
        invokeGitProcess(
          worktree,
          listOf(
            "-c",
            "core.hooksPath=$hookDirectory",
            "push",
            "--porcelain",
            request.head.pushUrl,
            "$localRef:$destination",
          ),
          stdin = null,
          extraEnvironment = environment,
          deadlineSeconds = GIT_HOOKED_COMMAND_TIMEOUT_SECONDS,
        )
      val detail = result.readFailure?.message ?: if (result.timedOut) "git push timed out" else result.output
      return if (result.exitCode == 0 && result.readFailure == null && !result.timedOut) {
        reconcile(request, destination, detail, pushed = true)
      } else if (GUARD_REFUSAL_MARKER in detail) {
        MonitorPublicationResult.Rejected(MonitorPublicationRefusal.GUARD_REFUSED, detail)
      } else {
        reconcile(request, destination, detail, pushed = false)
      }
    } finally {
      removeMonitorPrePushGuard(hookDirectory)
    }
  }

  private fun reconcile(
    request: MonitorPublicationRequest,
    destination: String,
    detail: String,
    pushed: Boolean,
  ): MonitorPublicationResult {
    val remote = monitorGit(request.worktree.path, "ls-remote", request.head.pushUrl, destination)
    val advertised =
      if (remote.ok) {
        remote.output.lineSequence().firstOrNull()?.substringBefore('\t')?.trim().orEmpty()
      } else {
        null
      }
    return when {
      advertised == request.repairSha -> MonitorPublicationResult.Acknowledged(advertised)
      advertised == null ->
        MonitorPublicationResult.Uncertain("The remote head could not be read after the push: ${remote.output}")
      pushed -> MonitorPublicationResult.Uncertain("The push reported success but the remote advertises $advertised.")
      advertised == request.expectedRemoteSha ->
        MonitorPublicationResult.Rejected(MonitorPublicationRefusal.REMOTE_REJECTED, detail)
      else -> MonitorPublicationResult.Uncertain("The remote head moved to $advertised during a failed push: $detail")
    }
  }

  private fun originalHook(worktree: Path): String? {
    val located = monitorGit(worktree, "rev-parse", "--git-path", "hooks/pre-push")
    if (!located.ok) return null
    val path = worktree.resolve(located.output).normalize()
    return if (Files.isRegularFile(path) && Files.isExecutable(path)) path.toString() else ""
  }
}
