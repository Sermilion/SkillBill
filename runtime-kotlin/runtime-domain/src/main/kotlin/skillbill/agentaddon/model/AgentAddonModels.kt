package skillbill.agentaddon.model

import skillbill.model.FileLocation

enum class AgentAddonConsumer(val id: String) {
  SKILL_BILL("skill-bill"),
  ;

  companion object {
    const val LEGACY_BILL_FEATURE_ID: String = "bill-feature"

    fun fromIdOrNull(id: String): AgentAddonConsumer? = entries.firstOrNull { it.id == id }

    fun unknownIdMessage(id: String): String =
      "Unknown agent add-on consumer '$id'. Supported: ${entries.joinToString { it.id }}."

    fun fromId(id: String): AgentAddonConsumer =
      fromIdOrNull(id) ?: throw IllegalArgumentException(unknownIdMessage(id))

    fun decode(id: String): AgentAddonConsumerDecoding =
      if (id == LEGACY_BILL_FEATURE_ID) {
        AgentAddonConsumerDecoding(SKILL_BILL, legacyId = id)
      } else {
        AgentAddonConsumerDecoding(fromId(id), legacyId = null)
      }
  }
}

data class AgentAddonConsumerDecoding(
  val consumer: AgentAddonConsumer,
  val legacyId: String?,
)

enum class AgentAddonValidationStatus(val wireValue: String) {
  VALID("valid"),
  INVALID("invalid"),
  ;

  companion object {
    fun fromWire(value: String): AgentAddonValidationStatus? = entries.firstOrNull { it.wireValue == value }
  }
}

data class AgentAddonDeclaration(
  val contractVersion: String,
  val slug: String,
  val description: String,
  val agents: List<String>,
  val consumers: List<AgentAddonConsumer>,
  val addonRoot: FileLocation,
  val manifestPath: FileLocation,
  val contentPath: FileLocation,
  val canonicalSourceIdentity: FileLocation,
)

data class AgentAddonCatalogueEntry(
  val identity: String,
  val slug: String,
  val description: String,
  val agentIds: List<String>,
  val consumers: List<String>,
  val manifestPath: FileLocation,
  val contentPath: FileLocation,
  val validationStatus: AgentAddonValidationStatus = AgentAddonValidationStatus.VALID,
  val diagnostics: List<String> = emptyList(),
)

data class InvalidAgentAddonCatalogueEntry(
  val identity: String,
  val slug: String,
  val manifestPath: FileLocation,
  val contentPath: FileLocation,
  val validationStatus: AgentAddonValidationStatus = AgentAddonValidationStatus.INVALID,
  val diagnostics: List<String>,
)

data class AgentAddonCatalogueInspection(
  val entries: List<AgentAddonCatalogueEntry>,
  val invalidEntries: List<InvalidAgentAddonCatalogueEntry>,
)

data class PersistedAgentAddonSelectionEntry(
  val slug: String,
  val sourceIdentity: String,
  val contentSha256: String,
) {
  init {
    val reason = violation(slug, sourceIdentity, contentSha256)
    require(reason == null) { reason.orEmpty() }
  }

  companion object {
    fun violation(
      slug: String,
      sourceIdentity: String,
      contentSha256: String,
    ): String? =
      when {
        !slug.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*")) -> "Invalid agent add-on slug '$slug'."
        !contentSha256.matches(Regex("[0-9a-f]{64}")) ->
          "Agent add-on '$slug' content digest must be a lowercase SHA-256 value."
        sourceIdentity.isBlank() -> "Agent add-on '$slug' source identity is required."
        else -> null
      }
  }
}

data class HydratedAgentAddonSelectionEntry(
  val persisted: PersistedAgentAddonSelectionEntry,
  val description: String,
  val content: String,
)

data class AgentAddonSelection(
  val entries: List<PersistedAgentAddonSelectionEntry> = emptyList(),
) {
  init {
    val reason = violation(entries)
    require(reason == null) { reason.orEmpty() }
  }

  companion object {
    fun violation(entries: List<PersistedAgentAddonSelectionEntry>): String? =
      if (entries.map { it.slug }.distinct().size == entries.size) {
        null
      } else {
        "Agent add-on selection contains duplicate slugs."
      }
  }
}

data class HydratedAgentAddonSelection(
  val entries: List<HydratedAgentAddonSelectionEntry> = emptyList(),
) {
  val persisted: AgentAddonSelection get() = AgentAddonSelection(entries.map { it.persisted })
}
