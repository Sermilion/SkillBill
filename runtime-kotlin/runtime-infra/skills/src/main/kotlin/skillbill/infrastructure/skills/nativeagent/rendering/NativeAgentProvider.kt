package skillbill.infrastructure.skills.nativeagent.rendering

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.jvm.resolveEnvironmentMap
import skillbill.infrastructure.skills.install.opencode.opencodeConfigRoots
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentSource
import skillbill.infrastructure.skills.nativeagent.composition.YAML_DOUBLE_QUOTE_ESCAPES
import skillbill.infrastructure.skills.nativeagent.composition.declaresReadOnlyToolset
import skillbill.infrastructure.skills.nativeagent.support.NativeAgentObservedPath
import skillbill.infrastructure.skills.nativeagent.support.agentPathExists
import skillbill.infrastructure.skills.nativeagent.support.claudeConfigRoots
import skillbill.infrastructure.skills.nativeagent.support.codexAgentsTargets
import skillbill.infrastructure.skills.nativeagent.support.detectCodexAgentsTargets
import skillbill.infrastructure.skills.nativeagent.support.devinAgentsDirectory
import skillbill.infrastructure.skills.nativeagent.support.devinConfigRoot
import skillbill.infrastructure.skills.nativeagent.support.discoverClaudeConfigRoots
import skillbill.infrastructure.skills.nativeagent.support.discoverCodexConfigRoots
import skillbill.install.model.SupportedAgent
import skillbill.ports.system.HostPlatformPort
import java.nio.file.Path

internal data class NativeAgentInventoryAgentDirectory(
  val path: Path,
  val observedParent: NativeAgentObservedPath?,
)

enum class NativeAgentProvider {
  Claude {
    override val supportedAgent = SupportedAgent.CLAUDE

    override fun render(source: NativeAgentSource): String = renderFrontmatterAgent(source, toolsetFields(source))

    override fun homeAgentDirs(
      home: Path,
      environment: Map<String, String>,
      hostPlatform: HostPlatformPort,
    ): List<Path> = claudeConfigRoots(home, resolveEnvironmentMap(environment)).map { it.resolve("agents") }
  },
  Codex {
    override val supportedAgent = SupportedAgent.CODEX

    override fun render(source: NativeAgentSource): String = renderCodexAgentToml(source)

    override fun homeAgentDirs(
      home: Path,
      environment: Map<String, String>,
      hostPlatform: HostPlatformPort,
    ): List<Path> = codexAgentsTargets(home, resolveEnvironmentMap(environment))
  },
  Cursor {
    override val supportedAgent = SupportedAgent.CURSOR

    override fun render(source: NativeAgentSource): String =
      renderFrontmatterAgent(source, cursorCapabilityFields(source))

    override fun homeAgentDirs(
      home: Path,
      environment: Map<String, String>,
      hostPlatform: HostPlatformPort,
    ): List<Path> = listOf(home.resolve(requireNotNull(supportedAgent.simpleHomeDirectory)).resolve("agents"))
  },
  OpenCode {
    override val supportedAgent = SupportedAgent.OPENCODE

    override fun render(source: NativeAgentSource): String =
      renderFrontmatterAgent(source, listOf("mode: primary") + openCodePermissionFields(source))

    override fun homeAgentDirs(
      home: Path,
      environment: Map<String, String>,
      hostPlatform: HostPlatformPort,
    ): List<Path> = listOf(opencodeConfigRoots(home, environment).configDirectory.resolve("agents"))
  },
  Devin {
    override val supportedAgent = SupportedAgent.DEVIN

    override fun render(source: NativeAgentSource): String =
      renderFrontmatterAgent(source, devinCapabilityFields(source))

    override fun homeAgentDirs(
      home: Path,
      environment: Map<String, String>,
      hostPlatform: HostPlatformPort,
    ): List<Path> = listOf(devinAgentsDirectory(devinConfigRoot(home, environment, hostPlatform)))
  },
  ;

  abstract val supportedAgent: SupportedAgent

