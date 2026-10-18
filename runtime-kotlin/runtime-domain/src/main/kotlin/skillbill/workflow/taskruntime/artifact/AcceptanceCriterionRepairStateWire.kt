package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidAcceptanceCriterionRepairStateSchema
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairDependency
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairReservation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairSourceTransition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.math.BigDecimal
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

fun AcceptanceCriterionRepairState.asRepairWireMap(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(
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
      Keys.BUDGET_START_OUTCOME_INDEX to budgetStartOutcomeIndex,
      Keys.REVISION to revision,
      Keys.MODE to mode.wireValue,
      Keys.STAGE to stage.wireValue,
      Keys.COMPLETED_REPAIR_EVIDENCE_REF to completedRepairEvidenceRef,
      Keys.ADMITTED_TREE_DIGEST to admittedTreeDigest,
      Keys.ADMITTED_CHECKPOINT_ID to admittedCheckpointId,
      Keys.SOURCE_TRANSITIONS to
        sourceTransitions.map { transition ->
          mapOf(
            Keys.CANONICAL_REF to transition.criterionRef,
            Keys.REVISION to transition.revision,
            Keys.FROM_DISPOSITION to transition.fromDisposition.wireValue,
            Keys.TO_DISPOSITION to transition.toDisposition.wireValue,
            Keys.PRODUCING_ID to transition.producingId,
            Keys.PRODUCING_DIGEST to transition.producingDigest,
            Keys.EVIDENCE_REF to transition.evidenceRef,
            Keys.TREE_DIGEST to transition.treeDigest,
            Keys.CHECKPOINT_ID to transition.checkpointId,
          )
        },
      Keys.PENDING_AUDIT_REFS to pendingAuditRefs,
      Keys.PROSE_AUDITS to proseAudits.map(::proseAuditWire),
      Keys.PENDING_CLARIFICATION_QUESTION to pendingClarificationQuestion,
    ),
  )

fun FeatureTaskRuntimeWorkflowArtifactMap.isPreContractAcceptanceCriterionRepairState(): Boolean =
  isObject && !containsKey(Keys.CONTRACT_VERSION)

fun FeatureTaskRuntimeWorkflowArtifactMap.toAcceptanceCriterionRepairState(
  sourceLabel: String,
): AcceptanceCriterionRepairState {
  validateDurableSize(sourceLabel)
  if (!isObject || this[Keys.CONTRACT_VERSION] != ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION) {
    throw invalidAcceptanceCriterionRepairStateSchema(
      sourceLabel,
      "repair state must be an object with the supported contract version.",
    )
  }
  val restored =
    AcceptanceCriterionRepairState(
      catalogId = string(Keys.CATALOG_ID),
      executionId = string(Keys.EXECUTION_ID),
      specDigest = string(Keys.SPEC_DIGEST),
      criteria = repairCriteria(),
      dependencies = repairDependencies(),
      unresolvedQueue = stringList(Keys.UNRESOLVED_QUEUE),
      currentCriterionRef = optionalString(Keys.CURRENT_CRITERION_REF),
      reservation = repairReservation(),
      outcomes = repairOutcomes(),
      unsuccessfulTotals = repairTotals(),
      budgetStartOutcomeIndex = repairBudgetStartOutcomeIndex(),
      revision = requiredLong(Keys.REVISION),
      mode = requiredEnum(Keys.MODE, AcceptanceCriterionRepairMode.entries) { it.wireValue },
      stage = requiredEnum(Keys.STAGE, AcceptanceCriterionRepairStage.entries) { it.wireValue },
      completedRepairEvidenceRef = optionalString(Keys.COMPLETED_REPAIR_EVIDENCE_REF),
      admittedTreeDigest = optionalString(Keys.ADMITTED_TREE_DIGEST),
      admittedCheckpointId = optionalString(Keys.ADMITTED_CHECKPOINT_ID),
      sourceTransitions = repairTransitions(),
      pendingAuditRefs = optionalStringList(Keys.PENDING_AUDIT_REFS),
      proseAudits = repairProseAudits(),
      pendingClarificationQuestion = optionalString(Keys.PENDING_CLARIFICATION_QUESTION),
    )
  return AcceptanceCriterionRepairState.restore(restored) ?: throw invalidAcceptanceCriterionRepairStateSchema(
    sourceLabel,
    "repair state is incoherent.",
  )
}

