package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.data.runtime.currentDesktopTempRoot
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import skillbill.infrastructure.launcher.process.codex.CodexRefusalSchema
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

internal suspend fun codexAppServerCompatibility(
  command: ChatCommand,
  request: ChatTurnRequest,
  diagnostics: Diagnostics,
): String? =
  withContext(Dispatchers.IO) {
    val base = command.probeInvocation
    val version = runChatProcessProbe(command, base + "--version")
    if (version.exitCode != 0 || "codex-cli 0.166.0" !in version.stdout) {
      return@withContext "The installed Codex CLI is incompatible with the supported 0.166.0 app-server protocol."
    }
    val directory = try {
      Files.createTempDirectory(currentDesktopTempRoot(), "skill-bill-codex-schema-")
    } catch (failure: IOException) {
      return@withContext "Could not inspect the installed Codex app-server protocol: ${failure.message.orEmpty()}"
    }
    try {
      val generated = runChatProcessProbe(
        command,
        base + listOf("app-server", "generate-json-schema", "--experimental", "--out", directory.toString()),
      )
      if (generated.exitCode != 0) {
        return@withContext "The installed Codex CLI could not expose its app-server protocol schema."
      }
      val root = directory.resolve("codex_app_server_protocol.v2.schemas.json")
      val schema = Json.parseToJsonElement(Files.readString(root)) as? JsonObject
        ?: return@withContext "The installed Codex app-server protocol schema is malformed."
      val definitions = schema["definitions"] as? JsonObject
        ?: return@withContext "The installed Codex app-server protocol schema has no definitions."
      val requirements = listOf(
        "v1/InitializeParams.json" to setOf("clientInfo"),
        "v1/InitializeResponse.json" to setOf("userAgent"),
        "v2/ThreadStartParams.json" to setOf("cwd", "model"),
        "v2/ThreadStartResponse.json" to setOf("thread", "cwd", "model") +
          setOfNotNull("sandbox".takeIf { request.mode == dev.skillbill.desktop.core.domain.model.ChatPermissionMode.ReadOnly }),
        "v2/ThreadResumeParams.json" to setOf("threadId"),
        "v2/ThreadResumeResponse.json" to setOf("thread", "cwd", "model") +
          setOfNotNull("sandbox".takeIf { request.mode == dev.skillbill.desktop.core.domain.model.ChatPermissionMode.ReadOnly }),
        "v2/TurnStartParams.json" to setOf("threadId", "input", "effort", "cwd", "sandboxPolicy"),
        "v2/TurnStartResponse.json" to setOf("turn"),
        "v2/AgentMessageDeltaNotification.json" to setOf("threadId", "turnId", "itemId", "delta"),
        "v2/ItemStartedNotification.json" to setOf("threadId", "turnId", "item"),
        "v2/ItemCompletedNotification.json" to setOf("threadId", "turnId", "item"),
        "v2/TurnCompletedNotification.json" to setOf("threadId", "turn"),
        "CommandExecutionRequestApprovalParams.json" to setOf("itemId", "threadId", "turnId", "availableDecisions"),
        "CommandExecutionRequestApprovalResponse.json" to setOf("decision"),
        "FileChangeRequestApprovalParams.json" to setOf("itemId", "threadId", "turnId", "grantRoot", "reason"),
        "FileChangeRequestApprovalResponse.json" to setOf("decision"),
        "v2/ServerRequestResolvedNotification.json" to setOf("requestId", "threadId"),
      )
      for ((relative, fields) in requirements) {
        val file = directory.resolve(relative)
        val projection = Json.parseToJsonElement(Files.readString(file)) as? JsonObject
          ?: return@withContext "The installed Codex app-server schema is missing $relative."
        codexProjectionCompatibility(relative, fields, projection, definitions, request)?.let { return@withContext it }
      }
      val clientRequests =
        Json.parseToJsonElement(Files.readString(directory.resolve("ClientRequest.json"))) as? JsonObject
        ?: return@withContext "The installed Codex app-server client-request schema is malformed."
      val clientDefinitions = clientRequests["definitions"] as? JsonObject
        ?: return@withContext "The installed Codex app-server client-request schema has no definitions."
      val clientNotifications =
        Json.parseToJsonElement(Files.readString(directory.resolve("ClientNotification.json"))) as? JsonObject
          ?: return@withContext "The installed Codex client-notification schema is malformed."
      val clientNotificationDefinitions = clientNotifications["definitions"] as? JsonObject ?: clientDefinitions
      val serverNotifications =
        Json.parseToJsonElement(Files.readString(directory.resolve("ServerNotification.json"))) as? JsonObject
          ?: return@withContext "The installed Codex server-notification schema is malformed."
      val serverNotificationDefinitions = serverNotifications["definitions"] as? JsonObject
        ?: return@withContext "The installed Codex server-notification schema has no definitions."
      val serverRequests =
        Json.parseToJsonElement(Files.readString(directory.resolve("ServerRequest.json"))) as? JsonObject
          ?: return@withContext "The installed Codex server-request schema is malformed."
      val serverRequestDefinitions = serverRequests["definitions"] as? JsonObject
        ?: return@withContext "The installed Codex server-request schema has no definitions."
      val methodBindings = listOf(
        Triple(clientRequests, "initialize", "InitializeParams"),
        Triple(clientRequests, "thread/start", "ThreadStartParams"),
        Triple(clientRequests, "thread/resume", "ThreadResumeParams"),
        Triple(clientRequests, "turn/start", "TurnStartParams"),
        Triple(clientNotifications, "initialized", null),
        Triple(serverNotifications, "item/agentMessage/delta", "AgentMessageDeltaNotification"),
        Triple(serverNotifications, "item/started", "ItemStartedNotification"),
        Triple(serverNotifications, "item/completed", "ItemCompletedNotification"),
        Triple(serverNotifications, "turn/completed", "TurnCompletedNotification"),
        Triple(serverNotifications, "serverRequest/resolved", "ServerRequestResolvedNotification"),
        Triple(serverRequests, "item/commandExecution/requestApproval", "CommandExecutionRequestApprovalParams"),
        Triple(serverRequests, "item/fileChange/requestApproval", "FileChangeRequestApprovalParams"),
      )
      if (methodBindings.any { (source, method, params) ->
          val sourceDefinitions = when (source) {
            clientRequests -> clientDefinitions
            clientNotifications -> clientNotificationDefinitions
            serverRequests -> serverRequestDefinitions
            else -> serverNotificationDefinitions
          }
          !schemaBindsMethod(source, method, params, sourceDefinitions)
        }
      ) {
        return@withContext "The installed Codex app-server methods do not bind to the supported parameter shapes."
      }
      CodexRefusalSchema.inspect(directory)?.let { return@withContext it }
      if (!codexApprovalResponseCompatible(directory.resolve("CommandExecutionRequestApprovalResponse.json"), true) ||
        !codexApprovalResponseCompatible(directory.resolve("FileChangeRequestApprovalResponse.json"), false)
      ) {
        return@withContext "The installed Codex approval response schema lacks safe one-time or refusal decisions."
      }
      if (!codexOptionsCompatible(directory.resolve("CommandExecutionRequestApprovalParams.json"))) {
        return@withContext "The installed Codex approval options do not expose the supported decision unions."
      }
      if (!codexItemVariantsCompatible(directory.resolve("v2/ItemStartedNotification.json"))) {
        return@withContext "The installed Codex item schema cannot supply complete command and file approval facts."
      }
      val completed =
        Json.parseToJsonElement(Files.readString(directory.resolve("v2/TurnCompletedNotification.json"))) as? JsonObject
        ?: return@withContext "The installed Codex completion notification schema is malformed."
      val completionTurn = (completed["definitions"] as? JsonObject)?.get("Turn") as? JsonObject
        ?: return@withContext "The installed Codex completion notification has no Turn definition."
      val completionTurnProperties = completionTurn["properties"] as? JsonObject
        ?: return@withContext "The installed Codex completion Turn has no fields."
      val completionDefinitions = completed["definitions"] as? JsonObject ?: definitions
      val completionTurnRequired = (completionTurn["required"] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
      if (!completionTurnProperties.keys.containsAll(setOf("id", "status")) ||
        !completionTurnRequired.containsAll(setOf("id", "status")) ||
        !schemaAllowsType(completionTurnProperties["id"], "string", completionDefinitions, emptySet(), false) ||
        !schemaAllowsType(completionTurnProperties["status"], "string", completionDefinitions, emptySet(), false)
      ) {
        return@withContext "The installed Codex completion Turn does not provide its required identity and status."
      }
      val inputDefinition = definitions["UserInput"] as? JsonObject
        ?: return@withContext "The installed Codex app-server schema has no user-input definition."
      if (!schemaSupportsInput(inputDefinition, "localImage", "path", definitions)) {
        return@withContext "The installed Codex app-server protocol does not support local image inputs."
      }
      if (!schemaSupportsInput(inputDefinition, "text", "text", definitions)) {
        return@withContext "The installed Codex app-server protocol does not support text inputs."
      }
      null
    } catch (failure: IOException) {
      "Could not inspect the installed Codex app-server protocol schema: ${failure.message.orEmpty()}"
    } catch (failure: IllegalArgumentException) {
      "The installed Codex app-server protocol schema is malformed: ${failure.message.orEmpty()}"
    } finally {
      try {
        Files.walk(directory).use { paths ->
          paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
      } catch (_: IOException) {
        diagnostics.record("chat.codex_schema_cleanup_failed")
      }
    }
  }

private fun codexApprovalResponseCompatible(file: Path, command: Boolean): Boolean {
  return try {
    val root = Json.parseToJsonElement(Files.readString(file)) as? JsonObject ?: return false
    val required = (root["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
    val properties = root["properties"] as? JsonObject ?: return false
    val definitions = root["definitions"] as? JsonObject ?: JsonObject(emptyMap())
    val decisions = codexDecisionTokens(properties["decision"], definitions)
    val supported = setOf("accept", "decline", "cancel", "acceptForSession") +
      setOf("acceptWithExecpolicyAmendment", "applyNetworkPolicyAmendment").takeIf { command }.orEmpty()
    required.contains("decision") && decisions.containsAll(supported)
  } catch (_: IOException) {
    false
  }
}

private fun codexOptionsCompatible(file: Path): Boolean {
  return try {
    val root = Json.parseToJsonElement(Files.readString(file)) as? JsonObject ?: return false
    val definitions = root["definitions"] as? JsonObject ?: return false
    val properties = root["properties"] as? JsonObject ?: return false
    val choices = codexDecisionTokens((properties["availableDecisions"] as? JsonObject)?.get("items"), definitions)
    val required = setOf("accept", "decline", "cancel", "acceptForSession", "acceptWithExecpolicyAmendment", "applyNetworkPolicyAmendment")
    choices.containsAll(required)
  } catch (_: IOException) {
    false
  }
}

private fun codexItemVariantsCompatible(file: Path): Boolean {
  return try {
    val root = Json.parseToJsonElement(Files.readString(file)) as? JsonObject ?: return false
    val definitions = root["definitions"] as? JsonObject ?: return false
    val item = definitions["ThreadItem"] ?: return false
    val command = codexItemVariant(item, definitions, "commandExecution", setOf("id", "command", "cwd"))
    val fileChange = codexItemVariant(item, definitions, "fileChange", setOf("id", "changes"))
    val change = definitions["FileUpdateChange"] as? JsonObject ?: return false
    val changeProperties = change["properties"] as? JsonObject ?: return false
    val itemProperties = ((item as? JsonObject)?.get("oneOf") as? JsonArray)
      ?.filterIsInstance<JsonObject>()
      ?.firstOrNull { candidate ->
        (candidate["title"] as? JsonPrimitive)?.content == "FileChangeThreadItem"
      }
      ?.get("properties") as? JsonObject ?: return false
    val commandProperties = ((item as? JsonObject)?.get("oneOf") as? JsonArray)
      ?.filterIsInstance<JsonObject>()
      ?.firstOrNull { candidate ->
        (candidate["title"] as? JsonPrimitive)?.content == "CommandExecutionThreadItem"
      }
      ?.get("properties") as? JsonObject ?: return false
    val changes = itemProperties["changes"] as? JsonObject ?: return false
    val changeReference = ((changes["items"] as? JsonObject)?.get("\$ref") as? JsonPrimitive)?.content
    val changeRequired = (change["required"] as? JsonArray).orEmpty()
      .mapNotNull { (it as? JsonPrimitive)?.content }
    command && fileChange && changeReference == "#/definitions/FileUpdateChange" &&
      schemaAllowsType(changes, "array", definitions, emptySet(), false) &&
      schemaAllowsType(commandProperties["command"], "string", definitions, emptySet(), false) &&
      schemaAllowsType(commandProperties["cwd"], "string", definitions, emptySet(), false) &&
      changeRequired.containsAll(setOf("diff", "kind", "path")) &&
      schemaAllowsType(changeProperties["diff"], "string", definitions, emptySet(), false) &&
      schemaAllowsType(changeProperties["path"], "string", definitions, emptySet(), false) &&
      schemaAllowsType(changeProperties["kind"], "object", definitions, emptySet(), false) &&
      codexDecisionTokens(changeProperties["kind"], definitions).containsAll(setOf("add", "delete", "update"))
  } catch (_: IOException) {
    false
  }
}

private fun codexItemVariant(
  node: JsonElement,
  definitions: JsonObject,
  variant: String,
  requiredFields: Set<String>,
  visited: Set<String> = emptySet(),
): Boolean {
  val objectSchema = node as? JsonObject ?: return false
  val properties = objectSchema["properties"] as? JsonObject
  val required = (objectSchema["required"] as? JsonArray).orEmpty()
    .mapNotNull { (it as? JsonPrimitive)?.content }
  val types = (((properties?.get("type") as? JsonObject)?.get("enum") as? JsonArray)
    ?.mapNotNull { (it as? JsonPrimitive)?.content }).orEmpty()
  if (variant in types && properties?.keys?.containsAll(requiredFields) == true &&
    required.containsAll(requiredFields)
  ) return true
  val reference = (objectSchema["\$ref"] as? JsonPrimitive)?.content
  if (reference != null && reference !in visited &&
    codexItemVariant(definitions[reference.substringAfterLast('/')] ?: return false, definitions, variant, requiredFields, visited + reference)
  ) return true
  return objectSchema.values.any { child ->
    when (child) {
      is JsonObject -> codexItemVariant(child, definitions, variant, requiredFields, visited)
      is JsonArray -> child.any { codexItemVariant(it, definitions, variant, requiredFields, visited) }
      else -> false
    }
  }
}

private fun codexDecisionTokens(element: JsonElement?, definitions: JsonObject): Set<String> {
  val found = linkedSetOf<String>()
  fun visit(node: JsonElement?, seen: Set<String>) {
    when (node) {
      is JsonObject -> {
        (node["enum"] as? JsonArray).orEmpty().mapNotNullTo(found) { (it as? JsonPrimitive)?.content }
        val ref = (node["\$ref"] as? JsonPrimitive)?.content
        if (ref != null && ref !in seen) visit(definitions[ref.substringAfterLast('/')], seen + ref)
        val props = node["properties"] as? JsonObject
        props?.keys?.filter { it.startsWith("accept") || it.startsWith("apply") }?.let(found::addAll)
        node.values.forEach { visit(it, seen) }
      }
      is JsonArray -> node.forEach { visit(it, seen) }
      else -> Unit
    }
  }
  visit(element, emptySet())
  return found
}

internal fun codexProjectionCompatibility(
  relative: String,
  fields: Set<String>,
  projection: JsonObject,
  definitions: JsonObject,
  request: ChatTurnRequest,
): String? {
  val properties = projection["properties"] as? JsonObject
    ?: return "The installed Codex app-server schema has no fields for $relative."
  if (!properties.keys.containsAll(fields)) {
    return "The installed Codex app-server protocol does not support the required $relative fields."
  }
  val required = (projection["required"] as? kotlinx.serialization.json.JsonArray)
    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
    .orEmpty()
  val outgoingFields = when (relative) {
    "v1/InitializeParams.json" -> setOf("clientInfo", "capabilities")
    "v2/ThreadStartParams.json" -> setOf("cwd") + setOfNotNull("model".takeIf { request.agent.model != null }) +
      setOfNotNull("sandbox".takeIf { request.mode == dev.skillbill.desktop.core.domain.model.ChatPermissionMode.ReadOnly })
    "v2/ThreadResumeParams.json" -> setOf("threadId", "cwd") +
      setOfNotNull("model".takeIf { request.agent.model != null }) +
      setOfNotNull("sandbox".takeIf { request.mode == dev.skillbill.desktop.core.domain.model.ChatPermissionMode.ReadOnly })
    "v2/TurnStartParams.json" -> setOf("threadId", "input", "cwd") +
      setOfNotNull(
        "model".takeIf { request.agent.model != null },
        "effort".takeIf { request.agent.effort != null },
        "sandboxPolicy",
      )
    else -> emptySet()
  }
  val launchParams = relative in setOf(
    "v1/InitializeParams.json", "v2/ThreadStartParams.json", "v2/ThreadResumeParams.json", "v2/TurnStartParams.json",
  )
  if (launchParams && !outgoingFields.containsAll(required)) {
    return "The installed Codex app-server protocol requires fields the adapter cannot supply " +
      "in $relative."
  }
  val consumedRequired = when (relative) {
    "v1/InitializeResponse.json" -> setOf("userAgent")
    "v2/ThreadStartResponse.json", "v2/ThreadResumeResponse.json" -> setOf("thread") +
      setOfNotNull("sandbox".takeIf { request.mode == dev.skillbill.desktop.core.domain.model.ChatPermissionMode.ReadOnly })
    "v2/TurnStartResponse.json" -> setOf("turn")
    "v2/AgentMessageDeltaNotification.json" -> setOf("threadId", "turnId", "itemId", "delta")
    "v2/ItemStartedNotification.json", "v2/ItemCompletedNotification.json" -> setOf("threadId", "turnId", "item")
    "v2/TurnCompletedNotification.json" -> setOf("threadId", "turn")
    "CommandExecutionRequestApprovalParams.json", "FileChangeRequestApprovalParams.json" ->
      setOf("itemId", "threadId", "turnId")
    "v2/ServerRequestResolvedNotification.json" -> setOf("requestId", "threadId")
    else -> emptySet()
  }
  if (!required.containsAll(consumedRequired)) {
    return "The installed Codex app-server protocol makes consumed fields optional in $relative."
  }
  val expectedTypes = when (relative) {
    "v1/InitializeParams.json" -> mapOf("clientInfo" to "object")
    "v1/InitializeResponse.json" -> mapOf("userAgent" to "string")
    "v2/ThreadStartParams.json" -> mapOf("cwd" to "string", "model" to "string", "sandbox" to "string")
    "v2/ThreadStartResponse.json", "v2/ThreadResumeResponse.json" ->
      mapOf("thread" to "object", "cwd" to "string", "model" to "string") +
        setOfNotNull("sandbox".takeIf {
          request.mode == dev.skillbill.desktop.core.domain.model.ChatPermissionMode.ReadOnly
        }).associateWith { "object" }
    "v2/ThreadResumeParams.json" -> mapOf("threadId" to "string", "cwd" to "string", "model" to "string", "sandbox" to "string")
    "v2/TurnStartParams.json" ->
      mapOf(
        "threadId" to "string",
        "input" to "array",
        "model" to "string",
        "effort" to "string",
        "cwd" to "string",
        "sandboxPolicy" to "object",
      )
    "CommandExecutionRequestApprovalParams.json" -> mapOf(
      "itemId" to "string", "threadId" to "string", "turnId" to "string", "startedAtMs" to "integer",
      "command" to "string", "cwd" to "string", "reason" to "string", "availableDecisions" to "array",
      "commandActions" to "array", "additionalPermissions" to "object", "networkApprovalContext" to "object",
      "approvalId" to "string", "environmentId" to "string", "kind" to "string",
      "proposedExecpolicyAmendment" to "array", "proposedNetworkPolicyAmendments" to "array",
    )
    "FileChangeRequestApprovalParams.json" -> mapOf(
      "itemId" to "string", "threadId" to "string", "turnId" to "string", "startedAtMs" to "integer",
      "reason" to "string", "grantRoot" to "string",
    )
    "v2/ServerRequestResolvedNotification.json" -> mapOf("requestId" to "string", "threadId" to "string")
    "v2/TurnStartResponse.json" -> mapOf("turn" to "object")
    "v2/AgentMessageDeltaNotification.json" ->
      mapOf("threadId" to "string", "turnId" to "string", "itemId" to "string", "delta" to "string")
    "v2/ItemStartedNotification.json", "v2/ItemCompletedNotification.json" ->
      mapOf("threadId" to "string", "turnId" to "string", "item" to "object")
    else -> mapOf("threadId" to "string", "turn" to "object")
  }
  val nullableRequestValues = when (relative) {
    "v2/ThreadStartParams.json" -> setOf("cwd") + setOfNotNull("model".takeIf { request.agent.model != null }) +
      setOfNotNull("sandbox".takeIf { request.mode == dev.skillbill.desktop.core.domain.model.ChatPermissionMode.ReadOnly })
    "v2/ThreadResumeParams.json" -> setOf("cwd") + setOfNotNull("model".takeIf { request.agent.model != null }) +
      setOfNotNull("sandbox".takeIf { request.mode == dev.skillbill.desktop.core.domain.model.ChatPermissionMode.ReadOnly })
    "v2/TurnStartParams.json" -> setOf("cwd") + setOfNotNull(
      "model".takeIf { request.agent.model != null },
      "effort".takeIf { request.agent.effort != null },
      "sandboxPolicy",
    )
    "CommandExecutionRequestApprovalParams.json" -> setOf(
      "command", "cwd", "reason", "availableDecisions", "commandActions", "additionalPermissions",
      "networkApprovalContext", "approvalId", "environmentId", "proposedExecpolicyAmendment",
      "proposedNetworkPolicyAmendments",
    )
    "FileChangeRequestApprovalParams.json" -> setOf("reason", "grantRoot")
    else -> emptySet()
  }
  val checkedTypes = if (launchParams) expectedTypes.filterKeys(outgoingFields::contains) else expectedTypes
  if (checkedTypes.any { (name, type) ->
      !schemaAllowsType(
        properties[name],
        type,
        definitions,
        emptySet(),
        allowNull = name in nullableRequestValues,
      )
    }
  ) {
    return "The installed Codex app-server protocol has incompatible field types in $relative."
  }
  run {
    val requiredToken = when (relative) {
      "v2/ThreadStartParams.json", "v2/ThreadResumeParams.json" ->
        ("read-only" to properties["sandbox"]).takeIf {
          request.mode == dev.skillbill.desktop.core.domain.model.ChatPermissionMode.ReadOnly
        }
      "v2/ThreadStartResponse.json", "v2/ThreadResumeResponse.json" ->
        ("readOnly" to properties["sandbox"]).takeIf {
          request.mode == dev.skillbill.desktop.core.domain.model.ChatPermissionMode.ReadOnly
        }
      "v2/TurnStartParams.json" -> (if (
        request.mode == dev.skillbill.desktop.core.domain.model.ChatPermissionMode.ReadOnly
      ) "readOnly" else "workspaceWrite") to properties["sandboxPolicy"]
      else -> null
    }
    if (requiredToken != null && requiredToken.first !in codexDecisionTokens(requiredToken.second, definitions)) {
      return "The installed Codex app-server protocol does not support the required read-only sandbox in $relative."
    }
  }
  return null
}

private fun schemaBindsMethod(
  schema: JsonElement,
  method: String,
  params: String?,
  definitions: JsonObject,
  visited: Set<String> = emptySet(),
): Boolean {
  val objectSchema = schema as? JsonObject ?: return false
  val ref = (objectSchema["\$ref"] as? JsonPrimitive)?.content?.substringAfterLast('/')
  if (ref != null && ref !in visited) {
    val target = definitions[ref]
    if (target != null && schemaBindsMethod(target, method, params, definitions, visited + ref)) return true
  }
  val properties = objectSchema["properties"] as? JsonObject
  val methodValues = ((properties?.get("method") as? JsonObject)?.get("enum") as? kotlinx.serialization.json.JsonArray)
    ?.mapNotNull { (it as? JsonPrimitive)?.content }
  val paramsRef =
    ((properties?.get("params") as? JsonObject)?.get("\$ref") as? JsonPrimitive)?.content?.substringAfterLast('/')
  val required = (objectSchema["required"] as? JsonArray)
    ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
  if (method in methodValues.orEmpty() && paramsRef == params && "method" in required &&
    (params == null || "params" in required)
  ) return true
  return objectSchema.values.any { child ->
    when (child) {
      is JsonObject -> schemaBindsMethod(child, method, params, definitions, visited)
      is kotlinx.serialization.json.JsonArray ->
        child.any { schemaBindsMethod(it, method, params, definitions, visited) }
      else -> false
    }
  }
}

private fun schemaSupportsInput(
  schema: kotlinx.serialization.json.JsonElement,
  variant: String,
  payload: String,
  definitions: JsonObject,
  visited: Set<String> = emptySet(),
): Boolean {
  val objectSchema = schema as? JsonObject ?: return false
  val reference = objectSchema["\$ref"] as? kotlinx.serialization.json.JsonPrimitive
  val referenceName = reference?.content?.substringAfterLast('/')
  if (referenceName != null && referenceName !in visited) {
    val referenced = definitions[referenceName]
    if (referenced != null && schemaSupportsInput(
        referenced,
        variant,
        payload,
        definitions,
        visited + referenceName,
      )
    ) return true
  }
  val properties = objectSchema["properties"] as? JsonObject
  val discriminator = properties?.obj("type")
  val const = discriminator?.get("const") as? kotlinx.serialization.json.JsonPrimitive
  val enumValues = (discriminator?.get("enum") as? kotlinx.serialization.json.JsonArray)
    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
  val payloadSchema = properties?.obj(payload)
  val required = (objectSchema["required"] as? kotlinx.serialization.json.JsonArray)
    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
  if ((const?.content == variant || variant in enumValues.orEmpty()) &&
    "type" in required && payload in required &&
    payloadSchema?.get("type")?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content == "string"
  ) return true
  return objectSchema.values.any { child ->
    when (child) {
      is JsonObject -> schemaSupportsInput(child, variant, payload, definitions, visited)
      is kotlinx.serialization.json.JsonArray -> child.any {
        schemaSupportsInput(it, variant, payload, definitions, visited)
      }
      else -> false
    }
  }
}

private fun schemaAllowsType(
  schema: JsonElement?,
  expected: String,
  definitions: JsonObject,
  visited: Set<String>,
  allowNull: Boolean,
): Boolean {
  val objectSchema = schema as? JsonObject ?: return false
  val reference = (objectSchema["\$ref"] as? JsonPrimitive)?.content?.substringAfterLast('/')
  if (reference != null && reference !in visited) {
    val resolved = definitions[reference]
    if (resolved != null) return schemaAllowsType(resolved, expected, definitions, visited + reference, allowNull)
  }
  val type = objectSchema["type"]
  val types = when (type) {
    is JsonPrimitive -> setOf(type.content)
    is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
    else -> emptySet()
  }
  if (expected in types && (allowNull || "null" !in types)) return true
  return listOf("allOf", "anyOf", "oneOf").any { key ->
    (objectSchema[key] as? JsonArray)?.any { schemaAllowsType(it, expected, definitions, visited, allowNull) } == true
  }
}
