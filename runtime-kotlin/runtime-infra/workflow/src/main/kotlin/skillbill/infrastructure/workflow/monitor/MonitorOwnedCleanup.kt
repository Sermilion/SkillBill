package skillbill.infrastructure.workflow.monitor

import skillbill.ports.monitor.model.MonitorOwnershipState
import skillbill.ports.monitor.model.MonitorResolvedTarget
import skillbill.ports.monitor.model.MonitorRetentionReason
import skillbill.ports.monitor.model.MonitorSettlement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

private const val NOT_ANCESTOR_EXIT_CODE = 1

internal fun monitorRefNamespace(ownerToken: String): String = "refs/skill-bill/monitor/$ownerToken"

internal fun monitorBranchName(ownerToken: String): String = "skill-bill/monitor/$ownerToken"

internal fun monitorNow(
  clock: Clock,
  record: MonitorOwnershipRecord,
): Instant = maxOf(clock.instant(), record.acquiredAt)

internal class MonitorOwnedCleanup(
  private val clock: Clock,
) {
  fun settle(
    directory: Path,
    record: MonitorOwnershipRecord,
    target: MonitorResolvedTarget?,
  ): MonitorSettlement {
    val retention = retentionFor(directory, record, target)
    return if (retention != null) {
      retain(directory, record, retention)
    } else {
      removeOwned(directory, record)
    }
  }

  private fun retentionFor(
    directory: Path,
    record: MonitorOwnershipRecord,
    target: MonitorResolvedTarget?,
  ): MonitorSettlement.Retained? {
    namespaceRetention(record)?.let { return it }
    val path = record.executionPath?.let(Path::of)?.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
    return if (path == null) {
      orphanedBranchRetention(record, target)
    } else {
      unownedRetention(directory, path, record)
        ?: dirtyRetention(path)
        ?: publicationVerdict(path, "the worktree", monitorGit(path, "rev-parse", "HEAD"), record, target)
          ?.let { (reason, detail) -> MonitorSettlement.Retained(reason, path, detail) }
    }
  }

  private fun namespaceRetention(record: MonitorOwnershipRecord): MonitorSettlement.Retained? {
    val namespace = monitorRefNamespace(record.ownerToken)
    val ownedBranch = monitorBranchName(record.ownerToken)
    val foreignRef = record.ownedRefs.firstOrNull { it !in setOf("$namespace/head", "$namespace/base") }
    val problem =
      when {
        record.executionBranch != null && record.executionBranch != ownedBranch ->
          "the recorded branch ${record.executionBranch} is not the owned branch $ownedBranch"
        foreignRef != null -> "the recorded ref $foreignRef is outside the owned namespace $namespace"
        else -> null
      }
    return problem?.let {
      MonitorSettlement.Retained(MonitorRetentionReason.UNOWNED_RESOURCE, record.executionPath?.let(Path::of), it)
    }
  }

  private fun unownedRetention(
    directory: Path,
    path: Path,
    record: MonitorOwnershipRecord,
  ): MonitorSettlement.Retained? {
    val problem =
      try {
        when {
          Files.isSymbolicLink(path) -> "the worktree path is a symbolic link"
          !path.toRealPath().startsWith(directory.toRealPath()) -> "the worktree path left the admission directory"
          currentBranch(path) != record.executionBranch -> "the worktree no longer has its owned branch checked out"
          else -> null
        }
      } catch (failure: IOException) {
        "the worktree path could not be verified: ${failure.message}"
      }
    return problem?.let { MonitorSettlement.Retained(MonitorRetentionReason.UNOWNED_RESOURCE, path, it) }
  }

  private fun dirtyRetention(path: Path): MonitorSettlement.Retained? {
    val status = monitorGit(path, "status", "--porcelain", "--untracked-files=all")
    val lines = status.output.lines().filter(String::isNotBlank)
    val reason =
      when {
        !status.ok -> MonitorRetentionReason.PUBLICATION_UNKNOWN
        lines.any { !it.startsWith("??") } -> MonitorRetentionReason.DIRTY_WORKTREE
        lines.isNotEmpty() -> MonitorRetentionReason.UNTRACKED_FILES
        else -> null
      }
    val detail =
      if (status.ok) "the worktree has ${lines.size} changed path(s)" else "git status failed: ${status.output}"
    return reason?.let { MonitorSettlement.Retained(it, path, detail) }
  }

  private fun orphanedBranchRetention(
    record: MonitorOwnershipRecord,
    target: MonitorResolvedTarget?,
  ): MonitorSettlement.Retained? {
    val branch = record.executionBranch ?: return null
    val primaryRoot = Path.of(record.primaryRoot)
    if (!monitorGit(primaryRoot, "show-ref", "--verify", "--quiet", "refs/heads/$branch").ok) return null
    val tip = monitorGit(primaryRoot, "rev-parse", "--verify", "refs/heads/$branch^{commit}")
    return publicationVerdict(primaryRoot, "the owned branch $branch", tip, record, target)
      ?.let { (reason, detail) -> MonitorSettlement.Retained(reason, record.executionPath?.let(Path::of), detail) }
  }

  private fun publicationVerdict(
    gitRoot: Path,
    subject: String,
    tip: GitRun,
    record: MonitorOwnershipRecord,
    target: MonitorResolvedTarget?,
  ): Pair<MonitorRetentionReason, String>? =
    when {
      !tip.ok -> unknown("the head of $subject could not be read: ${tip.output}")
      tip.output in setOfNotNull(record.pinnedHeadSha, record.lastPublishedSha) -> null
      target == null -> unknown("published state cannot be verified without the target remote")
      else -> remotePublication(gitRoot, subject, record, target, tip.output)
    }

  private fun remotePublication(
    gitRoot: Path,
    subject: String,
    record: MonitorOwnershipRecord,
    target: MonitorResolvedTarget,
    headSha: String,
  ): Pair<MonitorRetentionReason, String>? {
    val headRef = "${monitorRefNamespace(record.ownerToken)}/head"
    val fetch = monitorFetchBranch(Path.of(record.primaryRoot), target.head.fetchUrl, target.head.tip.ref, headRef)
    val ancestor = if (fetch.ok) monitorGit(gitRoot, "merge-base", "--is-ancestor", headSha, headRef) else null
    return when {
      ancestor == null -> unknown("the remote head could not be fetched: ${fetch.output}")
      ancestor.ok -> null
      ancestor.exitCode == NOT_ANCESTOR_EXIT_CODE ->
        MonitorRetentionReason.UNPUBLISHED_COMMITS to "$subject has commits missing from the remote head"
      else -> unknown("publication could not be verified: ${ancestor.output}")
    }
  }

  private fun unknown(detail: String): Pair<MonitorRetentionReason, String> =
    MonitorRetentionReason.PUBLICATION_UNKNOWN to detail

  private fun currentBranch(path: Path): String? =
    monitorGit(path, "symbolic-ref", "--short", "HEAD").takeIf(GitRun::ok)?.output

  private fun removeOwned(
    directory: Path,
    record: MonitorOwnershipRecord,
  ): MonitorSettlement {
    val primaryRoot = Path.of(record.primaryRoot)
    val failure =
      removeWorktree(primaryRoot, record) ?: removeBranch(primaryRoot, record) ?: removeRefs(primaryRoot, record)
    return if (failure == null) {
      MonitorAdmissionFiles.write(
        directory,
        record.copy(
          state = MonitorOwnershipState.RELEASED,
          executionPath = null,
          executionBranch = null,
          ownedRefs = emptyList(),
          retention = null,
          updatedAt = monitorNow(clock, record),
        ),
      )
      MonitorSettlement.Released
    } else {
      retain(
        directory,
        record,
        MonitorSettlement.Retained(
          MonitorRetentionReason.CLEANUP_FAILED,
          record.executionPath?.let(Path::of),
          failure,
        ),
      )
    }
  }

  private fun removeWorktree(
    primaryRoot: Path,
    record: MonitorOwnershipRecord,
  ): String? {
    val path = record.executionPath?.takeIf { Files.exists(Path.of(it), LinkOption.NOFOLLOW_LINKS) } ?: return null
    val removal = monitorGit(primaryRoot, "worktree", "remove", path)
    return if (removal.ok) null else "git worktree remove failed: ${removal.output}"
  }

  private fun removeBranch(
    primaryRoot: Path,
    record: MonitorOwnershipRecord,
  ): String? {
    val branch = record.executionBranch ?: return null
    if (!monitorGit(primaryRoot, "show-ref", "--verify", "--quiet", "refs/heads/$branch").ok) return null
    val removal = monitorGit(primaryRoot, "branch", "-D", branch)
    return if (removal.ok) null else "git branch -D failed: ${removal.output}"
  }

  private fun removeRefs(
    primaryRoot: Path,
    record: MonitorOwnershipRecord,
  ): String? =
    record.ownedRefs
      .filter { monitorGit(primaryRoot, "show-ref", "--verify", "--quiet", it).ok }
      .firstNotNullOfOrNull { ref ->
        val deletion = monitorGit(primaryRoot, "update-ref", "-d", ref)
        if (deletion.ok) null else "git update-ref failed: ${deletion.output}"
      }

  private fun retain(
    directory: Path,
    record: MonitorOwnershipRecord,
    retention: MonitorSettlement.Retained,
  ): MonitorSettlement {
    MonitorAdmissionFiles.write(
      directory,
      record.copy(
        state = MonitorOwnershipState.RETAINED,
        retention = MonitorRetentionRecord(retention.reason, retention.detail, retention.path?.toString()),
        updatedAt = monitorNow(clock, record),
      ),
    )
    return retention
  }
}