private fun FeatureTaskRuntimeWorkflowArtifactMap.repairBudgetStartOutcomeIndex(): Int =
  if (containsKey(Keys.BUDGET_START_OUTCOME_INDEX)) {
    requiredLong(Keys.BUDGET_START_OUTCOME_INDEX).takeIf { it in 0..Int.MAX_VALUE }?.toInt()
      ?: throw invalidAcceptanceCriterionRepairStateSchema("serial-repair", "Budget start outcome index is invalid.")
  } else {
    0
  }

private fun FeatureTaskRuntimeWorkflowArtifactMap.validateDurableSize(sourceLabel: String) {
  if (
    JsonCodec.valueToJsonString(this).toByteArray(Charsets.UTF_8).size >
    AcceptanceCriterionRepairState.MAX_DURABLE_UTF8_BYTES
  ) {
    throw invalidAcceptanceCriterionRepairStateSchema(sourceLabel, "Durable serial state exceeds its UTF-8 capacity.")
  }
}

private fun FeatureTaskRuntimeWorkflowArtifactMap.string(key: String): String =
  this[key] as? String ?: throw invalidAcceptanceCriterionRepairStateSchema("serial-repair", "$key is invalid.")

private fun FeatureTaskRuntimeWorkflowArtifactMap.requiredBoolean(key: String): Boolean =
  this[key] as? Boolean ?: throw invalidAcceptanceCriterionRepairStateSchema("serial-repair", "$key is invalid.")

private fun FeatureTaskRuntimeWorkflowArtifactMap.requiredLong(key: String): Long =
  (this[key] as? Number)?.toLongIfExact() ?: throw invalidAcceptanceCriterionRepairStateSchema(
    "serial-repair",
    "$key is invalid.",
  )

private fun Number.toLongIfExact(): Long? =
  when (this) {
    is Byte, is Short, is Int, is Long -> toLong()
    else ->
      try {
        BigDecimal(toString()).longValueExact()
      } catch (_: ArithmeticException) {
        null
      } catch (_: NumberFormatException) {
        null
      }
  }

private fun <E> FeatureTaskRuntimeWorkflowArtifactMap.requiredEnum(
  key: String,
  values: List<E>,
  wireValue: (E) -> String,
): E =
  values.firstOrNull { wireValue(it) == this[key] }
    ?: throw invalidAcceptanceCriterionRepairStateSchema("serial-repair", "$key is invalid.")

private fun FeatureTaskRuntimeWorkflowArtifactMap.objectList(key: String): List<Map<String, Any?>> =
  (this[key] as? List<*>)?.map { repairObject(it) }
    ?: throw invalidAcceptanceCriterionRepairStateSchema("serial-repair", "$key is invalid.")

private fun FeatureTaskRuntimeWorkflowArtifactMap.stringList(key: String): List<String> =
  (this[key] as? List<*>)?.map {
    it as? String ?: throw invalidAcceptanceCriterionRepairStateSchema(
      "serial-repair",
      "$key contains a non-string.",
    )
  }
    ?: throw invalidAcceptanceCriterionRepairStateSchema("serial-repair", "$key is invalid.")

private fun FeatureTaskRuntimeWorkflowArtifactMap.optionalStringList(key: String): List<String> =
  if (this[key] == null) emptyList() else stringList(key)

private fun Map<String, Any?>.requiredString(key: String): String =
  this[key] as? String ?: throw invalidAcceptanceCriterionRepairStateSchema("serial-repair", "$key is invalid.")

private fun Map<String, Any?>.requiredBoolean(key: String): Boolean =
  this[key] as? Boolean ?: throw invalidAcceptanceCriterionRepairStateSchema("serial-repair", "$key is invalid.")

private fun Map<String, Any?>.requiredInt(key: String): Int =
  (this[key] as? Number)?.toLongIfExact()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    ?: throw invalidAcceptanceCriterionRepairStateSchema("serial-repair", "$key is invalid.")

private fun Map<String, Any?>.requiredLong(key: String): Long =
  (this[key] as? Number)?.toLong() ?: throw invalidAcceptanceCriterionRepairStateSchema(
    "serial-repair",
    "$key is invalid.",
  )

private fun <E> Map<String, Any?>.requiredEnum(
  key: String,
  values: List<E>,
  wireValue: (E) -> String,
): E =
  values.firstOrNull { wireValue(it) == this[key] }
    ?: throw invalidAcceptanceCriterionRepairStateSchema("serial-repair", "$key is invalid.")

