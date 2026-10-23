package skillbill.infrastructure.host.projectstore

import skillbill.ports.projectstore.model.RepositoryLineage
import java.nio.file.Path
import java.security.MessageDigest

internal fun lineageProjectId(lineage: RepositoryLineage): String =
  "l-" + hash16(lineage.rootCommits.sorted().joinToString("\n"))

internal fun noCommitsProjectId(commonDir: Path): String = "p-" + hash16(commonDir.toString())

internal fun nonGitProjectId(directory: Path): String = "d-" + hash16(directory.toString())

internal fun lineageCandidates(
  stores: List<StoredProject>,
  lineage: RepositoryLineage,
): List<StoredProject> {
  val sharing =
    stores.filter { store ->
      store.record.keyKind == ProjectKeyKind.LINEAGE &&
        store.record.linkedTo == null &&
        store.record.rootCommits.any(lineage.rootCommits::contains)
    }
  val remoteUrl = lineage.remoteUrl
  if (sharing.size < 2 || remoteUrl == null) return sharing
  return sharing.filter { it.record.remoteUrl == remoteUrl }.singleOrNull()?.let(::listOf) ?: sharing
}

private fun hash16(text: String): String =
  MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
    .take(HASH_PREFIX_LENGTH)

private const val HASH_PREFIX_LENGTH = 16
