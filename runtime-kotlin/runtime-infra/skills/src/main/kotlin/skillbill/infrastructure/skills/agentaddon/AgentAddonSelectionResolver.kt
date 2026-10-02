package skillbill.infrastructure.skills.agentaddon

import me.tatarka.inject.annotations.Inject
import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelectionEntry
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.infrastructure.contracts.sha256Hex
import skillbill.install.model.SupportedAgent
import skillbill.model.toPath
import skillbill.ports.agentaddon.AgentAddonSelectionPort
import skillbill.ports.diagnostics.RuntimeDiagnostics
import java.nio.file.Files
import java.nio.file.Path

@Inject
class AgentAddonSelectionResolver(
  private val diagnostics: RuntimeDiagnostics,
) : AgentAddonSelectionPort {
  override fun resolveInitial(
    repoRoot: Path,
    requestedSlugs: List<String>,
    consumer: AgentAddonConsumer,
    receivingAgentIds: List<String>,
    externalSourceRoots: List<Path>,
  ): HydratedAgentAddonSelection {
    validateRequestedSlugs(requestedSlugs)
    if (requestedSlugs.isNotEmpty() && receivingAgentIds.isEmpty()) {
      invalidAgentAddonSelection(
        "A non-empty agent add-on selection requires at least one receiving agent.",
      )
    }
    val receivingAgents = receivingAgentIds.map(::parseAgent)
    val catalogue =
      discoverAgentAddons(repoRoot, externalSourceRoots, diagnostics = diagnostics).associateBy { it.slug }
    return HydratedAgentAddonSelection(
      requestedSlugs.map { slug ->
        val declaration =
          catalogue[slug]
            ?: invalidAgentAddonSelection("Unknown agent add-on '$slug'.")
        validateCompatibility(slug, declaration.consumers, declaration.agents, consumer, receivingAgents)
        hydrate(
          slug = slug,
          description = declaration.description,
          sourceIdentity = declaration.canonicalSourceIdentity.toPath(),
          contentPath = declaration.contentPath.toPath(),
        )
      },
    )
  }

  override fun verifyPersisted(
    selection: AgentAddonSelection,
    consumer: AgentAddonConsumer,
    receivingAgentIds: List<String>,
  ): HydratedAgentAddonSelection =
    verifyPersistedAgentAddonSelection(
      PersistedAgentAddonSelectionVerifyRequest(
        selection = selection,
        consumer = consumer,
        receivingAgentIds = receivingAgentIds,
        parseAgent = ::parseAgent,
        validateCompatibility = ::validateCompatibility,
        stringList = ::stringList,
        decodeConsumer = { id -> decodeAgentAddonConsumer(id, AGENT_ADDON_PERSISTED_CONSUMER_SEAM, diagnostics) },
      ),
    )

  private fun hydrate(
    slug: String,
    description: String,
    sourceIdentity: Path,
    contentPath: Path,
  ): HydratedAgentAddonSelectionEntry {
    val bytes = Files.readAllBytes(contentPath)
    return HydratedAgentAddonSelectionEntry(
      persisted =
        PersistedAgentAddonSelectionEntry(
          slug,
          sourceIdentity.toString(),
          sha256Hex(bytes),
        ),
      description = description,
      content = bytes.toString(Charsets.UTF_8),
    )
  }

  private fun validateRequestedSlugs(slugs: List<String>) {
    val malformed = slugs.firstOrNull { !it.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) }
    if (malformed != null) invalidAgentAddonSelection("Malformed agent add-on slug '$malformed'.")
    val duplicate = slugs.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
    if (duplicate != null) {
      invalidAgentAddonSelection(
        "Agent add-on '$duplicate' was selected more than once.",
      )
    }
  }

  private fun parseAgent(id: String): String =
    runCatching {
      SupportedAgent.parseAgentAddonId(
        id,
      ).wireValue
    }.getOrElse {
      invalidAgentAddonSelection("Unknown receiving agent '$id'.")
    }

  private fun validateCompatibility(
    slug: String,
    consumers: List<AgentAddonConsumer>,
    agents: List<String>,
    consumer: AgentAddonConsumer,
    receivingAgents: List<String>,
  ) {
    if (consumer !in consumers) {
      invalidAgentAddonSelection(
        "Agent add-on '$slug' does not support consumer '${consumer.id}'.",
      )
    }
    val incompatible = receivingAgents.firstOrNull { it !in agents }
    if (incompatible != null) {
      invalidAgentAddonSelection(
        "Agent add-on '$slug' is incompatible with receiving agent '$incompatible'.",
      )
    }
  }

  private fun stringList(
    values: Map<*, *>,
    key: String,
  ): List<String> = (values[key] as? List<*>)?.map { it as? String ?: invalidField(key) } ?: invalidField(key)

  private fun invalidField(key: String): Nothing =
    invalidAgentAddonSelection("Selected agent add-on manifest field '$key' is malformed.")
}
