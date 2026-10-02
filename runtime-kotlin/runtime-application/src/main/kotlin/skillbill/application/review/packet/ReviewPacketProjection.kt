package skillbill.application.review.packet

import skillbill.application.review.model.ReviewContextEnvelope
import skillbill.application.review.parallel.planning.criteriaReferences
import skillbill.application.review.spec.toProjectionPayload
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.review.context.model.commit.ReviewAssignment
import skillbill.review.context.model.commit.ReviewLaneDecision
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.launch.GovernedReviewAdjudicationLaunch
import skillbill.review.context.model.launch.GovernedReviewIntegrationLaunch
import skillbill.review.context.model.launch.GovernedReviewLaunch
import skillbill.review.context.model.launch.GovernedReviewVerificationLaunch
import skillbill.review.context.model.launch.ReviewSpecialistSummary
import skillbill.review.context.model.packet.ReviewContextPacket
import skillbill.review.context.model.packet.ReviewPacketConsumerContract
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingVerdict

fun ReviewContextPacket.toParentPacketEnvelope(): ReviewContextEnvelope =
  ReviewContextEnvelope.from(
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to REVIEW_CONTEXT_CONTRACT_VERSION,
      "kind" to "parent_packet",
      "review_id" to reviewId,
      "packet_digest" to digest,
      "review_revision" to reviewRevision.toEnvelope(),
      "repository_identity" to repositoryIdentity,
      "base_revision" to baseRevision,
      "head_revision" to headRevision,
      SharedPayloadKeys.STATUS to status.normalizeLineEndings(),
      "stack" to stack,
      "pack" to pack,
      "add_ons" to addOns.sorted(),
      "composed_layers" to composedLayers,
      "selected_lanes" to selectedLanes,
      "lane_decisions" to
        laneDecisions
          .sortedWith(compareBy(ReviewLaneDecision::orderIndex, ReviewLaneDecision::lane))
          .map { it.toEnvelope() },
      "changed_hunks" to
        changedHunks
          .sortedWith(compareBy(ReviewChangedHunk::path, ReviewChangedHunk::newStart))
          .map { it.toEnvelope() },
      "commit_units" to commitUnits.sortedBy { it.orderIndex }.map { it.toEnvelope() },
      "commit_sequence_digest" to commitSequenceDigest,
      "coverage_fact" to coverageFact.toEnvelope(),
      "routing_matrix" to routingMatrix.toEnvelope(),
      "matched_rules" to matchedRules.sortedBy { it.ruleId }.map { it.toEnvelope() },
      "learnings_references" to learningsReferences.sortedBy { it.learningId }.map { it.toEnvelope() },
      "build_test_facts" to buildTestFacts.sortedWith(compareBy({ it.kind }, { it.command })).map { it.toEnvelope() },
      "dependency_allowlist" to dependencyAllowlist.normalized.sorted(),
      "baseline_untracked_policy" to baselineUntrackedPolicy.toEnvelope(),
      "evidence_targets" to evidenceTargets.sortedBy { it.targetId }.map { it.toEnvelope() },
      "expansion_ledger" to
        expansionLedger.sortedWith(compareBy({ it.sequence }, { it.expansionId }))
          .map { it.toEnvelope() },
    ),
  )

fun ReviewAssignment.toAssignmentEnvelope(): ReviewContextEnvelope =
  ReviewContextEnvelope.from(
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to REVIEW_CONTEXT_CONTRACT_VERSION,
      "kind" to "assignment",
      "review_id" to reviewId,
      "packet_digest" to packetDigest,
      "assignment_digest" to digest,
      "review_revision" to reviewRevision.toEnvelope(),
      "lane" to lane,
      "lane_decision" to laneDecision.toEnvelope(),
      "base_revision" to baseRevision,
      "head_revision" to headRevision,
      "assigned_paths" to assignedPaths.sorted(),
      "assigned_hunks" to assignedHunks.sorted(),
      "assigned_bundle" to assignedBundle.toEnvelope(),
      "lane_routing" to laneRouting.map { it.toEnvelope() },
      "criteria_references" to criteriaReferences.sorted(),
      "matched_rules" to matchedRules.sortedBy { it.ruleId }.map { it.toEnvelope() },
      "learnings" to learnings.sortedBy { it.learningId }.map { it.toEnvelope() },
      "evidence_targets" to evidenceTargets.sortedBy { it.targetId }.map { it.toEnvelope() },
      "dependency_allowlist" to dependencyAllowlist.normalized.sorted(),
      "baseline_untracked_policy" to baselineUntrackedPolicy.toEnvelope(),
      "expansions" to expansions.sortedWith(compareBy({ it.sequence }, { it.expansionId })).map { it.toEnvelope() },
    ),
  )

