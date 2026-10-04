package skillbill.install.model

import skillbill.error.shellcontent.invalidAgentAddonAgentId

enum class SupportedAgent(
  val wireValue: String,
  val nativeAgentsKind: String,
  val nativeFileExtension: String,
  val simpleHomeDirectory: String?,
  val mcpConfigRelativePath: String,
  val mcpUsesToml: Boolean,
  val profileDirectoryPrefix: String? = null,
  val mcpProfileFileName: String = mcpConfigRelativePath.substringAfterLast('/'),
) {
  CLAUDE("claude", "claude-agents", "md", null, ".claude.json", false, ".claude-"),
  CODEX("codex", "codex-agents", "toml", null, ".codex/config.toml", true, ".codex-"),
  JUNIE("junie", "junie-agents", "md", ".junie", ".junie/mcp/mcp.json", false),
  CURSOR("cursor", "cursor-agents", "md", ".cursor", ".cursor/mcp.json", false),
  ;

  val id: String get() = wireValue

  companion object {
    val supportedIds: List<String> = entries.map(SupportedAgent::wireValue)

    fun fromWire(id: String): SupportedAgent {
      val normalized = id.trim().lowercase()
      return entries.firstOrNull { agent -> agent.wireValue == normalized }
        ?: throw IllegalArgumentException("Unknown agent '$id'. Supported agents: ${supportedIds.joinToString(", ")}.")
    }

    fun fromId(id: String): SupportedAgent = fromWire(id)

    fun fromNormalizedId(
      id: String,
      label: String = "agent",
    ): SupportedAgent {
      val normalized = id.trim().lowercase()
      require(normalized.isNotBlank()) { "$label is required. Supported agents: ${supportedIds.joinToString(", ")}." }
      return fromWire(normalized)
    }

    fun parseAgentAddonId(id: String): SupportedAgent {
      val normalized = id.trim().lowercase()
      return entries.firstOrNull { agent -> agent.wireValue == normalized }
        ?: throw invalidAgentAddonAgentId(
          agentId = id,
          reason = "Unknown agent. Supported agents: ${supportedIds.joinToString(", ")}.",
        )
    }
  }
}
