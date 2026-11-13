package skillbill.infrastructure.workflow.goalworktree

import skillbill.goalrunner.model.GoalPlanningCheckoutRecovery
import skillbill.infrastructure.workflow.worktree.parseWorktreeRecords
import skillbill.infrastructure.workflow.worktree.worktreeDirtyRetention
import skillbill.infrastructure.workflow.worktree.worktreeGit
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import skillbill.ports.monitor.model.MonitorRetentionReason
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeResult
import skillbill.ports.workflow.goalworktree.model.GoalWorktreeSettlement
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

private const val RECOVERY_LOCK_PREFIX = "skill-bill-recovery:"

private fun recoveryLockReason(recovery: GoalPlanningCheckoutRecovery): String =
  "$RECOVERY_LOCK_PREFIX${recovery.generation}"

internal fun recoveryRefusal(
  path: Path,
  reason: String,
): GoalWorktreeResult.Failed =
  GoalWorktreeResult.Failed(
    "Checkout recovery refused for $path because $reason. Preserve the checkout, registration and recovery " +
      "journal; restore the recorded revision or reconcile the affected ownership before retrying.",
  )

internal fun verifiedRecoveryIdentity(
  repoRoot: Path,
  owner: GoalPlanningOwnership,
  recovery: GoalPlanningCheckoutRecovery,
): String? {
  val target = owner.ownedPath
  val identity = readWorktreeGitIdentity(target) ?: return null
  val locked = Path.of(identity).resolve("locked")
  val creationMatches =
    Files.isDirectory(target, NOFOLLOW_LINKS) && Files.isRegularFile(locked, NOFOLLOW_LINKS) &&
      Files.readString(locked).trim() == recoveryLockReason(recovery)
  if (!creationMatches) return null
  val head = worktreeGit(target, "rev-parse", "HEAD")
  val common = commonGitDirectory(repoRoot)
  val listing = worktreeGit(repoRoot, "worktree", "list", "--porcelain", "-z")
  val registered =
    listing.ok &&
      parseWorktreeRecords(listing.output).any {
        "worktree $target" in it && "locked initializing" !in it
      }
  val repositoryMatches = registered && common != null && commonGitDirectory(target) == common
  val workMatches = recoveryWorkMatches(target, owner, head.ok, head.output)
  val marked =
    repositoryMatches && workMatches &&
      (verifiedIdentity(target, owner) == identity || writeOwnershipMarker(target, owner))
  return identity.takeIf { marked && verifiedIdentity(target, owner) == it }
}

private fun recoveryWorkMatches(
  target: Path,
  owner: GoalPlanningOwnership,
  headOk: Boolean,
  headOutput: String,
): Boolean =
  headOk && headOutput.trim() == owner.initialCommit && worktreeDirtyRetention(target) == null &&
    recoveryUntrackedProblem(target) == null

private fun archiveMissingRegistration(
  repoRoot: Path,
  owner: GoalPlanningOwnership,
  recovery: GoalPlanningCheckoutRecovery,
): Boolean {
  val target = owner.ownedPath
  if (Files.exists(target, NOFOLLOW_LINKS)) return false
  val common = commonGitDirectory(repoRoot) ?: return false
  val oldIdentity = Path.of(recovery.previousGitIdentity)
  if (!Files.exists(oldIdentity, NOFOLLOW_LINKS)) return registrationAbsent(repoRoot, target)
  val ownedDirectory =
    Files.isDirectory(oldIdentity, NOFOLLOW_LINKS) &&
      oldIdentity.parent.toRealPath() == common.resolve("worktrees").toRealPath()
  val gitdir = oldIdentity.resolve("gitdir")
  val registrationMatches =
    ownedDirectory && Files.isRegularFile(gitdir, NOFOLLOW_LINKS) &&
      Files.readString(gitdir).trim() == target.resolve(".git").toString() &&
      removalRegistrationMatches(owner, oldIdentity)
  return if (registrationMatches) {
    val history = common.resolve("skill-bill-recovery")
    Files.createDirectories(history)
    val archived = history.resolve(recovery.generation)
    if (!Files.isDirectory(history, NOFOLLOW_LINKS) || Files.exists(archived, NOFOLLOW_LINKS)) {
      false
    } else {
      Files.move(oldIdentity, archived, ATOMIC_MOVE)
      registrationAbsent(repoRoot, target)
    }
  } else {
    false
  }
}

private fun removalRegistrationMatches(
  owner: GoalPlanningOwnership,
  identity: Path,
): Boolean {
  val removal = removalMarker(owner.ownedPath)
  val marker = identity.resolve(OWNERSHIP_MARKER)
  if (!Files.isRegularFile(removal, NOFOLLOW_LINKS) || !Files.isRegularFile(marker, NOFOLLOW_LINKS)) return false
  val removalValue = Files.readString(removal)
  val suffix = "\n${owner.reservationId}"
  if (!removalValue.endsWith(suffix)) return false
  val expected =
    listOf(
      owner.reservationId,
      owner.repositoryIdentity,
      owner.ownedPath.toAbsolutePath().normalize().toString(),
      owner.initialCommit,
      removalValue.removeSuffix(suffix),
    ).joinToString("\n")
  return Files.readString(marker) == expected
}

