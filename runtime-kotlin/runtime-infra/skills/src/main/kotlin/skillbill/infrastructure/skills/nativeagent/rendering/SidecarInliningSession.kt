package skillbill.infrastructure.skills.nativeagent.rendering

import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentCompositionTarget
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentCompositionTargetSource
import skillbill.infrastructure.skills.nativeagent.composition.displayPath
import skillbill.infrastructure.skills.nativeagent.composition.invalidCompositionInput
import skillbill.infrastructure.skills.nativeagent.composition.platformPackRoot
import skillbill.infrastructure.skills.nativeagent.platformpack.NativeAgentPointerSpec
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.Path

private val LOCAL_MARKDOWN_LINK_PATTERN = Regex("""\[([^]\n]+)]\(([^)\s]+\.md(?:#[^)]*)?)\)""")

internal class SidecarInliningSession(
  repoRoot: Path,
  target: NativeAgentCompositionTarget,
  additionalPackRoots: List<Path> = emptyList(),
) {
  private val resolver = sidecarResolver(repoRoot, target, additionalPackRoots)
  private val inlined = linkedMapOf<Path, String>()
  private val claimed = linkedSetOf<Path>()

  fun claim(path: Path) {
    claimed.add(path)
  }

  fun rewrite(
    text: String,
    ownerPath: Path,
  ): String = rewriteMarkdownLinks(text, ownerPath, resolver, inlined, claimed)

  fun inlinedReferenceBlocks(): String =
    buildString {
      inlined.forEach { (path, text) ->
        append("\n\n")
        append("## Inlined Reference: ${path.fileName}")
        append("\n\n")
        append(text.trimEnd())
      }
    }
}

private fun rewriteMarkdownLinks(
  text: String,
  ownerPath: Path,
  resolver: MarkdownSidecarResolver,
  inlined: LinkedHashMap<Path, String>,
  claimed: LinkedHashSet<Path>,
): String =
  LOCAL_MARKDOWN_LINK_PATTERN.replace(text) { match ->
    val label = match.groupValues[1]
    val rawTarget = match.groupValues[2]
    val sidecarPath = resolver.resolve(rawTarget, ownerPath)
    if (claimed.add(sidecarPath)) {
      val sidecarText = normalizeMarkdownLineEndings(Files.readString(sidecarPath)).trimEnd()
      inlined[sidecarPath] = ""
      inlined[sidecarPath] = rewriteMarkdownLinks(sidecarText, sidecarPath, resolver, inlined, claimed)
    }
    label
  }

private fun sidecarResolver(
  repoRoot: Path,
  target: NativeAgentCompositionTarget,
  additionalPackRoots: List<Path>,
): MarkdownSidecarResolver =
  when (target.source) {
    NativeAgentCompositionTargetSource.PlatformManifest ->
      platformPointerSidecarResolver(repoRoot, target, additionalPackRoots)
    NativeAgentCompositionTargetSource.SiblingContent -> siblingMarkdownSidecarResolver(repoRoot, target.contentPath)
  }

private fun platformPointerSidecarResolver(
  repoRoot: Path,
  target: NativeAgentCompositionTarget,
  additionalPackRoots: List<Path> = emptyList(),
): MarkdownSidecarResolver {
  val root = repoRoot.toAbsolutePath().normalize()
  val contentPath = target.contentPath
  val packRoot =
    requireNotNull(
      platformPackRoot(root, contentPath.toAbsolutePath().normalize(), additionalPackRoots),
    ) {
      "${displayPath(root, contentPath)}: platform-pack native agent composition requires a platform.yaml manifest"
    }
  val pack =
    requireNotNull(target.manifest) {
      "${displayPath(root, contentPath)}: platform-pack native agent composition requires a parsed " +
        "platform.yaml manifest"
    }
  val skillRelativeDir = packRoot.relativize(contentPath.parent).toString().replace('\\', '/')
  val declared =
    pack.pointers
      .filter { pointer -> pointer.skillRelativeDir == skillRelativeDir }
      .associateBy(NativeAgentPointerSpec::name)
  return MarkdownSidecarResolver(root) { rawTarget, ownerPath ->
    val linkPath = linkPathWithoutFragment(rawTarget)
    if ('/' !in linkPath && '\\' !in linkPath) {
      val siblingPath = contentPath.parent.resolve(linkPath).toAbsolutePath().normalize()
      if (Files.isRegularFile(siblingPath)) {
        return@MarkdownSidecarResolver siblingPath
      }
    }
    val linkName = linkPath.substringAfterLast('/')
    val pointer =
      declared[linkName]
        ?: invalidCompositionInput(
          "${displayPath(root, ownerPath)}: local markdown link '$rawTarget' is not declared in " +
            "platform.yaml pointers for '$skillRelativeDir'",
        )
    val sidecarPath = root.resolve(pointer.target).toAbsolutePath().normalize()
    if (!Files.isRegularFile(sidecarPath)) {
      invalidCompositionInput(
        "${displayPath(root, ownerPath)}: declared markdown sidecar '$rawTarget' is missing at " +
          "'${displayPath(root, sidecarPath)}'",
      )
    }
    sidecarPath
  }
}

private fun siblingMarkdownSidecarResolver(
  repoRoot: Path,
  contentPath: Path,
): MarkdownSidecarResolver {
  val root = repoRoot.toAbsolutePath().normalize()
  val contentDir = contentPath.parent.toAbsolutePath().normalize()
  return MarkdownSidecarResolver(root) { rawTarget, ownerPath ->
    val linkPath = linkPathWithoutFragment(rawTarget)
    if ('/' in linkPath || '\\' in linkPath || ".." in linkPath) {
      invalidCompositionInput(
        "${displayPath(root, ownerPath)}: local markdown link '$rawTarget' must resolve to a sibling file",
      )
    }
    val sidecarPath = contentDir.resolve(linkPath).toAbsolutePath().normalize()
    if (!Files.isRegularFile(sidecarPath)) {
      invalidCompositionInput(
        "${displayPath(root, ownerPath)}: local markdown link '$rawTarget' is unresolved",
      )
    }
    sidecarPath
  }
}

private fun linkPathWithoutFragment(rawTarget: String): String = rawTarget.substringBefore('#')

private class MarkdownSidecarResolver(
  private val repoRoot: Path,
  private val resolvePath: (rawTarget: String, ownerPath: Path) -> Path,
) {
  fun resolve(
    rawTarget: String,
    ownerPath: Path,
  ): Path =
    resolvePath(rawTarget, ownerPath).also { path ->
      if (!Files.isRegularFile(path)) {
        invalidCompositionInput(
          "${displayPath(repoRoot, ownerPath)}: local markdown link '$rawTarget' is unresolved",
        )
      }
    }
}
