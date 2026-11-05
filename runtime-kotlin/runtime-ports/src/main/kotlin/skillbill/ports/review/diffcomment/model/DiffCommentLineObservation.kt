package skillbill.ports.review.diffcomment.model

sealed interface DiffCommentLineObservation {
  data class Text(val value: String) : DiffCommentLineObservation

  data object Missing : DiffCommentLineObservation

  data object Rejected : DiffCommentLineObservation
}
