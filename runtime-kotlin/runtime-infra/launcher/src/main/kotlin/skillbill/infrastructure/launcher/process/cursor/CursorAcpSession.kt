package skillbill.infrastructure.launcher.process.cursor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.approval.ApprovalOwnerEffects
import skillbill.ports.approval.model.ApprovalDeliveryState
import skillbill.ports.approval.model.ApprovalOwnerSession
import skillbill.ports.approval.model.ApprovalPendingWait
import java.time.Clock
import java.time.Duration
import skillbill.infrastructure.launcher.process.cursor.CursorAcpKeys as K

data class CursorAcpLaunch(
  val cwd: String,
  val prompt: String,
  val model: String?,
  val mode: String?,
  val savedSession: String? = null,
  val images: List<CursorAcpImage> = emptyList(),
  val mcpDependency: CursorAcpMcpDependency = CursorAcpMcpDependency.ProjectUserOnly,
  val provider: AcpProvider = AcpProvider.Cursor,
  val mcpServers: List<AcpStdioMcpServer> = emptyList(),
  val additionalDirectories: List<String> = emptyList(),
  val modelOnCommandLine: Boolean = false,
) {
  constructor(
    cwd: String,
    prompt: String,
    model: String?,
    mode: String?,
    savedSession: String?,
    images: List<CursorAcpImage>,
  ) : this(cwd, prompt, model, mode, savedSession, images, CursorAcpMcpDependency.ProjectUserOnly)
}

fun commandCarriesModel(
  command: List<String>,
  model: String?,
): Boolean = model != null && command.windowed(2).any { it == listOf("--model", model) }

enum class CursorAcpMcpDependency { ProjectUserOnly, TeamDashboardRequired }

fun cursorAcpDependencyRefusal(dependency: CursorAcpMcpDependency): String? =
  when (dependency) {
    CursorAcpMcpDependency.TeamDashboardRequired ->
      "Cursor ACP does not provide team-dashboard MCP servers; this request is unsupported and was not started."
    CursorAcpMcpDependency.ProjectUserOnly -> null
  }

data class CursorAcpImage(val data: String, val mimeType: String)

data class AcpStdioMcpServer(
  val name: String,
  val command: String,
  val args: List<String>,
  val env: List<AcpEnvVariable>,
)

data class AcpEnvVariable(val name: String, val value: String)

sealed interface CursorAcpEvent {
  data class Text(val text: String) : CursorAcpEvent

  data class Tool(val update: JsonObject) : CursorAcpEvent

  data class Unsupported(val method: String, val payload: String) : CursorAcpEvent
}

data class CursorAcpOutcome(
  val text: String,
  val sessionId: String?,
  val model: String?,
  val failure: String?,
)

