package skillbill.infrastructure.skills.agentaddon

import skillbill.agentaddon.model.AgentAddonCatalogueEntry
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.error.shellcontent.agentAddonPointerCollision
import skillbill.error.shellcontent.invalidAgentAddonDeliveryTarget
import skillbill.model.toPath
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal data class AgentAddonPointer(
  val consumer: AgentAddonConsumer,
  val slug: String,
  val name: String,
  val manifestRelativePath: String,
  val contentRelativePath: String,
  val target: Path,
  val manifestBytes: ByteArray,
  val contentBytes: ByteArray,
  val renderedBytes: ByteArray,
)

class AgentAddonDeliveryResolver {
  internal fun resolve(
    repoRoot: Path,
    consumer: AgentAddonConsumer,
  ): List<AgentAddonPointer> {
    val canonicalRoot = repoRoot.toRealPath()
    val pointers =
      discoverAgentAddons(canonicalRoot)
        .filter { consumer in it.consumers }
        .sortedBy { it.slug }
        .map { declaration ->
          val manifest = validateTarget(canonicalRoot, declaration.slug, declaration.manifestPath.toPath())
          val content = validateTarget(canonicalRoot, declaration.slug, declaration.contentPath.toPath())
          val name = "agent-addon-${declaration.slug}.md"
          if (canonicalRoot.relativize(content).toString().replace(File.separatorChar, '/') == name) {
            throw invalidAgentAddonDeliveryTarget(
              declaration.slug,
              content.toString(),
              "self-reference is forbidden",
            )
          }
          val contentBytes = Files.readAllBytes(content)
          AgentAddonPointer(
            consumer = consumer,
            slug = declaration.slug,
            name = name,
            manifestRelativePath = relative(canonicalRoot, manifest),
            contentRelativePath = relative(canonicalRoot, content),
            target = content,
            manifestBytes = Files.readAllBytes(manifest),
            contentBytes = contentBytes,
            renderedBytes = normalizeMarkdown(contentBytes),
          )
        }
    pointers.groupBy { portableFileName(it.name) }.values.firstOrNull { it.size > 1 }?.let {
      throw agentAddonPointerCollision(it.first().name)
    }
    return pointers
  }

  fun catalogue(repoRoot: Path): List<AgentAddonCatalogueEntry> =
    discoverAgentAddons(repoRoot).map { declaration ->
      declaration.toCatalogueEntry()
    }

  private fun validateTarget(
    root: Path,
    slug: String,
    path: Path,
  ): Path {
    requireValidTarget(
      !path.isAbsolute || path.toAbsolutePath().normalize().startsWith(root),
      slug,
      path,
      "absolute path escapes repository root",
    )
    val normalized = path.toAbsolutePath().normalize()
    requireValidTarget(normalized.startsWith(root), slug, path, "path escapes repository root")
    requireValidTarget(
      Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS),
      slug,
      path,
      "target must be a regular repository file",
    )
    val real = normalized.toRealPath()
    requireValidTarget(real.startsWith(root), slug, path, "real path escapes repository root")
    return real
  }

  private fun requireValidTarget(
    valid: Boolean,
    slug: String,
    path: Path,
    reason: String,
  ) {
    if (!valid) throw invalidAgentAddonDeliveryTarget(slug, path.toString(), reason)
  }

  private fun relative(
    root: Path,
    path: Path,
  ): String =
    root.relativize(path).normalize().toString()
      .replace(File.separatorChar, '/')

  private fun normalizeMarkdown(bytes: ByteArray): ByteArray {
    val text = bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n')
    return (text.trimEnd() + "\n").toByteArray(StandardCharsets.UTF_8)
  }
}
