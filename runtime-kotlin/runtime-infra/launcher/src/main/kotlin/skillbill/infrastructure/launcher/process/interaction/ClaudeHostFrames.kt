package skillbill.infrastructure.launcher.process.interaction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import skillbill.approval.model.PROVIDER_REMEMBER_OPTION_KIND
import skillbill.contracts.approval.ApprovalOption
import skillbill.error.core.catchingNonCancellation
import java.nio.charset.StandardCharsets

internal object ClaudeHostFrames {
  fun initialize(requestId: String): ByteArray =
    frame(
      buildJsonObject {
        put("type", "control_request")
        put("request_id", requestId)
        putJsonObject("request") { put("subtype", "initialize") }
      },
    )

  fun userMessage(text: String): ByteArray =
    frame(
      buildJsonObject {
        put("type", "user")
        putJsonObject("message") {
          put("role", "user")
          putJsonArray("content") {
            add(
              buildJsonObject {
                put("type", "text")
                put("text", text)
              },
            )
          }
        }
      },
    )

  fun allow(
    requestId: JsonElement,
    originalInput: JsonElement,
  ): ByteArray =
    controlResponse(requestId) {
      put("behavior", "allow")
      put("updatedInput", originalInput)
    }

  fun deny(
    requestId: JsonElement,
    message: String,
  ): ByteArray =
    controlResponse(requestId) {
      put("behavior", "deny")
      put("message", message)
    }

  fun unsupported(
    requestId: JsonElement,
    message: String,
  ): ByteArray =
    frame(
      buildJsonObject {
        put("type", "control_response")
        putJsonObject("response") {
          put("subtype", "error")
          put("request_id", requestId)
          put("error", message)
        }
      },
    )

  private fun controlResponse(
    requestId: JsonElement,
    body: JsonObjectBuilder.() -> Unit,
  ): ByteArray =
    frame(
      buildJsonObject {
        put("type", "control_response")
        putJsonObject("response") {
          put("subtype", "success")
          put("request_id", requestId)
          putJsonObject("response", body)
        }
      },
    )

  private fun frame(element: JsonElement): ByteArray = (element.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
}

internal class PermissionFrame(
  val toolName: String,
  val input: JsonObject,
  val body: JsonObject,
  val line: String,
) {
  fun reason(): String? =
    listOf("description", "decision_reason")
      .mapNotNull { key -> body[key]?.takeUnless { it is JsonNull }?.let(::contentText) }
      .filter(String::isNotBlank)
      .joinToString("\n\n")
      .ifBlank { null }

  fun rememberOptions(): List<ApprovalOption> =
    (body["permission_suggestions"] as? JsonArray).orEmpty().mapIndexed { index, suggestion ->
      ApprovalOption("provider_remember_${index + 1}", suggestionLabel(suggestion), PROVIDER_REMEMBER_OPTION_KIND)
    }

  private fun suggestionLabel(suggestion: JsonElement): String {
    val entry = suggestion as? JsonObject ?: return suggestion.toString()
    val rules =
      (entry["rules"] as? JsonArray).orEmpty().joinToString(", ") { rule ->
        val item = rule as? JsonObject
        listOfNotNull(
          (item?.get("toolName") as? JsonPrimitive)?.contentOrNull,
          (item?.get("ruleContent") as? JsonPrimitive)?.contentOrNull?.let { "($it)" },
        ).joinToString("").ifBlank { rule.toString() }
      }
    val detail =
      listOfNotNull(
        (entry["behavior"] as? JsonPrimitive)?.contentOrNull,
        rules.ifBlank { null },
        (entry["mode"] as? JsonPrimitive)?.contentOrNull?.let { "mode $it" },
      ).joinToString(" ")
    val destination = (entry["destination"] as? JsonPrimitive)?.contentOrNull?.let { " in $it" }.orEmpty()
    val type = (entry["type"] as? JsonPrimitive)?.contentOrNull ?: "suggestion"
    return "Provider remember: $type${detail.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()}$destination"
  }

  private fun contentText(element: JsonElement): String = (element as? JsonPrimitive)?.content ?: element.toString()
}

internal fun originalInput(providerPayload: String): JsonObject? =
  catchingNonCancellation { Json.parseToJsonElement(providerPayload) }.getOrNull()
    .let { it as? JsonObject }
    ?.let { it["request"] as? JsonObject }
    ?.let { it["input"] as? JsonObject }

internal class JsonLineSplitter(private val maxLineChars: Int = MAX_LINE_CHARS) {
  private val buffer = StringBuilder()
  private var skipping = false

  fun feed(chunk: String): List<String> {
    val lines = ArrayList<String>()
    var index = 0
    while (index < chunk.length) {
      val newline = chunk.indexOf('\n', index)
      val end = if (newline < 0) chunk.length else newline
      if (!skipping) {
        buffer.append(chunk, index, end)
        if (buffer.length > maxLineChars) {
          buffer.setLength(0)
          skipping = true
        }
      }
      if (newline < 0) break
      if (!skipping && buffer.isNotEmpty()) lines.add(buffer.toString())
      buffer.setLength(0)
      skipping = false
      index = newline + 1
    }
    return lines
  }

  private companion object {
    const val MAX_LINE_CHARS = 4_000_000
  }
}
