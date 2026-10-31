package skillbill.infrastructure.skills.agentaddon

import skillbill.agentaddon.model.AgentAddonCatalogueEntry
import skillbill.agentaddon.model.InvalidAgentAddonCatalogueEntry
import skillbill.error.core.catchingNonCancellation
import skillbill.model.FileLocation
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

internal fun collectAgentAddonCatalogueEntries(
  roots: List<Path>,
  schemaValidator: AgentAddonSchemaValidator,
): Pair<MutableList<AgentAddonCatalogueEntry>, MutableList<InvalidAgentAddonCatalogueEntry>> {
  val entries = mutableListOf<AgentAddonCatalogueEntry>()
  val invalidEntries = mutableListOf<InvalidAgentAddonCatalogueEntry>()
  roots.forEach { root ->
    if (!Files.exists(root)) {
      return@forEach
    }
    Files.list(root).use { stream ->
      stream.filter { !it.name.startsWith(".") }.sorted().forEach { sourceRoot ->
        val manifest = sourceRoot.resolve(MANIFEST_FILE)
        val content = sourceRoot.resolve(CONTENT_FILE)
        catchingNonCancellation { parseSource(sourceRoot, schemaValidator) }
          .onSuccess { declaration ->
            entries += declaration.toCatalogueEntry()
          }
          .onFailure { error ->
            invalidEntries +=
              InvalidAgentAddonCatalogueEntry(
                identity = "agent-addon:${sourceRoot.name}",
                slug = sourceRoot.name,
                manifestPath = manifest.toFileLocation(),
                contentPath = content.toFileLocation(),
                diagnostics = listOf(error.message ?: "Agent add-on declaration is invalid."),
              )
          }
      }
    }
  }
  return entries to invalidEntries
}

internal fun reconcileAgentAddonCatalogueIncoherence(
  entries: MutableList<AgentAddonCatalogueEntry>,
  invalidEntries: MutableList<InvalidAgentAddonCatalogueEntry>,
) {
  val incoherent = mutableMapOf<FileLocation, MutableList<String>>()
  entries.filter { it.manifestPath.toPath().parent.name != it.slug }.forEach { entry ->
    incoherent.getOrPut(entry.manifestPath) { mutableListOf() } +=
      "source directory '${entry.manifestPath.toPath().parent.name}' must match slug '${entry.slug}'"
  }
  entries.groupBy { it.slug }.filterValues { it.size > 1 }.forEach { (slug, duplicates) ->
    duplicates.forEach { entry ->
      incoherent.getOrPut(entry.manifestPath) { mutableListOf() } += "duplicate slug '$slug'"
    }
  }
  entries.groupBy {
    it.manifestPath.toPath().toRealPath()
  }.filterValues { it.size > 1 }.forEach { (identity, duplicates) ->
    duplicates.forEach { entry ->
      incoherent.getOrPut(entry.manifestPath) { mutableListOf() } +=
        "duplicate canonical source identity '$identity'"
    }
  }
  entries.removeAll { entry ->
    incoherent[entry.manifestPath]?.let { diagnostics ->
      invalidEntries +=
        InvalidAgentAddonCatalogueEntry(
          identity = "agent-addon:${entry.manifestPath.toPath().parent.name}",
          slug = entry.manifestPath.toPath().parent.name,
          manifestPath = entry.manifestPath,
          contentPath = entry.contentPath,
          diagnostics = diagnostics,
        )
      true
    } ?: false
  }
}

internal fun agentAddonInspectionRoots(
  repoRoot: Path,
  externalSourceRoots: List<Path>,
): List<Path> =
  listOf(repoRoot.toAbsolutePath().normalize().resolve(AGENT_ADDONS_DIRECTORY)) +
    externalSourceRoots.map { it.toAbsolutePath().normalize() }

private const val AGENT_ADDONS_DIRECTORY = "agent-addons"
private const val MANIFEST_FILE = "agent-addon.yaml"
private const val CONTENT_FILE = "content.md"
