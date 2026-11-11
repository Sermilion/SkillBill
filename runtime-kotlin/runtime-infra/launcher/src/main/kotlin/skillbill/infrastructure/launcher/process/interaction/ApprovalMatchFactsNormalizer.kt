package skillbill.infrastructure.launcher.process.interaction

import skillbill.approval.model.ApprovalMatchFacts
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

internal object ApprovalMatchFactsNormalizer {
  fun commandFacts(text: String?): ApprovalMatchFacts.Command? =
    text?.trim()?.takeIf { it.isNotEmpty() }?.let(ApprovalMatchFacts::Command)

  fun pathFacts(
    checkoutRoot: String,
    base: String,
    raw: List<String>,
  ): ApprovalMatchFacts.Paths? {
    if (raw.isEmpty() || raw.any { it.isBlank() }) return null
    return try {
      relativizeAll(Path.of(checkoutRoot).toRealPath(), base, raw)
    } catch (_: IOException) {
      null
    } catch (_: InvalidPathException) {
      null
    }
  }

  fun withinCheckout(
    checkoutRoot: String,
    directory: String,
  ): Boolean =
    try {
      val root = Path.of(checkoutRoot).toRealPath()
      resolveAgainst(root, directory).toRealPath().startsWith(root)
    } catch (_: IOException) {
      false
    } catch (_: InvalidPathException) {
      false
    }

  private fun relativizeAll(
    root: Path,
    base: String,
    raw: List<String>,
  ): ApprovalMatchFacts.Paths? {
    val baseDir = resolveAgainst(root, base)
    val relative = raw.map { entry -> relativePath(root, baseDir, entry) ?: return null }
    return ApprovalMatchFacts.Paths(relative.distinct())
  }

  private fun relativePath(
    root: Path,
    baseDir: Path,
    entry: String,
  ): String? {
    var target = Path.of(entry)
    if (target.isAbsolute.not()) {
      target = baseDir.resolve(target)
    }
    var existing = target
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
      existing = existing.parent ?: return null
    }
    val real =
      if (existing == target) {
        existing.toRealPath()
      } else {
        val tail = target.subpath(existing.nameCount, target.nameCount)
        if (tail.any { it.toString() == ".." }) return null
        existing.toRealPath().resolve(tail).normalize()
      }
    if (!real.startsWith(root) || real == root) return null
    return root.relativize(real).joinToString("/") { it.toString() }
  }

  private fun resolveAgainst(
    root: Path,
    path: String,
  ): Path {
    val resolved = Path.of(path)
    return if (resolved.isAbsolute) resolved else root.resolve(resolved)
  }
}
