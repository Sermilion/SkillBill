package skillbill.infrastructure.skills.install.reconcile

import skillbill.error.core.catchingNonCancellation
import skillbill.infrastructure.contracts.sha256HexOfFile
import skillbill.infrastructure.skills.install.identity.SKILL_CONTENT_IDENTITY_FILENAME
import skillbill.infrastructure.skills.install.identity.SkillContentIdentity
import skillbill.infrastructure.skills.install.identity.installedSkillSlug
import skillbill.infrastructure.skills.install.staging.installedSkillsCacheRoot
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

private const val CONTENT_FILENAME = "content.md"

/**
 * Compares a local skill's authored files with the staging copy the last install published under
 * `~/.skill-bill/installed-skills`. Any missing, unreadable, or ambiguous staging reads as edited,
 * so the caller keeps the local copy.
 */
internal fun installedStagingSourceReader(home: Path): PublishedSourceReader =
  PublishedSourceReader { sourceDir, authoredFiles ->
    catchingNonCancellation {
      val staging = latestPublishedStaging(home, sourceDir) ?: return@catchingNonCancellation false
      authoredFiles.all { file -> matchesStagedFile(sourceDir, staging, file) }
    }.getOrDefault(false)
  }

// shortcut: picks the newest staging published from this source dir instead of resolving the
// agent link that targets it; resolve through the owned link if several installs share a source.
private fun latestPublishedStaging(
  home: Path,
  sourceDir: Path,
): Path? {
  val cacheRoot = installedSkillsCacheRoot(home)
  if (!Files.isDirectory(cacheRoot, LinkOption.NOFOLLOW_LINKS)) return null
  val slugPrefix = installedSkillSlug(sourceDir) + "-"
  val canonicalSource = canonicalSourceIdentity(sourceDir)
  return Files.list(cacheRoot).use { stream ->
    stream
      .filter { dir -> dir.fileName.toString().startsWith(slugPrefix) }
      .filter { dir -> Files.isRegularFile(dir.resolve(SKILL_CONTENT_IDENTITY_FILENAME), LinkOption.NOFOLLOW_LINKS) }
      .filter { dir ->
        catchingNonCancellation { SkillContentIdentity.fromInstalled(dir).canonicalSourceIdentity }
          .getOrNull() == canonicalSource
      }
      .toList()
  }.maxByOrNull { dir -> Files.getLastModifiedTime(dir.resolve(SKILL_CONTENT_IDENTITY_FILENAME)) }
}

private fun matchesStagedFile(
  sourceDir: Path,
  staging: Path,
  file: Path,
): Boolean {
  val relative = sourceDir.relativize(file)
  if (relative.toString() == CONTENT_FILENAME) {
    return SkillContentIdentity.fromInstalled(staging).exactContentSha256 == sha256HexOfFile(file)
  }
  val staged = staging.resolve(relative).normalize()
  return staged.startsWith(staging) &&
    Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS) &&
    Files.readAllBytes(staged).contentEquals(Files.readAllBytes(file))
}

private fun canonicalSourceIdentity(sourceDir: Path): String {
  val source = sourceDir.toAbsolutePath().normalize()
  return try {
    source.toRealPath().toString()
  } catch (_: IOException) {
    source.toString()
  }
}
