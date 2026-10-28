package skillbill.contracts.persistence

object DesktopCommitSignal {
  const val FILE_SUFFIX = ".skill-bill-commit-signal"
  const val GENERATION_LABEL = "generation"
  const val REVISION_SEPARATOR = "="
  const val CHANGED_SUFFIX = ".changed"
  const val CHANGED_BASE_SEPARATOR = ":"
  const val CHANGED_ID_SEPARATOR = ","
  const val OVERFLOW_MARKER = "*"
  const val MAX_CHANGED_IDS_PER_TOPIC = 16
  const val MAX_SIGNAL_CHARS = 8192
}
