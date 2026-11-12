package skillbill.infrastructure.workflow.goalworktree

import me.tatarka.inject.annotations.Inject
import skillbill.goalrunner.model.GoalPlanningCheckoutRecovery
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
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import skillbill.ports.monitor.model.MonitorRetentionReason
import skillbill.ports.time.RuntimeTimingPort
import skillbill.ports.time.model.RuntimeWaitResult
import skillbill.ports.workflow.goalworktree.GoalWorktreeResources
import skillbill.ports.workflow.goalworktree.model.GoalBranchCheckout
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeSettleRequest
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeSettlement
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
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
  private val database: DatabaseSessionFactory,
) : GoalWorktreeResources {
  override fun createOrAdopt(
    repoRoot: Path,
    issueKey: String,
    recordedPath: Path?,
    revision: String,
    ownership: GoalPlanningOwnership?,
  ): GoalWorktreeResult =
    try {
      if (recordedPath != null) {
        val proof =
          ownership
            ?: return GoalWorktreeResult.Failed(
              "the recorded goal checkout $recordedPath has no ownership proof. Preserve it and reconcile its " +
                "owner before retrying.",
            )
        val normalizedPath = recordedPath.toAbsolutePath().normalize()
        if (!matchesReservation(proof, repoRoot, normalizedPath, issueKey, revision)) {
          return GoalWorktreeResult.Failed(
            "the recorded goal checkout $recordedPath does not match its ownership proof. Preserve it and " +
              "inspect the ownership record.",
          )
        }
        withWorktreeRecoveryLock(
          canonicalTarget(recordedPath),
          {
            GoalWorktreeResult.Failed(
              "Checkout recovery is already active for $recordedPath. Retry after it finishes.",
            )
          },
        ) {
          withDurableCheckoutAccess(database, proof) {
            createOrAdoptAt(repoRoot, canonicalTarget(recordedPath), revision, proof)
          }
        }
      } else {
        createOrAdoptAtStatePath(repoRoot, issueKey, revision)
      }
    } catch (failure: IOException) {
      preparationFailed(issueKey, failure)
    } catch (failure: SecurityException) {
      preparationFailed(issueKey, failure)
    }

  private fun matchesReservation(
    proof: GoalPlanningOwnership,
    repoRoot: Path,
    normalizedPath: Path,
    issueKey: String,
    revision: String,
  ): Boolean =
    proof.ownedPath.toAbsolutePath().normalize() == normalizedPath &&
      plannedPath(repoRoot, issueKey, proof.reservationId).toAbsolutePath().normalize() == normalizedPath &&
      proof.normalizedIssueKey == FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(issueKey) &&
      reservationStateMatches(proof, revision)

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

  override fun plannedPath(
    repoRoot: Path,
    issueKey: String,
    reservationId: String?,
  ): Path =
    canonicalTarget(
      statePath(
        requireNotNull(repositoryStateKey(repoRoot) as? RepositoryStateKeyResult.Resolved).key,
        issueKey,
        reservationId,
      ),
    )

  override fun gitWorktreeIdentity(path: Path): String? = readWorktreeGitIdentity(path)

  override fun retentionVerdict(request: GoalWorktreeSettleRequest): GoalWorktreeSettlement.Retained? {
    val path = request.worktreePath
    val proof = ownershipProofRetention(request)
    if (proof != null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return proof
    val ownership = ownershipRetention(request)
    if (ownership != null) return ownership
    if (Files.exists(removalMarker(path), LinkOption.NOFOLLOW_LINKS)) {
      return retained(
        MonitorRetentionReason.UNOWNED_RESOURCE,
        path,
        "an interrupted removal needs inspection; preserve the checkout and its removal receipt",
      )
    }
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

  private fun ownershipProofRetention(request: GoalWorktreeSettleRequest): GoalWorktreeSettlement.Retained? {
    val path = request.worktreePath
    val ownership = request.ownership
    if (ownership == null || !matchesSettlementOwner(request, ownership)) {
      return retained(
        MonitorRetentionReason.UNOWNED_RESOURCE,
        path,
        "a matching goal or standalone planning ownership record is required before cleanup; preserve the path and " +
          "reconcile ownership",
      )
    }
    val root = goalsRoot(request.primaryRoot)
    val pathProblem =
      root?.let { worktreePathProblem(path, it, "the goals directory") }
        ?: if (root == null) "the goals directory could not be resolved" else null
    pathProblem?.let { problem ->
      return retained(MonitorRetentionReason.UNOWNED_RESOURCE, path, problem)
    }
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
      (ownership.gitWorktreeIdentity == null || verifiedIdentity(path, ownership) != ownership.gitWorktreeIdentity)
    ) {
      return retained(
        MonitorRetentionReason.UNOWNED_RESOURCE,
        path,
        "the checkout Git directory does not match its ownership record; preserve the path and inspect its " +
          "registration",
      )
    }
    return null
  }

  private fun matchesSettlementOwner(
    request: GoalWorktreeSettleRequest,
    ownership: GoalPlanningOwnership,
  ): Boolean {
    val issueKey = request.issueKey?.takeIf { it.isNotBlank() } ?: return false
    val path = request.worktreePath.toAbsolutePath().normalize()
    return matchesSettlementActor(request, ownership) &&
      ownership.normalizedIssueKey == FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(issueKey) &&
      ownership.ownedPath.toAbsolutePath().normalize() == path &&
      plannedPath(request.primaryRoot, issueKey, ownership.reservationId).toAbsolutePath().normalize() == path
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
    return try {
      ownershipProofRetention(request)?.let { return it }
      withWorktreeRecoveryLock(
        request.worktreePath,
        {
          retained(
            MonitorRetentionReason.UNOWNED_RESOURCE,
            request.worktreePath,
            "checkout recovery is active; preserve the checkout and retry after it finishes",
          )
        },
      ) { settleExclusively(request) }
    } catch (failure: IOException) {
      retained(
        MonitorRetentionReason.CLEANUP_FAILED,
        request.worktreePath,
        "checkout settlement failed: ${failure.message}. Preserve the path and inspect its recovery journal.",
      )
    } catch (failure: SecurityException) {
      retained(
        MonitorRetentionReason.CLEANUP_FAILED,
        request.worktreePath,
        "checkout settlement was denied: ${failure.message}. Preserve the path and inspect its recovery journal.",
      )
    }
  }

  private fun settleExclusively(request: GoalWorktreeSettleRequest): GoalWorktreeSettlement {
    val path = request.worktreePath
    ownershipProofRetention(request)?.let { return it }
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return retained(
        MonitorRetentionReason.UNOWNED_RESOURCE,
        path,
        "the checkout is missing and its work cannot be verified; preserve its registration and inspect " +
          "the ownership record and any unpublished or untracked work before retrying",
      )
    }
    val ownership = requireNotNull(request.ownership)
    val recovery =
      GoalPlanningCheckoutRecovery(
        UUID.randomUUID().toString(),
        requireNotNull(ownership.gitWorktreeIdentity),
        GoalPlanningCheckoutRecovery.State.FENCED,
      )
    val admission =
      database.owningTransaction { unit ->
        unit.goalPlanningOwnership.advanceCheckoutRecovery(ownership, recovery, recovery.state)
      }
    if (admission !is OwningTransactionResult.Committed || !admission.value) {
      return retained(
        MonitorRetentionReason.UNOWNED_RESOURCE,
        path,
        "exclusive settlement could not be established; preserve the checkout and retry after all writers stop",
      )
    }
    val failure = retentionVerdict(request) ?: prepareRemoval(request, ownership, recovery)
    if (failure != null) {
      if (!finishRecovery(ownership, recovery, ownership.gitWorktreeIdentity)) {
        diagnostics.warning("checkout fence retained: seam=goal_worktree.settle path=$path recovery=inspect_journal")
      }
    }
    return failure ?: removeProvenCheckout(database, request.primaryRoot, path, ownership)
  }

  private fun prepareRemoval(
    request: GoalWorktreeSettleRequest,
    ownership: GoalPlanningOwnership,
    recovery: GoalPlanningCheckoutRecovery,
  ): GoalWorktreeSettlement.Retained? {
    val path = request.worktreePath
    removalSafety(request.primaryRoot, path, ownership, ownership.gitWorktreeIdentity)?.let { return it }
    checkoutRemovalProblem(path, ownership)?.let { return it }
    val locked = Path.of(requireNotNull(ownership.gitWorktreeIdentity)).resolve("locked")
    if (Files.exists(locked, LinkOption.NOFOLLOW_LINKS) && !isConsumedRecoveryLock(database, ownership, locked)) {
      return retained(
        MonitorRetentionReason.UNOWNED_RESOURCE,
        path,
        "the checkout has an unknown Git lock; preserve it and identify its owner before retrying cleanup",
      )
    }
    val failure = markForRemoval(path, ownership)
    return if (failure != null) {
      failure
    } else {
      val proven =
        database.transaction { unit ->
          unit.goalPlanningOwnership.advanceCheckoutRecovery(
            ownership,
            recovery,
            GoalPlanningCheckoutRecovery.State.PROVEN,
          )
        }
      if (proven) {
        null
      } else {
        retained(
          MonitorRetentionReason.UNOWNED_RESOURCE,
          path,
          "recovery proof could not be committed; preserve the checkout and inspect its ownership journal",
        )
      }
    }
  }

  private fun removalSafety(
    primaryRoot: Path,
    path: Path,
    ownership: GoalPlanningOwnership,
    expectedGitIdentity: String?,
  ): GoalWorktreeSettlement.Retained? {
    val listing = listWorktrees(primaryRoot)
    if (verifiedIdentity(path, ownership) != expectedGitIdentity || !listing.ok ||
      entryOf(listing, path.toAbsolutePath().normalize()) != WorktreeEntry.FINISHED
    ) {
      return retained(
        MonitorRetentionReason.UNOWNED_RESOURCE,
        path,
        "the checkout Git directory changed before removal; preserve the path and inspect ownership",
      )
    }
    val changed =
      worktreeDirtyRetention(path)?.let { retained(it.reason, path, it.detail) } ?: publicationRetention(path)
    if (changed != null) {
      return changed
    }
    if (verifiedIdentity(path, ownership) != expectedGitIdentity) {
      return retained(MonitorRetentionReason.UNOWNED_RESOURCE, path, "checkout ownership changed before removal")
    }
    return null
  }

  private fun markForRemoval(
    path: Path,
    ownership: GoalPlanningOwnership,
  ): GoalWorktreeSettlement.Retained? {
    val expectedGitIdentity = ownership.gitWorktreeIdentity
    val marker = removalMarker(path)
    return try {
      if (expectedGitIdentity == null || verifiedIdentity(path, ownership) != expectedGitIdentity) {
        return retained(
          MonitorRetentionReason.UNOWNED_RESOURCE,
          path,
          "the checkout Git directory changed before removal; preserve the path and inspect ownership",
        )
      }
      val identity =
        removalIdentity(path, "$expectedGitIdentity\n${ownership.reservationId}")
          ?: return retained(
            MonitorRetentionReason.CLEANUP_FAILED,
            path,
            "the removal directory identity is unavailable",
          )
      Files.writeString(marker, identity, CREATE_NEW, WRITE)
      null
    } catch (failure: IOException) {
      retained(MonitorRetentionReason.CLEANUP_FAILED, path, "could not mark the removal: ${failure.message}")
    } catch (failure: SecurityException) {
      retained(MonitorRetentionReason.CLEANUP_FAILED, path, "could not mark the removal: ${failure.message}")
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
        createOrAdoptAt(repoRoot, canonicalTarget(statePath(key.key, issueKey, null)), revision)
      }
    }
  }

  private fun statePath(
    repositoryKey: String,
    issueKey: String,
    reservationId: String?,
  ): Path {
    val segment = GOAL_SEGMENT_UNSAFE.replace(issueKey, "-")
    require(segment !in UNUSABLE_GOAL_SEGMENTS)
    val issuePath =
      resolveTelemetryStateDir(environment.environment, environment.userHome)
        .resolve(GOALS_DIRECTORY)
        .resolve(repositoryKey)
        .resolve(segment)
    return (reservationId?.let { issuePath.resolve(it) } ?: issuePath).resolve(CHECKOUT_DIRECTORY)
  }

  private fun createOrAdoptAt(
    repoRoot: Path,
    target: Path,
    revision: String,
    ownership: GoalPlanningOwnership? = null,
  ): GoalWorktreeResult {
    if (Files.isSymbolicLink(target)) {
      return GoalWorktreeResult.Failed("the goal worktree path $target is a symbolic link.")
    }
    val listing = listWorktrees(repoRoot)
    if (!listing.ok) {
      return GoalWorktreeResult.Failed("could not list worktrees for $target: ${listing.output}")
    }
    return adoptListedWorktree(repoRoot, target, revision, ownership, listing)
  }

  private fun adoptListedWorktree(
    repoRoot: Path,
    target: Path,
    revision: String,
    ownership: GoalPlanningOwnership?,
    listing: GitRun,
  ): GoalWorktreeResult {
    val entry = entryOf(listing, target)
    if (ownership != null) {
      val recovery = database.read { it.goalPlanningOwnership.checkoutRecovery(ownership) }
      if (recovery != null) return reconcileRecovery(repoRoot, target, ownership, recovery, entry)
    }
    if (Files.exists(removalMarker(target), LinkOption.NOFOLLOW_LINKS)) {
      return GoalWorktreeResult.Failed(
        "the goal worktree path $target has an interrupted removal marker without durable recovery proof. " +
          "Preserve it and inspect its ownership journal before retrying.",
      )
    }
    val missingOwner =
      ownership?.takeIf {
        it.state != GoalPlanningOwnership.State.RESERVED && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)
      }
    return if (missingOwner != null) {
      GoalWorktreeResult.Failed(
        "the recorded checkout $target is missing without durable recovery proof. Preserve its registration " +
          "and restore or inspect unpublished and untracked work before retrying.",
      )
    } else {
      val result = resolveWorktreeEntry(repoRoot, target, revision, ownership, entry)
      if (ownership != null && result !is GoalWorktreeResult.Failed) {
        verifyOwnedResult(repoRoot, target, ownership, result)
      } else {
        result
      }
    }
  }

  private fun reconcileRecovery(
    repoRoot: Path,
    target: Path,
    ownership: GoalPlanningOwnership,
    recovery: GoalPlanningCheckoutRecovery,
    entry: WorktreeEntry,
  ): GoalWorktreeResult {
    val present = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
    val existingMatches =
      present && entry == WorktreeEntry.FINISHED && sameRepository(repoRoot, target) &&
        verifiedIdentity(target, ownership) == ownership.gitWorktreeIdentity
    return when {
      recovery.previousGitIdentity != ownership.gitWorktreeIdentity || entry == WorktreeEntry.INITIALIZING ->
        recoveryRefusal(target, "the recorded Git identity is mismatched or still initializing")
      existingMatches -> {
        if (finishRecovery(ownership, recovery, ownership.gitWorktreeIdentity)) {
          deleteQuietly(removalMarker(target))
          if (recovery.state == GoalPlanningCheckoutRecovery.State.PROVEN) {
            GoalWorktreeResult.Created(target, ownership.gitWorktreeIdentity)
          } else {
            GoalWorktreeResult.Adopted(target, ownership.gitWorktreeIdentity)
          }
        } else {
          recoveryRefusal(target, "checkout admission conflicted with its recovery generation")
        }
      }
      recovery.state != GoalPlanningCheckoutRecovery.State.PROVEN ->
        recoveryRefusal(target, "no affirmative absence-of-unrecoverable-work proof was recorded")
      else -> reconstructProvenCheckout(repoRoot, ownership, recovery, present)
    }
  }

  private fun reconstructProvenCheckout(
    repoRoot: Path,
    ownership: GoalPlanningOwnership,
    recovery: GoalPlanningCheckoutRecovery,
    present: Boolean,
  ): GoalWorktreeResult {
    val target = ownership.ownedPath
    prepareRecoveryCheckout(repoRoot, ownership, recovery, present)?.let { return recoveryRefusal(target, it) }
    val identity =
      verifiedRecoveryIdentity(repoRoot, ownership, recovery)
        ?: return recoveryRefusal(target, "the replacement checkout lacks matching creation and revision evidence")
    if (!finishRecovery(ownership, recovery, identity)) {
      return recoveryRefusal(target, "the replacement identity conflicted with its durable recovery generation")
    }
    deleteQuietly(removalMarker(target))
    diagnostics.warning(
      "goal checkout reconstructed: seam=goal_worktree.recovery path=$target revision=${ownership.initialCommit}",
    )
    return GoalWorktreeResult.Created(target, identity)
  }

  private fun finishRecovery(
    ownership: GoalPlanningOwnership,
    recovery: GoalPlanningCheckoutRecovery,
    identity: String?,
  ): Boolean =
    database.transaction { unit ->
      unit.goalPlanningOwnership.advanceCheckoutRecovery(
        ownership,
        recovery,
        GoalPlanningCheckoutRecovery.State.CONSUMED,
        identity,
      )
    }

  private fun resolveWorktreeEntry(
    repoRoot: Path,
    target: Path,
    revision: String,
    ownership: GoalPlanningOwnership?,
    entry: WorktreeEntry,
  ): GoalWorktreeResult {
    val present = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
    return when {
      entry == WorktreeEntry.UNLISTED && present -> adoptConcurrentOrFail(repoRoot, target)
      entry == WorktreeEntry.UNLISTED -> add(repoRoot, target, revision)
      !present && ownership != null ->
        GoalWorktreeResult.Failed(
          "the recorded goal worktree $target is missing. Preserve its record and " +
            "inspect its registration before retrying.",
        )
      !present -> pruneAndAdd(repoRoot, target, revision)
      !isRealDirectory(target) -> GoalWorktreeResult.Failed("the goal worktree path $target is not a directory.")
      entry == WorktreeEntry.FINISHED && !sameRepository(repoRoot, target) ->
        GoalWorktreeResult.Failed(
          "the goal worktree path $target is registered to a different Git repository. Preserve it and " +
            "inspect its ownership.",
        )
      entry == WorktreeEntry.FINISHED -> GoalWorktreeResult.Adopted(target)
      else -> awaitInitializing(repoRoot, target)
    }
  }

  private fun verifyOwnedResult(
    repoRoot: Path,
    target: Path,
    ownership: GoalPlanningOwnership,
    result: GoalWorktreeResult,
  ): GoalWorktreeResult {
    if (result is GoalWorktreeResult.Created && !verifyCreatedOwnership(repoRoot, target, ownership)) {
      return GoalWorktreeResult.Failed(
        "could not establish ownership of $target after creation. Preserve the path and inspect its registration.",
      )
    }
    val identity = verifiedIdentity(target, ownership)
    if (identity == null ||
      (ownership.state != GoalPlanningOwnership.State.RESERVED && identity != ownership.gitWorktreeIdentity)
    ) {
      return GoalWorktreeResult.Failed(
        "the goal worktree path $target lacks matching checkout ownership evidence. " +
          "Preserve it and inspect ownership.",
      )
    }
    return when (result) {
      is GoalWorktreeResult.Created -> GoalWorktreeResult.Created(target, identity)
      is GoalWorktreeResult.Adopted -> GoalWorktreeResult.Adopted(target, identity)
      is GoalWorktreeResult.Failed -> result
    }
  }

  private fun verifyCreatedOwnership(
    repoRoot: Path,
    target: Path,
    ownership: GoalPlanningOwnership,
  ): Boolean {
    val head = worktreeGit(target, "rev-parse", "HEAD")
    val listing = listWorktrees(repoRoot)
    val registered =
      listing.ok && entryOf(
        listing,
        target,
      ) == WorktreeEntry.FINISHED && sameRepository(repoRoot, target)
    val matchesRevision = head.ok && head.output.trim() == ownership.initialCommit
    return registered && matchesRevision && ownership.state == GoalPlanningOwnership.State.RESERVED &&
      writeOwnershipMarker(target, ownership)
  }

  private fun sameRepository(
    repoRoot: Path,
    target: Path,
  ): Boolean {
    fun commonDirectory(path: Path): Path? {
      val result = worktreeGit(path, "rev-parse", "--git-common-dir")
      if (!result.ok) return null
      val commonValue = result.output.trim().takeIf(String::isNotBlank) ?: return null
      val common = Path.of(commonValue)
      return (if (common.isAbsolute) common else path.resolve(common)).toRealPath()
    }
    return try {
      commonDirectory(repoRoot) != null && commonDirectory(repoRoot) == commonDirectory(target)
    } catch (_: IOException) {
      false
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
      WorktreeEntry.FINISHED ->
        if (sameRepository(repoRoot, target)) GoalWorktreeResult.Adopted(target) else null
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
      isRealDirectory(target) && entry == WorktreeEntry.FINISHED && sameRepository(repoRoot, target) ->
        GoalWorktreeResult.Adopted(target)
      isRealDirectory(target) && entry == WorktreeEntry.FINISHED ->
        GoalWorktreeResult.Failed(
          "the goal worktree path $target is registered to a different Git repository. " +
            "Preserve it and inspect its ownership.",
        )
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
}

private fun reservationStateMatches(
  proof: GoalPlanningOwnership,
  revision: String,
): Boolean =
  proof.state != GoalPlanningOwnership.State.SUPERSEDED &&
    (proof.state != GoalPlanningOwnership.State.RESERVED || proof.initialCommit == revision.trim())

private fun matchesSettlementActor(
  request: GoalWorktreeSettleRequest,
  ownership: GoalPlanningOwnership,
): Boolean {
  val workflowId = request.standaloneWorkflowId ?: return matchesControlOwner(request, ownership)
  if (ownership.repositoryIdentity != request.repositoryIdentity) return false
  if (ownership.parentWorkflowId != null || request.parentWorkflowId != null) return false
  return ownership.state == GoalPlanningOwnership.State.INITIALIZED && ownership.workflowId == workflowId
}

private fun matchesControlOwner(
  request: GoalWorktreeSettleRequest,
  ownership: GoalPlanningOwnership,
): Boolean =
  ownership.state == GoalPlanningOwnership.State.TRANSFERRED &&
    !ownership.parentWorkflowId.isNullOrBlank() &&
    !request.parentWorkflowId.isNullOrBlank() && ownership.parentWorkflowId == request.parentWorkflowId &&
    request.repositoryIdentity != null && ownership.repositoryIdentity == request.repositoryIdentity

internal fun removalIdentity(
  path: Path,
  gitIdentity: String,
): String? {
  val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
  if (!attributes.isDirectory) return null
  val key = attributes.fileKey() ?: return null
  return "$key\n${attributes.creationTime()}\n$gitIdentity"
}

internal fun removalMarker(path: Path): Path = path.resolveSibling("${path.fileName}$REMOVAL_MARKER_SUFFIX")

private fun retained(
  reason: MonitorRetentionReason,
  path: Path,
  detail: String,
) = GoalWorktreeSettlement.Retained(reason, path, detail)

private fun isRealDirectory(target: Path): Boolean = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)

