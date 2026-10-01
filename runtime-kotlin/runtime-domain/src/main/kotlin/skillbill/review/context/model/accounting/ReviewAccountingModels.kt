package skillbill.review.context.model.accounting

import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.review.context.model.packet.ReviewLaneSegmentAccounting

data class ReviewAccountingCounters(
  val launchBytes: Long = 0,
  val evidenceBytes: Long = 0,
  val resultBytes: Long = 0,
  val expansions: Int = 0,
  val toolCalls: Int = 0,
  val modelTurns: Int = 0,
) {
  init {
    require(listOf(launchBytes, evidenceBytes, resultBytes).all { it >= 0 })
    require(listOf(expansions, toolCalls, modelTurns).all { it >= 0 })
  }

  operator fun plus(other: ReviewAccountingCounters): ReviewAccountingCounters =
    ReviewAccountingCounters(
      launchBytes + other.launchBytes,
      evidenceBytes + other.evidenceBytes,
      resultBytes + other.resultBytes,
      expansions + other.expansions,
      toolCalls + other.toolCalls,
      modelTurns + other.modelTurns,
    )
}

data class ReviewAccountingInput(
  val lane: String,
  val assignmentDigest: String,
  val counters: ReviewAccountingCounters = ReviewAccountingCounters(),
  val terminalOutcome: ReviewAccountingTerminalOutcome = ReviewAccountingTerminalOutcome.COMPLETED,
  val bundleCompositionDigest: String? = null,
  val segmentAccounting: List<ReviewLaneSegmentAccounting> = emptyList(),
  val unreviewedSegmentIds: List<String> = emptyList(),
  val children: List<ReviewAccountingInput> = emptyList(),
  val evidenceDelivery: ReviewEvidenceDelivery? = null,
) {
  init {
    require(lane.isNotBlank() && assignmentDigest.isNotBlank())
  }
}

data class ReviewAccountingNode(
  val lane: String,
  val assignmentDigest: String,
  val counters: ReviewAccountingCounters,
  val inclusiveCounters: ReviewAccountingCounters,
  val terminalOutcome: ReviewAccountingTerminalOutcome,
  val bundleCompositionDigest: String?,
  val segmentAccounting: List<ReviewLaneSegmentAccounting>,
  val unreviewedSegmentIds: List<String>,
  val children: List<ReviewAccountingNode>,
  val evidenceDelivery: ReviewEvidenceDelivery? = null,
)

data class ReviewCommitRoutingAccounting(
  val commitSequenceDigest: String,
  val routingDigest: String,
  val commitCount: Int,
  val laneCount: Int,
  val focusedCommitCount: Int,
  val skippedCommitCount: Int,
  val focusedPairCount: Int,
  val skippedPairCount: Int,
  val incompleteLanes: List<String> = emptyList(),
) {
  init {
    require(commitSequenceDigest.isNotBlank() && routingDigest.isNotBlank())
    require(commitCount >= 1) { "A routed review covers at least one commit." }
    require(
      listOf(laneCount, focusedCommitCount, skippedCommitCount, focusedPairCount, skippedPairCount)
        .all { it >= 0 },
    )
    require(focusedCommitCount + skippedCommitCount == commitCount) {
      "Every commit is either focused by some lane or skipped by all of them."
    }
  }
}

data class ReviewParentAnalysisConsumption(
  val analyzedPairs: Int,
  val analyzedBytes: Long,
  val maxAnalysisPairs: Int,
  val maxAnalysisBytes: Long,
) {
  init {
    require(analyzedPairs >= 0 && analyzedBytes >= 0)
    require(maxAnalysisPairs >= 1 && maxAnalysisBytes >= 1)
  }
}

data class ReviewIntegrationAccounting(
  val commitSequenceDigest: String,
  val terminalOutcome: ReviewIntegrationTerminalOutcome,
  val summarizedLaneCount: Int,
  val findingCount: Int,
  val counters: ReviewAccountingCounters = ReviewAccountingCounters(),
  val skipReason: String? = null,
) {
  constructor(
    commitSequenceDigest: String,
    terminalOutcome: String,
    summarizedLaneCount: Int,
    findingCount: Int,
    counters: ReviewAccountingCounters = ReviewAccountingCounters(),
    skipReason: String? = null,
  ) : this(
    commitSequenceDigest,
    requireNotNull(ReviewIntegrationTerminalOutcome.fromWire(terminalOutcome)) {
      "Unknown integration outcome '$terminalOutcome'."
    },
    summarizedLaneCount,
    findingCount,
    counters,
    skipReason,
  )

  init {
    require(commitSequenceDigest.isNotBlank())
    require(summarizedLaneCount >= 0 && findingCount >= 0)
    if (terminalOutcome == ReviewIntegrationTerminalOutcome.SKIPPED_NOT_APPLICABLE) {
      require(!skipReason.isNullOrBlank()) { "A skipped integration pass must record why." }
    }
  }
}

data class ReviewAccountingSummary(
  val reviewId: String,
  val packetDigest: String,
  val parent: ReviewAccountingNode,
  val lanes: List<ReviewAccountingNode>,
  val aggregateCounters: ReviewAccountingCounters,
  val commitRouting: ReviewCommitRoutingAccounting? = null,
  val parentAnalysis: ReviewParentAnalysisConsumption? = null,
  val integration: ReviewIntegrationAccounting? = null,
  val contractVersion: String = REVIEW_CONTEXT_CONTRACT_VERSION,
)

data class ReviewEvidenceDelivery(
  val requiredUnits: Int,
  val deliveredUnits: Int,
  val requestCount: Int,
) {
  init {
    require(requiredUnits >= 0 && deliveredUnits in 0..requiredUnits && requestCount >= 0)
  }

  val remainingUnits: Int get() = requiredUnits - deliveredUnits
}