private fun Map<String, Any?>.stringList(key: String): List<String> =
  (this[key] as? List<*>)?.map {
    it as? String ?: throw invalidAcceptanceCriterionRepairStateSchema(
      "serial-repair",
      "$key contains a non-string.",
    )
  }
    ?: throw invalidAcceptanceCriterionRepairStateSchema("serial-repair", "$key is invalid.")

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
    Keys.CLOSURE_EVIDENCE_REF to value.closureEvidenceRef,
    Keys.CLOSURE_TREE_DIGEST to value.closureTreeDigest,
    Keys.CLOSURE_CHECKPOINT_ID to value.closureCheckpointId,
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
    Keys.STAGE_REVISION to value.stageRevision,
  )

private fun outcomeWire(value: AcceptanceCriterionRepairOutcome): Map<String, Any?> =
  mapOf(
    Keys.OUTCOME_ID to value.outcomeId,
    Keys.RESERVATION_ID to value.reservationId,
    Keys.CRITERION_REF to value.criterionRef,
    Keys.DIGEST to value.digest,
    Keys.OUTCOME_KIND to value.kind.wireValue,
    Keys.EVIDENCE_REF to value.evidenceRef,
    Keys.EXPECTED_REVISION to value.verifiedRevision,
    Keys.AUDIT_BINDING_DIGEST to value.auditBindingDigest,
    Keys.TREE_DIGEST to value.treeDigest,
    Keys.CHECKPOINT_ID to value.checkpointId,
  )

private fun repairObject(raw: Any?): Map<String, Any?> {
  val objectValue =
    raw as? Map<*, *>
      ?: throw invalidAcceptanceCriterionRepairStateSchema("serial-repair", "Expected an object.")
  return objectValue.entries.associate { (key, value) ->
    (
      key as? String ?: throw invalidAcceptanceCriterionRepairStateSchema(
        "serial-repair",
        "Expected a string key.",
      )
    ) to value
  }
}

private fun proseAuditWire(value: AcceptanceCriterionProseAudit): Map<String, Any?> =
  mapOf(
    Keys.ID to value.id,
    Keys.REVISION to value.revision,
    Keys.CRITERION_REF to value.criterionRef,
    Keys.SATISFIED to value.satisfied,
    Keys.DIGEST to value.digest,
    Keys.PROSE to value.prose,
    Keys.TREE_DIGEST to value.treeDigest,
    Keys.CHECKPOINT_ID to value.checkpointId,
  )

private fun FeatureTaskRuntimeWorkflowArtifactMap.repairCriteria(): List<AcceptanceCriterionRepairCriterion> =
  objectList(Keys.CRITERIA).map { criterion ->
    AcceptanceCriterionRepairCriterion(
      canonicalRef = criterion.requiredString(Keys.CANONICAL_REF),
      originalAlias = criterion.optionalString(Keys.ORIGINAL_ALIAS),
      originalText = criterion.requiredString(Keys.ORIGINAL_TEXT),
      ordinal = criterion.requiredInt(Keys.ORDINAL),
      sourceDisposition =
        criterion.requiredEnum(
          Keys.SOURCE_DISPOSITION,
          AcceptanceCriterionSourceDisposition.entries,
        ) { it.wireValue },
      validationObligation =
        criterion.requiredEnum(
          Keys.VALIDATION_OBLIGATION,
          AcceptanceCriterionValidationObligation.entries,
        ) { it.wireValue },
      validationOwnerAvailable = criterion.requiredBoolean(Keys.VALIDATION_OWNER_AVAILABLE),
      evidenceRef = criterion.requiredString(Keys.EVIDENCE_REF),
      closureEvidenceRef = criterion.optionalString(Keys.CLOSURE_EVIDENCE_REF),
      closureTreeDigest = criterion.optionalString(Keys.CLOSURE_TREE_DIGEST),
      closureCheckpointId = criterion.optionalString(Keys.CLOSURE_CHECKPOINT_ID),
    )
  }

private fun FeatureTaskRuntimeWorkflowArtifactMap.repairDependencies(): List<AcceptanceCriterionRepairDependency> =
  objectList(Keys.DEPENDENCIES).map { edge ->
    AcceptanceCriterionRepairDependency(
      dependentRef = edge.requiredString(Keys.DEPENDENT_REF),
      prerequisiteRef = edge.requiredString(Keys.PREREQUISITE_REF),
      reason = edge.requiredString(Keys.REASON),
      declaringOutputRef = edge.requiredString(Keys.DECLARING_OUTPUT_REF),
      sourceEvidenceRef = edge.requiredString(Keys.SOURCE_EVIDENCE_REF),
    )
  }

