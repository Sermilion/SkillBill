package skillbill.review.context.model.launch

import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.review.context.model.accounting.ReviewBudgetKind
import skillbill.review.context.model.accounting.ReviewContextBudgetExceeded
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.commit.ReviewAssignment
import skillbill.review.context.model.commit.ReviewCommitUnit
import skillbill.review.context.model.hunk.REVIEW_BUNDLE_MEASUREMENT_PLACEHOLDER_BYTES
import skillbill.review.context.model.hunk.REVIEW_MIN_LANE_BUDGET_SEGMENT_COUNT
import skillbill.review.context.model.hunk.structuredString
import skillbill.review.context.model.packet.ReviewContextPacket
import skillbill.review.context.model.packet.ReviewLaneAssembledBundle
import skillbill.review.context.model.packet.ReviewLaneAssembledEntry
import skillbill.review.context.model.packet.ReviewLaneBundleSegment
import skillbill.review.context.model.packet.ReviewLaneBundleSegmentation
import skillbill.review.context.model.packet.ReviewLaneCompletionState
import skillbill.review.context.model.packet.ReviewPacketConsumerContract
import skillbill.review.context.model.packet.segmentAssembledBundle
import skillbill.review.context.model.packet.toCompletionState
import skillbill.review.model.ReviewLaneReviewDisposition

enum class ReviewConversationIsolation { FRESH }

