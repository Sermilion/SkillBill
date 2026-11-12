package skillbill.infrastructure.skills.install.mcp

import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.malformedMcpConfig
import skillbill.infrastructure.host.jvm.atomicWriteString
import skillbill.install.model.McpMutationResult
import skillbill.ports.repository.toFileLocation
import java.nio.file.Files
import java.nio.file.Path

enum class McpJsonEntryShape {
  TYPED_STDIO,
  PLAIN_STDIO,
}

object McpJsonConfig {
  private const val MCP_SERVERS_KEY: String = "mcpServers"
  private const val MANAGED_SERVER_NAME: String = "skill-bill"
  private const val TYPE_KEY: String = "type"
  private const val STDIO_TYPE: String = "stdio"
  private const val COMMAND_KEY: String = "command"
  private const val ARGS_KEY: String = "args"
  private const val ENV_KEY: String = "env"

  fun register(
    agent: String,
    path: Path,
    command: String,
    entryShape: McpJsonEntryShape = McpJsonEntryShape.TYPED_STDIO,
  ): McpMutationResult {
    val beforeContent = readMcpConfigText(path)
    val settings = parseMcpSettings(path, beforeContent).toMutableMap()
    val servers = mutableStringAnyMap(settings[MCP_SERVERS_KEY])
    servers[MANAGED_SERVER_NAME] = managedEntry(command, entryShape)
    settings[MCP_SERVERS_KEY] = servers
    val afterContent = JsonCodec.mapToJsonString(settings) + "\n"
    val changed = beforeContent != afterContent
    if (changed) {
      writeJson(path, settings)
    }
    return McpMutationResult(agent, path.toFileLocation(), changed = changed)
  }

  fun unregister(
    agent: String,
    path: Path,
  ): McpMutationResult {
    val settings = parseMcpSettings(path, readMcpConfigText(path)).toMutableMap()
    val servers = mutableStringAnyMap(settings[MCP_SERVERS_KEY])
    val changed = servers.remove(MANAGED_SERVER_NAME) != null
    if (changed) {
      if (servers.isEmpty()) {
        settings.remove(MCP_SERVERS_KEY)
      } else {
        settings[MCP_SERVERS_KEY] = servers
      }
      writeJson(path, settings)
    }
    return McpMutationResult(agent, path.toFileLocation(), changed = changed)
  }

  private fun managedEntry(
    command: String,
    entryShape: McpJsonEntryShape,
  ): Map<String, Any?> =
    when (entryShape) {
      McpJsonEntryShape.TYPED_STDIO ->
        linkedMapOf(TYPE_KEY to STDIO_TYPE, COMMAND_KEY to command, ARGS_KEY to emptyList<String>())
      McpJsonEntryShape.PLAIN_STDIO ->
        linkedMapOf(COMMAND_KEY to command, ARGS_KEY to emptyList<String>(), ENV_KEY to emptyMap<String, String>())
    }

  private fun readMcpConfigText(path: Path): String = if (Files.exists(path)) Files.readString(path) else ""

  private fun parseMcpSettings(
    path: Path,
    raw: String,
  ): Map<String, Any?> {
    if (raw.isBlank()) return linkedMapOf()
    val settings =
      JsonCodec.anyToStringAnyMap(JsonCodec.parseObjectOrNull(raw)?.let(JsonCodec::jsonElementToValue))
        ?: throw malformedMcpConfig(path.toString(), "the document is not a well-formed JSON object")
    val servers = settings[MCP_SERVERS_KEY]
    if (settings.containsKey(MCP_SERVERS_KEY) && JsonCodec.anyToStringAnyMap(servers) == null) {
      throw malformedMcpConfig(path.toString(), "'$MCP_SERVERS_KEY' is not a JSON object")
    }
    return settings
  }
}

fun readJsonObject(path: Path): Map<String, Any?> {
  val raw = if (Files.exists(path)) Files.readString(path) else ""
  return if (raw.isBlank()) {
    linkedMapOf()
  } else {
    JsonCodec.anyToStringAnyMap(JsonCodec.parseObjectOrNull(raw)?.let(JsonCodec::jsonElementToValue))
      ?: throw IllegalArgumentException("Invalid JSON config at '$path'.")
  }
}

fun writeJson(
  path: Path,
  settings: Map<String, Any?>,
) {
  atomicWriteString(path, JsonCodec.mapToJsonString(settings) + "\n")
}

fun mutableStringAnyMap(value: Any?): MutableMap<String, Any?> =
  JsonCodec.anyToStringAnyMap(value)?.toMutableMap() ?: linkedMapOf()
