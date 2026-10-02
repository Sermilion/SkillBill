package skillbill.infrastructure.skills.scaffold.pointer

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest
import skillbill.model.toPath
import skillbill.scaffold.model.PlatformManifest
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo

private const val POINTER_FILE_MAX_BYTES: Long = 500L
private val POINTER_CONTENT_PATTERN: Regex = Regex("^(\\.{1,2}/)+[^\\s]+\\.md$")

internal data class PointerValidationReport(
  val issues: List<String>,
) {
  val passed: Boolean = issues.isEmpty()
}

internal fun validatePlatformPackPointers(repoRoot: Path): PointerValidationReport {
  val resolvedRepoRoot = repoRoot.toAbsolutePath().normalize()
  val packsRoot = resolvedRepoRoot.resolve("platform-packs")
  if (!Files.isDirectory(packsRoot)) {
    return PointerValidationReport(emptyList())
  }
  val packs = loadValidPlatformPackManifests(packsRoot)
  val issues = mutableListOf<String>()
  val declaredFiles = mutableSetOf<Path>()
  packs.forEach { pack ->
    validatePackPointersDriftAndMissing(resolvedRepoRoot, pack, declaredFiles, issues)
  }
  validatePackPointersOrphans(resolvedRepoRoot, packsRoot, declaredFiles, issues)
  return PointerValidationReport(issues.sorted())
}

private fun loadValidPlatformPackManifests(packsRoot: Path): List<PlatformManifest> {
  val packDirs =
    Files.list(packsRoot).use { stream ->
      stream
        .filter { it.isDirectory() && !it.fileName.toString().startsWith(".") }
        .toList()
    }
  return packDirs.mapNotNull { dir -> tryLoadPlatformManifest(dir) }
}

private fun tryLoadPlatformManifest(dir: Path): PlatformManifest? =
  try {
    loadPlatformManifest(dir)
  } catch (error: SkillBillRuntimeException) {
    error.rethrowUnless(error.isShellContentContractFailure())
    null
  }

private fun validatePackPointersDriftAndMissing(
  repoRoot: Path,
  pack: PlatformManifest,
  declaredFiles: MutableSet<Path>,
  issues: MutableList<String>,
) {
  pack.pointers.forEach { spec ->
    val pointerFile = pack.packRoot.resolve(spec.skillRelativeDir).resolve(spec.name).toPath().normalize()
    declaredFiles.add(pointerFile)

    val pointerExists =
      Files.isSymbolicLink(pointerFile) ||
        Files.isRegularFile(pointerFile, LinkOption.NOFOLLOW_LINKS)
    if (!pointerExists) {
      issues += "${displayPointer(repoRoot, pointerFile)}: declared pointer is missing on disk"
      return@forEach
    }
    runCatching { renderPointer(repoRoot, pack.packRoot.toPath(), spec) }
      .onFailure { error ->
        issues += "${displayPointer(repoRoot, pointerFile)}: cannot render pointer: ${error.message.orEmpty()}"
      }
      .onSuccess { rendered ->

        val expected = rendered.trimEnd('\n', '\r')

        val actual =
          if (Files.isSymbolicLink(pointerFile)) {
            Files.readSymbolicLink(pointerFile).toString().replace(File.separatorChar, '/')
          } else {
            Files.readString(pointerFile).trimEnd('\n', '\r')
          }
        if (expected != actual) {
          issues += "${displayPointer(repoRoot, pointerFile)}: pointer drifted from manifest " +
            "(expected '$expected', found '$actual')"
        }
      }
  }
}

private fun validatePackPointersOrphans(
  repoRoot: Path,
  packsRoot: Path,
  declaredFiles: Set<Path>,
  issues: MutableList<String>,
) {
  val packDirs =
    Files.list(packsRoot).use { stream ->
      stream
        .filter { it.isDirectory() && !it.fileName.toString().startsWith(".") }
        .toList()
    }
  packDirs.forEach { packDir ->
    discoverPointerCandidates(packDir).forEach { candidate ->
      reportIfOrphan(repoRoot, candidate, declaredFiles, issues)
    }
  }
}

private fun discoverPointerCandidates(packDir: Path): List<Path> {
  val resolvedPackDir = packDir.toAbsolutePath().normalize()
  Files.walk(packDir).use { stream ->
    return stream
      .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(it) }
      .filter { it.fileName.toString().endsWith(".md") }
      .filter { !isInsideExcludedSubtree(resolvedPackDir, it) }
      .filter { looksLikePointerFile(it) }
      .toList()
  }
}

private fun isInsideExcludedSubtree(
  packDir: Path,
  candidate: Path,
): Boolean {
  val resolvedCandidate = candidate.toAbsolutePath().normalize()
  val relative = if (resolvedCandidate.startsWith(packDir)) packDir.relativize(resolvedCandidate) else null
  val firstSegment = relative?.takeIf { it.nameCount > 0 }?.getName(0)?.toString()
  return firstSegment == "addons" || firstSegment == "native-agents"
}

private fun reportIfOrphan(
  repoRoot: Path,
  candidate: Path,
  declaredFiles: Set<Path>,
  issues: MutableList<String>,
) {
  val resolved = candidate.normalize()
  if (resolved !in declaredFiles) {
    issues += "${displayPointer(repoRoot, resolved)}: orphan pointer file is not declared in any " +
      "platform.yaml 'pointers:' block"
  }
}

private fun looksLikePointerFile(path: Path): Boolean {
  if (Files.isSymbolicLink(path)) {
    return true
  }
  val size = runCatching { Files.size(path) }.getOrNull() ?: 0L
  val text =
    if (size in 1..POINTER_FILE_MAX_BYTES) {
      runCatching { Files.readString(path) }.getOrNull().orEmpty()
    } else {
      ""
    }
  return text.isNotEmpty() &&
    text.count { it == '\n' } <= 1 &&
    POINTER_CONTENT_PATTERN.matches(text.trim())
}

private fun displayPointer(
  repoRoot: Path,
  path: Path,
): String {
  val resolvedRoot = repoRoot.toAbsolutePath().normalize()
  val resolvedPath = path.toAbsolutePath().normalize()
  return runCatching { resolvedPath.relativeTo(resolvedRoot).toString().replace('\\', '/') }
    .getOrDefault(resolvedPath.toString())
}
