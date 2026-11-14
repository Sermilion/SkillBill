package skillbill.infrastructure.launcher.process.cursor

import kotlinx.serialization.json.JsonObject
import skillbill.infrastructure.launcher.process.cursor.CursorAcpKeys as K

internal fun composedAcpModel(options: List<JsonObject>): String? {
  val family = options.firstOrNull { it.text(K.CATEGORY) == MODEL_CATEGORY }?.text(K.CURRENT_VALUE)
  val thinking = options.firstOrNull { it.text(K.CATEGORY) == THOUGHT_LEVEL_CATEGORY }
  val level = thinking?.text(K.CURRENT_VALUE)
  val base =
    family?.let {
      configValues(thinking?.get(K.OPTIONS)).firstNotNullOfOrNull { offered ->
        family.removeSuffix("-$offered").takeIf { stripped -> stripped != family && stripped.isNotEmpty() }
      }
    }
  val suffix = SPEED_SUFFIXES[options.firstOrNull { it.text(K.ID) == SPEED_ID }?.text(K.CURRENT_VALUE)]
  return if (base == null || level == null || suffix == null) null else "$base-$level$suffix"
}

private const val MODEL_CATEGORY = "model"
private const val THOUGHT_LEVEL_CATEGORY = "thought_level"
private const val SPEED_ID = "speed"

private val SPEED_SUFFIXES: Map<String?, String> = mapOf(null to "", "standard" to "", "fast" to "-lightning")
