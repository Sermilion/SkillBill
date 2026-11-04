package dev.skillbill.desktop.core.domain.model

import kotlin.time.Instant

enum class CommentState { Pending, Open, Resolved, Declined }
enum class CommentAuthor { User, Agent }
enum class CommentSide { Old, New }
enum class CommentOutcome { Fixed, Refuted, Skipped, Failed }

data class CommentThread(
  val id: String,
  val path: String?,
  val side: CommentSide?,
  val line: Int?,
  val anchorLineText: String?,
  val state: CommentState,
  val outdated: Boolean,
  val sourceLabel: String?,
  val severity: String?,
  val findingRef: String?,
  val comments: List<Comment>,
)

data class Comment(
  val id: String,
  val author: CommentAuthor,
  val agentId: String?,
  val body: String,
  val outcome: CommentOutcome?,
  val createdAt: Instant,
  val editedAt: Instant?,
)

data class CommentRejection(val reason: String)

sealed interface DiffCommentsState {
  data object Loading : DiffCommentsState
  data class Available(val branch: String, val threads: List<CommentThread>, val canAuthor: Boolean) : DiffCommentsState
  data class Unavailable(val reason: String) : DiffCommentsState
}
