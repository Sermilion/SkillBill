package skillbill.ports.review.model

import skillbill.error.shellcontent.invalidReviewContextSchemaError

sealed interface ReviewCheckpointFileIdentity {
  data class Regular(val digest: String) : ReviewCheckpointFileIdentity {
    init {
      if (!digest.matches(Regex("[a-f0-9]{40}|[a-f0-9]{64}"))) {
        throw invalidReviewContextSchemaError("review-source", "Checkpoint file identity is invalid.")
      }
    }
  }

  data object Absent : ReviewCheckpointFileIdentity

  enum class Unavailable : ReviewCheckpointFileIdentity { SYMBOLIC_LINK, DIRECTORY, OTHER }
}
