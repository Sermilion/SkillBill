package skillbill.engine.featuretask.model.audit

import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionGapStatus
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceRepairDecision
import skillbill.workflow.taskruntime.model.audit.AcceptanceRepairStep
import skillbill.workflow.taskruntime.model.audit.acceptanceCriterionIdentity
import java.security.MessageDigest

internal object AcceptanceGapAuditScope {
  fun discoveryCriteria(scope: AcceptanceCriterionRepairRunState): List<Pair<String, String>>? {
    val state = scope.snapshot
    if (state != null) {
      if (AcceptanceRepairDecision.auditKind(state) != AcceptanceRepairStep.Discover) return null
      return AcceptanceRepairDecision.discoveryCriteria(state).map { it.canonicalRef to it.originalText }
    }
    if (!scope.freshDiscoveryRequired()) return null
    return scope.binding?.authoredCriteria.orEmpty().mapIndexedNotNull { index, text ->
      acceptanceCriterionIdentity(text, index + 1)?.let { it.canonicalRef to text }
    }
  }

  fun key(
    scope: AcceptanceCriterionRepairRunState,
    state: AcceptanceCriterionRepairState? = scope.snapshot,
  ): String? {
    if (state == null) return freshDiscoveryKey(scope)
    val reportScope =
      when {
        state.stage == AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION ->
          "verify:${state.reservation?.reservationId}:${state.currentCriterionRef}:" +
            state.criteria.singleOrNull { it.canonicalRef == state.currentCriterionRef }
              ?.gaps?.filter { it.status == AcceptanceCriterionGapStatus.OPEN }
              ?.joinToString(",") { it.id }.orEmpty()
        AcceptanceRepairDecision.auditKind(state) == AcceptanceRepairStep.Discover ->
          "discovery:" + AcceptanceRepairDecision.discoveryCriteria(state).joinToString(",") { it.canonicalRef }
        else -> return null
      }
    return digest(
      "${state.catalogId}|${state.executionId}|${state.specDigest}|$reportScope|" +
        "${state.outcomes.size}|${state.proseAudits.size}",
    )
  }

  private fun freshDiscoveryKey(scope: AcceptanceCriterionRepairRunState): String? {
    if (!scope.freshDiscoveryRequired()) return null
    val binding = scope.binding ?: return null
    val refs = discoveryCriteria(scope).orEmpty().map { it.first }
    return digest(
      "${binding.catalogId}|${binding.executionId}|${binding.specDigest}|discovery:${refs.joinToString(",")}|0|0",
    )
  }

  private const val DIGEST_BYTES = 8
  private const val BYTE_MASK = 0xff

  private fun digest(identity: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).take(DIGEST_BYTES)
    return "gap:${digest.joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }}"
  }
}
