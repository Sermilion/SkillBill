package skillbill.infrastructure.workflow.goalworktree

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.host.resolveTelemetryStateDir
import skillbill.infrastructure.workflow.monitor.GitRun
import skillbill.infrastructure.workflow.monitor.NO_PROMPT_ENVIRONMENT
import skillbill.infrastructure.workflow.monitor.monitorGit
import skillbill.infrastructure.workflow.process.GIT_HOOKED_COMMAND_TIMEOUT_SECONDS
import skillbill.infrastructure.workflow.process.RepositoryStateKeyResult
import skillbill.infrastructure.workflow.process.invokeGitProcess
import skillbill.infrastructure.workflow.process.repositoryStateKey
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.time.model.RuntimeWaitResult
import skillbill.ports.workflow.goalworktree.GoalWorktreeResources
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val GOALS_DIRECTORY = "goals"
private const val CHECKOUT_DIRECTORY = "checkout"
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
    return worktreeRecords(listing)
      .firstOrNull { record -> "branch refs/heads/$branch" in record }
      ?.firstOrNull { field -> field.startsWith(WORKTREE_FIELD_PREFIX) }
      ?.let { field -> Path.of(field.removePrefix(WORKTREE_FIELD_PREFIX)) }
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
      entry == WorktreeEntry.FINISHED -> GoalWorktreeResult.Adopted(target)
      else -> awaitInitializing(repoRoot, target)
    }
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

  private fun worktreeRecords(listing: GitRun): List<List<String>> =
    listing.output.split("\u0000\u0000").map { it.split('\u0000') }

  private fun entryOf(
    listing: GitRun,
    target: Path,
  ): WorktreeEntry {
    val record =
      worktreeRecords(listing).firstOrNull { "$WORKTREE_FIELD_PREFIX$target" in it } ?: return WorktreeEntry.UNLISTED
    return if (LOCKED_INITIALIZING_FIELD in record) WorktreeEntry.INITIALIZING else WorktreeEntry.FINISHED
  }
}
