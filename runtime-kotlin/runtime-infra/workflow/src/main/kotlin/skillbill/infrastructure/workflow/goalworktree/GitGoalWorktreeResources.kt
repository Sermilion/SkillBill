package skillbill.infrastructure.workflow.goalworktree

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.host.jvm.deleteRecursively
import skillbill.infrastructure.host.jvm.resolveTelemetryStateDir
import skillbill.infrastructure.workflow.monitor.GitRun
import skillbill.infrastructure.workflow.monitor.NO_PROMPT_ENVIRONMENT
import skillbill.infrastructure.workflow.monitor.monitorGit
import skillbill.infrastructure.workflow.process.GIT_HOOKED_COMMAND_TIMEOUT_SECONDS
import skillbill.infrastructure.workflow.process.RepositoryStateKeyResult
import skillbill.infrastructure.workflow.process.invokeGitProcess
import skillbill.infrastructure.workflow.process.repositoryStateKey
import skillbill.infrastructure.workflow.worktree.parseWorktreeRecords
import skillbill.infrastructure.workflow.worktree.worktreeDirtyRetention
import skillbill.infrastructure.workflow.worktree.worktreeGit
import skillbill.infrastructure.workflow.worktree.worktreePathProblem
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.monitor.model.MonitorRetentionReason
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.time.model.RuntimeWaitResult
import skillbill.ports.workflow.goalworktree.GoalWorktreeResources
import skillbill.ports.workflow.goalworktree.model.GoalBranchCheckout
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeSettleRequest
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeSettlement
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import kotlin.time.Duration.Companion.seconds

private const val GOALS_DIRECTORY = "goals"
private const val CHECKOUT_DIRECTORY = "checkout"
private const val REMOVAL_MARKER_SUFFIX = ".removing"
private const val WORKTREE_FIELD_PREFIX = "worktree "
private val GOAL_SEGMENT_UNSAFE = Regex("[^A-Za-z0-9._-]")
private val UNUSABLE_GOAL_SEGMENTS = setOf("", ".", "..")

private const val LOCKED_INITIALIZING_FIELD = "locked initializing"
private val WORKTREE_ADD_ENVIRONMENT = NO_PROMPT_ENVIRONMENT + ("LC_ALL" to "C")
private const val ADD_WAIT_POLL_SECONDS = 2L

private const val ADD_WAIT_POLLS = GIT_HOOKED_COMMAND_TIMEOUT_SECONDS / ADD_WAIT_POLL_SECONDS

private enum class WorktreeEntry { UNLISTED, INITIALIZING, FINISHED }

