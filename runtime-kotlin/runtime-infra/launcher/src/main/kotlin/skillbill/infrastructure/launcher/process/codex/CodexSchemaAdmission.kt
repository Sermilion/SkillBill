package skillbill.infrastructure.launcher.process.codex

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.nio.file.Path
import skillbill.infrastructure.launcher.process.codex.CodexRpcKeys as K

fun codexVersionRefusal(versionOutput: String): String? {
  val expected = Regex("(?:^|\\s)codex-cli ${Regex.escape(CODEX_PROTOCOL_VERSION)}(?:$|\\s)")
  return if (expected.containsMatchIn(versionOutput.trim())) {
    null
  } else {
    "The installed Codex CLI is incompatible with the supported $CODEX_PROTOCOL_VERSION app-server."
  }
}

object CodexSchemaAdmission {
  private val mapper = ObjectMapper()

  fun inspect(
    directory: Path,
    settings: CodexThreadSettings,
    localImages: Boolean = false,
  ): String? =
    try {
      inspectShapes(directory, settings.model, settings.effort, settings.workspaceRoots.isNotEmpty(), settings.readOnly)
        ?: inspectLocalImages(directory).takeIf { localImages }
        ?: inspectAutoReview(directory).takeIf { settings.autoReview }
    } catch (failure: IOException) {
      "Could not read the installed Codex app-server schema: ${failure.message.orEmpty()}"
    }

  private fun inspectLocalImages(directory: Path): String? {
    val input = read(directory, "v2/TurnStartParams.json")
    val variant =
      input.path(S.DEFINITIONS).path("UserInput").path(S.ONE_OF).firstOrNull {
        val types = it.path(S.PROPERTIES).path(K.TYPE).path(S.ENUM)
        types.any { token -> token.asText() == CodexAppServerProtocol.LOCAL_IMAGE_TYPE }
      }
    val supported =
      variant != null &&
        variant.path(S.REQUIRED).map(JsonNode::asText).containsAll(setOf(K.TYPE, K.PATH)) &&
        allows(variant.path(S.PROPERTIES).path(K.PATH), input, "string", false, emptySet())
    return if (supported) null else "The installed Codex app-server protocol does not support local image inputs."
  }

  private fun inspectAutoReview(directory: Path): String? {
    val supported =
      listOf("v2/ThreadStartParams.json", "v2/TurnStartParams.json").all { file ->
        val schema = read(directory, file)
        val properties = schema.path(S.PROPERTIES)
        enumTokens(properties.path(K.APPROVAL_POLICY), schema, emptySet()).contains(K.ON_REQUEST_POLICY) &&
          enumTokens(properties.path(K.APPROVALS_REVIEWER), schema, emptySet()).contains(K.AUTO_REVIEW_REVIEWER)
      }
    return if (supported) null else "The installed Codex app-server cannot route approvals to its auto-review agent."
  }

  private fun inspectShapes(
    directory: Path,
    model: String?,
    effort: String?,
    workspaceRoots: Boolean,
    readOnly: Boolean,
  ): String? {
    val outgoing =
      listOfNotNull(
        K.CWD,
        K.MODEL.takeIf { model != null },
        K.WORKSPACE_ROOTS.takeIf { workspaceRoots && !readOnly },
        K.SANDBOX.takeIf { readOnly },
      ).toSet()
    val turnFields =
      (outgoing - setOf(K.WORKSPACE_ROOTS, K.SANDBOX)) + setOf(K.THREAD_ID, K.INPUT) +
        listOfNotNull(K.EFFORT.takeIf { effort != null }, K.SANDBOX_POLICY)
    val projections = sessionShapes(outgoing, turnFields, readOnly) + approvalShapes()
    for (shape in projections) {
      val schema = read(directory, shape.file)
      if (!acceptsShape(schema, shape)) {
        return "The installed Codex app-server has incompatible fields in ${shape.file}."
      }
    }
    val bindings = methodBindings()
    for ((file, methods) in bindings) {
      val schema = read(directory, file)
      if (methods.any { (method, params) -> !binds(schema, schema, method, params, emptySet()) }) {
        return "The installed Codex app-server has incompatible method bindings in $file."
      }
    }
    if (!sandboxPoliciesCompatible(directory, readOnly)) {
      return "The installed Codex app-server does not support the required read-only sandbox shapes."
    }
    return inspectInitialization(
      directory,
    ) ?: inspectNested(directory) ?: inspectApprovalDecisions(directory) ?: CodexRefusalSchema.inspect(directory)
  }

