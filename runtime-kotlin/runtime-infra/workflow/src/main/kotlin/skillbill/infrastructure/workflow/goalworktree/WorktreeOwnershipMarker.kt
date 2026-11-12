package skillbill.infrastructure.workflow.goalworktree

import skillbill.infrastructure.workflow.worktree.worktreeGit
import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

internal const val OWNERSHIP_MARKER = "skill-bill-ownership"

internal fun readWorktreeGitIdentity(path: Path): String? =
  try {
    val result = worktreeGit(path, "rev-parse", "--path-format=absolute", "--git-dir")
    if (!result.ok) null else Path.of(result.output.trim()).toRealPath().toString()
  } catch (_: IOException) {
    null
  } catch (_: SecurityException) {
    null
  }

private fun markerValue(
  path: Path,
  ownership: GoalPlanningOwnership,
  identity: String,
): String? {
  val directoryIdentity = removalIdentity(path, identity) ?: return null
  return listOf(
    ownership.reservationId,
    ownership.repositoryIdentity,
    ownership.ownedPath.toAbsolutePath().normalize().toString(),
    ownership.initialCommit,
    directoryIdentity,
  ).joinToString("\n")
}

internal fun writeOwnershipMarker(
  path: Path,
  ownership: GoalPlanningOwnership,
): Boolean {
  return try {
    val identity = readWorktreeGitIdentity(path) ?: return false
    val value = markerValue(path, ownership, identity) ?: return false
    Files.writeString(Path.of(identity).resolve(OWNERSHIP_MARKER), value, CREATE_NEW, WRITE)
    verifiedIdentity(path, ownership) == identity
  } catch (_: IOException) {
    false
  } catch (_: SecurityException) {
    false
  }
}

internal fun verifiedIdentity(
  path: Path,
  ownership: GoalPlanningOwnership,
): String? {
  return try {
    val identity = readWorktreeGitIdentity(path) ?: return null
    val marker = Path.of(identity).resolve(OWNERSHIP_MARKER)
    val expected = markerValue(path, ownership, identity) ?: return null
    if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) && Files.readString(marker) == expected) {
      identity
    } else {
      null
    }
  } catch (_: IOException) {
    null
  } catch (_: SecurityException) {
    null
  }
}