private fun FeatureTaskRuntimeWorkflowArtifactMap.repairReservation(): AcceptanceCriterionRepairReservation? =
  this[Keys.RESERVATION]?.let { raw ->
    val reservation = repairObject(raw)
    AcceptanceCriterionRepairReservation(
      reservationId = reservation.requiredString(Keys.RESERVATION_ID),
      criterionRef = reservation.requiredString(Keys.CRITERION_REF),
      revision = reservation.requiredLong(Keys.REVISION),
      stageRevision = reservation.requiredLong(Keys.STAGE_REVISION),
    )
  }

private fun FeatureTaskRuntimeWorkflowArtifactMap.repairOutcomes(): List<AcceptanceCriterionRepairOutcome> =
  objectList(Keys.OUTCOMES).map { outcome ->
    AcceptanceCriterionRepairOutcome(
      outcomeId = outcome.requiredString(Keys.OUTCOME_ID),
      reservationId = outcome.requiredString(Keys.RESERVATION_ID),
      criterionRef = outcome.requiredString(Keys.CRITERION_REF),
      digest = outcome.requiredString(Keys.DIGEST),
      kind =
        outcome.requiredEnum(
          Keys.OUTCOME_KIND,
          AcceptanceCriterionRepairOutcomeKind.entries,
        ) { it.wireValue },
      evidenceRef = outcome.requiredString(Keys.EVIDENCE_REF),
      verifiedRevision = (outcome[Keys.EXPECTED_REVISION] as? Number)?.toLongIfExact(),
      treeDigest = outcome.optionalString(Keys.TREE_DIGEST),
      checkpointId = outcome.optionalString(Keys.CHECKPOINT_ID),
      auditBindingDigest = outcome.optionalString(Keys.AUDIT_BINDING_DIGEST),
    )
  }

private fun FeatureTaskRuntimeWorkflowArtifactMap.repairTotals(): Map<String, Int> =
  repairObject(this[Keys.UNSUCCESSFUL_TOTALS]).entries.associate { (key, value) ->
    val ref =
      key
    val total =
      (value as? Number)?.toLongIfExact()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
        ?: throw invalidAcceptanceCriterionRepairStateSchema("serial-repair", "Invalid total.")
    ref to total
  }

private fun FeatureTaskRuntimeWorkflowArtifactMap.repairTransitions(): List<AcceptanceCriterionRepairSourceTransition> =
  objectList(Keys.SOURCE_TRANSITIONS).map { transition ->
    AcceptanceCriterionRepairSourceTransition(
      criterionRef = transition.requiredString(Keys.CANONICAL_REF),
      revision = transition.requiredLong(Keys.REVISION),
      fromDisposition =
        transition.requiredEnum(
          Keys.FROM_DISPOSITION,
          AcceptanceCriterionSourceDisposition.entries,
        ) { it.wireValue },
      toDisposition =
        transition.requiredEnum(
          Keys.TO_DISPOSITION,
          AcceptanceCriterionSourceDisposition.entries,
        ) { it.wireValue },
      producingId = transition.requiredString(Keys.PRODUCING_ID),
      producingDigest = transition.requiredString(Keys.PRODUCING_DIGEST),
      evidenceRef = transition.requiredString(Keys.EVIDENCE_REF),
      treeDigest = transition.optionalString(Keys.TREE_DIGEST),
      checkpointId = transition.optionalString(Keys.CHECKPOINT_ID),
    )
  }

private fun FeatureTaskRuntimeWorkflowArtifactMap.repairProseAudits(): List<AcceptanceCriterionProseAudit> =
  (this[Keys.PROSE_AUDITS] as? List<*>)?.map { raw ->
    val audit = repairObject(raw)
    AcceptanceCriterionProseAudit(
      id = audit.requiredString(Keys.ID),
      revision = audit.requiredLong(Keys.REVISION),
      criterionRef = audit.optionalString(Keys.CRITERION_REF),
      satisfied = audit.requiredBoolean(Keys.SATISFIED),
      digest = audit.requiredString(Keys.DIGEST),
      prose = audit.requiredString(Keys.PROSE),
      treeDigest = audit.requiredString(Keys.TREE_DIGEST),
      checkpointId = audit.requiredString(Keys.CHECKPOINT_ID),
    )
  } ?: emptyList()

private fun FeatureTaskRuntimeWorkflowArtifactMap.optionalString(key: String): String? =
  optionalRepairString(
    this[key],
    key,
  )

private fun Map<String, Any?>.optionalString(key: String): String? = optionalRepairString(this[key], key)

private fun optionalRepairString(
  value: Any?,
  key: String,
): String? =
  if (value == null || value is String) {
    value
  } else {
    throw invalidAcceptanceCriterionRepairStateSchema("serial-repair", "$key must be a string or null.")
  }