class CursorAcpSession(
  private val launch: CursorAcpLaunch,
  private val clock: Clock,
  private val writeLine: (String) -> Boolean,
  private val saveSession: (String) -> Boolean,
  private val onEvent: (CursorAcpEvent) -> Unit,
  private val onCard: (ApprovalRequestRecord) -> Unit,
  private val record: (String) -> Unit,
  private val onModel: (String) -> Unit = {},
) : ApprovalOwnerEffects {
  private val lock = Any()
  private val providerName = launch.provider.name
  private val seam = launch.provider.seam
  private val startedAt = clock.instant()
  private var expectedId = 1
  private var expectedMethod = K.INITIALIZE
  private var sessionId: String? = launch.savedSession
  private var capabilities = JsonObject(emptyMap())
  private var model: String? = null
  private var selectedModel: String? = null
  private var pendingConfigId: String? = null
  private var pendingConfigValue: String? = null
  private var pendingConfigIsMode = false
  private var selectingBypass = false
  private var bypassEnabled = false
  private var setup = JsonObject(emptyMap())
  private var closed = false
  private var closing = false
  private val text = StringBuilder()
  private var textTruncated = false
  private var result: CursorAcpOutcome? = null
  private var permissions: CursorAcpPermissions? = null
  private var providerFailure: String? = null

  @Volatile var ready: Boolean = false
    private set

  fun bind(owner: ApprovalOwnerSession) {
    synchronized(lock) {
      check(permissions == null && !closed)
      permissions = CursorAcpPermissions(owner, launch.cwd, ::write, onCard, ::stop, record, launch.provider)
    }
  }

  fun start() {
    if (launch.provider.lacksTeamDashboardMcp) {
      cursorAcpDependencyRefusal(launch.mcpDependency)?.let {
        record("seam=$seam outcome=dependency_unsupported")
        return stop(it)
      }
    }
    send(
      K.INITIALIZE,
      buildJsonObject {
        put(K.PROTOCOL_VERSION, 1)
        put(K.CLIENT_CAPABILITIES, buildJsonObject {})
        put(
          K.CLIENT_INFO,
          buildJsonObject {
            put(K.NAME, "skill-bill")
            put(K.VERSION, "1.0.0")
          },
        )
      },
    )
  }

  fun observe(line: String) {
    if (outcome() != null) return
    if (line.length > MAX_CAPTURE) return stop("$providerName sent an oversized ACP frame.")
    val message =
      catchingNonCancellation { Json.parseToJsonElement(line) }.getOrNull() as? JsonObject
        ?: return stop("$providerName sent a malformed ACP frame.")
    if (message.text(K.JSONRPC) != "2.0") return stop("$providerName sent an incompatible JSON-RPC envelope.")
    val method = message.text(K.METHOD)
    when {
      method != null && message[K.ID] != null -> blocking(message, method)
      method == K.SESSION_UPDATE_METHOD -> updateMessage(message)
      method != null -> record("seam=$seam outcome=notification_ignored method=$method")
      message[K.ID] == JsonPrimitive(expectedId) -> response(message)
      else -> stop("$providerName returned an unexpected ACP response identity.")
    }
  }

  private fun updateMessage(message: JsonObject) {
    val params = message.obj(K.PARAMS) ?: return stop("$providerName update has no parameters.")
    update(params)
  }

  private fun response(message: JsonObject) {
    val body =
      message.obj(K.RESULT)
        ?: return stop(acpRejection(launch, expectedMethod, message.obj(K.ERROR), selectingModel()))
    when (expectedMethod) {
      K.INITIALIZE -> initialize(body)
      K.AUTHENTICATE -> acquireSession()
      K.SESSION_NEW, K.SESSION_LOAD -> acquired(body)
      K.SESSION_SET_CONFIG -> acceptConfiguration(body)
      K.SESSION_SET_MODEL -> acceptLegacyModel(body)
      K.SESSION_SET_MODE -> prompt()
      K.SESSION_PROMPT -> {
        val reason = body.text(K.STOP_REASON)
        val failure = providerFailure ?: if (reason == "end_turn") null else "$providerName prompt ended with $reason."
        finish(failure)
      }
    }
  }

  private fun acceptConfiguration(body: JsonObject) {
    if (selectingBypass) return acceptBypass(body)
    val acknowledged =
      (body[K.CONFIG_OPTIONS] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.any {
        it.text(K.ID) == pendingConfigId && it.text(K.CURRENT_VALUE) == pendingConfigValue
      }
    if (!acknowledged) {
      stop("$providerName did not acknowledge the selected session configuration.")
    } else if (pendingConfigIsMode) {
      prompt()
    } else {
      model = selectedModel
      model?.let(onModel)
      selectMode()
    }
  }

  private fun acceptLegacyModel(body: JsonObject) {
    if (!launch.provider.exactModels) {
      model = selectedModel
    } else if (selectedModel != null && body.text(K.MODEL_ID) == selectedModel) {
      model = selectedModel
      model?.let(onModel)
    } else {
      record("seam=$seam outcome=model_selected_unconfirmed reported_identity=unavailable")
    }
    selectMode()
  }

  private fun configUpdate(update: JsonObject) {
    val value =
      (update[K.CONFIG_OPTIONS] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .firstOrNull { it.text(K.CATEGORY) == "model" }
        ?.let { it.text(K.CURRENT_VALUE) ?: "" }
        ?: return record("seam=$seam outcome=informational_update")
    model = value.ifEmpty { null }
    if (model == null) {
      record("seam=$seam outcome=model_update_malformed reported_identity=unavailable")
    } else if (model != selectedModel) {
      record("seam=$seam outcome=model_changed_by_provider selected=$selectedModel reported=$model")
    }
    model?.let(onModel)
  }

  private fun initialize(body: JsonObject) {
    if ((body[K.PROTOCOL_VERSION] as? JsonPrimitive)?.intOrNull != 1) {
      return stop("$providerName ACP protocol version 1 is required; no prompt was sent.")
    }
    capabilities = body.obj(K.AGENT_CAPABILITIES) ?: JsonObject(emptyMap())
    if (launch.savedSession != null && !capabilities.flag(K.LOAD_SESSION)) {
      return stop(
        "$providerName cannot load the saved session. Start an explicit new chat; history " +
          "was not replaced.",
      )
    }
    if (launch.images.isNotEmpty() && capabilities.obj(K.PROMPT_CAPABILITIES)?.flag(K.IMAGE) != true) {
      return stop("$providerName did not advertise image prompts; no prompt was sent.")
    }
    sessionContextRefusal(launch, capabilities)?.let { reason ->
      record("seam=$seam outcome=session_context_unsupported")
      return stop(reason)
    }
    login(body)
  }

  private fun login(body: JsonObject) {
    val method = launch.provider.authMethodId ?: return acquireSession()
    val login =
      (body[K.AUTH_METHODS] as? JsonArray).orEmpty().any {
        (it as? JsonObject)?.text(K.ID) == method
      }
    if (!login) return stop("$providerName did not advertise $method authentication; no prompt was sent.")
    send(K.AUTHENTICATE, buildJsonObject { put(K.METHOD_ID, method) })
  }

  private fun acquireSession() {
    send(
      if (launch.savedSession == null) K.SESSION_NEW else K.SESSION_LOAD,
      buildJsonObject {
        put(K.CWD, launch.cwd)
        put(K.MCP_SERVERS, acpMcpServers(launch.mcpServers))
        if (launch.additionalDirectories.isNotEmpty()) {
          put(K.ADDITIONAL_DIRECTORIES, JsonArray(launch.additionalDirectories.map(::JsonPrimitive)))
        }
        launch.savedSession?.let { put(K.SESSION_ID, it) }
      },
    )
  }

  private fun acquired(body: JsonObject) {
    val acquired = if (launch.savedSession == null) body.text(K.SESSION_ID) else launch.savedSession
    val returned = body.text(K.SESSION_ID)
    if (acquired.isNullOrBlank() || (returned != null && returned != acquired)) {
      return stop("$providerName did not preserve the provider session; start an explicit new chat.")
    }
    sessionId = acquired
    permissions?.bindSession(acquired)
    setup = body
    if (!saveSession(
        acquired,
      )
    ) {
      return stop("The $providerName provider session could not be saved; no prompt was sent.")
    }
    selectModel()
  }

  private fun selectModel() {
    val requested = launch.model
    val config =
      (setup[K.CONFIG_OPTIONS] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .firstOrNull { it.text(K.CATEGORY) == "model" }
    if (requested == null) {
      model = config?.text(K.CURRENT_VALUE) ?: setup.obj(K.MODELS)?.text(K.CURRENT_MODEL_ID)
      model?.let(onModel)
      return selectMode()
    }
    if (config == null && launch.provider.exactModels && setup.obj(K.MODELS) == null) {
      if (!launch.modelOnCommandLine) {
        record("seam=$seam outcome=model_metadata_absent_unconfirmed")
        return stop(
          "$providerName did not report its models, so the selected model '$requested' could not be confirmed; " +
            "no prompt was sent.",
        )
      }
      record("seam=$seam outcome=model_metadata_absent reported_identity=unavailable")
      return selectMode()
    }
    if (config == null) selectLegacyModel(requested) else configureModel(config, requested)
  }

  private fun selectingModel(): Boolean =
    expectedMethod == K.SESSION_SET_MODEL ||
      (expectedMethod == K.SESSION_SET_CONFIG && !pendingConfigIsMode && !selectingBypass)

  private fun configureModel(
    config: JsonObject,
    requested: String,
  ) {
    val selected =
      resolveModel(requested, configValues(config[K.OPTIONS]).toList())
        ?: return stop(
          "$providerName does not advertise the selected model/effort '$requested'; no " +
            "prompt was sent.",
        )
    if (config.text(K.CURRENT_VALUE) == selected) {
      model = selected
      onModel(selected)
      return selectMode()
    }
    pendingConfigId = config.text(K.ID)
    pendingConfigValue = selected
    pendingConfigIsMode = false
    send(
      K.SESSION_SET_CONFIG,
      buildJsonObject {
        put(K.SESSION_ID, sessionId)
        put(K.CONFIG_ID, config.text(K.ID))
        put(K.VALUE, selected)
      },
    )
  }

  private fun selectLegacyModel(requested: String) {
    val models = setup.obj(K.MODELS)
    val offered =
      (models?.get(K.AVAILABLE_MODELS) as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text(K.MODEL_ID) }
    val selected =
      resolveModel(requested, offered)
        ?: return stop(
          "$providerName does not advertise the selected model/effort '$requested'; no " +
            "prompt was sent.",
        )
    if (models?.text(K.CURRENT_MODEL_ID) == selected) {
      model = selected
      onModel(selected)
      return selectMode()
    }
    send(
      K.SESSION_SET_MODEL,
      buildJsonObject {
        put(K.SESSION_ID, sessionId)
        put(K.MODEL_ID, selected)
      },
    )
  }

  private fun resolveModel(
    requested: String,
    advertised: List<String>,
  ): String? {
    val selected =
      if (launch.provider.exactModels) {
        requested.takeIf { it in advertised }
      } else {
        resolveAcpModel(requested, advertised)
      } ?: return null
    if (selected != requested) {
      record("seam=$seam outcome=model_variant_resolved requested=$requested selected=$selected")
    }
    selectedModel = selected
    return selected
  }

  private fun selectMode() {
    val mode = launch.mode
    if (mode == null) prompt() else selectMode(mode)
  }

  private fun selectMode(mode: String) {
    val config =
      (setup[K.CONFIG_OPTIONS] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .firstOrNull { it.text(K.CATEGORY) == "mode" }
    if (config != null) {
      if (mode !in configValues(config[K.OPTIONS])) return stop("$providerName does not advertise the requested mode.")
      if (config.text(K.CURRENT_VALUE) == mode) return prompt()
      pendingConfigId = config.text(K.ID)
      pendingConfigValue = mode
      pendingConfigIsMode = true
      return send(
        K.SESSION_SET_CONFIG,
        buildJsonObject {
          put(K.SESSION_ID, sessionId)
          put(K.CONFIG_ID, pendingConfigId)
          put(K.VALUE, pendingConfigValue)
        },
      )
    }
    val modes = setup.obj(K.MODES)
    val offered = (modes?.get(K.AVAILABLE_MODES) as? JsonArray).orEmpty()
    if (offered.none { (it as? JsonObject)?.text(K.ID) == mode }) {
      return stop("$providerName does not advertise the requested '$mode' mode; no prompt was sent.")
    }
    send(
      K.SESSION_SET_MODE,
      buildJsonObject {
        put(K.SESSION_ID, sessionId)
        put(K.MODE_ID, mode)
      },
    )
  }

  private fun acceptBypass(body: JsonObject) {
    val enabled =
      (body[K.CONFIG_OPTIONS] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        .firstOrNull { it.text(K.ID) == K.BRAVE_MODE }?.get(K.CURRENT_VALUE)
    if (enabled != JsonPrimitive(true) && enabled != JsonPrimitive(K.BRAVE_ON)) {
      return stop("$providerName did not acknowledge Brave mode; no prompt was sent.")
    }
    selectingBypass = false
    bypassEnabled = true
    prompt()
  }

  private fun prompt() {
    if (launch.provider == AcpProvider.Junie && !bypassEnabled) {
      selectingBypass = true
      send(
        K.SESSION_SET_CONFIG,
        buildJsonObject {
          put(K.SESSION_ID, sessionId)
          put(K.CONFIG_ID, K.BRAVE_MODE)
          put(K.VALUE, true)
        },
      )
      return
    }
    ready = true
    send(
      K.SESSION_PROMPT,
      buildJsonObject {
        put(K.SESSION_ID, sessionId)
        put(
          K.PROMPT,
          JsonArray(
            listOf(
              buildJsonObject {
                put(K.TYPE, "text")
                put(K.TEXT, launch.prompt)
              },
            ) +
              launch.images.map { image ->
                buildJsonObject {
                  put(K.TYPE, K.IMAGE)
                  put(K.DATA, image.data)
                  put(K.MIME_TYPE, image.mimeType)
                }
              },
          ),
        )
      },
    )
  }

  private fun update(params: JsonObject) {
    if (params.text(K.SESSION_ID) != sessionId) return
    if (!ready) return
    val update = params.obj(K.UPDATE) ?: return stop("$providerName sent a malformed session update.")
    when (update.text(K.SESSION_UPDATE)) {
      "agent_message_chunk" -> agentChunk(update)
      "tool_call", "tool_call_update" -> {
        permissions?.observeTool(update)
        onEvent(CursorAcpEvent.Tool(update))
      }
      K.CONFIG_OPTION_UPDATE ->
        if (launch.provider.exactModels) configUpdate(update) else record("seam=$seam outcome=informational_update")
      else -> record("seam=$seam outcome=informational_update")
    }
  }

  private fun agentChunk(update: JsonObject) {
    val content = update.obj(K.CONTENT) ?: return stop("$providerName sent malformed assistant content.")
    if (content.text(K.TYPE) != "text") return stop("$providerName sent unsupported assistant content.")
    val chunk = content.text(K.TEXT) ?: return stop("$providerName sent malformed assistant text.")
    val overflow =
      synchronized(lock) {
        if (text.length + chunk.length > MAX_CAPTURE) {
          textTruncated = true
          true
        } else {
          text.append(chunk)
          false
        }
      }
    if (overflow) {
      stop(
        "$providerName assistant output exceeded the capture limit.",
      )
    } else {
      onEvent(CursorAcpEvent.Text(chunk))
    }
  }

  private fun blocking(
    message: JsonObject,
    method: String,
  ) {
    val id = message[K.ID] ?: return stop("$providerName blocking request has no identity.")
    if (method == K.REQUEST_PERMISSION) {
      val owner = permissions
      if (owner != null) {
        owner.request(message)
      } else {
        reply(id, permissionAllowResult(message))
      }
      return
    }
    reply(id, cursorExtensionAcceptance(method, message.obj(K.PARAMS)))
    record("seam=$seam outcome=extension_accepted method=$method")
  }

  private fun permissionAllowResult(message: JsonObject): JsonObject {
    val optionId = CursorPermissionRequests.allowOnceOptionId(message)
    val outcome = if (optionId == null) "permission_unavailable_cancelled" else "permission_auto_allowed"
    record("seam=$seam outcome=$outcome")
    return buildJsonObject {
      put(
        K.OUTCOME,
        buildJsonObject {
          put(K.OUTCOME, if (optionId == null) K.CANCELLED else K.SELECTED)
          optionId?.let { put(K.OPTION_ID, it) }
        },
      )
    }
  }

  private fun reply(
    id: JsonElement,
    result: JsonObject,
  ) {
    write(
      buildJsonObject {
        put(K.JSONRPC, "2.0")
        put(K.ID, id)
        put(K.RESULT, result)
      },
    )
  }

  fun textSnapshot(): String = synchronized(lock) { text.toString() }

  fun textCaptureTruncated(): Boolean = synchronized(lock) { textTruncated }

  fun outcome(): CursorAcpOutcome? = synchronized(lock) { result }

  fun pendingWait(): ApprovalPendingWait? = permissions?.pendingWait()

  fun enforceOpening() {
    if (!ready && Duration.between(startedAt, clock.instant()) >= OPENING_TIMEOUT) {
      stop("$providerName did not finish ACP setup in time; no prompt was sent.")
    }
  }

  override fun deliver(settled: ApprovalRequestRecord): ApprovalDeliveryState =
    permissions?.deliver(settled) ?: ApprovalDeliveryState.UNCERTAIN

  override fun stop(reason: String) {
    record("seam=$seam outcome=stopped")
    finish(reason)
  }

  fun close() {
    if (outcome() == null) stop("$providerName session was cancelled or disconnected before prompt completion.")
    permissions?.release()
  }

  private fun finish(failure: String?) {
    synchronized(lock) {
      if (closed || closing) return
      closing = true
    }
    try {
      if (failure == null) permissions?.clear() else permissions?.release()
      if (failure != null && ready) {
        write(
          buildJsonObject {
            put(K.JSONRPC, "2.0")
            put(K.METHOD, K.SESSION_CANCEL)
            put(K.PARAMS, buildJsonObject { put(K.SESSION_ID, sessionId) })
          },
        )
      }
    } finally {
      permissions?.release()
      synchronized(lock) {
        closed = true
        result = CursorAcpOutcome(text.toString(), sessionId, model, failure)
        closing = false
      }
    }
  }

  private fun send(
    method: String,
    params: JsonObject,
  ) {
    expectedMethod = method
    expectedId += 1
    write(
      buildJsonObject {
        put(K.JSONRPC, "2.0")
        put(K.ID, expectedId)
        put(K.METHOD, method)
        put(K.PARAMS, params)
      },
    )
  }

  private fun write(message: JsonObject): Boolean {
    if (synchronized(lock) { closed }) return false
    val written = catchingNonCancellation { writeLine(message.toString()) }.getOrDefault(false)
    if (!written) {
      synchronized(lock) {
        closed = true
        result =
          CursorAcpOutcome(
            text.toString(),
            sessionId,
            model,
            "$providerName response delivery is uncertain; it will not be replayed.",
          )
      }
      record("seam=$seam outcome=uncertain_write")
    }
    return written
  }

  private companion object {
    const val MAX_CAPTURE = 4_000_000
    val OPENING_TIMEOUT: Duration = Duration.ofSeconds(30)
  }
}

private fun cursorExtensionAcceptance(
  method: String,
  params: JsonObject?,
): JsonObject =
  when (method) {
    K.UPDATE_TODOS ->
      buildJsonObject {
        put(
          K.OUTCOME,
          buildJsonObject {
            put(K.OUTCOME, K.ACCEPTED)
            put(K.TODOS, params?.get(K.TODOS) ?: JsonArray(emptyList()))
          },
        )
      }
    K.TASK ->
      buildJsonObject {
        put(
          K.OUTCOME,
          buildJsonObject {
            put(K.OUTCOME, K.COMPLETED)
            params?.get(K.AGENT_ID)?.let { put(K.AGENT_ID, it) }
            params?.get(K.DURATION_MS)?.let { put(K.DURATION_MS, it) }
          },
        )
      }
    K.GENERATE_IMAGE -> {
      val path = params?.text(K.FILE_PATH)
      buildJsonObject {
        put(
          K.OUTCOME,
          if (path == null) {
            buildJsonObject { put(K.OUTCOME, K.CANCELLED) }
          } else {
            buildJsonObject {
              put(K.OUTCOME, K.GENERATED)
              put(K.FILE_PATH, path)
            }
          },
        )
      }
    }
    K.ASK_QUESTION ->
      buildJsonObject {
        put(K.OUTCOME, buildJsonObject { put(K.OUTCOME, K.SKIPPED) })
      }
    K.CREATE_PLAN ->
      buildJsonObject {
        put(K.OUTCOME, buildJsonObject { put(K.OUTCOME, K.ACCEPTED) })
      }
    else ->
      buildJsonObject {
        put(K.OUTCOME, buildJsonObject { put(K.OUTCOME, K.ACCEPTED) })
      }
  }

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

private fun configValues(value: JsonElement?): Set<String> =
  (value as? JsonArray).orEmpty().flatMap { entry ->
    val option = entry as? JsonObject ?: return@flatMap emptyList()
    option.text(K.VALUE)?.let(::listOf) ?: configValues(option[K.OPTIONS]).toList()
  }.toSet()
