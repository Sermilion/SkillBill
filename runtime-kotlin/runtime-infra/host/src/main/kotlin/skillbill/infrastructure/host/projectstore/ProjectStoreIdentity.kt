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
  return sharing.filter { sameRemote(it.record.remoteUrl, remoteUrl) }.singleOrNull()?.let(::listOf) ?: sharing
}

internal fun sameRemote(
  first: String?,
  second: String?,
): Boolean {
  if (first == null || second == null) return false
  if (first == second) return true
  val key = remoteKey(first) ?: return false
  return key == remoteKey(second)
}

private fun remoteKey(url: String): String? {
  val trimmed = url.trim().removeSuffix("/").removeSuffix(".git")
  val scp = SCP_REMOTE.matchEntire(trimmed)
  val (authority, path) =
    when {
      scp != null -> scp.groupValues[1] to scp.groupValues[2]
      SCHEME_SEPARATOR in trimmed -> {
        val rest = trimmed.substringAfter(SCHEME_SEPARATOR)
        rest.substringBefore('/') to rest.substringAfter('/', "")
      }
      else -> return null
    }
  val host = authority.substringAfter('@').substringBefore(':').lowercase()
  return if (host.isEmpty() || path.isEmpty()) null else "$host/${path.trimStart('/')}"
}

private const val SCHEME_SEPARATOR = "://"

private val SCP_REMOTE = Regex("""((?:[^@/:]+@)?[^@/:]{2,}):(?!//)(.+)""")

private fun hash16(text: String): String =
  MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
    .take(HASH_PREFIX_LENGTH)

private const val HASH_PREFIX_LENGTH = 16
