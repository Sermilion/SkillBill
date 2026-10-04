package skillbill.review.context.model.accounting

import skillbill.review.context.model.commit.ReviewAssignment
import skillbill.review.context.model.hunk.SHA256_HEX
import skillbill.review.context.model.packet.ReviewContextPacket
import skillbill.text.sha256HexUtf8

const val REVIEW_CONTEXT_BUDGET_EXCEEDED: String = "review_context_budget_exceeded"

data class ReviewContextBudgetPolicy(
  val maxParentPacketBytes: Long = 524_288,
  val maxLaneLaunchBytes: Long = 65_536,
  val maxLaneEvidenceBytes: Long = 262_144,
  val maxEvidenceResultBytes: Long = 65_536,
  val maxLaneResultBytes: Long = 65_536,
  val maxAssignmentExpansions: Int = 3,
  val maxSpecialistToolCalls: Int = 40,
  val maxSpecialistModelTurns: Int = 24,
  val maxRoutingAnalysisPairs: Int = 4_096,
  val maxRoutingAnalysisBytes: Long = 33_554_432,
  val maxSpecIntentProjectionBytes: Long = 32_768,
) {
  init {
    val byteLimits =
      listOf(
        maxParentPacketBytes,
        maxLaneLaunchBytes,
        maxLaneEvidenceBytes,
        maxEvidenceResultBytes,
        maxLaneResultBytes,
        maxSpecIntentProjectionBytes,
      )
    require(byteLimits.all { it > 0 }) { "Review-context byte limits must be positive." }
    require(maxAssignmentExpansions >= 0) { "Assignment expansions cannot be negative." }
    require(maxSpecialistToolCalls > 0) { "Specialist tool-call budget must be positive." }
    require(maxSpecialistModelTurns > 0) { "Specialist model-turn budget must be positive." }
    require(maxRoutingAnalysisPairs > 0) { "Routing-analysis commit/lane pair budget must be positive." }
    require(maxRoutingAnalysisBytes > 0) { "Routing-analysis hunk-material byte budget must be positive." }
    require(maxEvidenceResultBytes <= maxLaneEvidenceBytes) {
      "Evidence-result bytes cannot exceed cumulative lane-evidence bytes."
    }
    require(maxLaneLaunchBytes <= maxParentPacketBytes) { "Lane-launch bytes cannot exceed parent-packet bytes." }
  }

  companion object {
    val DEFAULT: ReviewContextBudgetPolicy = ReviewContextBudgetPolicy()

    fun deriveLaneEvidenceBytes(
      basePolicy: ReviewContextBudgetPolicy,
      assignment: ReviewAssignment,
      packet: ReviewContextPacket,
    ): Long {
      val packetBytes = packet.changedHunks.sumOf { it.contentBytes }
      if (packetBytes == 0L) return basePolicy.maxLaneEvidenceBytes
      val bytesByHunkId = packet.changedHunks.associate { it.hunkId to it.contentBytes }
      val assignmentBytes = assignment.assignedHunks.sumOf { bytesByHunkId.getValue(it) }
      val scaled = basePolicy.maxLaneEvidenceBytes * assignmentBytes / packetBytes
      return maxOf(scaled, basePolicy.maxEvidenceResultBytes)
    }
  }
}

sealed interface ReviewBudgetOutcome {
  val lane: String
  val budgetKind: ReviewBudgetKind
  val configuredLimit: Long
  val observedValue: Long
  val packetDigest: String
  val assignmentDigest: String
  val enforceable: Boolean
  val type: String
}

data class ReviewContextBudgetExceeded(
  override val lane: String,
  override val budgetKind: ReviewBudgetKind,
  override val configuredLimit: Long,
  override val observedValue: Long,
  override val packetDigest: String,
  override val assignmentDigest: String,
  override val enforceable: Boolean,
) : ReviewBudgetOutcome {
  override val type: String = REVIEW_CONTEXT_BUDGET_EXCEEDED

  init {
    require(lane.isNotBlank())
    require(configuredLimit >= 0 && observedValue > configuredLimit)
  }
}

data class ReviewLaneIdentity(val lane: String, val packetDigest: String, val assignmentDigest: String) {
  init {
    require(lane.isNotBlank()) { "Review lane identity lane must not be blank." }
    require(packetDigest.matches(SHA256_HEX) && assignmentDigest.matches(SHA256_HEX)) {
      "Review lane identity digests must be lowercase SHA-256."
    }
  }

  companion object {
    fun of(assignment: ReviewAssignment): ReviewLaneIdentity =
      ReviewLaneIdentity(assignment.lane, assignment.packetDigest, assignment.digest)

    fun ofParallelLane(
      agentId: String,
      parentPrompt: String,
    ): ReviewLaneIdentity =
      ReviewLaneIdentity(
        lane = agentId,
        packetDigest = sha256HexUtf8(parentPrompt.replace("\r\n", "\n")),
        assignmentDigest = sha256HexUtf8(agentId + "\u001f" + parentPrompt.replace("\r\n", "\n")),
      )
  }
}

object ReviewBudgetEvaluator {
  fun laneResultOutcome(
    identity: ReviewLaneIdentity,
    budget: ReviewContextBudgetPolicy,
    observedBytes: Long,
  ): ReviewContextBudgetExceeded? =
    exceededOrNull(
      identity,
      ReviewBudgetKind.LANE_RESULT_BYTES,
      budget.maxLaneResultBytes,
      observedBytes,
    )

  fun exceededOrNull(
    identity: ReviewLaneIdentity,
    budgetKind: ReviewBudgetKind,
    configuredLimit: Long,
    observedValue: Long,
  ): ReviewContextBudgetExceeded? =
    if (observedValue > configuredLimit) {
      ReviewContextBudgetExceeded(
        lane = identity.lane,
        budgetKind = budgetKind,
        configuredLimit = configuredLimit,
        observedValue = observedValue,
        packetDigest = identity.packetDigest,
        assignmentDigest = identity.assignmentDigest,
        enforceable = true,
      )
    } else {
      null
    }
}
