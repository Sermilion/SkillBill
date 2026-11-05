package skillbill.engine.work.control

import skillbill.contracts.system.AgentHomeEnvironmentKeys
import skillbill.install.model.SupportedAgent
import java.nio.file.Path

/** The outcome of resolving which agent profile an operation launch runs under. */
internal sealed interface OperationProfileResolution {
  /** [profile] is the agent config directory to pin, or null for the agent's default home. */
  data class Resolved(val profile: String?) : OperationProfileResolution

  data class Refused(val reason: String) : OperationProfileResolution
}

internal object OperationAgentHomes {
  private val HOME_KEYS =
    mapOf(
      SupportedAgent.CLAUDE.wireValue to AgentHomeEnvironmentKeys.CLAUDE_CONFIG_DIR,
      SupportedAgent.CODEX.wireValue to AgentHomeEnvironmentKeys.CODEX_HOME,
      SupportedAgent.CURSOR.wireValue to AgentHomeEnvironmentKeys.CURSOR_CONFIG_DIR,
    )

  fun effectiveProfile(
    agentId: String,
    explicit: String?,
    current: Map<String, String>,
    userHome: Path,
  ): OperationProfileResolution {
    val requested = explicit?.trim()?.takeIf(String::isNotEmpty)
    val key = HOME_KEYS[agentId]
    return when {
      key == null && requested != null ->
        OperationProfileResolution.Refused(
          "The $agentId agent has no profile setting; start it with its default profile.",
        )
      key == null -> OperationProfileResolution.Resolved(null)
      requested?.any(Char::isISOControl) == true ->
        OperationProfileResolution.Refused("The agent profile '$requested' is not valid.")
      else -> {
        val value = requested ?: current[key]?.trim()?.takeIf(String::isNotEmpty)
        OperationProfileResolution.Resolved(value?.takeUnless { isClaudeDefault(agentId, it, userHome) })
      }
    }
  }

  fun pinned(
    agentId: String,
    profile: String?,
  ): Map<String, String> = HOME_KEYS[agentId]?.let { mapOf(it to profile.orEmpty()) } ?: emptyMap()

  private fun isClaudeDefault(
    agentId: String,
    value: String,
    userHome: Path,
  ): Boolean =
    agentId == SupportedAgent.CLAUDE.wireValue &&
      Path.of(value).normalize() == userHome.resolve(".claude").normalize()
}