fun GovernedReviewLaunch.toLaunchEnvelope(): ReviewContextEnvelope =
  ReviewContextEnvelope.from(
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to REVIEW_CONTEXT_CONTRACT_VERSION,
      "kind" to "launch",
      "review_id" to assignment.reviewId,
      "packet_digest" to assignment.packetDigest,
      "assignment_digest" to assignment.digest,
      "review_revision" to assignment.reviewRevision.toEnvelope(),
      "lane" to assignment.lane,
      "base_revision" to assignment.baseRevision,
      "head_revision" to assignment.headRevision,
      "specialist_contract" to specialistContract,
      "consumer_contract" to ReviewPacketConsumerContract.CONSUMER_CONTRACT,
      "rubric" to rubric,
      "assigned_paths" to assignment.assignedPaths.sorted(),
      "assigned_hunks" to assignment.assignedHunks.sorted(),
      "assigned_commit_units" to assignedCommitUnits().map { it.toAssignedEnvelope() },
      "lane_routing" to assignment.laneRouting.map { it.toEnvelope() },
      "coverage_fact" to packet.coverageFact.toEnvelope(),
      "bundle" to assembledBundle.toLaunchEnvelope(segmentation, completionState),
      "criteria_references" to assignment.criteriaReferences.sorted(),
      "matched_rules" to assignment.matchedRules.sortedBy { it.ruleId }.map { it.toEnvelope() },
      "learnings" to assignment.learnings.sortedBy { it.learningId }.map { it.toEnvelope() },
      "evidence_targets" to assignment.evidenceTargets.sortedBy { it.targetId }.map { it.toEnvelope() },
      "dependency_allowlist" to assignment.dependencyAllowlist.normalized.sorted(),
      "baseline_untracked_policy" to assignment.baselineUntrackedPolicy.toEnvelope(),
      "forbidden_rediscovery" to ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY,
      "evidence_surface_rules" to ReviewPacketConsumerContract.EVIDENCE_SURFACE_RULES,
      "report_structure" to ReviewPacketConsumerContract.REPORT_STRUCTURE,
      "broker_id" to brokerId,
      "isolation" to isolation.name.lowercase(),
      "budget" to budget.toEnvelope(),
    ),
  )

internal fun GovernedReviewVerificationLaunch.toVerificationLaunchEnvelope(): ReviewContextEnvelope =
  ReviewContextEnvelope.from(
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to REVIEW_CONTEXT_CONTRACT_VERSION,
      "kind" to "verification_launch",
      "review_id" to packet.reviewId,
      "packet_digest" to packet.digest,
      "review_revision" to packet.reviewRevision.toEnvelope(),
      "finding" to finding.toEnvelope(),
      "cited_region" to
        linkedMapOf(
          "path" to citedRegion.path,
          "start_line" to citedRegion.startLine,
          "end_line" to citedRegion.endLine,
        ),
      "delta_reference" to
        linkedMapOf(
          "base_revision" to packet.baseRevision,
          "head_revision" to packet.headRevision,
        ),
      "evidence_surface_rules" to evidenceSurfaceRules,
      "dependency_allowlist" to dependencyAllowlist.normalized.sorted(),
      "forbidden_rediscovery" to ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY,
      "broker_id" to brokerId,
      "isolation" to isolation.name.lowercase(),
      "budget" to budget.toEnvelope(),
    ),
  )

