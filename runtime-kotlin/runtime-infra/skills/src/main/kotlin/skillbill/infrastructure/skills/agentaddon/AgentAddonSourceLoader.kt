package skillbill.infrastructure.skills.agentaddon

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.agentaddon.model.AgentAddonCatalogueEntry
import skillbill.agentaddon.model.AgentAddonCatalogueInspection
import skillbill.agentaddon.model.AgentAddonDeclaration
import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.missingAgentAddonDeclaration
import skillbill.install.model.SupportedAgent
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.repository.toFileLocation
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name

private const val AGENT_ADDONS_DIRECTORY = "agent-addons"
private const val MANIFEST_FILE = "agent-addon.yaml"
private const val CONTENT_FILE = "content.md"
private val allowedEntries = setOf(MANIFEST_FILE, CONTENT_FILE)

fun discoverAgentAddons(
  repoRoot: Path,
  externalSourceRoots: List<Path> = emptyList(),
  schemaValidator: AgentAddonSchemaValidator = AgentAddonSchemaValidator(),
  diagnostics: RuntimeDiagnostics? = null,
): List<AgentAddonDeclaration> {
  val repoAddonRoot = repoRoot.toAbsolutePath().normalize().resolve(AGENT_ADDONS_DIRECTORY)
  val sourceRoots =
    listOf(AgentAddonSourceRoot(repoAddonRoot, required = false)) +
      externalSourceRoots.map { root -> AgentAddonSourceRoot(root.toAbsolutePath().normalize(), required = true) }
  val candidates =
    sourceRoots.flatMap { sourceRoot ->
      discoverAgentAddonRoot(sourceRoot, schemaValidator, diagnostics)
    }
  validateSourceCoherence(candidates)
  return candidates.sortedBy { it.slug }
}

fun inspectAgentAddons(
  repoRoot: Path,
  externalSourceRoots: List<Path> = emptyList(),
  schemaValidator: AgentAddonSchemaValidator = AgentAddonSchemaValidator(),
): AgentAddonCatalogueInspection {
  val roots = agentAddonInspectionRoots(repoRoot, externalSourceRoots)
  val (entries, invalidEntries) = collectAgentAddonCatalogueEntries(roots, schemaValidator)
  reconcileAgentAddonCatalogueIncoherence(entries, invalidEntries)
  return AgentAddonCatalogueInspection(entries.sortedBy { it.slug }, invalidEntries.sortedBy { it.slug })
}

internal fun AgentAddonDeclaration.toCatalogueEntry() =
  AgentAddonCatalogueEntry(
    identity = "agent-addon:$slug",
    slug = slug,
    description = description,
    agentIds = agents,
    consumers = consumers.map { it.id },
    manifestPath = manifestPath,
    contentPath = contentPath,
  )

fun requireAgentAddon(
  repoRoot: Path,
  slug: String,
): AgentAddonDeclaration =
  discoverAgentAddons(repoRoot).firstOrNull { it.slug == slug }
    ?: throw missingAgentAddonDeclaration(slug, repoRoot.resolve(AGENT_ADDONS_DIRECTORY).toString())

