package dev.skillbill.desktop.core.domain.model

data class QaPreviewRequest(
  val sessionId: Long,
  val workId: String,
  val invocationId: String,
  val invocationDirectory: String?,
  val active: Boolean,
)

data class QaPreviewFrame(val width: Int, val height: Int, val argb: IntArray)

sealed interface QaPreviewState {
  data class Waiting(val reason: Reason) : QaPreviewState
  data class Live(val frame: QaPreviewFrame) : QaPreviewState
  data object Ended : QaPreviewState

  enum class Reason { SourceUnavailable, InvalidSource, IncompleteFrame, UnsupportedFormat, LimitsExceeded, ReadFailed, BlankDisplay }
}

data class GoalQaPreview(
  val invocationId: String,
  val invocationDirectory: String,
  val targets: List<String>,
  val active: Boolean,
  val subtaskId: Int?,
)
