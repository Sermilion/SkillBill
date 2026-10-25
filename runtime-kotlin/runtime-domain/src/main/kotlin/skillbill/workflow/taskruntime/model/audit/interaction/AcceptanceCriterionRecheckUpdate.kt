package skillbill.workflow.taskruntime.model.audit.interaction

import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionInteractionTrace
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition

data class AcceptanceCriterionRecheckUpdate(
  val recheckRefs: List<String>,
  val recheckAnyChangeRefs: List<String>,
  val newAnyChangeRefs: List<String>,
  val droppedRefs: List<String> = emptyList(),
)

fun AcceptanceCriterionRepairState.recheckUpdateAfter(
  changedPaths: List<String>,
  isTestPath: (String) -> Boolean,
): AcceptanceCriterionRecheckUpdate {
  val trace = interactionTrace
  val changed = changedPaths.map(AcceptanceCriterionEvidencePaths::normalize).toSet()
  val underRepair = reservation?.criterionRef ?: currentCriterionRef
  val candidates = if (changed.isEmpty()) emptyList() else criteria.filter { it.isRecheckCandidate(this, underRepair) }
  val cited = candidates.associate { it.canonicalRef to AcceptanceCriterionEvidencePaths.pathsOf(it, isTestPath) }
  val touched = cited.filterValues { paths -> paths.any { it in changed } }.keys
  val withoutPath = cited.filterValues { it.isEmpty() }.keys
  val limit = AcceptanceCriterionInteractionTrace.MAX_SCENARIO_REFS
  val recheck = (trace.recheckRefs + touched).distinct().sorted().take(limit)
  val anyChange = (trace.recheckAnyChangeRefs + withoutPath).distinct().filterNot { it in recheck }.sorted().take(limit)
  return AcceptanceCriterionRecheckUpdate(
    recheckRefs = recheck,
    recheckAnyChangeRefs = anyChange,
    newAnyChangeRefs = withoutPath.filter { it in anyChange && it !in trace.recheckAnyChangeRefs }.sorted(),
    droppedRefs = (touched + withoutPath).filter { it !in recheck && it !in anyChange }.distinct().sorted(),
  )
}

fun AcceptanceCriterionRepairState.withRecheckUpdate(
  update: AcceptanceCriterionRecheckUpdate,
): AcceptanceCriterionRepairState =
  copy(
    interactionTrace =
      interactionTrace.copy(
        recheckRefs = update.recheckRefs,
        recheckAnyChangeRefs = update.recheckAnyChangeRefs,
      ),
  )

private fun AcceptanceCriterionRepairCriterion.isRecheckCandidate(
  state: AcceptanceCriterionRepairState,
  underRepair: String?,
): Boolean =
  sourceDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED && closureEvidenceRef != null &&
    canonicalRef != underRepair && canonicalRef !in state.unresolvedQueue && canonicalRef !in state.pendingAuditRefs
