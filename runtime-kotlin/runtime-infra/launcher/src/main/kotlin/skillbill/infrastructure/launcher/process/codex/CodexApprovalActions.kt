package skillbill.infrastructure.launcher.process.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import skillbill.approval.model.ApprovalActionClass
import skillbill.approval.model.ApprovalMatchFacts
import skillbill.approval.model.DENY_OPTION_KIND
import skillbill.approval.model.ONE_TIME_ALLOW_OPTION_KIND
import skillbill.approval.model.PROVIDER_REMEMBER_OPTION_KIND
import skillbill.contracts.CanonicalJson
import skillbill.contracts.approval.ApprovalOption
import skillbill.infrastructure.launcher.process.interaction.ApprovalMatchFactsNormalizer
import skillbill.infrastructure.launcher.process.codex.CodexRpcKeys as K

private enum class CodexCommandActionType(val wireValue: String) {
  READ("read"),
  LIST_FILES("listFiles"),
  SEARCH("search"),
  UNKNOWN("unknown"),
}

internal enum class CodexPatchKind(val wireValue: String) {
  ADD("add"),
  DELETE("delete"),
  UPDATE("update"),
}

private enum class CodexExecutionKind(val wireValue: String) {
  COMMAND("command"),
}

internal data class CodexApprovalAction(
  val kind: String,
  val complete: Boolean,
  val canonical: String?,
  val subject: String,
  val display: String,
  val actionClass: ApprovalActionClass,
  val joined: JsonObject,
)

internal object CodexApprovalActions {
  fun decode(
    method: String,
    params: JsonObject,
    item: JsonObject?,
  ): CodexApprovalAction {
    val command = method == K.ITEM_COMMAND_APPROVAL
    val kind = if (command) K.COMMAND_EXECUTION_TYPE else K.FILE_CHANGE_TYPE
    val joined = JsonObject(item.orEmpty() + params.filterValues { it != JsonNull })
    val complete = completeAction(params, item, joined, kind, command)
    val nonOrdinary = escalated(params, command)
    val canonical =
      if (complete && !nonOrdinary) {
        CanonicalJson.encode(
          buildJsonObject {
            put(K.ACTION, kind)
            put(K.ITEM_ACTION_FACTS, JsonObject(joined.filterKeys { it !in METADATA && it !in PROGRESS }))
          },
        )
      } else {
        null
      }
    val subject = if (command) joined.text(K.COMMAND) else joined[K.CHANGES]?.toString()
    return CodexApprovalAction(
      kind,
      complete,
      canonical,
      subject ?: "Incomplete Codex action",
      buildJsonObject {
        put(K.REQUEST, params)
        item?.let { put(K.ITEM_STARTED, it) }
        put(K.COMPLETE, complete)
      }.toString(),
      when {
        nonOrdinary -> ApprovalActionClass.PERMISSION_ESCALATION
        command -> ApprovalActionClass.ORDINARY_COMMAND
        else -> ApprovalActionClass.ORDINARY_TOOL
      },
      joined,
    )
  }

  fun matchFacts(
    action: CodexApprovalAction,
    checkout: String,
  ): ApprovalMatchFacts? {
    if (action.canonical == null || action.actionClass !in ORDINARY_MATCH_FACT_CLASSES) return null
    return if (action.kind == K.COMMAND_EXECUTION_TYPE) {
      commandMatchFacts(action.joined, checkout)
    } else {
      fileMatchFacts(action.joined, checkout)
    }
  }

  private fun commandMatchFacts(
    joined: JsonObject,
    checkout: String,
  ): ApprovalMatchFacts? {
    val cwd = joined.text(K.CWD) ?: return null
    if (!ApprovalMatchFactsNormalizer.withinCheckout(checkout, cwd)) return null
    return ApprovalMatchFactsNormalizer.commandFacts(unwrapShell(joined.text(K.COMMAND)))
  }