private fun registrationAbsent(
  repoRoot: Path,
  target: Path,
): Boolean {
  val listing = worktreeGit(repoRoot, "worktree", "list", "--porcelain", "-z")
  return listing.ok && parseWorktreeRecords(listing.output).none { "worktree $target" in it }
}

private fun commonGitDirectory(path: Path): Path? {
  val result = worktreeGit(path, "rev-parse", "--path-format=absolute", "--git-common-dir")
  return if (result.ok) Path.of(result.output.trim()).toRealPath() else null
}

internal fun recoveryUntrackedProblem(path: Path): String? {
  val result = worktreeGit(path, "ls-files", "--others", "--directory")
  return when {
    !result.ok -> "untracked and ignored files could not be verified: ${result.output}"
    result.output.isNotBlank() -> "the checkout contains untracked or ignored files; preserve them before retrying"
    else -> null
  }
}

internal fun removeProvenCheckout(
  database: DatabaseSessionFactory,
  primaryRoot: Path,
  path: Path,
  ownership: GoalPlanningOwnership,
): GoalWorktreeSettlement {
  val locked = Path.of(requireNotNull(ownership.gitWorktreeIdentity)).resolve("locked")
  if (Files.exists(locked, NOFOLLOW_LINKS)) {
    if (!isConsumedRecoveryLock(database, ownership, locked)) {
      return GoalWorktreeSettlement.Retained(
        MonitorRetentionReason.UNOWNED_RESOURCE,
        path,
        "the checkout has an unknown Git lock; preserve it and identify its owner before retrying cleanup",
      )
    }
    val unlocked = worktreeGit(primaryRoot, "worktree", "unlock", path.toString())
    if (!unlocked.ok) {
      return GoalWorktreeSettlement.Retained(MonitorRetentionReason.CLEANUP_FAILED, path, unlocked.output)
    }
  }
  val removal = worktreeGit(primaryRoot, "worktree", "remove", path.toString())
  return if (removal.ok) {
    GoalWorktreeSettlement.Removed
  } else {
    GoalWorktreeSettlement.Retained(
      MonitorRetentionReason.CLEANUP_FAILED,
      path,
      "git worktree remove failed: ${removal.output}. Preserve the checkout and recovery journal before retrying.",
    )
  }
}

internal fun isConsumedRecoveryLock(
  database: DatabaseSessionFactory,
  ownership: GoalPlanningOwnership,
  locked: Path,
): Boolean {
  if (!Files.isRegularFile(locked, NOFOLLOW_LINKS)) return false
  val reason = Files.readString(locked).trim()
  if (!reason.startsWith(RECOVERY_LOCK_PREFIX)) return false
  return database.read {
    it.goalPlanningOwnership.isConsumedCheckoutRecovery(ownership, reason.removePrefix(RECOVERY_LOCK_PREFIX))
  }
}

internal fun prepareRecoveryCheckout(
  repoRoot: Path,
  owner: GoalPlanningOwnership,
  recovery: GoalPlanningCheckoutRecovery,
  present: Boolean,
): String? {
  val target = owner.ownedPath
  val revision = worktreeGit(repoRoot, "rev-parse", "--verify", "${owner.initialCommit}^{commit}")
  if (!revision.ok || revision.output.trim() != owner.initialCommit) return "the exact proved revision is unavailable"
  if (present) return null
  if (!archiveMissingRegistration(repoRoot, owner, recovery)) {
    return "its stale registration could not be identified exclusively"
  }
  val added =
    worktreeGit(
      repoRoot,
      "worktree",
      "add",
      "--detach",
      "--lock",
      "--reason",
      recoveryLockReason(recovery),
      target.toString(),
      owner.initialCommit,
    )
  return if (added.ok) null else "Git reconstruction was interrupted or refused: ${added.output}"
}

internal fun withDurableCheckoutAccess(
  database: DatabaseSessionFactory,
  owner: GoalPlanningOwnership,
  access: () -> GoalWorktreeResult,
): GoalWorktreeResult {
  val result =
    database.owningTransaction { unit ->
      if (unit.goalPlanningOwnership.findByWorkflow(owner.workflowId) != owner) {
        recoveryRefusal(owner.ownedPath, "checkout ownership changed before access")
      } else {
        access()
      }
    }
  return when (result) {
    is OwningTransactionResult.Committed -> result.value
    is OwningTransactionResult.Refused ->
      database.transaction { unit ->
        when {
          unit.goalPlanningOwnership.findByWorkflow(owner.workflowId) != owner ->
            recoveryRefusal(owner.ownedPath, "checkout ownership changed before access")
          unit.goalPlanningOwnership.checkoutRecovery(owner) != null ->
            recoveryRefusal(owner.ownedPath, "recovery must commit before the enclosing writer transaction starts")
          else -> access()
        }
      }
  }
}
