package skillbill.review.context.model.commit

import skillbill.review.context.model.hunk.canonicalFieldList
import skillbill.review.context.model.hunk.canonicalFields
import skillbill.text.sha256HexUtf8

internal const val REVIEW_ROUTING_REASON_MAX_CHARS: Int = 600

enum class ReviewCommitLaneDisposition { FOCUSED, SKIPPED }

data class ReviewCommitLaneDecision(
  val commitSha: String,
  val orderIndex: Int,
  val lane: String,
  val disposition: ReviewCommitLaneDisposition,
  val reason: String,
  val signals: List<String> = emptyList(),
) {
  init {
    require(commitSha.isNotBlank()) { "Commit/lane decision commit identity must not be blank." }
    require(orderIndex >= 0) { "Commit/lane decision order index cannot be negative." }
    require(lane.isNotBlank()) { "Commit/lane decision lane must not be blank." }
    require(reason.isNotBlank()) {
      "Commit/lane decision '$commitSha'/'$lane' must carry a non-blank reason; a skip with no reason is unfalsifiable."
    }
    require(reason.length <= REVIEW_ROUTING_REASON_MAX_CHARS) {
      "Commit/lane decision reason exceeds the bounded limit of $REVIEW_ROUTING_REASON_MAX_CHARS characters."
    }
    require(signals.distinct().size == signals.size) { "Commit/lane decision signals must be unique." }
    require(signals.all(String::isNotBlank)) { "Commit/lane decision signals must not be blank." }
  }

  val focused: Boolean get() = disposition == ReviewCommitLaneDisposition.FOCUSED

  val canonical: String get() =
    canonicalFields(
      commitSha,
      orderIndex,
      lane,
      disposition.name,
      reason.replace("\r\n", "\n"),
      canonicalFieldList(signals.sorted()),
    )
}

data class ReviewCommitLaneRoutingMatrix(
  val commitShas: List<String>,
  val lanes: List<String>,
  val decisions: List<ReviewCommitLaneDecision>,
) {
  init {
    require(commitShas.isNotEmpty()) { "A routing matrix must analyze at least one commit unit." }
    require(commitShas.distinct().size == commitShas.size) { "Routing matrix commit identities must be unique." }
    require(lanes.isNotEmpty()) { "A routing matrix must analyze at least one lane." }
    require(lanes.distinct().size == lanes.size) { "Routing matrix lanes must be unique." }
    val pairs = decisions.map { it.commitSha to it.lane }
    require(pairs.distinct().size == pairs.size) { "Routing matrix carries a duplicate commit/lane decision." }
    val expected = commitShas.flatMap { sha -> lanes.map { sha to it } }.toSet()
    val missing = expected - pairs.toSet()
    require(missing.isEmpty()) {
      "Routing matrix is missing a final decision for ${missing.size} commit/lane pair(s); every pair must be decided."
    }
    val unknown = pairs.toSet() - expected
    require(unknown.isEmpty()) { "Routing matrix decides commit/lane pairs it does not analyze: $unknown." }
    val orderBySha = commitShas.withIndex().associate { (index, sha) -> sha to index }
    require(decisions.all { it.orderIndex == orderBySha.getValue(it.commitSha) }) {
      "Routing matrix decision order index diverges from the analyzed commit order."
    }
  }

  fun focusedCommits(lane: String): List<String> =
    decisions
      .filter { it.lane == lane && it.focused }
      .sortedBy { it.orderIndex }
      .map { it.commitSha }

  fun decisionsFor(lane: String): List<ReviewCommitLaneDecision> =
    decisions
      .filter { it.lane == lane }
      .sortedBy { it.orderIndex }

  val focusedPairCount: Int get() = decisions.count { it.focused }
  val analyzedPairCount: Int get() = decisions.size

  val canonical: String get() =
    canonicalFields(
      canonicalFieldList(commitShas),
      canonicalFieldList(lanes),
      canonicalFieldList(
        decisions.sortedWith(compareBy({ it.orderIndex }, { it.lane })).map { it.canonical },
      ),
    )

  val routingDigest: String get() = sha256HexUtf8(canonical)
}
