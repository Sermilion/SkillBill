package dev.skillbill.desktop.core.data.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import skillbill.infrastructure.launcher.process.codex.CodexThreadSettings

internal suspend fun codexResumeSettings(
  settings: CodexThreadSettings,
  savedThread: String?,
  call: suspend (String, JsonObject) -> JsonObject,
): CodexThreadSettings = if (savedThread != null && settings.model == null) {
  settings.copy(model = codexDefaultModel(settings.cwd, call))
} else settings

internal suspend fun codexDefaultModel(
  cwd: String,
  call: suspend (String, JsonObject) -> JsonObject,
): String {
  val config = call("config/read", buildJsonObject {
    put(DefaultModelKeys.CWD, cwd)
    put(DefaultModelKeys.INCLUDE_LAYERS, false)
  }).obj(DefaultModelKeys.CONFIG) ?: error("Codex returned no effective configuration.")
  config.string(DefaultModelKeys.MODEL)?.takeIf(String::isNotBlank)?.let { return it }
  val cursors = mutableSetOf<String>()
  var cursor: String? = null
  do {
    val page = call("model/list", buildJsonObject {
      put(DefaultModelKeys.LIMIT, 100)
      cursor?.let { put(DefaultModelKeys.CURSOR, it) }
    })
    val rows = page[DefaultModelKeys.DATA] as? JsonArray ?: error("Codex returned no model catalog.")
    rows.filterIsInstance<JsonObject>().firstOrNull { it[DefaultModelKeys.IS_DEFAULT] == JsonPrimitive(true) }
      ?.string(DefaultModelKeys.MODEL)?.takeIf(String::isNotBlank)?.let { return it }
    cursor = page.string(DefaultModelKeys.NEXT_CURSOR)
    check(cursor == null || (cursors.add(cursor) && cursors.size <= 100)) { "Codex returned an invalid model cursor." }
  } while (cursor != null)
  error("Codex did not identify a default model. Select an explicit model and retry.")
}

private object DefaultModelKeys {
  const val CWD = "cwd"
  const val INCLUDE_LAYERS = "includeLayers"
  const val CONFIG = "config"
  const val MODEL = "model"
  const val LIMIT = "limit"
  const val CURSOR = "cursor"
  const val DATA = "data"
  const val IS_DEFAULT = "isDefault"
  const val NEXT_CURSOR = "nextCursor"
}
