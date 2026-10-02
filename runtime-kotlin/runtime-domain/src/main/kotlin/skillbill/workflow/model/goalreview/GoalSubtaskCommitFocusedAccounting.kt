package skillbill.workflow.model.goalreview

import skillbill.contracts.scaffold.wire.optionalList
import skillbill.contracts.scaffold.wire.optionalString
import skillbill.review.context.model.accounting.ReviewIntegrationTerminalOutcome
import skillbill.review.context.model.hunk.SHA256_HEX
import skillbill.workflow.model.persistence.artifact.asExactIntOrNull
import skillbill.workflow.model.persistence.artifact.asExactLongOrNull
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

data class GoalSubtaskCommitFocusedAccounting(
  val commitSequenceDigest: String,
  val commitCount: Int,
  val laneCount: Int,
  val focusedCommitCount: Int,
  val skippedCommitCount: Int,
  val integrationTerminalOutcome: ReviewIntegrationTerminalOutcome,
  val routingDigest: String? = null,
  val focusedPairCount: Int? = null,
  val skippedPairCount: Int? = null,
  val laneBundleSizes: Map<String, Long> = emptyMap(),
  val laneSegmentCounts: Map<String, Int> = emptyMap(),
  val incompleteLanes: List<String> = emptyList(),
  val parentAnalysisPairs: Int? = null,
  val parentAnalysisBytes: Long? = null,
  val integrationSkipReason: String? = null,
  val integrationFindingCount: Int? = null,
) {
  init {
    require(commitSequenceDigest.matches(SHA256_HEX)) {
      "Commit-focused accounting requires a SHA-256 commit sequence identity."
    }
    require(listOf(commitCount, laneCount, focusedCommitCount, skippedCommitCount).all { it >= 0 })
    require(focusedCommitCount + skippedCommitCount == commitCount) {
      "Every commit is either focused by some lane or skipped by all of them."
    }
    require(incompleteLanes.distinct().size == incompleteLanes.size)
    if (integrationTerminalOutcome == ReviewIntegrationTerminalOutcome.SKIPPED_NOT_APPLICABLE) {
      require(!integrationSkipReason.isNullOrBlank()) {
        "A skipped integration pass must record why it was not applicable."
      }
    }
  }

  val isCleanCoverage: Boolean get() = incompleteLanes.isEmpty()

  fun toPersistenceWire(): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(toArtifactMap())

  internal fun toArtifactMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      "commit_sequence_digest" to commitSequenceDigest,
      "commit_count" to commitCount,
      "lane_count" to laneCount,
      "focused_commit_count" to focusedCommitCount,
      "skipped_commit_count" to skippedCommitCount,
      "integration_terminal_outcome" to integrationTerminalOutcome.wireValue,
    ).apply {
      routingDigest?.let { put("routing_digest", it) }
      focusedPairCount?.let { put("focused_pair_count", it) }
      skippedPairCount?.let { put("skipped_pair_count", it) }
      laneBundleSizes.takeIf { it.isNotEmpty() }?.let { put("lane_bundle_sizes", it.toSortedMap()) }
      laneSegmentCounts.takeIf { it.isNotEmpty() }?.let { put("lane_segment_counts", it.toSortedMap()) }
      incompleteLanes.takeIf { it.isNotEmpty() }?.let { put("incomplete_lanes", it.sorted()) }
      parentAnalysisPairs?.let { put("parent_analysis_pairs", it) }
      parentAnalysisBytes?.let { put("parent_analysis_bytes", it) }
      integrationSkipReason?.let { put("integration_skip_reason", it) }
      integrationFindingCount?.let { put("integration_finding_count", it) }
    }

  companion object {
    val SKIPPED_NOT_APPLICABLE: String = ReviewIntegrationTerminalOutcome.SKIPPED_NOT_APPLICABLE.wireValue
    val INTEGRATION_TERMINAL_OUTCOMES: Set<String> =
      ReviewIntegrationTerminalOutcome.entries
        .mapTo(linkedSetOf(), ReviewIntegrationTerminalOutcome::wireValue)

    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    internal fun fromArtifactMap(
      raw: Map<String, Any?>,
      path: String,
    ): GoalSubtaskCommitFocusedAccounting {
      raw.requireOnlyReviewStateKeys(ARTIFACT_KEYS, path)
      val reader = reviewStateReader(raw, path)
      return GoalSubtaskCommitFocusedAccounting(
        commitSequenceDigest = reader.requiredString("commit_sequence_digest"),
        commitCount = reader.requiredInt("commit_count"),
        laneCount = reader.requiredInt("lane_count"),
        focusedCommitCount = reader.requiredInt("focused_commit_count"),
        skippedCommitCount = reader.requiredInt("skipped_commit_count"),
        integrationTerminalOutcome =
          requireNotNull(
            ReviewIntegrationTerminalOutcome.fromWire(
              reader.requiredString("integration_terminal_outcome"),
            ),
          ) {
            "Unknown integration terminal outcome at '$path.integration_terminal_outcome'."
          },
        routingDigest = reader.optionalString("routing_digest"),
        focusedPairCount = reader.optionalInt("focused_pair_count"),
        skippedPairCount = reader.optionalInt("skipped_pair_count"),
        laneBundleSizes = raw.longCountMap("lane_bundle_sizes", path),
        laneSegmentCounts =
          raw.longCountMap("lane_segment_counts", path)
            .mapValues { (entryKey, value) ->
              value.asExactIntOrNull()
                ?: reviewStateError("$path.lane_segment_counts.$entryKey", "must be an integer.")
            },
        incompleteLanes =
          reader.optionalList("incomplete_lanes")
            .orEmpty()
            .mapIndexed { index, value ->
              (value as? String)?.takeIf(String::isNotBlank)
                ?: reviewStateError("$path.incomplete_lanes[$index]", "must be a non-blank string.")
            },
        parentAnalysisPairs = reader.optionalInt("parent_analysis_pairs"),
        parentAnalysisBytes = reader.optionalInt("parent_analysis_bytes")?.toLong(),
        integrationSkipReason = reader.optionalString("integration_skip_reason"),
        integrationFindingCount = reader.optionalInt("integration_finding_count"),
      )
    }

    private val ARTIFACT_KEYS =
      setOf(
        "commit_sequence_digest",
        "commit_count",
        "lane_count",
        "focused_commit_count",
        "skipped_commit_count",
        "integration_terminal_outcome",
        "routing_digest",
        "focused_pair_count",
        "skipped_pair_count",
        "lane_bundle_sizes",
        "lane_segment_counts",
        "incomplete_lanes",
        "parent_analysis_pairs",
        "parent_analysis_bytes",
        "integration_skip_reason",
        "integration_finding_count",
      )

    private fun Map<String, Any?>.longCountMap(
      key: String,
      path: String,
    ): Map<String, Long> {
      val raw = this[key] ?: return emptyMap()
      return raw.toReviewStateMap("$path.$key").mapValues { (entryKey, value) ->
        value.asExactLongOrNull()
          ?: reviewStateError("$path.$key.$entryKey", "must be an integer.")
      }
    }
  }
}