  private fun fileMatchFacts(
    joined: JsonObject,
    checkout: String,
  ): ApprovalMatchFacts? {
    if (joined[K.GRANT_ROOT].present() != null) return null
    val paths = changePaths(joined[K.CHANGES]) ?: return null
    return ApprovalMatchFactsNormalizer.pathFacts(checkout, joined.text(K.CWD) ?: checkout, paths)
  }

  private fun changePaths(value: JsonElement?): List<String>? {
    val changes = value as? JsonArray ?: return null
    return changes.map { entry ->
      (entry as? JsonObject)?.text(K.PATH) ?: return null
    }
  }

  private fun unwrapShell(command: String?): String? {
    val trimmed = command?.trim() ?: return null
    SHELL_WRAPPED_COMMAND.matchEntire(trimmed)?.let { return it.groupValues[1] }
    val first = trimmed.takeWhile { !it.isWhitespace() }
    if (first.isNotEmpty() && SHELL_BINARY.matches(first)) return null
    return trimmed
  }

  private fun completeAction(
    params: JsonObject,
    item: JsonObject?,
    joined: JsonObject,
    kind: String,
    command: Boolean,
  ): Boolean {
    val correctItem = item == null || item.text(K.TYPE) == kind
    val validAction = if (command) validCommand(joined) else validChanges(joined[K.CHANGES])
    return correctItem && !conflictingFacts(params, item) && validAction
  }

  private fun conflictingFacts(
    params: JsonObject,
    item: JsonObject?,
  ): Boolean =
    listOf(K.COMMAND, K.CWD, K.COMMAND_ACTIONS, K.CHANGES).any { key ->
      val requested = params[key].present()
      val started = item?.get(key).present()
      requested != null && started != null && requested != started
    }

  fun decisions(params: JsonObject): List<JsonElement> =
    when (val value = params[K.AVAILABLE_DECISIONS]) {
      null, JsonNull -> CODEX_DEFAULT_APPROVAL_DECISIONS.map(::JsonPrimitive)
      is JsonArray -> value
      else -> emptyList()
    }

  fun options(
    params: JsonObject,
    complete: Boolean,
  ): List<ApprovalOption> =
    decisions(params).mapIndexed { index, raw ->
      val name = decisionName(raw)
      val kind =
        when (name) {
          K.ACCEPT -> if (complete) ONE_TIME_ALLOW_OPTION_KIND else "provider_option"
          K.DECLINE -> DENY_OPTION_KIND
          K.ACCEPT_FOR_SESSION, K.ACCEPT_WITH_EXECPOLICY_AMENDMENT, K.APPLY_NETWORK_POLICY_AMENDMENT ->
            PROVIDER_REMEMBER_OPTION_KIND
          else -> "provider_option"
        }
      ApprovalOption("codex-$index", raw.toString(), kind)
    }

  fun decisionName(value: JsonElement): String? =
    when (value) {
      is JsonPrimitive -> value.content.takeIf { value.isString }
      is JsonObject -> value.keys.singleOrNull()?.takeIf { it in REMEMBERED }
      else -> null
    }

  private fun validCommand(value: JsonObject): Boolean {
    val command = value.text(K.COMMAND)
    val cwd = value.text(K.CWD)
    val rawActions = value[K.COMMAND_ACTIONS]
    if (rawActions == JsonNull) return false
    val actions = rawActions.present()
    val validActions =
      actions == null || actions is JsonArray &&
        actions.all(::validCommandAction)
    return !command.isNullOrBlank() && !cwd.isNullOrBlank() && validActions
  }

