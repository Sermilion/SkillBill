package skillbill.idestatus.model

const val IDE_STATUS_CURRENT_ACTIVITY_MAX_CODE_POINTS: Int = 160

fun boundedIdeStatusActivity(value: String?): String? {
  val cleaned =
    value
      ?.filterNot(Char::isISOControl)
      ?.trim()
      ?.takeIf(String::isNotBlank)
      ?: return null
  return cleaned.takeCodePoints(IDE_STATUS_CURRENT_ACTIVITY_MAX_CODE_POINTS)
}

private fun String.takeCodePoints(maxCodePoints: Int): String {
  if (codePointCount(0, length) <= maxCodePoints) return this
  val end = offsetByCodePoints(0, maxCodePoints)
  return substring(0, end)
}
