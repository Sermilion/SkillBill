package dev.skillbill.tray.infrastructure.data

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Names a repository after its main checkout, so work run in a linked worktree is not titled by the worktree folder. */
internal object RepositoryNames {
  private val names = ConcurrentHashMap<String, String>()

  fun nameOf(repositoryRoot: String): String = names.getOrPut(repositoryRoot) { resolve(repositoryRoot) }

  internal fun resolve(repositoryRoot: String): String {
    val root = runCatching { Path.of(repositoryRoot) }.getOrNull()
    val main = root?.let(::mainCheckout)
    return (main ?: root)?.fileName?.toString() ?: repositoryRoot
  }

  private fun mainCheckout(root: Path): Path? = runCatching {
    val marker = root.resolve(".git")
    if (!Files.isRegularFile(marker)) return null
    val gitDir = Files.readAllLines(marker).firstOrNull { it.startsWith("gitdir:") }
      ?.removePrefix("gitdir:")?.trim()?.takeIf(String::isNotEmpty)
      ?.let { root.resolve(it).normalize() } ?: return null
    val commonDir = gitDir.resolve("commondir").takeIf(Files::isRegularFile)
      ?.let { gitDir.resolve(Files.readString(it).trim()).normalize() } ?: return null
    commonDir.takeIf { it.fileName?.toString() == ".git" }?.parent
  }.getOrNull()
}