  private fun validCommandAction(value: JsonElement): Boolean {
    val action = value as? JsonObject ?: return false
    if (action.text(K.COMMAND).isNullOrBlank()) return false
    return when (action.text(K.TYPE)) {
      CodexCommandActionType.READ.wireValue ->
        !action.text(K.NAME).isNullOrBlank() && !action.text(K.PATH).isNullOrBlank() &&
          action.keys.all { it in setOf(K.TYPE, K.COMMAND, K.NAME, K.PATH) }
      CodexCommandActionType.LIST_FILES.wireValue ->
        optionalText(action, K.PATH) && action.keys.all { it in setOf(K.TYPE, K.COMMAND, K.PATH) }
      CodexCommandActionType.SEARCH.wireValue ->
        optionalText(action, K.PATH) && optionalText(action, K.QUERY) &&
          action.keys.all { it in setOf(K.TYPE, K.COMMAND, K.PATH, K.QUERY) }
      CodexCommandActionType.UNKNOWN.wireValue -> action.keys.all { it in setOf(K.TYPE, K.COMMAND) }
      else -> false
    }
  }

  private fun optionalText(
    action: JsonObject,
    key: String,
  ): Boolean =
    when (val value = action[key]) {
      null, JsonNull -> true
      is JsonPrimitive -> value.isString
      else -> false
    }

  private fun validChanges(value: JsonElement?): Boolean {
    val changes = value as? JsonArray ?: return false
    return changes.isNotEmpty() &&
      changes.all { entry ->
        val change = entry as? JsonObject
        val kind = change?.get(K.KIND) as? JsonObject
        return@all !change?.text(K.PATH).isNullOrBlank() && !change.text(K.DIFF).isNullOrBlank() &&
          kind?.text(K.TYPE) in CodexPatchKind.entries.map(CodexPatchKind::wireValue)
      }
  }

  private fun escalated(
    params: JsonObject,
    command: Boolean,
  ): Boolean {
    val fields = if (command) COMMAND_FIELDS else FILE_FIELDS
    val extensions = params.keys.any { it !in fields }
    val escalation = ESCALATION_FIELDS.any { params[it].present() != null }
    val specialCommand = params.text(K.KIND)?.let { it != CodexExecutionKind.COMMAND.wireValue } == true
    return extensions || escalation || specialCommand
  }

  private val ORDINARY_MATCH_FACT_CLASSES =
    setOf(ApprovalActionClass.ORDINARY_COMMAND, ApprovalActionClass.ORDINARY_TOOL)
  private val SHELL_WRAPPED_COMMAND =
    Regex("""^(?:/bin/|/usr/bin/)?(?:bash|zsh|sh) -l?c '([^']*)'$""")
  private val SHELL_BINARY = Regex("""^(?:/bin/|/usr/bin/)?(?:bash|zsh|sh)$""")
  private val REMEMBERED = setOf(K.ACCEPT_WITH_EXECPOLICY_AMENDMENT, K.APPLY_NETWORK_POLICY_AMENDMENT)
  private val ESCALATION_FIELDS =
    setOf(
      K.ADDITIONAL_PERMISSIONS,
      K.NETWORK_APPROVAL_CONTEXT,
      K.PROPOSED_EXECPOLICY_AMENDMENT,
      K.PROPOSED_NETWORK_POLICY_AMENDMENTS,
    )
  private val METADATA =
    setOf(
      K.THREAD_ID, K.TURN_ID, K.ITEM_ID, K.STARTED_AT_MS, K.APPROVAL_ID,
      K.REASON, K.TITLE, K.AVAILABLE_DECISIONS, K.ID,
    )
  private val PROGRESS =
    setOf(K.TYPE, K.STATUS, K.AGGREGATED_OUTPUT, K.EXIT_CODE, K.ERROR, K.STARTED_AT, K.COMPLETED_AT)
  private val COMMAND_FIELDS =
    METADATA + ESCALATION_FIELDS +
      setOf(
        K.COMMAND,
        K.CWD,
        K.COMMAND_ACTIONS,
        K.ENVIRONMENT_ID,
        K.KIND,
      )
  private val FILE_FIELDS = METADATA + setOf(K.GRANT_ROOT, K.CHANGES)
}

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonElement?.present(): JsonElement? = takeUnless { it == JsonNull }
