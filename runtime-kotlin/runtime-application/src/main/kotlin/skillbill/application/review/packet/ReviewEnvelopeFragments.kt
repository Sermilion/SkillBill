package skillbill.application.review.packet

import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.commit.ReviewLaneDecision
import skillbill.review.context.model.hunk.ReviewBuildTestFact
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.hunk.ReviewEvidenceTarget
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import skillbill.review.context.model.hunk.ReviewHunkEvidenceLocator
import skillbill.review.context.model.hunk.ReviewLearningsReference
import skillbill.review.context.model.hunk.ReviewRevision
import skillbill.review.context.model.hunk.ReviewRuleReference

internal fun ReviewRevision.toEnvelope(): Map<String, Any?> =
  linkedMapOf(
    "session_id" to sessionId,
    "run_revision" to runRevision,
  )

internal fun ReviewHunkEvidenceLocator.toEnvelope(): Map<String, Any?> =
  linkedMapOf(
    "store_path" to storePath,
    "payload_file" to payloadFile,
    "hunk_header" to hunkHeader,
  )

internal fun ReviewChangedHunk.toEnvelope(): Map<String, Any?> =
  linkedMapOf(
    "hunk_id" to hunkId,
    "path" to path,
    "old_start" to oldStart,
    "old_count" to oldCount,
    "new_start" to newStart,
    "new_count" to newCount,
    "content_digest" to contentDigest,
    "evidence_locator" to evidenceLocator.toEnvelope(),
  )

internal fun ReviewLaneDecision.toEnvelope(): Map<String, Any?> =
  linkedMapOf(
    "lane" to lane,
    "included" to included,
    "reason" to reason,
    "signals" to signals.sorted(),
    "owned_paths" to normalizedOwnedPaths.sorted(),
    "order_index" to orderIndex,
    "required_lane" to required,
    "origin_layer_chains" to originLayerChains,
    "owning_pack" to owningPack,
    "specialist_skill_name" to specialistSkillName,
    "add_ons" to addOns,
  )

internal fun ReviewRuleReference.toEnvelope(): Map<String, Any?> =
  linkedMapOf(
    "rule_id" to ruleId,
    "source_path" to sourcePath,
    "excerpt" to excerpt.normalizeLineEndings(),
    "digest" to digest,
  )

internal fun ReviewLearningsReference.toEnvelope(): Map<String, Any?> =
  linkedMapOf(
    "learning_id" to learningId,
    "source" to source,
    "scope" to scope,
    "title" to title.normalizeLineEndings(),
    "rule_text" to ruleText.normalizeLineEndings(),
    "digest" to digest,
  )

internal fun ReviewBuildTestFact.toEnvelope(): Map<String, Any?> =
  linkedMapOf(
    "kind" to kind,
    "command" to command,
    "outcome" to outcome,
  )

internal fun ReviewEvidenceTarget.toEnvelope(): Map<String, Any?> =
  linkedMapOf(
    "target_id" to targetId,
    "path" to path,
    "hunk_ids" to hunkIds.sorted(),
  )

internal fun ReviewExpansionRecord.toEnvelope(): Map<String, Any?> =
  linkedMapOf(
    "expansion_id" to expansionId,
    "assignment_digest" to assignmentDigest,
    "requested_path" to requestedPath,
    "reachability_reason" to reachabilityReason,
    "authorized" to authorized,
    "sequence" to sequence,
  )

internal fun ReviewContextBudgetPolicy.toEnvelope(): Map<String, Any?> =
  linkedMapOf(
    "max_parent_packet_bytes" to maxParentPacketBytes,
    "max_lane_launch_bytes" to maxLaneLaunchBytes,
    "max_lane_evidence_bytes" to maxLaneEvidenceBytes,
    "max_evidence_result_bytes" to maxEvidenceResultBytes,
    "max_lane_result_bytes" to maxLaneResultBytes,
    "max_assignment_expansions" to maxAssignmentExpansions,
    "max_specialist_tool_calls" to maxSpecialistToolCalls,
    "max_specialist_model_turns" to maxSpecialistModelTurns,
    "max_routing_analysis_pairs" to maxRoutingAnalysisPairs,
    "max_routing_analysis_bytes" to maxRoutingAnalysisBytes,
  )
