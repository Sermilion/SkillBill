package skillbill.workflow.taskruntime.model.audit

fun AcceptanceCriterionRepairState.qaReauditRefs(scope: Collection<String>): List<String>? {
  if (stage != AcceptanceCriterionRepairStage.ACCEPTED || reservation != null) return null
  if (scope.any { ref -> criteria.none { it.canonicalRef == ref } }) return null
  return criteria.sortedBy(AcceptanceCriterionRepairCriterion::ordinal)
    .filter { it.canonicalRef in scope && it.sourceDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED }
    .map(AcceptanceCriterionRepairCriterion::canonicalRef)
}

fun AcceptanceCriterionRepairState.admitQaReaudit(refs: List<String>): AcceptanceCriterionRepairState? {
  if (refs.isEmpty() || qaReauditRefs(refs) != refs) return null
  return copy(
    mode = AcceptanceCriterionRepairMode.CURRENT_CRITERION_TARGETED,
    stage = AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED,
    pendingAuditRefs = refs,
    currentCriterionRef = refs.first(),
    revision = revision + 1,
  )
}