  private fun sandboxPoliciesCompatible(
    directory: Path,
    readOnly: Boolean,
  ): Boolean {
    val start = read(directory, "v2/ThreadStartParams.json")
    val resume = read(directory, "v2/ThreadResumeParams.json")
    val turn = read(directory, "v2/TurnStartParams.json")
    val startSandbox = start.path(S.PROPERTIES).path(K.SANDBOX)
    val resumeSandbox = resume.path(S.PROPERTIES).path(K.SANDBOX)
    val policy = turn.path(S.PROPERTIES).path(K.SANDBOX_POLICY)
    val responses =
      listOf("v2/ThreadStartResponse.json", "v2/ThreadResumeResponse.json")
        .map { file -> read(directory, file).let { it.path(S.PROPERTIES).path(K.SANDBOX) to it } }
    return (
      !readOnly ||
        listOf(startSandbox to start, resumeSandbox to resume).all { (sandbox, root) ->
          enumTokens(sandbox, root, emptySet()).contains(K.READ_ONLY_SANDBOX)
        }
    ) && (
      !readOnly ||
        responses.all { (sandbox, root) ->
          enumTokens(sandbox, root, emptySet()).contains(K.READ_ONLY_POLICY)
        }
    ) && enumTokens(policy, turn, emptySet()).contains(K.WORKSPACE_WRITE_POLICY) &&
      (!readOnly || enumTokens(policy, turn, emptySet()).contains(K.READ_ONLY_POLICY))
  }

  private fun sessionShapes(
    outgoing: Set<String>,
    turnFields: Set<String>,
    readOnly: Boolean,
  ): List<Shape> =
    listOf(
      Shape(
        "v1/InitializeParams.json",
        mapOf(K.CLIENT_INFO to "object", K.CAPABILITIES to "object"),
        setOf(K.CLIENT_INFO, K.CAPABILITIES),
      ),
      Shape("v1/InitializeResponse.json", mapOf(K.USER_AGENT to "string")),
      Shape(
        "v2/ThreadStartParams.json",
        threadRequestFields + listOfNotNull(K.SANDBOX.takeIf { readOnly }?.let { it to "string" }),
        outgoing,
        threadRequestFields.keys + listOfNotNull(K.SANDBOX.takeIf { readOnly }),
      ),
      Shape(
        "v2/ThreadResumeParams.json",
        threadRequestFields +
          listOfNotNull(K.SANDBOX.takeIf { readOnly }?.let { it to "string" }) + (K.THREAD_ID to "string"),
        outgoing + K.THREAD_ID,
        threadRequestFields.keys + listOfNotNull(K.SANDBOX.takeIf { readOnly }),
      ),
      Shape("v2/ThreadStartResponse.json", threadResponseFields(readOnly)),
      Shape("v2/ThreadResumeResponse.json", threadResponseFields(readOnly)),
      Shape(
        "v2/TurnStartParams.json",
        (turnFields + setOf(K.MODEL, K.EFFORT)).associateWith {
          when (it) {
            K.INPUT -> "array"
            K.SANDBOX_POLICY -> "object"
            else -> "string"
          }
        },
        turnFields,
        nullableFields = setOf(K.CWD, K.MODEL, K.EFFORT, K.SANDBOX_POLICY),
      ),
      Shape("v2/TurnStartResponse.json", mapOf(K.TURN to "object")),
      Shape("v2/AgentMessageDeltaNotification.json", deltaFields),
      Shape(
        "v2/ItemStartedNotification.json",
        mapOf(K.THREAD_ID to "string", K.TURN_ID to "string", K.ITEM to "object", K.STARTED_AT_MS to "integer"),
      ),
      Shape(
        "v2/ItemCompletedNotification.json",
        mapOf(K.THREAD_ID to "string", K.TURN_ID to "string", K.ITEM to "object"),
      ),
      Shape("v2/TurnCompletedNotification.json", mapOf(K.THREAD_ID to "string", K.TURN to "object")),
    )