data class GovernedReviewLaunch(
  val assignment: ReviewAssignment,
  val packet: ReviewContextPacket,
  val specialistContract: String,
  val rubric: String,
  val brokerId: String,
  val budget: ReviewContextBudgetPolicy,
  val isolation: ReviewConversationIsolation = ReviewConversationIsolation.FRESH,
) {
  init {
    require(specialistContract.isNotBlank() && rubric.isNotBlank() && brokerId.isNotBlank())
    require(assignment.reviewId == packet.reviewId) { "Launch assignment belongs to a different review." }
    require(assignment.packetDigest == packet.digest) {
      "Launch assignment carries packet digest '${assignment.packetDigest}' but the packet recomputes to " +
        "'${packet.digest}'; a launch cannot be projected from an unattested assignment."
    }
    require(assignment.lane in packet.selectedLanes) {
      "Launch lane '${assignment.lane}' is not a selected lane of the packet."
    }
    require(assignment.reviewRevision == packet.reviewRevision) { "Launch review revision differs from the packet." }
    require(assignment.baseRevision == packet.baseRevision && assignment.headRevision == packet.headRevision) {
      "Launch base/head revisions differ from the packet."
    }
    require(assignment.baselineUntrackedPolicy == packet.baselineUntrackedPolicy) {
      "Launch baseline-untracked policy differs from the packet policy."
    }
    val packetDecision = packet.laneDecisions.single { it.lane == assignment.lane }
    require(assignment.laneDecision == packetDecision) { "Launch lane decision differs from the packet." }
    val normalizedAssignedPaths = assignment.assignedPaths.toSet()
    val unowned = normalizedAssignedPaths.filterNot { it in packet.ownedPaths }
    require(unowned.isEmpty()) { "Launch claims paths the packet does not own: ${unowned.sorted()}." }
    require(normalizedAssignedPaths == packetDecision.normalizedOwnedPaths.toSet()) {
      "Launch paths differ from the packet lane decision."
    }
    val unownedHunks = assignment.assignedHunks.filterNot { it in packet.ownedHunkIds }
    require(unownedHunks.isEmpty()) { "Launch claims hunk ids the packet does not own." }

    val expectedHunks = packet.focusedHunkIds(packetDecision)
    require(assignment.assignedHunks.toSet() == expectedHunks) {
      "Launch hunks differ from the focused-commit hunks the packet routed to the lane."
    }
    require(assignment.laneRouting == packet.routingMatrix.decisionsFor(assignment.lane)) {
      "Launch lane routing differs from the packet routing matrix for lane '${assignment.lane}'."
    }
    require(assignment.dependencyAllowlist.normalized.all { it in packet.dependencyAllowlist.normalized }) {
      "Launch dependency allowlist escapes the packet allowlist."
    }
    require(assignment.matchedRules.toSet() == packet.matchedRules.toSet()) {
      "Launch matched rules differ from the packet rules."
    }
    val expectedTargets =
      packet.evidenceTargets
        .filter { it.path in packetDecision.normalizedOwnedPaths }
        .toSet()
    require(assignment.evidenceTargets.toSet() == expectedTargets) {
      "Launch evidence targets differ from the packet targets for the lane."
    }
    requireBundleMatchesPacket()
  }

  private fun requireBundleMatchesPacket() {
    val unitsBySha = packet.commitUnits.associateBy { it.commitSha }
    val outside = assignment.assignedBundle.entries.map { it.commitSha }.filterNot { it in unitsBySha }
    require(outside.isEmpty()) {
      "Launch assignment claims commit units outside its packet: ${outside.sorted()}."
    }
    assignment.assignedBundle.entries.forEach { entry ->
      val unit = unitsBySha.getValue(entry.commitSha)
      require(entry.orderIndex == unit.orderIndex) {
        "Launch bundle entry '${entry.commitSha}' order differs from the packet commit order."
      }
      val strayHunks = entry.hunkIds.filterNot { it in unit.hunkIds }
      require(strayHunks.isEmpty()) {
        "Launch bundle attributes hunks to commit '${entry.commitSha}' that the commit does not own."
      }
    }
    require(assignment.assignedBundle.hunkIds.toSet() == assignment.assignedHunks.toSet()) {
      "Launch bundle must cover exactly the assigned hunks so every projected body is commit-attributable."
    }
  }

  private val projectedUnits: List<ReviewCommitUnit>
    get() {
      val unitsBySha = packet.commitUnits.associateBy { it.commitSha }
      return assignment.assignedBundle.entries.map { entry -> unitsBySha.getValue(entry.commitSha) }
    }

  val assembledBundle: ReviewLaneAssembledBundle by lazy(LazyThreadSafetyMode.PUBLICATION) {
    ReviewLaneAssembledBundle.assemble(assignment, packet)
  }

  val segmentation: ReviewLaneBundleSegmentation by lazy(LazyThreadSafetyMode.PUBLICATION) {
    segmentAssembledBundle(assembledBundle, budget.maxLaneLaunchBytes, ::measureBundleEntries)
  }

  val completionState: ReviewLaneCompletionState by lazy(LazyThreadSafetyMode.PUBLICATION) {
    segmentation.toCompletionState(assembledBundle.compositionDigest)
  }

  fun requireCodexForkTurns(forkTurns: String?) {
    require(forkTurns == "none") { "Governed Codex review launches require fork_turns none." }
  }

  val deliveredEntries: List<ReviewLaneAssembledEntry> get() = segmentation.segments.flatMap { it.entries }

  val canonicalPayload: String get() = renderCanonicalPayload(deliveredEntries, segmentation)

  fun budgetOutcomeOrNull(): ReviewContextBudgetExceeded? {
    val overhead = measureBundleEntries(emptyList())
    val renderedBytes = canonicalPayload.toByteArray(Charsets.UTF_8).size.toLong()
    val allowance = budget.maxLaneLaunchBytes * maxOf(REVIEW_MIN_LANE_BUDGET_SEGMENT_COUNT, segmentation.segments.size)
    return if (overhead > budget.maxLaneLaunchBytes || renderedBytes > allowance) {
      ReviewContextBudgetExceeded(
        lane = assignment.lane,
        budgetKind = ReviewBudgetKind.LANE_LAUNCH_BYTES,
        configuredLimit = budget.maxLaneLaunchBytes,
        observedValue = maxOf(overhead, renderedBytes),
        packetDigest = assignment.packetDigest,
        assignmentDigest = assignment.digest,
        enforceable = true,
      )
    } else {
      null
    }
  }

  private fun measureBundleEntries(entries: List<ReviewLaneAssembledEntry>): Long {
    val synthetic =
      if (entries.isEmpty()) {
        ReviewLaneBundleSegmentation(emptyList(), emptyList(), budget.maxLaneLaunchBytes)
      } else {
        ReviewLaneBundleSegmentation(
          segments =
            listOf(
              ReviewLaneBundleSegment(
                segmentId = "measure",
                entries = entries,
                measuredBytes = REVIEW_BUNDLE_MEASUREMENT_PLACEHOLDER_BYTES,
              ),
            ),
          unreviewableEntries = emptyList(),
          budgetLimitBytes = budget.maxLaneLaunchBytes,
        )
      }
    return renderCanonicalPayload(entries, synthetic).toByteArray(Charsets.UTF_8).size.toLong()
  }

  private fun renderCanonicalPayload(
    entries: List<ReviewLaneAssembledEntry>,
    segments: ReviewLaneBundleSegmentation,
  ): String =
    buildString {
      appendLaunchIdentity()
      appendGovernanceText()
      appendAssignedSurface()
      appendBundleSection(entries, segments)
      appendPolicySurface()
    }.trimEnd()

  private fun StringBuilder.appendLaunchIdentity() {
    appendLine("contract_version: \"$REVIEW_CONTEXT_CONTRACT_VERSION\"")
    appendLine("kind: launch")
    appendLine("review_id: ${assignment.reviewId}")
    appendLine("review_revision: ${assignment.reviewRevision.sessionId}@${assignment.reviewRevision.runRevision}")
    appendLine("packet_digest: ${assignment.packetDigest}")
    appendLine("assignment_digest: ${assignment.digest}")
    appendLine("lane: ${assignment.lane}")
    appendLine("base_revision: ${assignment.baseRevision}")
    appendLine("head_revision: ${assignment.headRevision}")
    appendLine("broker_id: $brokerId")
  }

  private fun StringBuilder.appendGovernanceText() {
    appendLine("specialist_contract: |")
    specialistContract.replace("\r\n", "\n").lineSequence().forEach { appendLine("  $it") }
    appendLine("rubric: |")
    rubric.replace("\r\n", "\n").lineSequence().forEach { appendLine("  $it") }
    appendLine("consumer_contract: |")
    ReviewPacketConsumerContract.CONSUMER_CONTRACT.lineSequence().forEach { appendLine("  $it") }
  }

  private fun StringBuilder.appendAssignedSurface() {
    appendLine("assigned_paths:")
    assignment.assignedPaths.sorted().forEach { appendLine("  - ${structuredString(it)}") }
    appendLine("assigned_hunks:")
    assignment.assignedHunks.sorted().forEach { appendLine("  - $it") }
    appendLine(
      "coverage_fact: base=${packet.coverageFact.baseRevision}, head=${packet.coverageFact.headRevision}, " +
        "commits=${packet.coverageFact.commitCount}, chain_verified=${packet.coverageFact.chainVerified}, " +
        "path_coverage_verified=${packet.coverageFact.pathCoverageVerified}, " +
        "degraded_reason=${structuredString(packet.coverageFact.degradedReason.orEmpty())}",
    )
    appendLine("assigned_commit_units:")
    projectedUnits.forEach { unit ->
      appendLine("  - commit_sha: ${structuredString(unit.commitSha)}")
      appendLine("    parent_sha: ${structuredString(unit.parentSha)}")
      appendLine("    subject: ${structuredString(unit.subject.replace("\r\n", "\n"))}")
      appendLine("    order_index: ${unit.orderIndex}")
      appendLine("    source: ${unit.source.name.lowercase()}")
    }
    appendLine("lane_routing:")
    assignment.laneRouting.forEach { decision ->
      appendLine("  - commit_sha: ${structuredString(decision.commitSha)}")
      appendLine("    order_index: ${decision.orderIndex}")
      appendLine("    disposition: ${decision.disposition.name.lowercase()}")
      appendLine("    reason: ${structuredString(decision.reason)}")
    }
  }

  private fun StringBuilder.appendBundleSection(
    entries: List<ReviewLaneAssembledEntry>,
    segments: ReviewLaneBundleSegmentation,
  ) {
    val disposition =
      if (segments.incomplete) {
        ReviewLaneReviewDisposition.INCOMPLETE
      } else {
        ReviewLaneReviewDisposition.COMPLETE
      }
    appendLine("bundle:")
    appendLine("  composition_digest: ${assembledBundle.compositionDigest}")
    appendLine("  lane_disposition: ${disposition.wireValue}")
    if (segments.incomplete) {
      appendLine("  budget_dimension: lane_launch_bytes")
    }
    appendLine("  unreviewed_segment_ids:")
    segments.unreviewedSegmentIds.forEach { appendLine("    - $it") }
    appendLine("  entries:")
    entries.forEach { entry ->
      appendLine("    - commit_sha: ${structuredString(entry.commitSha)}")
      appendLine("      parent_sha: ${structuredString(entry.parentSha)}")
      appendLine("      subject: ${structuredString(entry.subject.replace("\r\n", "\n"))}")
      appendLine("      order_index: ${entry.orderIndex}")
      appendLine("      path: ${structuredString(entry.hunk.path)}")
      appendLine("      hunk_id: ${entry.hunkId}")
      appendLine("      old_start: ${entry.hunk.oldStart}")
      appendLine("      old_count: ${entry.hunk.oldCount}")
      appendLine("      new_start: ${entry.hunk.newStart}")
      appendLine("      new_count: ${entry.hunk.newCount}")
      appendLine("      content_digest: ${entry.hunk.contentDigest}")
      appendLine("      evidence_locator:")
      appendLine("        store_path: ${structuredString(entry.hunk.evidenceLocator.storePath)}")
      appendLine("        payload_file: ${structuredString(entry.hunk.evidenceLocator.payloadFile)}")
      appendLine("        hunk_header: ${structuredString(entry.hunk.evidenceLocator.hunkHeader)}")
    }
    appendLine("  segments:")
    segments.segments.forEach { segment ->
      appendLine("    - segment_id: ${segment.segmentId}")
      appendLine("      measured_bytes: ${segment.measuredBytes}")
      appendLine("      composition_digest: ${segment.compositionDigest}")
      appendLine("      entries:")
      segment.entries.forEach { entry ->
        appendLine("        - commit_sha: ${structuredString(entry.commitSha)}")
        appendLine("          order_index: ${entry.orderIndex}")
        appendLine("          hunk_id: ${entry.hunkId}")
        appendLine("          path: ${structuredString(entry.hunk.path)}")
      }
    }
  }

  private fun StringBuilder.appendPolicySurface() {
    appendLine("criteria_references:")
    assignment.criteriaReferences.sorted().forEach { appendLine("  - $it") }
    appendLine("matched_rules:")
    assignment.matchedRules.sortedBy { it.ruleId }.forEach { rule ->
      appendLine("  - rule_id: ${structuredString(rule.ruleId)}")
      appendLine("    source_path: ${structuredString(rule.sourcePath)}")
      appendLine("    excerpt: |")
      rule.excerpt.replace("\r\n", "\n").lineSequence().forEach { appendLine("      $it") }
      appendLine("    digest: ${rule.digest}")
    }
    appendLine("learnings:")
    assignment.learnings.sortedBy { it.learningId }.forEach { learning ->
      appendLine("  - learning_id: ${structuredString(learning.learningId)}")
      appendLine("    source: ${structuredString(learning.source)}")
      appendLine("    scope: ${structuredString(learning.scope)}")
      appendLine("    title: ${structuredString(learning.title.replace("\r\n", "\n"))}")
      appendLine("    rule_text: |")
      learning.ruleText.replace("\r\n", "\n").lineSequence().forEach { appendLine("      $it") }
      appendLine("    digest: ${learning.digest}")
    }
    appendLine("evidence_targets:")
    assignment.evidenceTargets.map { it.targetId }.sorted().forEach { appendLine("  - $it") }
    appendLine("dependency_allowlist:")
    assignment.dependencyAllowlist.normalized.sorted().forEach { appendLine("  - ${structuredString(it)}") }
    appendLine("baseline_untracked_policy:")
    appendLine("  included_paths:")
    assignment.baselineUntrackedPolicy.includedPaths.sorted()
      .forEach { appendLine("    - ${structuredString(it)}") }
    appendLine("  excluded_paths:")
    assignment.baselineUntrackedPolicy.excludedPaths.sorted()
      .forEach { appendLine("    - ${structuredString(it)}") }
    appendLine("forbidden_rediscovery:")
    ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY.forEach { appendLine("  - $it") }
    appendLine("evidence_surface_rules: |")
    ReviewPacketConsumerContract.EVIDENCE_SURFACE_RULES.lineSequence().forEach { appendLine("  $it") }
    appendLine("report_structure: |")
    ReviewPacketConsumerContract.REPORT_STRUCTURE.lineSequence().forEach { appendLine("  $it") }
    appendLine(
      "budgets: launch=${budget.maxLaneLaunchBytes}, evidence=${budget.maxLaneEvidenceBytes}, " +
        "result=${budget.maxLaneResultBytes}, expansions=${budget.maxAssignmentExpansions}, " +
        "tool_calls=${budget.maxSpecialistToolCalls}, model_turns=${budget.maxSpecialistModelTurns}",
    )
  }
}
