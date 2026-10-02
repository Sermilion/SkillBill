package skillbill.review.context.model.packet

import skillbill.review.context.model.commit.ReviewCommitCoverageFact
import skillbill.review.context.model.commit.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.commit.ReviewCommitUnit
import skillbill.review.context.model.commit.ReviewLaneDecision
import skillbill.review.context.model.hunk.ReviewBaselineUntrackedPolicy
import skillbill.review.context.model.hunk.ReviewBuildTestFact
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.hunk.ReviewDependencyAllowlist
import skillbill.review.context.model.hunk.ReviewEvidenceTarget
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import skillbill.review.context.model.hunk.ReviewLearningsReference
import skillbill.review.context.model.hunk.ReviewRevision
import skillbill.review.context.model.hunk.ReviewRuleReference
import skillbill.review.context.model.hunk.canonicalFieldList
import skillbill.text.sha256HexUtf8

data class ReviewContextPacket(
  val reviewId: String,
  val repositoryIdentity: String,
  val baseRevision: String,
  val headRevision: String,
  val status: String,
  val stack: String?,
  val pack: String?,
  val addOns: List<String>,
  val selectedLanes: List<String>,
  val changedHunks: List<ReviewChangedHunk>,
  val commitUnits: List<ReviewCommitUnit>,
  val coverageFact: ReviewCommitCoverageFact,
  val routingMatrix: ReviewCommitLaneRoutingMatrix,
  val reviewRevision: ReviewRevision,
  val laneDecisions: List<ReviewLaneDecision>,
  val matchedRules: List<ReviewRuleReference> = emptyList(),
  val learningsReferences: List<ReviewLearningsReference> = emptyList(),
  val buildTestFacts: List<ReviewBuildTestFact> = emptyList(),
  val dependencyAllowlist: ReviewDependencyAllowlist = ReviewDependencyAllowlist.EMPTY,
  val evidenceTargets: List<ReviewEvidenceTarget> = emptyList(),
  val expansionLedger: List<ReviewExpansionRecord> = emptyList(),
  val composedLayers: List<String> = emptyList(),
  val baselineUntrackedPolicy: ReviewBaselineUntrackedPolicy = ReviewBaselineUntrackedPolicy.EMPTY,
) {
  init {
    require(reviewId.isNotBlank() && repositoryIdentity.isNotBlank())
    require(baseRevision.isNotBlank() && headRevision.isNotBlank())
    require(selectedLanes.isNotEmpty() && selectedLanes.distinct().size == selectedLanes.size)
    require(addOns.distinct().size == addOns.size)
    require(composedLayers.all(String::isNotBlank) && composedLayers.distinct().size == composedLayers.size)
    require(changedHunks.map { it.hunkId }.distinct().size == changedHunks.size) { "Changed hunk ids must be unique." }
    require(laneDecisions.map { it.lane }.distinct().size == laneDecisions.size) {
      "Lane decisions must carry one entry per lane."
    }
    require(laneDecisions.filter { it.included }.map { it.lane }.toSet() == selectedLanes.toSet()) {
      "Lane decisions must cover exactly the selected lanes."
    }
    require(matchedRules.map { it.ruleId }.distinct().size == matchedRules.size) { "Matched rules must be unique." }
    require(learningsReferences.map { it.learningId }.distinct().size == learningsReferences.size) {
      "Learnings references must be unique."
    }
    require(evidenceTargets.map { it.targetId }.distinct().size == evidenceTargets.size) {
      "Evidence target ids must be unique."
    }
    require(expansionLedger.map { it.expansionId }.distinct().size == expansionLedger.size) {
      "Expansion ledger ids must be unique."
    }
    require(expansionLedger.map { it.sequence }.distinct().size == expansionLedger.size) {
      "Expansion ledger sequences must be unique."
    }
    val owned = changedHunks.map { it.path }.toSet()
    val ownedHunks = changedHunks.map { it.hunkId }.toSet()
    val reachable = owned + dependencyAllowlist.normalized
    val escaping = expansionLedger.map { it.requestedPath }.filterNot { it in reachable }
    require(escaping.isEmpty()) {
      "Expansion ledger authorizes paths outside the packet allowlist and owned paths: ${escaping.sorted()}."
    }
    val targetPaths = evidenceTargets.map { it.path }.filterNot { it in owned }
    require(targetPaths.isEmpty()) { "Evidence targets name paths the packet does not own: ${targetPaths.sorted()}." }
    val targetHunks = evidenceTargets.flatMap { it.hunkIds }.filterNot { it in ownedHunks }
    require(targetHunks.isEmpty()) { "Evidence targets name hunk ids the packet does not own." }
    ReviewContextPacketCommitEvidenceValidator.validate(this, ownedHunks)
    requireRoutingMatrix()
  }

  private fun requireRoutingMatrix() {
    val orderedShas = commitUnits.sortedBy { it.orderIndex }.map { it.commitSha }
    require(routingMatrix.commitShas == orderedShas) {
      "Routing matrix commit order does not match the packet commit sequence."
    }
    val unanalyzed = selectedLanes.filterNot { it in routingMatrix.lanes }
    require(unanalyzed.isEmpty()) { "Selected lanes were never routed: ${unanalyzed.sorted()}." }
    val unfocused = selectedLanes.filter { routingMatrix.focusedCommits(it).isEmpty() }
    require(unfocused.isEmpty()) {
      "Selected lanes focused no commit, so they would review nothing: ${unfocused.sorted()}."
    }
  }

  fun focusedHunkIds(laneDecision: ReviewLaneDecision): Set<String> {
    val focused = routingMatrix.focusedCommits(laneDecision.lane).toSet()
    val ownedPaths = laneDecision.normalizedOwnedPaths.toSet()
    return commitUnits.filter { it.commitSha in focused }
      .flatMap { unit -> unit.canonicalHunks.filter { it.path in ownedPaths }.map { it.hunkId } }
      .toSet()
  }

  val ownedCommitIds: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    commitUnits.map { it.commitSha }.toSet()
  }

  val commitSequenceDigest: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
    sha256HexUtf8(canonicalFieldList(commitUnits.sortedBy { it.orderIndex }.map { it.commitUnitId }))
  }

  val ownedPaths: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    changedHunks.map { it.path }.toSet()
  }
  val ownedHunkIds: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    changedHunks.map { it.hunkId }.toSet()
  }

  val digest: String get() = sha256HexUtf8(canonicalValue())

  val expansionLedgerDigest: String
    get() =
      sha256HexUtf8(
        expansionLedger.sortedWith(compareBy({ it.sequence }, { it.expansionId }))
          .joinToString("\n") { it.canonical },
      )

  val canonicalBytes: Long
    get() =
      (canonicalValue() + expansionLedgerDigest + expansionLedger.joinToString("\n") { it.canonical })
        .toByteArray(Charsets.UTF_8).size.toLong()

  private fun canonicalValue(): String =
    listOf(
      reviewId,
      reviewRevision.canonical,
      repositoryIdentity,
      baseRevision,
      headRevision,
      status.replace("\r\n", "\n"),
      stack.orEmpty(),
      pack.orEmpty(),
      canonicalFieldList(addOns.sorted()),
      canonicalFieldList(composedLayers),
      canonicalFieldList(selectedLanes),
      laneDecisions.sortedWith(compareBy(ReviewLaneDecision::orderIndex, ReviewLaneDecision::lane))
        .map { it.canonical }.let { canonicalFieldList(it) },
      changedHunks.sortedBy { it.packetCanonical() }
        .map { it.packetCanonical() }.let { canonicalFieldList(it) },
      commitUnits.sortedBy { it.orderIndex }
        .map { it.canonicalValue() }.let { canonicalFieldList(it) },
      coverageFact.canonical,
      routingMatrix.canonical,
      canonicalFieldList(matchedRules.map { it.canonical }.sorted()),
      canonicalFieldList(learningsReferences.map { it.canonical }.sorted()),
      canonicalFieldList(buildTestFacts.map { it.canonical }.sorted()),
      dependencyAllowlist.canonical,
      baselineUntrackedPolicy.canonical,
      canonicalFieldList(evidenceTargets.map { it.canonical }.sorted()),
    ).let { canonicalFieldList(it) }
}
