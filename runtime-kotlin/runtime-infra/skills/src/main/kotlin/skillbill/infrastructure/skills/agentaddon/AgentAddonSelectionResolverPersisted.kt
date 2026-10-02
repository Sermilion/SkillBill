package skillbill.infrastructure.skills.agentaddon

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelectionEntry
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.AgentAddonFailureCode
import skillbill.infrastructure.contracts.sha256Hex
import skillbill.install.model.SupportedAgent
import java.nio.file.Files
import java.nio.file.Path

internal fun verifyPersistedAgentAddonSelection(
  request: PersistedAgentAddonSelectionVerifyRequest,
): HydratedAgentAddonSelection {
  if (request.selection.entries.isNotEmpty() && request.receivingAgentIds.isEmpty()) {
    invalidAgentAddonSelection("A non-empty agent add-on selection requires at least one receiving agent.")
  }
  val receivingAgents = request.receivingAgentIds.map(request.parseAgent)
  return HydratedAgentAddonSelection(
    request.selection.entries.map { recorded ->
      hydratePersistedAgentAddonEntry(recorded, request, receivingAgents)
    },
  )
}

private fun hydratePersistedAgentAddonEntry(
  recorded: PersistedAgentAddonSelectionEntry,
  request: PersistedAgentAddonSelectionVerifyRequest,
  receivingAgents: List<String>,
): HydratedAgentAddonSelectionEntry {
  val stringList = request.stringList
  val manifest = Path.of(recorded.sourceIdentity)
  if (!Files.isRegularFile(manifest)) {
    invalidAgentAddonSelection(
      "Selected agent add-on '${recorded.slug}' source is missing at '${recorded.sourceIdentity}'.",
    )
  }
  val values = YAMLMapper().readValue(Files.readAllBytes(manifest), Map::class.java)
  val slug = values["slug"] as? String
  if (slug != recorded.slug) {
    invalidAgentAddonSelection(
      "Selected source '${recorded.sourceIdentity}' declares '$slug', expected '${recorded.slug}'.",
    )
  }
  val consumers = stringList(values, "consumers").map(request.decodeConsumer).distinct()
  val agents = stringList(values, "agent_ids").map { id -> SupportedAgent.parseAgentAddonId(id).wireValue }
  request.validateCompatibility(recorded.slug, consumers, agents, request.consumer, receivingAgents)
  val contentPath = manifest.resolveSibling("content.md")
  if (!Files.isRegularFile(contentPath)) {
    invalidAgentAddonSelection("Selected agent add-on '${recorded.slug}' content.md is missing.")
  }
  val bytes = Files.readAllBytes(contentPath)
  if (persistedAgentAddonSha256(bytes) != recorded.contentSha256) {
    throw SkillBillRuntimeException(
      AgentAddonFailureCode.SELECTION_DRIFT,
      "Selected agent add-on '${recorded.slug}' changed at '${recorded.sourceIdentity}'; " +
        "start a new run to accept the new content.",
    )
  }
  return HydratedAgentAddonSelectionEntry(
    persisted = recorded,
    description =
      values["description"] as? String
        ?: invalidAgentAddonSelection("Selected agent add-on '${recorded.slug}' has no description."),
    content = bytes.toString(Charsets.UTF_8),
  )
}

internal fun invalidAgentAddonSelection(message: String): Nothing =
  throw SkillBillRuntimeException(AgentAddonFailureCode.INVALID_SELECTION, message)

internal fun persistedAgentAddonSha256(bytes: ByteArray): String = sha256Hex(bytes)