  private fun approvalShapes(): List<Shape> =
    listOf(
      Shape(
        "CommandExecutionRequestApprovalParams.json",
        mapOf(
          K.ITEM_ID to "string", K.THREAD_ID to "string", K.TURN_ID to "string", K.STARTED_AT_MS to "integer",
          K.COMMAND to "string", K.CWD to "string", K.REASON to "string", K.AVAILABLE_DECISIONS to "array",
          K.COMMAND_ACTIONS to "array", K.ADDITIONAL_PERMISSIONS to "object", K.NETWORK_APPROVAL_CONTEXT to "object",
          K.APPROVAL_ID to "string", K.ENVIRONMENT_ID to "string", K.KIND to "string",
          K.PROPOSED_EXECPOLICY_AMENDMENT to "array", K.PROPOSED_NETWORK_POLICY_AMENDMENTS to "array",
        ),
        setOf(K.ITEM_ID, K.THREAD_ID, K.TURN_ID, K.STARTED_AT_MS),
        nullableFields =
          setOf(
            K.COMMAND, K.CWD, K.REASON, K.AVAILABLE_DECISIONS, K.COMMAND_ACTIONS,
            K.ADDITIONAL_PERMISSIONS, K.NETWORK_APPROVAL_CONTEXT, K.APPROVAL_ID, K.ENVIRONMENT_ID,
            K.PROPOSED_EXECPOLICY_AMENDMENT, K.PROPOSED_NETWORK_POLICY_AMENDMENTS,
          ),
      ),
      Shape(
        "FileChangeRequestApprovalParams.json",
        mapOf(
          K.ITEM_ID to "string",
          K.THREAD_ID to "string",
          K.TURN_ID to "string",
          K.STARTED_AT_MS to "integer",
          K.GRANT_ROOT to "string",
          K.REASON to "string",
        ),
        setOf(K.ITEM_ID, K.THREAD_ID, K.TURN_ID, K.STARTED_AT_MS),
        nullableFields = setOf(K.GRANT_ROOT, K.REASON),
      ),
      Shape("CommandExecutionRequestApprovalResponse.json", mapOf(K.PROVIDER_DECISION to "string")),
      Shape("FileChangeRequestApprovalResponse.json", mapOf(K.PROVIDER_DECISION to "string")),
      Shape("v2/ServerRequestResolvedNotification.json", mapOf(K.REQUEST_ID to "string", K.THREAD_ID to "string")),
    )

  private fun methodBindings(): Map<String, Map<String, String?>> =
    mapOf(
      "ClientRequest.json" to
        mapOf(
          K.INITIALIZE_METHOD to "InitializeParams",
          K.THREAD_START_METHOD to "ThreadStartParams",
          K.THREAD_RESUME_METHOD to "ThreadResumeParams",
          K.TURN_START_METHOD to "TurnStartParams",
        ),
      "ClientNotification.json" to mapOf(K.INITIALIZED to null),
      "ServerNotification.json" to
        mapOf(
          "item/agentMessage/delta" to "AgentMessageDeltaNotification",
          K.ITEM_STARTED_METHOD to "ItemStartedNotification",
          K.ITEM_COMPLETED_METHOD to "ItemCompletedNotification",
          K.TURN_COMPLETED_METHOD to "TurnCompletedNotification",
          "serverRequest/resolved" to "ServerRequestResolvedNotification",
        ),
      "ServerRequest.json" to
        mapOf(
          K.ITEM_COMMAND_APPROVAL to "CommandExecutionRequestApprovalParams",
          K.ITEM_FILE_APPROVAL to "FileChangeRequestApprovalParams",
        ),
    )