private fun discoverAgentAddonRoot(
  sourceRoot: AgentAddonSourceRoot,
  schemaValidator: AgentAddonSchemaValidator,
  diagnostics: RuntimeDiagnostics?,
): List<AgentAddonDeclaration> {
  val rootLabel = sourceRoot.path.toString()
  return sourceOperation(rootLabel, "agent add-on root cannot be read") {
    val rootAttributes =
      try {
        Files.readAttributes(sourceRoot.path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
      } catch (_: NoSuchFileException) {
        null
      }
        ?: if (sourceRoot.required) {
          invalid(rootLabel, "agent add-on root must exist")
        } else {
          return@sourceOperation emptyList()
        }
    if (!rootAttributes.isDirectory) {
      invalid(rootLabel, "agent add-on root must be a directory")
    }
    val sourceDirectories =
      Files.list(sourceRoot.path).use { stream ->
        stream.filter { !it.name.startsWith(".") }.sorted().toList()
      }
    sourceDirectories.map { parseSource(it, schemaValidator, diagnostics) }
  }
}

internal fun parseSource(
  sourceRoot: Path,
  validator: AgentAddonSchemaValidator,
  diagnostics: RuntimeDiagnostics? = null,
): AgentAddonDeclaration {
  val manifest = sourceRoot.resolve(MANIFEST_FILE)
  val content = sourceRoot.resolve(CONTENT_FILE)
  val sourceLabel = manifest.toString()
  return sourceOperation(sourceLabel, "manifest cannot be parsed") {
    if (!Files.isDirectory(sourceRoot)) invalid(sourceLabel, "source entry must be a directory")
    requireRegularFile(manifest, sourceLabel)
    requireRegularFile(content, sourceLabel)
    val extras = unexpectedEntries(sourceRoot)
    if (extras.isNotEmpty()) {
      invalid(
        sourceLabel,
        "only $MANIFEST_FILE and $CONTENT_FILE are allowed; found ${extras.joinToString()}",
      )
    }
    val parsed: Any? = YAMLMapper().readValue(Files.readString(manifest), Any::class.java)
    if (parsed !is Map<*, *>) invalid(sourceLabel, "manifest must be a top-level mapping")
    val values = requireNotNull(JsonCodec.anyToStringAnyMap(parsed))
    validator.validate(values, sourceLabel)
    val slug = values.string("slug", sourceLabel)
    val description = values.string("description", sourceLabel)
    val violations = mutableListOf<String>()
    if (!description.isValidDescription()) {
      violations += "description must be non-blank, trimmed, and single-line"
    }
    val agentIds = values.stringList("agent_ids", sourceLabel)
    val agents =
      agentIds.map { id ->
        runCatching { SupportedAgent.parseAgentAddonId(id) }.getOrElse {
          violations += "unknown agent id '$id'; supported: ${SupportedAgent.supportedIds.joinToString()}"
          null
        }
      }.filterNotNull()
    val consumerIds = values.stringList("consumers", sourceLabel)
    val consumers =
      consumerIds.map { id ->
        runCatching { decodeAgentAddonConsumer(id, AGENT_ADDON_DECLARED_CONSUMER_SEAM, diagnostics) }.getOrElse {
          violations += it.message ?: "unknown consumer '$id'"
          null
        }
      }.filterNotNull().distinct()
    if (violations.isNotEmpty()) invalid(sourceLabel, violations.joinToString("; "))
    AgentAddonDeclaration(
      contractVersion = values.string("contract_version", sourceLabel),
      slug = slug,
      description = description,
      agents = agents.map(SupportedAgent::wireValue),
      consumers = consumers,
      addonRoot = sourceRoot.toFileLocation(),
      manifestPath = manifest.toFileLocation(),
      contentPath = content.toFileLocation(),
      canonicalSourceIdentity = manifest.toRealPath().toFileLocation(),
    )
  }
}

private fun validateSourceCoherence(candidates: List<AgentAddonDeclaration>) {
  val violations = mutableListOf<String>()
  candidates.filter { it.addonRoot.fileName != it.slug }.forEach { declaration ->
    violations += "${declaration.manifestPath}: source directory '${declaration.addonRoot.fileName}' " +
      "must match slug '${declaration.slug}'"
  }
  candidates.groupBy { it.slug }.filterValues { it.size > 1 }.forEach { (slug, declarations) ->
    violations += "duplicate slug '$slug' declared by " +
      declarations.joinToString { it.manifestPath.toString() }
  }
  candidates.groupBy { it.canonicalSourceIdentity }.filterValues { it.size > 1 }.forEach { (identity, _) ->
    violations += "duplicate canonical source identity '$identity'"
  }
  if (violations.isNotEmpty()) invalid(AGENT_ADDONS_DIRECTORY, violations.sorted().joinToString("; "))
}

private data class AgentAddonSourceRoot(
  val path: Path,
  val required: Boolean,
)

private fun unexpectedEntries(sourceRoot: Path): List<String> =
  Files.list(sourceRoot).use { stream ->
    stream.map { it.name }.filter { it !in allowedEntries }.sorted().toList()
  }

private fun String.isValidDescription(): Boolean {
  if (isBlank() || this != trim()) return false
  return '\n' !in this && '\r' !in this
}

private fun requireRegularFile(
  path: Path,
  sourceLabel: String,
) {
  if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
    invalid(sourceLabel, "${path.name} must be a regular file")
  }
}
