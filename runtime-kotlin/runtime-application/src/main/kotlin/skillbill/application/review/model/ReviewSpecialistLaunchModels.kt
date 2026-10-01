package skillbill.application.review.model

import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.commit.ReviewAssignment
import skillbill.review.context.model.packet.ReviewContextPacket
import java.nio.file.Path

internal enum class ReviewWorkerKind {
  PROVIDER_NATIVE,
  GENERIC,
}

internal data class ReviewRubricProjection(val rubricId: String, val body: String, val area: String? = null) {
  init {
    require(rubricId.isNotBlank()) { "A projected rubric must carry an id." }
    require(body.isNotBlank()) { "A projected rubric must carry a body." }
    require(area == null || area.isNotBlank()) { "A projected rubric area must be non-blank when present." }
  }
}

internal data class ReviewSpecialistLaunchRequest(
  val packet: ReviewContextPacket,
  val assignment: ReviewAssignment,
  val specialistContract: String,
  val rubrics: List<ReviewRubricProjection>,
  val brokerId: String,
  val budget: ReviewContextBudgetPolicy,
  val agentId: String,
  val workerKind: ReviewWorkerKind,
  val logicalWorkerName: String? = null,
  val repoRoot: Path,
  val namedDependencies: Set<String> = emptySet(),
  val prelaunchExpansions: List<ReviewExpansionAuthorizationRequest> = emptyList(),
  val attempt: Int = 1,
) {
  init {
    require(attempt >= 1) { "Review specialist launch attempt must be positive." }
  }
}
