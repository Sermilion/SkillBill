package skillbill.infrastructure.skills.install.mcp

import skillbill.contracts.JsonCodec
import skillbill.infrastructure.host.jvm.atomicWriteString
import skillbill.install.model.McpMutationResult
import skillbill.ports.repository.toFileLocation
import java.nio.file.Files
import java.nio.file.Path

object McpJsonConfig {
  fun register(
    agent: String,
    path: Path,
    command: String,
  ): McpMutationResult {
    val beforeContent = if (Files.exists(path)) Files.readString(path) else ""
    val settings = readJsonObject(path).toMutableMap()
    val servers = mutableStringAnyMap(settings[McpConfigKeys.MCP_SERVERS])
    servers.remove(McpConfigKeys.SKILL_BILL_SERVER)
    servers.remove(McpConfigKeys.SONA_SERVER)
    servers[McpConfigKeys.SONA_SERVER] =
      linkedMapOf(
        McpConfigKeys.TYPE to McpConfigKeys.STDIO,
        McpConfigKeys.COMMAND to command,
        McpConfigKeys.ARGS to emptyList<String>(),
      )
    settings[McpConfigKeys.MCP_SERVERS] = servers
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
    val settings = readJsonObject(path).toMutableMap()
    val servers = mutableStringAnyMap(settings[McpConfigKeys.MCP_SERVERS])
    val changed =
      servers.containsKey(McpConfigKeys.SKILL_BILL_SERVER) || servers.containsKey(McpConfigKeys.SONA_SERVER)
    if (changed) {
      servers.remove(McpConfigKeys.SKILL_BILL_SERVER)
      servers.remove(McpConfigKeys.SONA_SERVER)
      if (servers.isEmpty()) {
        settings.remove(McpConfigKeys.MCP_SERVERS)
      } else {
        settings[McpConfigKeys.MCP_SERVERS] = servers
      }
      writeJson(path, settings)
    }
    return McpMutationResult(agent, path.toFileLocation(), changed = changed)
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

internal object McpConfigKeys {
  const val MCP_SERVERS = "mcpServers"
  const val SKILL_BILL_SERVER = "skill-bill"
  const val SONA_SERVER = "sona"
  const val TYPE = "type"
  const val STDIO = "stdio"
  const val COMMAND = "command"
  const val ARGS = "args"
}
