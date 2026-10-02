package skillbill.review.context.model.commit

import skillbill.review.context.model.bundle.ReviewLaneBundle
import skillbill.review.context.model.hunk.ReviewBaselineUntrackedPolicy
import skillbill.review.context.model.hunk.ReviewDependencyAllowlist
import skillbill.review.context.model.hunk.ReviewEvidenceTarget
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import skillbill.review.context.model.hunk.ReviewLearningsReference
import skillbill.review.context.model.hunk.ReviewRevision
import skillbill.review.context.model.hunk.ReviewRuleReference
import skillbill.review.context.model.hunk.SHA256_HEX
import skillbill.review.context.model.hunk.canonicalFieldList
import skillbill.review.model.requireRepositoryRelativePath
import skillbill.text.sha256HexUtf8

data class ReviewAssignment(
  val reviewId: String,
  val packetDigest: String,
  val lane: String,
  val baseRevision: String,
  val headRevision: String,
  val assignedPaths: List<String>,
  val assignedHunks: List<String>,
  val assignedBundle: ReviewLaneBundle = ReviewLaneBundle.EMPTY,
  val laneRouting: List<ReviewCommitLaneDecision> = emptyList(),
  val criteriaReferences: List<String> = emptyList(),
  val matchedRules: List<ReviewRuleReference> = emptyList(),
  val learnings: List<ReviewLearningsReference> = emptyList(),
  val evidenceTargets: List<ReviewEvidenceTarget> = emptyList(),
  val reviewRevision: ReviewRevision,
  val laneDecision: ReviewLaneDecision,
  val dependencyAllowlist: ReviewDependencyAllowlist = ReviewDependencyAllowlist.EMPTY,
  val expansions: List<ReviewExpansionRecord> = emptyList(),
  val baselineUntrackedPolicy: ReviewBaselineUntrackedPolicy = ReviewBaselineUntrackedPolicy.EMPTY,
) {
  init {
    require(reviewId.isNotBlank() && lane.isNotBlank() && baseRevision.isNotBlank() && headRevision.isNotBlank())
    require(packetDigest.matches(SHA256_HEX)) { "Packet digest must be lowercase SHA-256." }
    require(assignedPaths.distinct().size == assignedPaths.size) { "Assigned paths must be unique." }
    assignedPaths.forEach(::requireRepositoryRelativePath)
    require(assignedHunks.distinct().size == assignedHunks.size) { "Assigned hunk ids must be unique." }

    require(assignedBundle.hunkIds.all { it in assignedHunks }) {
      "Assignment '$lane' bundle attributes hunks that are not assigned to the lane."
    }
    require(laneRouting.all { it.lane == lane }) { "Assignment '$lane' carries routing for a different lane." }
    require(laneRouting.map { it.commitSha }.distinct().size == laneRouting.size) {
      "Assignment '$lane' routing repeats a commit."
    }
    require(laneRouting.map { it.orderIndex }.zipWithNext().all { (previous, next) -> previous < next }) {
      "Assignment '$lane' routing must preserve packet commit order."
    }

    val explicitlySkipped = laneRouting.filterNot { it.focused }.map { it.commitSha }.toSet()
    val fromSkipped = assignedBundle.entries.map { it.commitSha }.filter { it in explicitlySkipped }
    require(fromSkipped.isEmpty()) {
      "Assignment '$lane' claims hunks from commits routing skipped for the lane: ${fromSkipped.sorted()}."
    }
    require(laneDecision.lane == lane) { "Lane decision '${laneDecision.lane}' does not describe lane '$lane'." }
    require(laneDecision.included) { "Assignments exist only for included lanes; '$lane' is excluded." }
    require(matchedRules.map { it.ruleId }.distinct().size == matchedRules.size) { "Matched rules must be unique." }
    require(learnings.map { it.learningId }.distinct().size == learnings.size) { "Learnings must be unique." }
    require(evidenceTargets.map { it.targetId }.distinct().size == evidenceTargets.size) {
      "Evidence target ids must be unique."
    }
    val assigned = assignedPaths.toSet()
    require(dependencyAllowlist.normalized.none { it in assigned }) {
      "Dependency-allowlist entries must be disjoint from assigned paths."
    }
    require(expansions.map { it.expansionId }.distinct().size == expansions.size) {
      "Assignment expansion ids must be unique."
    }
    require(expansions.map { it.sequence }.distinct().size == expansions.size) {
      "Assignment expansion sequences must be unique."
    }
    require(expansions.all { it.assignmentDigest == digest }) {
      "Assignment '$lane' expansions must reference their enclosing assignment digest."
    }
    val reachable = assigned + dependencyAllowlist.normalized
    val escaping = expansions.map { it.requestedPath }.filterNot { it in reachable }
    require(escaping.isEmpty()) {
      "Assignment '$lane' expansions authorize paths outside its allowlist and assigned paths: ${escaping.sorted()}."
    }
  }

  val expansionsDigest: String
    get() =
      sha256HexUtf8(
        expansions.sortedWith(compareBy({ it.sequence }, { it.expansionId })).joinToString("\n") { it.canonical },
      )
  val digest: String
    get() =
      sha256HexUtf8(
        listOf(
          reviewId,
          packetDigest,
          reviewRevision.canonical,
          lane,
          laneDecision.canonical,
          baseRevision,
          headRevision,
          canonicalFieldList(assignedPaths.sorted()),
          canonicalFieldList(assignedHunks.sorted()),
          assignedBundle.canonical,
          canonicalFieldList(laneRouting.sortedBy { it.orderIndex }.map { it.canonical }),
          canonicalFieldList(criteriaReferences.sorted()),
          canonicalFieldList(matchedRules.map { it.canonical }.sorted()),
          canonicalFieldList(learnings.map { it.canonical }.sorted()),
          canonicalFieldList(evidenceTargets.map { it.canonical }.sorted()),
          dependencyAllowlist.canonical,
          baselineUntrackedPolicy.canonical,
        ).let { canonicalFieldList(it) }.replace("\r\n", "\n"),
      )
}
