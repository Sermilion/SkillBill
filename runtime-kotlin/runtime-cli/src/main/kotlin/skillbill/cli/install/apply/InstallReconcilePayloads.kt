package skillbill.cli.install.apply

import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.contracts.SharedPayloadKeys
import skillbill.install.model.ReconciliationPlan
import skillbill.install.model.SkillReconciliationOutcome

internal fun reconcilePayload(
  plan: ReconciliationPlan,
  refreshed: Boolean,
  applied: Boolean = false,
  installedPaths: List<String> = emptyList(),
  prunedPaths: List<String> = emptyList(),
): Map<String, Any?> =
  mapOf(
    SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
    "applied" to applied,
    "baseline_refreshed" to refreshed,
    "installed_paths" to installedPaths,
    "pruned_paths" to prunedPaths,
    "outcomes" to plan.outcomes.map(::reconcileOutcomeWireMap),
  )

internal fun reconcileMachineReport(
  plan: ReconciliationPlan,
  refreshed: Boolean,
  applied: Boolean,
  installedPaths: List<String>,
  prunedPaths: List<String>,
): String =
  buildString {
    plan.outcomes.forEach { outcome ->
      append("reconcile_outcome: kind=")
      append(reconcileOutcomeKind(outcome))
      reconcileOutcomeUpstreamHash(outcome)?.let { hash ->
        append(" upstream_hash=")
        append(hash)
      }
      append(" path=")
      append(outcome.skillRelativePath)
      append('\n')
    }
    append("reconcile_summary: applied=")
    append(applied)
    append(" baseline_refreshed=")
    append(refreshed)
    append(" installed_count=")
    append(installedPaths.size)
    append(" pruned_count=")
    append(prunedPaths.size)
    append('\n')
  }

private fun reconcileOutcomeKind(outcome: SkillReconciliationOutcome): String =
  when (outcome) {
    is SkillReconciliationOutcome.Adopt -> "adopt"
    is SkillReconciliationOutcome.Unchanged -> "unchanged"
    is SkillReconciliationOutcome.Prune -> "prune"
    is SkillReconciliationOutcome.LocallyAuthored -> "locally-authored"
  }

private fun reconcileOutcomeUpstreamHash(outcome: SkillReconciliationOutcome): String? =
  when (outcome) {
    is SkillReconciliationOutcome.Adopt -> outcome.upstreamHash
    is SkillReconciliationOutcome.Unchanged -> outcome.upstreamHash
    is SkillReconciliationOutcome.Prune -> null
    is SkillReconciliationOutcome.LocallyAuthored -> null
  }

private fun reconcileOutcomeWireMap(outcome: SkillReconciliationOutcome): Map<String, Any?> =
  when (outcome) {
    is SkillReconciliationOutcome.Adopt ->
      mapOf(
        "path" to outcome.skillRelativePath,
        "outcome" to "adopt",
        "upstream_hash" to outcome.upstreamHash,
        "local_hash" to outcome.localHash,
        "baseline_hash" to outcome.baselineHash,
      )
    is SkillReconciliationOutcome.Unchanged ->
      mapOf(
        "path" to outcome.skillRelativePath,
        "outcome" to "unchanged",
        "upstream_hash" to outcome.upstreamHash,
        "local_hash" to outcome.upstreamHash,
        "baseline_hash" to outcome.baselineHash,
      )
    is SkillReconciliationOutcome.Prune ->
      mapOf(
        "path" to outcome.skillRelativePath,
        "outcome" to "prune",
        "local_hash" to outcome.localHash,
        "baseline_hash" to outcome.baselineHash,
      )
    is SkillReconciliationOutcome.LocallyAuthored ->
      mapOf(
        "path" to outcome.skillRelativePath,
        "outcome" to "locally-authored",
        "local_hash" to outcome.localHash,
        "baseline_hash" to outcome.baselineHash,
      )
  }
