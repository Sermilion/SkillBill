package skillbill.infrastructure.workflow.filesystem

import me.tatarka.inject.annotations.Inject
import skillbill.ports.system.CheckedOutBranchSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

@Inject
class FileSystemCheckedOutBranchSource : CheckedOutBranchSource {
  override fun checkedOutBranch(repoRoot: Path): String? {
    val gitDir = resolveGitDir(repoRoot) ?: return null
    val head = readHead(gitDir.resolve("HEAD"))?.trim()
    return head
      ?.takeIf { it.startsWith(HEAD_REF_PREFIX) }
      ?.removePrefix(HEAD_REF_PREFIX)
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
  }

  private fun resolveGitDir(repoRoot: Path): Path? {
    val marker = repoRoot.resolve(".git")
    return when {
      Files.isDirectory(marker) -> marker

      Files.isRegularFile(marker) -> gitDirPointerTarget(repoRoot, marker)
      else -> null
    }
  }

  private fun gitDirPointerTarget(
    repoRoot: Path,
    marker: Path,
  ): Path? {
    val target =
      readHead(marker)
        ?.lineSequence()
        ?.firstOrNull { it.startsWith(GITDIR_PREFIX) }
        ?.removePrefix(GITDIR_PREFIX)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val path = Path.of(target)
    return if (path.isAbsolute) path else repoRoot.resolve(path).normalize()
  }

  private fun readHead(path: Path): String? {
    if (!Files.isRegularFile(path)) return null
    return try {
      Files.readString(path)
    } catch (error: IOException) {
      checkedOutBranchLog.warning("checked-out branch probe could not read $path: ${error.message}")
      null
    }
  }

  private companion object {
    const val GITDIR_PREFIX = "gitdir:"
    const val HEAD_REF_PREFIX = "ref: refs/heads/"
  }
}

private val checkedOutBranchLog: Logger =
  Logger.getLogger("skillbill.infrastructure.workflow.filesystem.FileSystemCheckedOutBranchSource")