@Inject
class GitGoalWorktreeResources(
  private val environment: EnvironmentContext,
  private val diagnostics: RuntimeDiagnostics,
  private val timing: RuntimeTimingPort,
) : GoalWorktreeResources {
  override fun createOrAdopt(
    repoRoot: Path,
    issueKey: String,
    recordedPath: Path?,
    revision: String,
  ): GoalWorktreeResult =
    try {
      if (recordedPath != null) {
        createOrAdoptAt(repoRoot, canonicalTarget(recordedPath), revision)
      } else {
        createOrAdoptAtStatePath(repoRoot, issueKey, revision)
      }
    } catch (failure: IOException) {
      preparationFailed(issueKey, failure)
    } catch (failure: SecurityException) {
      preparationFailed(issueKey, failure)
    }

  override fun checkoutHolding(
    repoRoot: Path,
    branch: String,
  ): Path? {
    val listing = listWorktrees(repoRoot)
    if (!listing.ok) {
      diagnostics.warning(
        "goal branch holder unknown: seam=goal_worktree_list value_expected=worktree_list value_used=absent " +
          "repo=$repoRoot reason=${listing.output}",
      )
      return null
    }
    return parseWorktreeRecords(listing.output)
      .firstOrNull { record -> "branch refs/heads/$branch" in record }
      ?.firstOrNull { field -> field.startsWith(WORKTREE_FIELD_PREFIX) }
      ?.let { field -> Path.of(field.removePrefix(WORKTREE_FIELD_PREFIX)) }
  }

  override fun checkoutGoalBranch(
    primaryRoot: Path,
    branch: String,
  ): GoalBranchCheckout = checkoutGoalBranchAt(primaryRoot, branch)

  override fun retentionVerdict(request: GoalWorktreeSettleRequest): GoalWorktreeSettlement.Retained? {
    val path = request.worktreePath
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
    val ownership = ownershipRetention(request)
    if (ownership != null || removalInterrupted(path)) return ownership
    return worktreeDirtyRetention(path)?.let { retained(it.reason, path, it.detail) } ?: publicationRetention(path)
  }

  private fun ownershipRetention(request: GoalWorktreeSettleRequest): GoalWorktreeSettlement.Retained? {
    val path = request.worktreePath
    val root =
      goalsRoot(request.primaryRoot)
        ?: return retained(MonitorRetentionReason.UNOWNED_RESOURCE, path, "the goals directory could not be resolved")
    val problem = worktreePathProblem(path, root, "the goals directory")
    if (problem != null) return retained(MonitorRetentionReason.UNOWNED_RESOURCE, path, problem)
    val realPath =
      try {
        path.toRealPath()
      } catch (failure: IOException) {
        return retained(
          MonitorRetentionReason.UNOWNED_RESOURCE,
          path,
          "the worktree path could not be verified: ${failure.message}",
        )
      }
    return listingRetention(request.primaryRoot, path, realPath)
  }

  private fun listingRetention(
    primaryRoot: Path,
    path: Path,
    realPath: Path,
  ): GoalWorktreeSettlement.Retained? {
    val listing = worktreeGit(primaryRoot, "worktree", "list", "--porcelain", "-z")
    if (!listing.ok) {
      return retained(MonitorRetentionReason.UNOWNED_RESOURCE, path, "git worktree list failed: ${listing.output}")
    }
    val listed =
      parseWorktreeRecords(listing.output).any { record ->
        val listedPath = record.firstOrNull()?.removePrefix("worktree ")?.let(Path::of)
        listedPath != null &&
          try {
            listedPath.toRealPath() == realPath
          } catch (_: IOException) {
            false
          }
      }
    if (!listed) {
      return retained(MonitorRetentionReason.UNOWNED_RESOURCE, path, "the worktree is missing from git worktree list")
    }
    return null
  }

  private fun publicationRetention(path: Path): GoalWorktreeSettlement.Retained? {
    val published = worktreeGit(path, "rev-list", "--count", "HEAD", "--not", "--remotes")
    val count = published.output.trim().toLongOrNull()
    return when {
      !published.ok ->
        retained(MonitorRetentionReason.PUBLICATION_UNKNOWN, path, "git rev-list failed: ${published.output}")
      count == null ->
        retained(
          MonitorRetentionReason.PUBLICATION_UNKNOWN,
          path,
          "git rev-list returned an invalid count: ${published.output}",
        )
      count == 0L -> null
      else -> unpublishedRemoteRetention(path, count)
    }
  }

  private fun unpublishedRemoteRetention(
    path: Path,
    count: Long,
  ): GoalWorktreeSettlement.Retained? {
    val contained =
      worktreeGit(
        path,
        "for-each-ref",
        "--contains",
        "HEAD",
        "--format=%(refname)",
        "refs/remotes",
      )
    return when {
      !contained.ok ->
        retained(
          MonitorRetentionReason.PUBLICATION_UNKNOWN,
          path,
          "git for-each-ref failed: ${contained.output}",
        )
      contained.output.trim().isNotEmpty() -> null
      else ->
        retained(
          MonitorRetentionReason.UNPUBLISHED_COMMITS,
          path,
          "the worktree has $count commit(s) missing from remote-tracking refs",
        )
    }
  }

  override fun settle(request: GoalWorktreeSettleRequest): GoalWorktreeSettlement {
    val path = request.worktreePath
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      val prune = worktreeGit(request.primaryRoot, "worktree", "prune")
      if (!prune.ok) diagnostics.warning("goal worktree prune failed: seam=goal_worktree.settle path=$path", null)
      deleteQuietly(removalMarker(path))
      return GoalWorktreeSettlement.Absent
    }
    retentionVerdict(request)?.let { return it }
    val failure = if (removalInterrupted(path)) finishInterruptedRemoval(path) else remove(request.primaryRoot, path)
    if (failure != null) return failure
    val prune = worktreeGit(request.primaryRoot, "worktree", "prune")
    if (!prune.ok) {
      diagnostics.warning(
        "goal worktree prune failed: seam=goal_worktree.settle path=$path reason=${prune.output}",
        null,
      )
    }
    deleteQuietly(removalMarker(path))
    deleteQuietly(path.parent)
    return GoalWorktreeSettlement.Removed
  }

  private fun remove(
    primaryRoot: Path,
    path: Path,
  ): GoalWorktreeSettlement.Retained? {
    val marker = removalMarker(path)
    try {
      val identity =
        removalIdentity(path)
          ?: return retained(
            MonitorRetentionReason.CLEANUP_FAILED,
            path,
            "the removal directory identity is unavailable",
          )
      Files.deleteIfExists(marker)
      Files.writeString(marker, identity, CREATE_NEW, WRITE)
    } catch (failure: IOException) {
      return retained(MonitorRetentionReason.CLEANUP_FAILED, path, "could not mark the removal: ${failure.message}")
    }
    val removal = worktreeGit(primaryRoot, "worktree", "remove", "--force", path.toString())
    if (removal.ok) return null
    if (!removal.timedOut) deleteQuietly(marker)
    return retained(MonitorRetentionReason.CLEANUP_FAILED, path, "git worktree remove failed: ${removal.output}")
  }

  private fun finishInterruptedRemoval(path: Path): GoalWorktreeSettlement.Retained? {
    diagnostics.warning(
      "goal worktree removal was interrupted, finishing it: seam=goal_worktree.settle path=$path",
      null,
    )
    return try {
      deleteRecursively(path)
      null
    } catch (failure: IOException) {
      retained(
        MonitorRetentionReason.CLEANUP_FAILED,
        path,
        "could not finish the interrupted removal: ${failure.message}",
      )
    }
  }

  private fun deleteQuietly(target: Path) {
    try {
      Files.deleteIfExists(target)
    } catch (failure: IOException) {
      diagnostics.warning("goal worktree cleanup failed: seam=goal_worktree.settle path=$target", failure)
    } catch (failure: SecurityException) {
      diagnostics.warning("goal worktree cleanup failed: seam=goal_worktree.settle path=$target", failure)
    }
  }

  private fun removalInterrupted(path: Path): Boolean {
    val marker = removalMarker(path)
    if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return false
    try {
      val identity = removalIdentity(path)
      if (identity != null && Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) &&
        Files.readString(marker) == identity
      ) {
        return true
      }
      diagnostics.warning("goal worktree removal identity mismatch: seam=goal_worktree.settle path=$path", null)
    } catch (failure: IOException) {
      diagnostics.warning("goal worktree removal identity unavailable: seam=goal_worktree.settle path=$path", failure)
    }
    return false
  }

  private fun removalIdentity(path: Path): String? {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (!attributes.isDirectory) return null
    val key = attributes.fileKey() ?: return null
    return "$key\n${attributes.creationTime()}"
  }

  private fun removalMarker(path: Path): Path = path.resolveSibling("${path.fileName}$REMOVAL_MARKER_SUFFIX")

  private fun retained(
    reason: MonitorRetentionReason,
    path: Path,
    detail: String,
  ) = GoalWorktreeSettlement.Retained(reason, path, detail)

  private fun goalsRoot(repoRoot: Path): Path? {
    val key = repositoryStateKey(repoRoot) as? RepositoryStateKeyResult.Resolved ?: return null
    return resolveTelemetryStateDir(environment.environment, environment.userHome)
      .resolve(GOALS_DIRECTORY)
      .resolve(key.key)
  }

  private fun preparationFailed(
    issueKey: String,
    failure: Exception,
  ): GoalWorktreeResult {
    diagnostics.warning("goal worktree preparation failed: seam=goal_worktree issue=$issueKey", failure)
    return GoalWorktreeResult.Failed("could not prepare the goal worktree: ${failure.message}")
  }

  private fun createOrAdoptAtStatePath(
    repoRoot: Path,
    issueKey: String,
    revision: String,
  ): GoalWorktreeResult {
    val segment = GOAL_SEGMENT_UNSAFE.replace(issueKey, "-")
    if (segment in UNUSABLE_GOAL_SEGMENTS) {
      return GoalWorktreeResult.Failed("issue key '$issueKey' cannot name a goal worktree directory.")
    }
    return when (val key = repositoryStateKey(repoRoot)) {
      is RepositoryStateKeyResult.NotAWorkingTree ->
        GoalWorktreeResult.Failed("$repoRoot is not a Git working tree: ${key.detail}")
      is RepositoryStateKeyResult.CommonDirectoryUnresolved ->
        GoalWorktreeResult.Failed(
          "could not resolve the Git common directory ${key.commonDirectory}: ${key.failure.message}",
        )
      is RepositoryStateKeyResult.Resolved -> {
        val target =
          resolveTelemetryStateDir(environment.environment, environment.userHome)
            .resolve(GOALS_DIRECTORY)
            .resolve(key.key)
            .resolve(segment)
            .resolve(CHECKOUT_DIRECTORY)
        createOrAdoptAt(repoRoot, canonicalTarget(target), revision)
      }
    }
  }

  private fun canonicalTarget(target: Path): Path {
    val absolute = target.toAbsolutePath().normalize()
    val ancestor =
      generateSequence(absolute.parent) { it.parent }.firstOrNull { Files.exists(it) } ?: return absolute
    return ancestor.toRealPath().resolve(ancestor.relativize(absolute))
  }

  private fun createOrAdoptAt(
    repoRoot: Path,
    target: Path,
    revision: String,
  ): GoalWorktreeResult {
    if (Files.isSymbolicLink(target)) {
      return GoalWorktreeResult.Failed("the goal worktree path $target is a symbolic link.")
    }
    val listing = listWorktrees(repoRoot)
    if (!listing.ok) {
      return GoalWorktreeResult.Failed("could not list worktrees for $target: ${listing.output}")
    }
    val entry = entryOf(listing, target)
    val present = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
    return when {
      entry == WorktreeEntry.UNLISTED && present -> adoptConcurrentOrFail(repoRoot, target)
      entry == WorktreeEntry.UNLISTED -> add(repoRoot, target, revision)
      !present -> pruneAndAdd(repoRoot, target, revision)
      !isRealDirectory(target) -> GoalWorktreeResult.Failed("the goal worktree path $target is not a directory.")
      removalInterrupted(target) -> discardInterruptedRemoval(repoRoot, target, revision)
      entry == WorktreeEntry.FINISHED -> GoalWorktreeResult.Adopted(target)
      else -> awaitInitializing(repoRoot, target)
    }
  }

  private fun discardInterruptedRemoval(
    repoRoot: Path,
    target: Path,
    revision: String,
  ): GoalWorktreeResult {
    diagnostics.warning(
      "goal worktree removal was interrupted, recreating it: seam=goal_worktree path=$target",
      null,
    )
    deleteRecursively(target)
    Files.deleteIfExists(removalMarker(target))
    return pruneAndAdd(repoRoot, target, revision)
  }

  private fun pruneAndAdd(
    repoRoot: Path,
    target: Path,
    revision: String,
  ): GoalWorktreeResult {
    val pruned = monitorGit(repoRoot, "worktree", "prune")
    return if (pruned.ok) {
      add(repoRoot, target, revision)
    } else {
      GoalWorktreeResult.Failed("could not prune the missing worktree $target: ${pruned.output}")
    }
  }

  private fun adoptConcurrentOrFail(
    repoRoot: Path,
    target: Path,
  ): GoalWorktreeResult =
    settleOtherLaunch(repoRoot, target)
      ?: GoalWorktreeResult.Failed(
        "$target exists but git does not list it as a worktree. Move it away, then run the goal again.",
      )

  private fun add(
    repoRoot: Path,
    target: Path,
    revision: String,
  ): GoalWorktreeResult {
    if (revision.startsWith("-")) {
      return GoalWorktreeResult.Failed("could not create the goal worktree at $target: invalid revision '$revision'.")
    }
    Files.createDirectories(target.parent)
    Files.deleteIfExists(removalMarker(target))
    val added =
      invokeGitProcess(
        repoRoot,
        listOf("worktree", "add", "--detach", target.toString(), revision),
        stdin = null,
        extraEnvironment = WORKTREE_ADD_ENVIRONMENT,
        deadlineSeconds = GIT_HOOKED_COMMAND_TIMEOUT_SECONDS,
      )
    return when {
      added.timedOut ->
        GoalWorktreeResult.Failed(
          incompleteCheckoutReason(
            "the runtime killed git worktree add for $target after ${GIT_HOOKED_COMMAND_TIMEOUT_SECONDS}s",
            target,
          ),
        )
      added.readFailure == null && added.exitCode == 0 -> GoalWorktreeResult.Created(target)
      else ->
        settleOtherLaunch(repoRoot, target)
          ?: GoalWorktreeResult.Failed(
            "could not create the goal worktree at $target: ${added.readFailure?.message ?: added.output}",
          )
    }
  }

  private fun settleOtherLaunch(
    repoRoot: Path,
    target: Path,
  ): GoalWorktreeResult? {
    val listing = listWorktrees(repoRoot)
    if (!listing.ok || !isRealDirectory(target)) {
      return null
    }
    return when (entryOf(listing, target)) {
      WorktreeEntry.FINISHED -> GoalWorktreeResult.Adopted(target)
      WorktreeEntry.INITIALIZING -> awaitInitializing(repoRoot, target)
      WorktreeEntry.UNLISTED -> null
    }
  }

  private fun awaitInitializing(
    repoRoot: Path,
    target: Path,
  ): GoalWorktreeResult {
    diagnostics.warning(
      "goal worktree still initializing, waiting: seam=goal_worktree value_expected=finished_worktree " +
        "value_used=wait path=$target",
    )
    return (1..ADD_WAIT_POLLS).firstNotNullOfOrNull { pollInitializing(repoRoot, target) }
      ?: GoalWorktreeResult.Failed(
        incompleteCheckoutReason(
          "$target was still initializing after ${GIT_HOOKED_COMMAND_TIMEOUT_SECONDS}s, " +
            "so no launch is still creating it",
          target,
        ),
      )
  }

  private fun pollInitializing(
    repoRoot: Path,
    target: Path,
  ): GoalWorktreeResult? {
    if (timing.wait(ADD_WAIT_POLL_SECONDS.seconds) == RuntimeWaitResult.INTERRUPTED) {
      return GoalWorktreeResult.Failed("interrupted while waiting for another launch to create $target.")
    }
    val listing = listWorktrees(repoRoot)
    if (!listing.ok) {
      return GoalWorktreeResult.Failed("could not list worktrees for $target: ${listing.output}")
    }
    val entry = entryOf(listing, target)
    return when {
      isRealDirectory(target) && entry == WorktreeEntry.FINISHED -> GoalWorktreeResult.Adopted(target)
      isRealDirectory(target) && entry == WorktreeEntry.INITIALIZING -> null
      else ->
        GoalWorktreeResult.Failed(
          "another launch was creating $target and it ended without a worktree. Run the goal again.",
        )
    }
  }

  private fun incompleteCheckoutReason(
    opening: String,
    target: Path,
  ): String =
    "$opening. If git worktree list --porcelain still shows it as $LOCKED_INITIALIZING_FIELD, the checkout is " +
      "incomplete: run git worktree unlock $target, delete that directory and run git worktree prune. " +
      "Then run the goal again."

  private fun isRealDirectory(target: Path): Boolean = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)

  private fun listWorktrees(repoRoot: Path): GitRun = monitorGit(repoRoot, "worktree", "list", "--porcelain", "-z")

  private fun entryOf(
    listing: GitRun,
    target: Path,
  ): WorktreeEntry {
    val record =
      parseWorktreeRecords(listing.output)
        .firstOrNull { "$WORKTREE_FIELD_PREFIX$target" in it } ?: return WorktreeEntry.UNLISTED
    return if (LOCKED_INITIALIZING_FIELD in record) WorktreeEntry.INITIALIZING else WorktreeEntry.FINISHED
  }
}
