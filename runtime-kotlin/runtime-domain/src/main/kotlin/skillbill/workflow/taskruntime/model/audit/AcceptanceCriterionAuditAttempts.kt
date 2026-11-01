package skillbill.workflow.taskruntime.model.audit

fun AcceptanceCriterionRepairState.auditAttempts(): List<AcceptanceCriterionAuditAttempt> {
  data class SettledStep(
    val id: String,
    val revision: Long,
    val criterionRef: String?,
    val success: Boolean,
    val prose: String?,
  )

  val steps =
    (
      proseAudits.map { SettledStep(it.id, it.revision, it.criterionRef, it.satisfied, it.prose) } +
        outcomes.mapNotNull { outcome ->
          if (
            outcome.kind !in
            setOf(
              AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS,
              AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE,
            )
          ) {
            null
          } else {
            outcome.verifiedRevision?.let {
              SettledStep(outcome.outcomeId, it, outcome.criterionRef, outcome.successful, null)
            }
          }
        }
    ).sortedBy { it.revision }
  val open =
    criteria.filter { it.sourceDisposition != AcceptanceCriterionSourceDisposition.NOT_APPLICABLE }
      .mapTo(linkedSetOf()) { it.canonicalRef }

  return steps.mapIndexed { index, step ->
    if (step.criterionRef == null) {
      if (step.success) {
        open.clear()
      } else {
        step.prose?.let(::openCriteriaNamedIn)?.intersect(open)?.takeIf { it.isNotEmpty() }?.let {
          open.retainAll(it)
        }
      }
    } else if (step.success) {
      open.remove(step.criterionRef)
    } else {
      open.add(step.criterionRef)
    }
    AcceptanceCriterionAuditAttempt(step.id, index + 1, open.isEmpty(), open.size)
  }
}

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