private fun entryOf(
  listing: GitRun,
  target: Path,
): WorktreeEntry {
  val record =
    parseWorktreeRecords(listing.output)
      .firstOrNull { "$WORKTREE_FIELD_PREFIX$target" in it } ?: return WorktreeEntry.UNLISTED
  return if (LOCKED_INITIALIZING_FIELD in record) WorktreeEntry.INITIALIZING else WorktreeEntry.FINISHED
}

private fun canonicalTarget(target: Path): Path {
  val absolute = target.toAbsolutePath().normalize()
  val ancestor =
    generateSequence(absolute.parent) { it.parent }.firstOrNull { Files.exists(it) } ?: return absolute
  return ancestor.toRealPath().resolve(ancestor.relativize(absolute))
}

private fun checkoutRemovalProblem(
  path: Path,
  ownership: GoalPlanningOwnership,
): GoalWorktreeSettlement.Retained? {
  val head = worktreeGit(path, "rev-parse", "HEAD")
  if (!head.ok || head.output.trim() != ownership.initialCommit) {
    return retained(
      MonitorRetentionReason.UNOWNED_RESOURCE,
      path,
      "the checkout is not at its original recoverable revision; preserve it and inspect its commits",
    )
  }
  recoveryUntrackedProblem(path)?.let {
    return retained(MonitorRetentionReason.UNTRACKED_FILES, path, it)
  }
  return null
}

private fun listWorktrees(repoRoot: Path): GitRun = monitorGit(repoRoot, "worktree", "list", "--porcelain", "-z")
