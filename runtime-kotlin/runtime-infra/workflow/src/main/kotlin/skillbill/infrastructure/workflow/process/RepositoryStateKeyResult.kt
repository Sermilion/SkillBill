package skillbill.infrastructure.workflow.process

import skillbill.infrastructure.contracts.sha256Hex
import java.io.IOException
import java.nio.file.Path

private const val REPO_KEY_DIGEST_LENGTH = 16
private const val UNNAMED_REPOSITORY = "repo"
private val REPOSITORY_NAME_UNSAFE = Regex("[^A-Za-z0-9._-]")

internal sealed interface RepositoryStateKeyResult {
  data class Resolved(val key: String) : RepositoryStateKeyResult

  data class NotAWorkingTree(val detail: String) : RepositoryStateKeyResult

  data class CommonDirectoryUnresolved(
    val commonDirectory: Path,
    val failure: IOException,
  ) : RepositoryStateKeyResult
}

internal fun repositoryStateKey(repoRoot: Path): RepositoryStateKeyResult {
  val probe = runGitProcess(repoRoot, listOf("rev-parse", "--git-common-dir"))
  val output = probe.output.trim()
  val probeFailed = probe.timedOut || probe.readFailure != null || probe.exitCode != 0
  if (probeFailed || output.isEmpty()) {
    return RepositoryStateKeyResult.NotAWorkingTree(probe.readFailure?.message ?: output)
  }
  val commonDirectory = repoRoot.toAbsolutePath().resolve(output).normalize()
  return try {
    RepositoryStateKeyResult.Resolved(repoKey(commonDirectory.toRealPath()))
  } catch (failure: IOException) {
    RepositoryStateKeyResult.CommonDirectoryUnresolved(commonDirectory, failure)
  }
}

private fun repoKey(gitCommonDir: Path): String {
  val name =
    REPOSITORY_NAME_UNSAFE.replace(gitCommonDir.parent?.fileName?.toString().orEmpty(), "-")
      .ifEmpty { UNNAMED_REPOSITORY }
  return "$name-${sha256Hex(gitCommonDir.toString()).take(REPO_KEY_DIGEST_LENGTH)}"
}