  private fun inspectApprovalDecisions(directory: Path): String? {
    val command = read(directory, "CommandExecutionRequestApprovalParams.json")
    val available = command.path(S.PROPERTIES).path(K.AVAILABLE_DECISIONS).path(S.ITEMS)
    val commandChoices = decisionTokens(available, command, emptySet())
    val commandRequired =
      setOf(
        K.ACCEPT,
        K.DECLINE,
        K.CANCEL,
        K.ACCEPT_FOR_SESSION,
        K.ACCEPT_WITH_EXECPOLICY_AMENDMENT,
        K.APPLY_NETWORK_POLICY_AMENDMENT,
      )
    val commandResponse = read(directory, "CommandExecutionRequestApprovalResponse.json")
    val commandResponseChoices =
      decisionTokens(
        commandResponse.path(S.PROPERTIES).path(K.PROVIDER_DECISION),
        commandResponse,
        emptySet(),
      )
    val file = read(directory, "FileChangeRequestApprovalResponse.json")
    val fileResponseChoices = decisionTokens(file.path(S.PROPERTIES).path(K.PROVIDER_DECISION), file, emptySet())
    val fileRequired = setOf(K.ACCEPT, K.DECLINE, K.CANCEL, K.ACCEPT_FOR_SESSION)
    return if (!commandChoices.containsAll(commandRequired) || !commandResponseChoices.containsAll(commandRequired) ||
      !fileResponseChoices.containsAll(fileRequired)
    ) {
      "The installed Codex app-server approval decision union is incompatible " +
        "with the supported adapter."
    } else {
      null
    }
  }

  private fun decisionTokens(
    node: JsonNode,
    root: JsonNode,
    visited: Set<String>,
  ): Set<String> {
    val tokens = linkedSetOf<String>()
    val reference = node.path(S.REF).takeIf(JsonNode::isTextual)?.asText()
    if (reference != null && reference !in visited) {
      tokens += decisionTokens(root.at(reference.removePrefix("#")), root, visited + reference)
    }
    node.path(S.ENUM).filter(JsonNode::isTextual).mapTo(tokens) { it.asText() }
    node.path(S.PROPERTIES).fieldNames().asSequence()
      .filter { it.startsWith("accept") || it.startsWith("apply") }
      .toCollection(tokens)
    listOf(S.ANY_OF, S.ONE_OF, S.ALL_OF).forEach { key ->
      node.path(key).forEach { child -> tokens += decisionTokens(child, root, visited) }
    }
    return tokens
  }

  private fun enumTokens(
    node: JsonNode,
    root: JsonNode,
    visited: Set<String>,
  ): Set<String> {
    val tokens = linkedSetOf<String>()
    val reference = node.path(S.REF).takeIf(JsonNode::isTextual)?.asText()
    if (reference != null && reference !in visited) {
      tokens += enumTokens(root.at(reference.removePrefix("#")), root, visited + reference)
    }
    node.path(S.ENUM).filter(JsonNode::isTextual).mapTo(tokens) { it.asText() }
    val children = node.elements()
    while (children.hasNext()) tokens += enumTokens(children.next(), root, visited)
    return tokens
  }

  private fun inspectInitialization(directory: Path): String? {
    val schema = read(directory, "v1/InitializeParams.json")
    val clientInfo = schema.path(S.DEFINITIONS).path("ClientInfo")
    val capabilities = schema.path(S.DEFINITIONS).path("InitializeCapabilities")
    val clientFields = mapOf(K.NAME to "string", K.TITLE to "string", K.VERSION to "string")
    val capabilityFields = mapOf(K.EXPERIMENTAL_API to "boolean")
    val supported =
      acceptsShape(schema, Shape("ClientInfo", clientFields, clientFields.keys, setOf(K.TITLE)), clientInfo) &&
        acceptsShape(schema, Shape("InitializeCapabilities", capabilityFields, capabilityFields.keys), capabilities)
    return if (supported) {
      null
    } else {
      "The installed Codex app-server lacks the required initialization " +
        "capability shape."
    }
  }

