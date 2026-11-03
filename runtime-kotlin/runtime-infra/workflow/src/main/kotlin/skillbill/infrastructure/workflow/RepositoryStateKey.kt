package skillbill.infrastructure.workflow

import skillbill.infrastructure.contracts.sha256Hex
import java.nio.file.Path

private const val REPO_KEY_DIGEST_LENGTH = 16
private const val UNNAMED_REPOSITORY = "repo"
private val REPOSITORY_NAME_UNSAFE = Regex("[^A-Za-z0-9._-]")

internal fun repositoryStateKey(gitCommonDir: Path): String {
  val name =
    REPOSITORY_NAME_UNSAFE.replace(gitCommonDir.parent?.fileName?.toString().orEmpty(), "-")
      .ifEmpty { UNNAMED_REPOSITORY }
  return "$name-${sha256Hex(gitCommonDir.toString()).take(REPO_KEY_DIGEST_LENGTH)}"
}
