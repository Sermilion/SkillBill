package skillbill.infrastructure.launcher.process.cursor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.nio.file.InvalidPathException
import java.nio.file.Path
import skillbill.infrastructure.launcher.process.cursor.CursorAcpKeys as K

internal fun sessionContextRefusal(
  launch: CursorAcpLaunch,
  capabilities: JsonObject,
): String? {
  val provider = launch.provider.name
  val roots = launch.additionalDirectories
  val malformedServer =
    launch.mcpServers.any { server ->
      server.name.isBlank() || !isAbsolutePath(server.command)
    }
  return when {
    malformedServer ->
      "$provider runtime MCP server description is incomplete; the required runtime MCP executable must be an " +
        "absolute path. No prompt was sent."
    roots.any { !isAbsolutePath(it) } ->
      "$provider additional session directories must be absolute paths; no prompt was sent."
    roots.isNotEmpty() && !advertisesAdditionalDirectories(capabilities) ->
      "$provider did not advertise sessionCapabilities.additionalDirectories, so the required external " +
        "directories ${roots.joinToString(", ")} cannot be attached; no prompt was sent."
    else -> null
  }
}

private fun isAbsolutePath(value: String): Boolean =
  value.isNotBlank() &&
    try {
      Path.of(value).isAbsolute
    } catch (_: InvalidPathException) {
      false
    }

private fun advertisesAdditionalDirectories(capabilities: JsonObject): Boolean {
  val advertised = capabilities.obj(K.SESSION_CAPABILITIES)?.get(K.ADDITIONAL_DIRECTORIES) ?: return false
  return advertised != JsonNull && advertised != JsonPrimitive(false)
}

internal fun acpMcpServers(servers: List<AcpStdioMcpServer>): JsonArray =
  JsonArray(
    servers.map { server ->
      buildJsonObject {
        put(K.NAME, server.name)
        put(K.COMMAND, server.command)
        put(K.ARGS, JsonArray(server.args.map(::JsonPrimitive)))
        put(
          K.ENV,
          JsonArray(
            server.env.map { variable ->
              buildJsonObject {
                put(K.NAME, variable.name)
                put(K.VALUE, variable.value)
              }
            },
          ),
        )
      }
    },
  )

internal fun acpRejection(
  launch: CursorAcpLaunch,
  method: String,
  error: JsonObject?,
  selectingModel: Boolean,
): String {
  val provider = launch.provider
  val code = (error?.get(K.CODE) as? JsonPrimitive)?.intOrNull
  val detail = error?.text(K.MESSAGE).orEmpty().take(MAX_REJECTION_DETAIL)
  val suffix = "(code ${code ?: "absent"}): $detail"
  val setup = method == K.SESSION_NEW || method == K.SESSION_LOAD
  val login = provider.loginCommand
  return when {
    code == K.AUTH_REQUIRED_CODE && login != null ->
      "${provider.name} requires authentication during $method; run `$login` outside the worker, then retry. " +
        "No prompt was sent."
    selectingModel ->
      "${provider.name} rejected the requested model '${launch.model}' $suffix. No prompt was sent."
    setup && (launch.mcpServers.isNotEmpty() || launch.additionalDirectories.isNotEmpty()) ->
      "${provider.name} rejected $method with the required runtime MCP servers " +
        "${launch.mcpServers.map(AcpStdioMcpServer::name)} and directories ${launch.additionalDirectories} $suffix"
    else -> "${provider.name} rejected $method $suffix"
  }
}

private const val MAX_REJECTION_DETAIL = 500