internal fun GovernedReviewAdjudicationLaunch.toAdjudicationLaunchEnvelope(): ReviewContextEnvelope =
  ReviewContextEnvelope.from(
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to REVIEW_CONTEXT_CONTRACT_VERSION,
      "kind" to "adjudication_launch",
      "review_id" to packet.reviewId,
      "packet_digest" to packet.digest,
      "review_revision" to packet.reviewRevision.toEnvelope(),
      "finding" to finding.toEnvelope(),
      "stage_1_verdict" to stage1Verdict.toEnvelope(),
      "spec_intent_projection" to specIntentProjection.toProjectionPayload(),
      "cited_region" to
        linkedMapOf(
          "path" to citedRegion.path,
          "start_line" to citedRegion.startLine,
          "end_line" to citedRegion.endLine,
        ),
      "evidence_surface_rules" to evidenceSurfaceRules,
      "dependency_allowlist" to dependencyAllowlist.normalized.sorted(),
      "forbidden_rediscovery" to ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY,
      "broker_id" to brokerId,
      "isolation" to isolation.name.lowercase(),
      "budget" to budget.toEnvelope(),
    ),
  )

internal fun ReviewFindingVerdict.toEnvelope(): Map<String, Any?> =
  buildMap {
    put(SharedPayloadKeys.CONTRACT_VERSION, contractVersion)
    put("kind", "finding_verdict")
    put("stage", stage.wireValue)
    put("finding_ref", findingRef)
    put(ReviewFindingPayloadKeys.CLAIM_VERDICT, claimVerdict.wireValue)
    put("recorded_at", recordedAt)
    scopeDisposition?.let { put(ReviewFindingPayloadKeys.SCOPE_DISPOSITION, it.wireValue) }
    if (citations.isNotEmpty()) put(ReviewFindingPayloadKeys.CITATIONS, citations.map { it.toEnvelope() })
    severityAdjustment?.let { adjustment ->
      put(
        ReviewFindingPayloadKeys.SEVERITY_ADJUSTMENT,
        linkedMapOf(
          "direction" to adjustment.direction.wireValue,
          "justification" to adjustment.justification,
        ),
      )
    }
  }

private fun ReviewFindingCitation.toEnvelope(): Map<String, Any?> =
  linkedMapOf(
    "path" to path,
    "line" to line,
  )

private fun ParallelReviewMergedFinding.toEnvelope(): Map<String, Any?> =
  linkedMapOf(
    "finding_ref" to fNumber,
    "severity" to severity.displayName,
    "location" to location,
    "description" to description,
    "confidence" to confidence,
  )

fun GovernedReviewIntegrationLaunch.toIntegrationLaunchEnvelope(): ReviewContextEnvelope =
  ReviewContextEnvelope.from(
    linkedMapOf(
      SharedPayloadKeys.CONTRACT_VERSION to REVIEW_CONTEXT_CONTRACT_VERSION,
      "kind" to "integration_launch",
      "review_id" to packet.reviewId,
      "packet_digest" to packet.digest,
      "review_revision" to packet.reviewRevision.toEnvelope(),
      "commit_sequence_digest" to commitSequenceDigest,
      "base_revision" to packet.baseRevision,
      "head_revision" to packet.headRevision,
      "integration_contract" to integrationContract,
      "consumer_contract" to ReviewPacketConsumerContract.CONSUMER_CONTRACT,
      "commit_units" to packet.commitUnits.sortedBy { it.orderIndex }.map { it.toAssignedEnvelope() },
      "specialist_summaries" to specialistSummaries.sortedBy { it.lane }.map { it.toEnvelope() },
      "coverage_fact" to packet.coverageFact.toEnvelope(),
      "final_state_evidence_targets" to finalStateEvidenceTargets.map { it.toEnvelope() },
      "dependency_allowlist" to packet.dependencyAllowlist.normalized.sorted(),
      "forbidden_rediscovery" to ReviewPacketConsumerContract.FORBIDDEN_REDISCOVERY,
      "evidence_surface_rules" to ReviewPacketConsumerContract.EVIDENCE_SURFACE_RULES,
      "report_structure" to ReviewPacketConsumerContract.REPORT_STRUCTURE,
      "broker_id" to brokerId,
      "isolation" to isolation.name.lowercase(),
      "budget" to budget.toEnvelope(),
    ),
  )

private fun ReviewSpecialistSummary.toEnvelope(): Map<String, Any?> =
  linkedMapOf(
    "lane" to lane,
    "assignment_digest" to assignmentDigest,
    "lane_disposition" to disposition.wireValue,
    "assigned_paths" to assignedPaths.sorted(),
    "commit_shas" to commitShas,
    "finding_count" to findingCount,
    "unreviewed_segment_ids" to unreviewedSegmentIds,
    SharedPayloadKeys.SUMMARY to summary.normalizeLineEndings(),
  )
