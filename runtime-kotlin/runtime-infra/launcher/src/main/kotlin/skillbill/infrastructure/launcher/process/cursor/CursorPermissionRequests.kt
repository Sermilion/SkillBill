package skillbill.infrastructure.launcher.process.cursor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import skillbill.approval.model.ApprovalActionClass
import skillbill.approval.model.DENY_OPTION_KIND
import skillbill.approval.model.ONE_TIME_ALLOW_OPTION_KIND
import skillbill.approval.model.PROVIDER_REMEMBER_OPTION_KIND
import skillbill.approval.model.sameApprovalRegistrationPayload
import skillbill.contracts.CanonicalJson
import skillbill.contracts.approval.ApprovalAuthorityScope
import skillbill.contracts.approval.ApprovalOption
import skillbill.contracts.approval.ApprovalProviderRequestId
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalState
import skillbill.contracts.approval.OPERATOR_APPROVAL_CONTRACT_VERSION
import skillbill.ports.approval.model.ApprovalOwnerKey
import java.util.Base64
import skillbill.infrastructure.launcher.process.cursor.CursorAcpKeys as K

internal data class CursorPermissionInput(
  val id: ApprovalProviderRequestId,
  val tool: JsonObject,
  val options: List<CursorPermissionOption>,
  val message: JsonObject,
) {
  val toolId: String get() = tool.text(K.TOOL_CALL_ID).orEmpty()

  val session: String? get() = message.obj(K.PARAMS)?.text(K.SESSION_ID)
}

internal data class CursorPermissionOption(val id: String, val name: String, val kind: String)

internal class CursorPendingPermission(
  val request: ApprovalRequestRecord,
  val canonical: String?,
  val toolId: String,
  val options: List<CursorPermissionOption>,
  val actionClass: ApprovalActionClass,
  val writeCapable: Boolean,
) {
  var written = false

  fun matches(settled: ApprovalRequestRecord): Boolean {
    val scope =
      canonical?.let {
        request.authorityScope?.copy(
          profileIdentity = settled.authorityScope?.profileIdentity.orEmpty(),
          workingDirectory = settled.authorityScope?.workingDirectory.orEmpty(),
          actionFingerprint = it,
        )
      }
    return sameApprovalRegistrationPayload(request.copy(actionFingerprint = canonical, authorityScope = scope), settled)
  }
}

internal object CursorPermissionRequests {
  fun decode(
    message: JsonObject,
    session: String?,
  ): CursorPermissionInput? {
    val params = message.obj(K.PARAMS) ?: return null
    val id = message[K.ID].cursorRequestId() ?: return null
    val tool = params.obj(K.TOOL_CALL) ?: return null
    val options = options(params[K.OPTIONS])
    return if (params.text(K.SESSION_ID) != session || tool.text(K.TOOL_CALL_ID).isNullOrBlank() || options == null) {
      null
    } else {
      CursorPermissionInput(id, tool, options, message)
    }
  }

  private fun options(value: JsonElement?): List<CursorPermissionOption>? {
    val entries = value as? JsonArray ?: return null
    val options = entries.map { option(it) ?: return null }
    return options.takeIf { it.map(CursorPermissionOption::id).distinct().size == it.size }
  }

  fun allowOnceOptionId(message: JsonObject): String? {
    val entries = message.obj(K.PARAMS)?.get(K.OPTIONS) as? JsonArray ?: return null
    val parsed = entries.mapNotNull(::option)
    return parsed.firstOrNull { it.kind == CursorPermissionKind.ALLOW_ONCE.wireValue }?.id
      ?: parsed.firstOrNull { it.kind == CursorPermissionKind.ALLOW_ALWAYS.wireValue }?.id
  }

  private fun option(value: JsonElement): CursorPermissionOption? {
    val objectValue = value as? JsonObject ?: return null
    val id = objectValue.text(K.OPTION_ID)?.takeIf(String::isNotBlank)
    val name = objectValue.text(K.NAME)?.takeIf(String::isNotBlank)
    val kind = objectValue.text(K.KIND)
    return if (id == null || name == null || kind == null) null else CursorPermissionOption(id, name, kind)
  }

