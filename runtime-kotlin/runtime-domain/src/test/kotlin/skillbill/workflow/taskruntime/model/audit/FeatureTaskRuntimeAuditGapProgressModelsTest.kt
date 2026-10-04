package skillbill.workflow.taskruntime.model.audit

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimeAuditGapProgressModelsTest {
  @Test
  fun `audit-gap progress decodes a legacy artifact map`() {
    val decoded =
      FeatureTaskRuntimeAuditGapProgress.fromArtifactMap(
        legacyAuditGapProgressArtifact(
          criterionRefs = listOf("AC-002", "AC-001"),
          repositoryFingerprint = "fingerprint-1",
        ),
      )
    assertEquals(setOf("AC-001", "AC-002"), decoded.criterionRefs)
    assertEquals("fingerprint-1", decoded.repositoryFingerprint)
  }

  @Test
  fun `audit-gap progress decodes a null fingerprint`() {
    val decoded =
      FeatureTaskRuntimeAuditGapProgress.fromArtifactMap(
        legacyAuditGapProgressArtifact(criterionRefs = listOf("AC-002")),
      )
    assertEquals(setOf("AC-002"), decoded.criterionRefs)
    assertEquals(null, decoded.repositoryFingerprint)
  }

  @Test
  fun `audit-gap progress decode loud-fails on a malformed key set`() {
    val map = legacyAuditGapProgressArtifact(criterionRefs = listOf("AC-002")).toMutableMap()
    map["previous_criterion_refs"] = listOf(42)
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeAuditGapProgress.fromArtifactMap(map)
    }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
  }

  @Test
  fun `audit-gap progress decode loud-fails on an unknown key`() {
    val map = legacyAuditGapProgressArtifact(criterionRefs = listOf("AC-002")).toMutableMap()
    map["unexpected"] = "x"
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeAuditGapProgress.fromArtifactMap(map)
    }.also { assertEquals(WorkflowFailureCode.INVALID_WORKFLOW_STATE_SCHEMA, it.code) }
  }

  @Test
  fun `audit-gap pause decodes a legacy artifact map`() {
    val decoded =
      FeatureTaskRuntimeAuditGapPause.fromArtifactMap(
        legacyAuditGapPauseArtifact(
          pauseKind = FeatureTaskRuntimeAuditGapPauseKind.WARN_THRESHOLD.wireValue,
          reason = "crossed threshold",
          edgeIteration = 4,
          operatorDecision = FeatureTaskRuntimeAuditGapPause.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX,
          grantConsumed = false,
        ),
      )
    assertEquals(FeatureTaskRuntimeAuditGapPauseKind.WARN_THRESHOLD, decoded.pauseKind)
    assertEquals("crossed threshold", decoded.reason)
    assertEquals(4, decoded.edgeIteration)
    assertEquals(FeatureTaskRuntimeAuditGapPause.AUDIT_GAP_PAUSE_DECISION_RETRY_FIX, decoded.operatorDecision)
    assertEquals(false, decoded.grantConsumed)
  }

  @Test
  fun `audit-gap pause rejects accept-and-advance and bad kinds`() {
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimeAuditGapPause(
        pauseKind = "accept_and_advance",
        reason = "x",
        edgeIteration = 1,
      )
    }
  }

  private fun legacyAuditGapProgressArtifact(
    criterionRefs: List<String>,
    repositoryFingerprint: String? = null,
  ): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
      "record_kind" to "audit_gap_progress",
      "previous_criterion_refs" to criterionRefs,
    ).apply {
      repositoryFingerprint?.let { put("previous_repository_fingerprint", it) }
    }

  private fun legacyAuditGapPauseArtifact(
    pauseKind: String,
    reason: String,
    edgeIteration: Int,
    operatorDecision: String? = null,
    grantConsumed: Boolean = false,
  ): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
      "record_kind" to "audit_gap_pause",
      "pause_kind" to pauseKind,
      "reason" to reason,
      "edge_iteration" to edgeIteration,
      "grant_consumed" to grantConsumed,
    ).apply {
      operatorDecision?.let { put("operator_decision", it) }
    }
}
