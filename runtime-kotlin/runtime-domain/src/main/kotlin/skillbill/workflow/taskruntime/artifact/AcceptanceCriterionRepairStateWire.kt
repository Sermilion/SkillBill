package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairDependency
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairReservation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState

fun AcceptanceCriterionRepairState.asRepairWireMap(): Map<String, Any?> =
  mapOf(
    Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION,
    Keys.CATALOG_ID to catalogId,
    Keys.EXECUTION_ID to executionId,
    Keys.SPEC_DIGEST to specDigest,
    Keys.CRITERIA to criteria.map(::criterionWire),
    Keys.DEPENDENCIES to dependencies.map(::dependencyWire),
    Keys.UNRESOLVED_QUEUE to unresolvedQueue,
    Keys.CURRENT_CRITERION_REF to currentCriterionRef,
    Keys.RESERVATION to reservation?.let(::reservationWire),
    Keys.OUTCOMES to outcomes.map(::outcomeWire),
    Keys.UNSUCCESSFUL_TOTALS to unsuccessfulTotals,
    Keys.FINAL_AUDIT_REQUIRED to finalAuditRequired,
    Keys.FINAL_AUDIT_DIGEST to finalAuditDigest,
    Keys.REVISION to revision,
  )

private fun criterionWire(value: AcceptanceCriterionRepairCriterion): Map<String, Any?> =
  mapOf(
    Keys.CANONICAL_REF to value.canonicalRef,
    Keys.ORIGINAL_ALIAS to value.originalAlias,
    Keys.ORIGINAL_TEXT to value.originalText,
    Keys.ORDINAL to value.ordinal,
    Keys.SOURCE_DISPOSITION to value.sourceDisposition.wireValue,
    Keys.VALIDATION_OBLIGATION to value.validationObligation.wireValue,
    Keys.VALIDATION_OWNER_AVAILABLE to value.validationOwnerAvailable,
    Keys.EVIDENCE_REF to value.evidenceRef,
  )

private fun dependencyWire(value: AcceptanceCriterionRepairDependency): Map<String, Any?> =
  mapOf(
    Keys.DEPENDENT_REF to value.dependentRef,
    Keys.PREREQUISITE_REF to value.prerequisiteRef,
    Keys.REASON to value.reason,
    Keys.DECLARING_OUTPUT_REF to value.declaringOutputRef,
    Keys.SOURCE_EVIDENCE_REF to value.sourceEvidenceRef,
  )

private fun reservationWire(value: AcceptanceCriterionRepairReservation): Map<String, Any?> =
  mapOf(
    Keys.RESERVATION_ID to value.reservationId,
    Keys.CRITERION_REF to value.criterionRef,
    Keys.REVISION to value.revision,
  )

private fun outcomeWire(value: AcceptanceCriterionRepairOutcome): Map<String, Any?> =
  mapOf(
    Keys.OUTCOME_ID to value.outcomeId,
    Keys.RESERVATION_ID to value.reservationId,
    Keys.CRITERION_REF to value.criterionRef,
    Keys.DIGEST to value.digest,
    Keys.OUTCOME_KIND to value.kind.wireValue,
    Keys.EVIDENCE_REF to value.evidenceRef,
  )