  val directoryName: String get() = supportedAgent.nativeAgentsKind

  val extension: String get() = supportedAgent.nativeFileExtension

  abstract fun render(source: NativeAgentSource): String

  abstract fun homeAgentDirs(
    home: Path,
    environment: Map<String, String> = emptyMap(),
    hostPlatform: HostPlatformPort = JdkHostPlatformPort,
  ): List<Path>

  internal fun inventoryHomeAgentDirs(
    home: Path,
    environment: Map<String, String>,
  ): List<NativeAgentInventoryAgentDirectory> =
    when (this) {
      Claude -> {
        val roots = discoverClaudeConfigRoots(home, resolveEnvironmentMap(environment))
        roots.paths.map { root ->
          NativeAgentInventoryAgentDirectory(root.resolve("agents"), roots.observedRoots[root])
        }
      }
      Codex -> {
        val roots = discoverCodexConfigRoots(home, resolveEnvironmentMap(environment))
        val profileDirectories =
          roots.paths.map { root ->
            NativeAgentInventoryAgentDirectory(root.resolve("agents"), roots.observedRoots[root])
          }
        (profileDirectories + NativeAgentInventoryAgentDirectory(home.resolve(".agents/agents"), null))
          .distinctBy { directory -> directory.path.toAbsolutePath().normalize() }
      }
      Cursor,
      OpenCode,
      Devin,
      -> homeAgentDirs(home, environment).map { path -> NativeAgentInventoryAgentDirectory(path, null) }
    }

  fun fileName(logicalName: String): String = "$logicalName.$extension"

  fun activeHomeAgentDirs(
    home: Path,
    environment: Map<String, String> = emptyMap(),
    hostPlatform: HostPlatformPort = JdkHostPlatformPort,
  ): List<Path> =
    when (this) {
      Claude -> homeAgentDirs(home, environment)
      Codex -> detectCodexAgentsTargets(home, resolveEnvironmentMap(environment)).map { it.path }
      Cursor,
      -> {
        val targets = homeAgentDirs(home, environment)
        val root = home.resolve(requireNotNull(supportedAgent.simpleHomeDirectory))
        val present =
          targets.plusElement(root).map { path ->
            agentPathExists(path, "inspect ${supportedAgent.wireValue} native-agent directory")
          }.any { exists -> exists }
        if (present) targets else emptyList()
      }
      OpenCode -> {
        val targets = homeAgentDirs(home, environment)
        if (targets.any { path -> agentPathExists(path, "inspect opencode native-agent directory") }) {
          targets
        } else {
          emptyList()
        }
      }
      Devin -> {
        val root = devinConfigRoot(home, environment, hostPlatform)
        val target = devinAgentsDirectory(root)
        val present = listOf(target, root).any { path -> agentPathExists(path, "inspect Devin native-agent directory") }
        if (present) listOf(target) else emptyList()
      }
    }.map { it.toAbsolutePath().normalize() }

  fun cacheArtifactPath(
    cacheRoot: Path,
    logicalName: String,
  ): Path = cacheRoot.resolve(directoryName).resolve(fileName(logicalName)).toAbsolutePath().normalize()

  companion object {
    fun forSupportedAgent(agent: SupportedAgent): NativeAgentProvider =
      entries.first { provider ->
        provider.supportedAgent == agent
      }
  }
}