  fun build(
    key: ApprovalOwnerKey,
    cwd: String,
    decoded: CursorPermissionInput,
    merged: JsonObject,
    provider: AcpProvider = AcpProvider.Cursor,
  ): CursorPendingPermission {
    val rawInput = merged[K.RAW_INPUT]?.takeUnless { it == JsonNull }
    val toolKind = merged.text(K.KIND)
    val actionKind = merged.text(K.NAME) ?: toolKind.orEmpty()
    val ordinary = toolKind in ORDINARY_KINDS
    val input = rawInput as? JsonObject
    val complete = ordinary && actionKind.isNotBlank() && input?.isNotEmpty() == true
    val canonical =
      if (complete) {
        CanonicalJson.encode(
          buildJsonObject {
            put(K.KIND, toolKind)
            put(K.NAME, actionKind)
            put(K.RAW_INPUT, requireNotNull(rawInput))
            merged[K.LOCATIONS]?.let { put(K.LOCATIONS, it) }
          },
        )
      } else {
        null
      }
    val payload =
      buildJsonObject {
        put(K.REQUEST, decoded.message)
        put(K.MERGED_TOOL, merged)
      }.toString()
    val request =
      ApprovalRequestRecord(
        contractVersion = OPERATOR_APPROVAL_CONTRACT_VERSION,
        approvalId = approvalId(provider, key.processGeneration, decoded.id),
        repositoryIdentity = key.repositoryIdentity,
        checkoutRoot = cwd,
        runId = key.runId,
        processGeneration = key.processGeneration,
        provider = provider.id,
        providerRequestId = decoded.id,
        providerSessionId = decoded.session,
        title = merged.text(K.TITLE)?.takeIf(String::isNotBlank) ?: "${provider.name} permission request",
        reason = null,
        subject =
          input?.text(
            K.COMMAND,
          ) ?: input?.text(K.PATH) ?: actionKind.ifBlank { "${provider.name} tool request" },
        inputText = payload,
        options = decoded.options.map { ApprovalOption(it.id, it.name, cardKind(it.kind)) },
        authorityScope = ApprovalAuthorityScope("", "${provider.id}-acp-1", actionKind, "", "pending"),
        createdAt = "", deadlineAt = "", state = ApprovalState.PENDING, providerPayload = payload,
      )
    val actionClass =
      when {
        toolKind == "execute" -> ApprovalActionClass.ORDINARY_COMMAND
        ordinary -> ApprovalActionClass.ORDINARY_TOOL
        else -> ApprovalActionClass.PROVIDER_INTERACTION
      }
    return CursorPendingPermission(
      request,
      canonical,
      decoded.toolId,
      decoded.options,
      actionClass,
      toolKind !in READ_ONLY_KINDS,
    )
  }

  private fun cardKind(kind: String): String =
    when (kind) {
      CursorPermissionKind.ALLOW_ONCE.wireValue -> ONE_TIME_ALLOW_OPTION_KIND
      CursorPermissionKind.REJECT_ONCE.wireValue -> DENY_OPTION_KIND
      CursorPermissionKind.ALLOW_ALWAYS.wireValue, CursorPermissionKind.REJECT_ALWAYS.wireValue ->
        PROVIDER_REMEMBER_OPTION_KIND
      else -> "provider_option"
    }

  private fun approvalId(
    provider: AcpProvider,
    generation: String,
    id: ApprovalProviderRequestId,
  ): String {
    val typed =
      when (id) {
        is ApprovalProviderRequestId.Text -> "s:${id.value}"
        is ApprovalProviderRequestId.Integer -> "i:${id.value}"
      }
    return "${provider.id}:$generation:" +
      Base64.getUrlEncoder().withoutPadding().encodeToString(
        typed.encodeToByteArray(),
      )
  }

  private val ORDINARY_KINDS = setOf("read", "edit", "delete", "move", "search", "execute", "think")
  private val READ_ONLY_KINDS = setOf("read", "search", "think")
}

internal fun ApprovalProviderRequestId.cursorJson(): JsonElement =
  when (this) {
    is ApprovalProviderRequestId.Text -> JsonPrimitive(value)
    is ApprovalProviderRequestId.Integer -> Json.parseToJsonElement(value)
  }

private val JSON_INTEGER = Regex("-?(0|[1-9][0-9]*)")

private fun JsonElement?.cursorRequestId(): ApprovalProviderRequestId? {
  val id = this as? JsonPrimitive ?: return null
  return when {
    id.isString -> ApprovalProviderRequestId.Text(id.content)
    JSON_INTEGER.matches(id.content) -> ApprovalProviderRequestId.Integer(id.content)
    else -> null
  }
}
