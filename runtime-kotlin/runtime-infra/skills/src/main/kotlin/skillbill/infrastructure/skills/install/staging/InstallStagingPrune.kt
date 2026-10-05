package skillbill.infrastructure.skills.install.staging

import skillbill.infrastructure.skills.install.staging.content.INSTALL_CACHE_KEY_BYTES
import skillbill.infrastructure.skills.install.staging.content.installedSkillNameSlug
import skillbill.infrastructure.skills.install.staging.content.installedSkillSlug
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private val pruneLog: Logger = Logger.getLogger("skillbill.install.InstallStaging")

internal fun pruneStaleStagingDirs(
  home: Path,
  resolvedSource: Path,
  currentHash: String,
) {
  val slug = installedSkillSlug(resolvedSource)
  if (slug.isEmpty()) {
    return
  }
  val currentLeaf = "$slug-$currentHash"
  val hashRegex = slugHashRegex(slug)
  pruneCacheDirs(installedSkillsCacheRoot(home), "pruneStaleStagingDirs") { name ->
    name.matches(hashRegex) && name != currentLeaf
  }
}

internal fun pruneLegacySkillCacheDirs(
  home: Path,
  legacySkillNames: List<String>,
  liveSkillNames: List<String>,
) {
  val liveSlugs = liveSkillNames.map(::installedSkillNameSlug).toSet()
  val legacyRegexes =
    legacySkillNames
      .map(::installedSkillNameSlug)
      .filter { slug -> slug.isNotEmpty() && slug !in liveSlugs }
      .distinct()
      .map(::slugHashRegex)
  if (legacyRegexes.isEmpty()) {
    return
  }
  pruneCacheDirs(installedSkillsCacheRoot(home), "pruneLegacySkillCacheDirs") { name ->
    legacyRegexes.any { regex -> name.matches(regex) }
  }
}

private fun slugHashRegex(slug: String): Regex =
  Regex("^${Regex.escape(slug)}-[0-9a-f]{${INSTALL_CACHE_KEY_BYTES * 2}}$")

private fun pruneCacheDirs(
  cacheRoot: Path,
  label: String,
  shouldDelete: (String) -> Boolean,
) {
  if (!Files.isDirectory(cacheRoot)) {
    return
  }
  val candidates =
    try {
      Files.list(cacheRoot).use { stream ->
        stream
          .filter { entry -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) }
          .filter { entry -> shouldDelete(entry.fileName.toString()) }
          .toList()
      }
    } catch (error: IOException) {
      pruneLog.log(Level.WARNING, "$label list failure cacheRoot=$cacheRoot", error)
      emptyList()
    }
  candidates.forEach { stale ->
    try {
      deleteInstallStagingDirectory(stale)
    } catch (error: IOException) {
      logDeleteFailure(label, stale, error)
    } catch (error: UncheckedIOException) {
      logDeleteFailure(label, stale, error)
    }
  }
}

private fun logDeleteFailure(
  label: String,
  stale: Path,
  error: Exception,
) {
  pruneLog.log(
    Level.WARNING,
    "$label delete failure dir=$stale (suppressed; install completed successfully)",
    error,
  )
}
