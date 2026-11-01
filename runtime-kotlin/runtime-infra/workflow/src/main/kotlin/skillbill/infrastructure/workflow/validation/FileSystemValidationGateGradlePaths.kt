package skillbill.infrastructure.workflow.validation

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal object FileSystemValidationGateGradlePaths {
  fun repoRelativeQualityPath(
    repo: Path,
    rawPath: String,
  ): String {
    val diagnosticPath = Path.of(rawPath.removePrefix("file://"))
    val canonicalRepo = canonicalizeExisting(repo)
    return repoRelativeCompilerPath(repo, canonicalRepo, diagnosticPath, rawPath)
  }

  fun repoRelativeCompilerPath(
    repo: Path,
    canonicalRepo: Path,
    diagnosticPath: Path,
    rawPath: String,
  ): String {
    val absolute = diagnosticPath.toAbsolutePath().normalize()
    val canonicalFile = canonicalizeMaybeMissing(absolute)
    if (canonicalFile.startsWith(canonicalRepo)) {
      return canonicalRepo.relativize(canonicalFile).toString().replace('\\', '/')
    }
    if (absolute.startsWith(repo)) {
      return repo.relativize(absolute).toString().replace('\\', '/')
    }
    return rawPath.replace('\\', '/').removePrefix("/")
  }

  fun isProjectOwnedPath(
    repo: Path,
    rawPath: String,
  ): Boolean {
    val normalizedRepo = repo.toAbsolutePath().normalize()
    val canonicalRepo = canonicalizeExisting(normalizedRepo)
    val parsed = Path.of(rawPath.removePrefix("file://"))
    val absolute = if (parsed.isAbsolute) parsed.normalize() else normalizedRepo.resolve(parsed).normalize()
    return canonicalizeMaybeMissing(absolute).startsWith(canonicalRepo)
  }

  fun canonicalizeExisting(path: Path): Path =
    try {
      path.toRealPath()
    } catch (_: IOException) {
      path
    }

  fun parseGradleTaskPath(taskPath: String): Pair<String, String> {
    val segments = taskPath.trim(':').split(':').filter { it.isNotEmpty() }
    return when (segments.size) {
      0 -> "" to ""
      1 -> "" to segments[0]
      else -> segments.dropLast(1).joinToString(":") to segments.last()
    }
  }

  fun filePathFromAdviceBlock(line: String): String? {
    val match = Regex("""(?:file://)?(/[^\s:]+\.(?:kt|kts|java|gradle))""").find(line) ?: return null
    return match.groupValues[1]
  }

  fun repoRelativeAdvicePath(rawPath: String): String = rawPath.removePrefix("file://").trimStart('/')

  private fun canonicalizeMaybeMissing(path: Path): Path {
    if (Files.exists(path)) {
      return canonicalizeExisting(path)
    }
    val tail = ArrayDeque<Path>()
    var current: Path? = path
    while (current != null && !Files.exists(current)) {
      current.fileName?.let(tail::addFirst)
      current = current.parent
    }
    val realBase = current?.let(::canonicalizeExisting) ?: return path
    return tail.fold(realBase) { acc, name -> acc.resolve(name) }
  }
}