  private fun inspectNested(directory: Path): String? {
    val schema = read(directory, "v2/TurnCompletedNotification.json")
    val turn = schema.path(S.DEFINITIONS).path("Turn")
    val turnShape = Shape("Turn", mapOf(K.ID to "string", K.STATUS to "string"))
    val input = read(directory, "v2/TurnStartParams.json")
    val inputDefinition = input.path(S.DEFINITIONS).path("UserInput")
    val textVariant =
      inputDefinition.path(S.ONE_OF).firstOrNull {
        it.path(S.PROPERTIES).path("type").path(S.ENUM).any { token -> token.asText() == "text" }
      }
    val textRequired = textVariant?.path(S.REQUIRED)?.map(JsonNode::asText).orEmpty()
    val supplied = setOf(K.TYPE, K.TEXT, K.TEXT_ELEMENTS)
    val validText =
      textVariant?.let {
        acceptsShape(input, Shape("TextUserInput", mapOf(K.TEXT to "string", K.TYPE to "string"), supplied), it)
      } == true && textRequired.containsAll(setOf(K.TYPE, K.TEXT))
    val inputBinding = input.path(S.PROPERTIES).path(K.INPUT).path(S.ITEMS).path(S.REF).asText()
    val boundInput = inputBinding == "#/definitions/UserInput"
    val itemStarted = read(directory, "v2/ItemStartedNotification.json")
    val item = itemStarted.path(S.DEFINITIONS).path("ThreadItem")
    val commandItem = itemVariant(item, "CommandExecutionThreadItem", K.COMMAND_EXECUTION_TYPE)
    val fileItem = itemVariant(item, "FileChangeThreadItem", K.FILE_CHANGE_TYPE)
    val validItems = validStartedItems(itemStarted, commandItem, fileItem)
    val supported = listOf(acceptsShape(schema, turnShape, turn), validText, boundInput, validItems).all { it }
    return if (!supported) {
      "The installed Codex app-server does not provide the required turn or " +
        "text-input shape."
    } else {
      null
    }
  }

  private fun validStartedItems(
    root: JsonNode,
    command: JsonNode,
    file: JsonNode,
  ): Boolean {
    val commandFields = setOf(K.ID, K.TYPE, K.COMMAND, K.CWD)
    val fileFields = setOf(K.ID, K.TYPE, K.CHANGES)
    val commandShape = command.path(S.REQUIRED).map(JsonNode::asText).toSet().containsAll(commandFields)
    val fileShape = file.path(S.REQUIRED).map(JsonNode::asText).toSet().containsAll(fileFields)
    val commandTypes =
      setOf(K.COMMAND, K.CWD).all {
        allows(command.path(S.PROPERTIES).path(it), root, "string", false, emptySet())
      }
    return listOf(commandShape, fileShape, commandTypes, validFileChanges(root, file)).all { it }
  }

  private fun validFileChanges(
    root: JsonNode,
    item: JsonNode,
  ): Boolean {
    val ref = item.path(S.PROPERTIES).path(K.CHANGES).path(S.ITEMS).path(S.REF).asText()
    if (!ref.startsWith("#/definitions/")) return false
    val change = root.at(ref.removePrefix("#"))
    val fields = mapOf(K.PATH to "string", K.DIFF to "string", K.KIND to "object")
    val shape = change.path(S.REQUIRED).map(JsonNode::asText).toSet().containsAll(fields.keys)
    val types =
      fields.all {
          (name, type) ->
        allows(change.path(S.PROPERTIES).path(name), root, type, false, emptySet())
      }
    val kinds =
      enumTokens(
        change.path(S.PROPERTIES).path(K.KIND),
        root,
        emptySet(),
      ).containsAll(CodexPatchKind.entries.map(CodexPatchKind::wireValue))
    return shape && types && kinds
  }