private fun renderCodexAgentToml(agent: NativeAgentSource): String =
  buildString {
    append("""name = "${tomlBasicString(agent.name)}"""").append('\n')
    append("""description = "${tomlBasicString(agent.description)}"""").append('\n')
    append('\n')
    append("developer_instructions = \"\"\"").append('\n')
    append(tomlMultilineString(agent.body.trimEnd())).append('\n')
    append("\"\"\"").append('\n')
  }

private fun renderFrontmatterAgent(
  agent: NativeAgentSource,
  capabilityFields: List<String>,
): String =
  buildString {
    append("---").append('\n')
    append("name: ${yamlScalar(agent.name)}").append('\n')
    append("description: ${yamlScalar(agent.description)}").append('\n')
    capabilityFields.forEach { field -> append(field).append('\n') }
    append("---").append('\n')
    append('\n')
    append(agent.body.trimEnd()).append('\n')
  }

private fun openCodePermissionFields(agent: NativeAgentSource): List<String> {
  if (agent.tools.isEmpty()) return emptyList()
  val permissions = agent.tools.map(::openCodePermission).distinct()
  return listOf("permission:", "  \"*\": deny") +
    permissions.map { permission -> "  ${yamlScalar(permission)}: allow" }
}

private fun openCodePermission(tool: String): String =
  when (tool) {
    "Read" -> "read"
    "Grep" -> "grep"
    "Glob" -> "glob"
    "Bash" -> "bash"
    "Edit", "Write", "NotebookEdit" -> "edit"
    "Agent" -> "task"
    else -> openCodeMcpPermission(tool) ?: invalidOpenCodeCapability(tool)
  }

private fun openCodeMcpPermission(tool: String): String? {
  if (!tool.startsWith("mcp__")) return null
  val parts = tool.removePrefix("mcp__").split("__", limit = 2)
  if (parts.size != 2 || parts.any(String::isBlank)) return null
  return parts.joinToString("_") { part -> part.replace(OPEN_CODE_TOOL_PART_INVALID, "_") }
}

private fun invalidOpenCodeCapability(tool: String): Nothing =
  throw SkillBillRuntimeException(
    InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA,
    "OpenCode cannot map declared native-agent capability '$tool' to a permission.",
  )

private val OPEN_CODE_TOOL_PART_INVALID = Regex("[^A-Za-z0-9_-]")

private fun toolsetFields(agent: NativeAgentSource): List<String> =
  if (agent.tools.isEmpty()) {
    emptyList()
  } else {
    listOf("tools: ${agent.tools.joinToString(", ")}")
  }

private fun cursorCapabilityFields(agent: NativeAgentSource): List<String> =
  if (agent.declaresReadOnlyToolset) {
    listOf("readonly: true")
  } else {
    emptyList()
  }

private val YAML_RESERVED_LEADING_CHARS: Set<Char> =
  setOf('-', '?', ':', ',', '[', ']', '{', '}', '#', '&', '*', '!', '|', '>', '\'', '"', '%', '@', '`')
private val YAML_RESERVED_INLINE_CHARS: Set<Char> = setOf('\n', '\r', '\t')

private fun yamlScalar(value: String): String =
  if (yamlNeedsQuoting(value)) {
    "\"" + value.map { char -> YAML_DOUBLE_QUOTE_ESCAPES[char] ?: char.toString() }.joinToString("") + "\""
  } else {
    value
  }

private val YAML_PLAIN_RESOLVED_SCALARS: Set<String> =
  setOf("y", "n", "yes", "no", "true", "false", "on", "off", "null", "~")

private fun yamlNeedsQuoting(value: String): Boolean {
  if (value.isEmpty()) {
    return true
  }
  val edgeWhitespace = value.first().isWhitespace() || value.last().isWhitespace()
  val leadingReserved = value.first() in YAML_RESERVED_LEADING_CHARS
  val valueIndicator = value.contains(": ") || value.endsWith(":") || value.contains(" #")
  val inlineReserved = value.any { char -> char in YAML_RESERVED_INLINE_CHARS }
  val plainResolved = value.lowercase() in YAML_PLAIN_RESOLVED_SCALARS || value.toDoubleOrNull() != null
  return edgeWhitespace || leadingReserved || valueIndicator || inlineReserved || plainResolved
}

private fun tomlBasicString(value: String): String =
  buildString {
    value.forEach { char ->
      when (char) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\b' -> append("\\b")
        '\t' -> append("\\t")
        '\n' -> append("\\n")
        '\u000C' -> append("\\f")
        '\r' -> append("\\r")
        else -> append(char)
      }
    }
  }

private fun tomlMultilineString(value: String): String = value.replace("\\", "\\\\").replace("\"\"\"", "\\\"\\\"\\\"")
