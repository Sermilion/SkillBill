package skillbill.infrastructure.workflow.goalworktree

import skillbill.infrastructure.workflow.worktree.worktreeGit
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.model.GoalPlanningOwnership
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

internal const val OWNERSHIP_MARKER = "skill-bill-ownership"
private const val OWNERSHIP_MARKER_LINES = 7
private const val OWNERSHIP_FILE_KEY_LINE = 4
private val UNIX_FILE_KEY = Regex("\\(dev=[0-9a-fA-F]+,ino=([0-9]+)\\)")

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

internal fun verifiedCheckoutIdentity(
  path: Path,
  ownership: GoalPlanningOwnership,
  diagnostics: RuntimeDiagnostics,
): String? {
  verifiedIdentity(path, ownership)?.let { return it }
  return try {
    val identity = readWorktreeGitIdentity(path) ?: return null
    val marker = Path.of(identity).resolve(OWNERSHIP_MARKER)
    val matches =
      identity == ownership.gitWorktreeIdentity &&
        Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) &&
        checkoutMarkerMatches(Files.readString(marker), markerValue(path, ownership, identity))
    if (matches) {
      diagnostics.warning(
        "goal checkout device identity changed: seam=goal_worktree_ownership path=$path " +
          "outcome=adopted_matching_inode_birth_and_registration; original marker retained; " +
          "cleanup requires exact identity",
      )
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

private fun checkoutMarkerMatches(
  recorded: String,
  expected: String?,
): Boolean {
  val expectedLines = expected?.lines() ?: return false
  val recordedLines = recorded.lines()
  if (expectedLines.size != OWNERSHIP_MARKER_LINES || recordedLines.size != expectedLines.size) return false
  val expectedInode =
    UNIX_FILE_KEY.matchEntire(expectedLines[OWNERSHIP_FILE_KEY_LINE])?.groupValues?.get(1)
      ?: return false
  val recordedInode = UNIX_FILE_KEY.matchEntire(recordedLines[OWNERSHIP_FILE_KEY_LINE])?.groupValues?.get(1)
  return expectedInode == recordedInode &&
    expectedLines.indices.all {
      it == OWNERSHIP_FILE_KEY_LINE || expectedLines[it] == recordedLines[it]
    }
}
