package skillbill.infrastructure.skills.nativeagent.rendering

import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentSource
import skillbill.infrastructure.skills.nativeagent.platformpack.NativeAgentPlatformPack
import skillbill.infrastructure.skills.nativeagent.platformpack.NativeAgentPlatformPackLoader
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformPack
import skillbill.scaffold.model.PlatformManifest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.relativeTo

private const val FRONTMATTER_OPEN_LENGTH = 4

internal fun resolvePlatformManifestContentTarget(
  repoRoot: Path,
  packRoot: Path,
  sourcePath: Path,
  source: NativeAgentSource,
  packLoader: NativeAgentPlatformPackLoader,
): NativeAgentCompositionTarget? {
  val pack = packLoader.loadPlatformPack(packRoot)
  return declaredContentPaths(pack)
    .firstOrNull { path -> path.parent?.name == source.name }
    ?.also { contentPath ->
      require(Files.isRegularFile(contentPath)) {
        "${displayPath(repoRoot, sourcePath)}: native agent compose target is missing at " +
          "'${displayPath(repoRoot, contentPath)}'"
      }
    }
    ?.let { contentPath ->
      NativeAgentCompositionTarget(
        contentPath = contentPath,
        source = NativeAgentCompositionTargetSource.PlatformManifest,
        manifest = pack,
      )
    }
}

internal fun NativeAgentPlatformPackLoader.withAdditionalPackRoots(roots: List<Path>): NativeAgentPlatformPackLoader =
  if (roots.isEmpty()) {
    this
  } else {
    object : NativeAgentPlatformPackLoader by this {
      override fun loadPlatformPack(
        packRoot: Path,
        additionalPackRoots: List<Path>,
      ): NativeAgentPlatformPack =
        this@withAdditionalPackRoots.loadPlatformPack(packRoot, additionalPackRoots.ifEmpty { roots })
    }
  }

internal fun resolveSiblingContentTarget(
  sourcePath: Path,
  source: NativeAgentSource,
): NativeAgentCompositionTarget? =
  sourcePath.parent
    ?.parent
    ?.resolve("content.md")
    ?.toAbsolutePath()
    ?.normalize()
    ?.takeIf(Files::isRegularFile)
    ?.takeIf { contentPath -> readContentFrontmatterName(contentPath) == source.name }
    ?.let { contentPath ->
      NativeAgentCompositionTarget(
        contentPath = contentPath,
        source = NativeAgentCompositionTargetSource.SiblingContent,
      )
    }

internal fun platformPackRoot(
  repoRoot: Path,
  sourcePath: Path,
  additionalPackRoots: List<Path> = emptyList(),
): Path? {
  val packRoots = listOf(repoRoot.resolve("platform-packs")) + additionalPackRoots
  val canonicalSourcePath = canonicalize(sourcePath)
  return packRoots.firstNotNullOfOrNull { root ->
    val canonicalRoot = canonicalize(root)
    if (!canonicalSourcePath.startsWith(canonicalRoot)) {
      null
    } else if (root in additionalPackRoots) {
      root
    } else {
      try {
        canonicalSourcePath.relativeTo(canonicalRoot)
      } catch (_: IllegalArgumentException) {
        null
      }
        ?.firstOrNull()
        ?.toString()
        ?.let(root::resolve)
    }
  }
}

internal fun displayPath(
  root: Path,
  path: Path,
): String {
  val resolvedRoot = root.toAbsolutePath().normalize()
  val resolvedPath = path.toAbsolutePath().normalize()
  return try {
    resolvedPath.relativeTo(resolvedRoot).toString()
  } catch (_: IllegalArgumentException) {
    resolvedPath.toString()
  }
}

private fun canonicalize(path: Path): Path {
  val normalized = path.toAbsolutePath().normalize()
  return try {
    normalized.toRealPath()
  } catch (_: IOException) {
    normalized
  }
}

private fun declaredContentPaths(pack: NativeAgentPlatformPack): List<Path> =
  listOfNotNull(pack.declaredFiles.baseline) +
    pack.declaredFiles.areas.values.sortedBy { it.toString() }

private fun readContentFrontmatterName(contentPath: Path): String? {
  val text = Files.readString(contentPath).replace("\r\n", "\n")
  val end = text.indexOf("\n---\n", startIndex = FRONTMATTER_OPEN_LENGTH)
  return if (!text.startsWith("---\n") || end < 0) {
    null
  } else {
    text.substring(FRONTMATTER_OPEN_LENGTH, end)
      .lineSequence()
      .firstOrNull { line -> line.startsWith("name:") }
      ?.substringAfter(':')
      ?.trim()
      ?.trim('"', '\'')
      ?.takeIf { it.isNotBlank() }
  }
}