  private fun itemVariant(
    node: JsonNode,
    title: String,
    discriminator: String,
  ): JsonNode =
    node.path(S.ONE_OF).firstOrNull { candidate ->
      candidate.path("title").asText() == title &&
        candidate.path(S.PROPERTIES).path(K.TYPE).path(S.ENUM).any { it.asText() == discriminator }
    } ?: mapper.missingNode()

  private fun acceptsShape(
    root: JsonNode,
    shape: Shape,
    schema: JsonNode = root,
  ): Boolean {
    val properties = schema.path(S.PROPERTIES)
    val required = schema.path(S.REQUIRED).map(JsonNode::asText).toSet()
    val fields = shape.fields
    val completeness = shape.outgoing?.containsAll(required) ?: required.containsAll(shape.fields.keys)
    return completeness &&
      fields.all { (name, type) ->
        allows(properties.path(name), root, type, name in shape.nullableFields, emptySet())
      }
  }

  private fun allows(
    node: JsonNode,
    root: JsonNode,
    type: String,
    nullable: Boolean,
    visited: Set<String>,
  ): Boolean {
    val reference = node.path(S.REF).takeIf(JsonNode::isTextual)?.asText()
    val resolved = reference?.takeIf { it !in visited }?.let { root.at(it.removePrefix("#")) }
    if (resolved != null) return allows(resolved, root, type, nullable, visited + reference)
    val declared = node.path(S.TYPE)
    val types = if (declared.isArray) declared.map(JsonNode::asText) else listOf(declared.asText())
    return type in types && (nullable || "null" !in types) ||
      listOf(S.ANY_OF, S.ONE_OF, S.ALL_OF).any { key ->
        node.path(key).any { allows(it, root, type, nullable, visited) }
      }
  }

  private fun binds(
    node: JsonNode,
    root: JsonNode,
    method: String,
    params: String?,
    visited: Set<String>,
  ): Boolean {
    val reference = node.path(S.REF).takeIf(JsonNode::isTextual)?.asText()
    val resolved = reference?.takeIf { it !in visited }?.let { root.at(it.removePrefix("#")) }
    if (resolved != null && binds(resolved, root, method, params, visited + reference)) return true
    val properties = node.path(S.PROPERTIES)
    val methods = properties.path(K.METHOD).path(S.ENUM).map(JsonNode::asText)
    val parameter = properties.path(K.PARAMS).path(S.REF).asText().substringAfterLast('/')
    val required = node.path(S.REQUIRED).map(JsonNode::asText)
    val matches =
      method in methods && K.METHOD in required &&
        (params == null || parameter == params && K.PARAMS in required)
    return matches ||
      listOf(S.ANY_OF, S.ONE_OF, S.ALL_OF).any { key ->
        node.path(key).any { binds(it, root, method, params, visited) }
      }
  }

  private fun read(
    directory: Path,
    file: String,
  ): JsonNode = mapper.readTree(directory.resolve(file).toFile()) ?: mapper.missingNode()

  private data class Shape(
    val file: String,
    val fields: Map<String, String>,
    val outgoing: Set<String>? = null,
    val nullableFields: Set<String> = emptySet(),
  )

  private val threadRequestFields =
    mapOf(
      K.CWD to "string",
      K.MODEL to "string",
      K.WORKSPACE_ROOTS to "array",
    )

  private fun threadResponseFields(readOnly: Boolean) =
    mapOf(
      K.THREAD to "object",
      K.CWD to "string",
      K.MODEL to "string",
    ) + listOfNotNull(K.SANDBOX.takeIf { readOnly }?.let { it to "object" })

  private val deltaFields =
    mapOf(
      K.THREAD_ID to "string",
      K.TURN_ID to "string",
      K.ITEM_ID to "string",
      K.DELTA to "string",
    )

  private object S {
    const val DEFINITIONS = "definitions"
    const val PROPERTIES = "properties"
    const val REQUIRED = "required"
    const val TYPE = K.TYPE
    const val ITEMS = "items"
    const val REF = "\$ref"
    const val ENUM = "enum"
    const val ANY_OF = "anyOf"
    const val ONE_OF = "oneOf"
    const val ALL_OF = "allOf"
  }
}
