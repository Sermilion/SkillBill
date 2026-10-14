package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.JsonCodec
import skillbill.error.shellcontent.invalidAcceptanceCriterionRepairAccounting
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairDependency
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairFinalAudit
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
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

fun AcceptanceCriterionRepairState.asRepairWireMap(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(
    mapOf(
      Keys.PENDING_AUDIT_REFS to pendingAuditRefs,
      Keys.PENDING_CLARIFICATION_QUESTION to pendingClarificationQuestion,
      Keys.PROSE_AUDITS to
        proseAudits.map { audit ->
          mapOf(
            Keys.PRODUCING_ID to audit.id,
            Keys.REVISION to audit.revision,
            Keys.CRITERION_REF to audit.criterionRef,
            Keys.SATISFIED to audit.satisfied,
            Keys.DIGEST to audit.digest,
            Keys.PROSE to audit.prose,
            Keys.TREE_DIGEST to audit.treeDigest,
            Keys.CHECKPOINT_ID to audit.checkpointId,
          )
        },
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
      Keys.FINAL_AUDITS to
        finalAudits.map { audit ->
          mapOf(
            Keys.REVISION to audit.revision,
            Keys.DIGEST to audit.digest,
            Keys.CATALOG_ID to audit.catalogId,
            Keys.EXECUTION_ID to audit.executionId,
            Keys.SPEC_DIGEST to audit.specDigest,
            Keys.TREE_DIGEST to audit.treeDigest,
            Keys.CHECKPOINT_ID to audit.checkpointId,
            Keys.AUDITED_REFS to audit.auditedRefs,
            Keys.REGRESSED_REFS to audit.regressedRefs,
            Keys.DEPENDENCIES to audit.dependencies.map(::dependencyWire),
            Keys.AUDIT_BINDING_DIGEST to audit.auditBindingDigest,
          )
        },
    ),
  )

fun FeatureTaskRuntimeWorkflowArtifactMap.toAcceptanceCriterionRepairState(
  sourceLabel: String,
): AcceptanceCriterionRepairState {
  validateRepairWireSize(sourceLabel)
  return AcceptanceCriterionRepairState.restore(
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
      finalAuditRequired = requiredBoolean(Keys.FINAL_AUDIT_REQUIRED),
      finalAuditDigest = optionalString(Keys.FINAL_AUDIT_DIGEST),
      revision = requiredLong(Keys.REVISION),
      mode = requiredEnum(Keys.MODE, AcceptanceCriterionRepairMode.entries) { it.wireValue },
      stage = requiredEnum(Keys.STAGE, AcceptanceCriterionRepairStage.entries) { it.wireValue },
      completedRepairEvidenceRef = optionalString(Keys.COMPLETED_REPAIR_EVIDENCE_REF),
      admittedTreeDigest = optionalString(Keys.ADMITTED_TREE_DIGEST),
      admittedCheckpointId = optionalString(Keys.ADMITTED_CHECKPOINT_ID),
      sourceTransitions = repairTransitions(),
      finalAudits = repairFinalAudits(),
      pendingAuditRefs = if (containsKey(Keys.PENDING_AUDIT_REFS)) stringList(Keys.PENDING_AUDIT_REFS) else emptyList(),
      proseAudits =
        if (containsKey(Keys.PROSE_AUDITS)) {
          objectList(Keys.PROSE_AUDITS).map { audit ->
            AcceptanceCriterionProseAudit(
              audit.requiredString(Keys.PRODUCING_ID),
              audit.requiredLong(Keys.REVISION),
              audit.optionalString(Keys.CRITERION_REF),
              audit.requiredBoolean(Keys.SATISFIED),
              audit.requiredString(Keys.DIGEST),
              audit.requiredString(Keys.PROSE),
              audit.requiredString(Keys.TREE_DIGEST),
              audit.requiredString(Keys.CHECKPOINT_ID),
            )
          }
        } else {
          emptyList()
        },
      pendingClarificationQuestion = optionalString(Keys.PENDING_CLARIFICATION_QUESTION),
    ),
  ) ?: throw invalidAcceptanceCriterionRepairAccounting(sourceLabel, "repair state is incoherent.")
}

private fun FeatureTaskRuntimeWorkflowArtifactMap.validateRepairWireSize(sourceLabel: String) {
  if (!isObject) {
    throw invalidAcceptanceCriterionRepairAccounting(
      sourceLabel,
      "repair accounting must be an object.",
    )
  }
  val byteSize = JsonCodec.valueToJsonString(this).encodeToByteArray().size
  if (byteSize > AcceptanceCriterionRepairState.MAX_DURABLE_UTF8_BYTES) {
    throw invalidAcceptanceCriterionRepairAccounting(sourceLabel, "repair accounting exceeds its durable byte limit.")
  }
}

private fun FeatureTaskRuntimeWorkflowArtifactMap.string(key: String): String =
  this[key] as? String ?: throw invalidAcceptanceCriterionRepairAccounting("serial-repair", "$key is invalid.")

private fun FeatureTaskRuntimeWorkflowArtifactMap.requiredBoolean(key: String): Boolean =
  this[key] as? Boolean ?: throw invalidAcceptanceCriterionRepairAccounting("serial-repair", "$key is invalid.")

private fun FeatureTaskRuntimeWorkflowArtifactMap.requiredLong(key: String): Long = repairLong(this[key], key)

private fun <E> FeatureTaskRuntimeWorkflowArtifactMap.requiredEnum(
  key: String,
  values: List<E>,
  wireValue: (E) -> String,
): E =
  values.firstOrNull { wireValue(it) == this[key] }
    ?: throw invalidAcceptanceCriterionRepairAccounting("serial-repair", "$key is invalid.")

private fun FeatureTaskRuntimeWorkflowArtifactMap.objectList(key: String): List<Map<String, Any?>> =
  repairObjectList(this[key], key)

private fun FeatureTaskRuntimeWorkflowArtifactMap.stringList(key: String): List<String> =
  (this[key] as? List<*>)?.map {
    it as? String ?: throw invalidAcceptanceCriterionRepairAccounting(
      "serial-repair",
      "$key contains a non-string.",
    )
  }
    ?: throw invalidAcceptanceCriterionRepairAccounting("serial-repair", "$key is invalid.")

private fun Map<String, Any?>.requiredString(key: String): String =
  this[key] as? String ?: throw invalidAcceptanceCriterionRepairAccounting("serial-repair", "$key is invalid.")

private fun Map<String, Any?>.requiredBoolean(key: String): Boolean =
  this[key] as? Boolean ?: throw invalidAcceptanceCriterionRepairAccounting("serial-repair", "$key is invalid.")

private fun Map<String, Any?>.requiredInt(key: String): Int = repairInt(this[key], key)

private fun Map<String, Any?>.requiredLong(key: String): Long = repairLong(this[key], key)

private fun repairLong(
  value: Any?,
  key: String,
): Long =
  (value as? Number)?.toString()?.toLongOrNull()
    ?: throw invalidAcceptanceCriterionRepairAccounting("serial-repair", "$key must be an integer within Long range.")

private fun repairInt(
  value: Any?,
  key: String,
): Int {
  val integer = repairLong(value, key)
  if (integer !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
    throw invalidAcceptanceCriterionRepairAccounting("serial-repair", "$key must be an integer within Int range.")
  }
  return integer.toInt()
}

private fun repairObjectList(
  value: Any?,
  key: String,
): List<Map<String, Any?>> =
  (value as? List<*>)?.map(::repairObject)
    ?: throw invalidAcceptanceCriterionRepairAccounting("serial-repair", "$key must be an object list.")

private fun <E> Map<String, Any?>.requiredEnum(
  key: String,
  values: List<E>,
  wireValue: (E) -> String,
): E =
  values.firstOrNull { wireValue(it) == this[key] }
    ?: throw invalidAcceptanceCriterionRepairAccounting("serial-repair", "$key is invalid.")

private fun Map<String, Any?>.stringList(key: String): List<String> =
  (this[key] as? List<*>)?.map {
    it as? String ?: throw invalidAcceptanceCriterionRepairAccounting(
      "serial-repair",
      "$key contains a non-string.",
    )
  }
    ?: throw invalidAcceptanceCriterionRepairAccounting("serial-repair", "$key is invalid.")

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
      ?: throw invalidAcceptanceCriterionRepairAccounting("serial-repair", "Expected an object.")
  return objectValue.entries.associate { (key, value) ->
    (
      key as? String ?: throw invalidAcceptanceCriterionRepairAccounting(
        "serial-repair",
        "Expected a string key.",
      )
    ) to value
  }
}

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
      verifiedRevision = outcome[Keys.EXPECTED_REVISION]?.let { repairLong(it, Keys.EXPECTED_REVISION) },
      treeDigest = outcome.optionalString(Keys.TREE_DIGEST),
      checkpointId = outcome.optionalString(Keys.CHECKPOINT_ID),
      auditBindingDigest = outcome.optionalString(Keys.AUDIT_BINDING_DIGEST),
    )
  }

private fun FeatureTaskRuntimeWorkflowArtifactMap.repairTotals(): Map<String, Int> =
  repairObject(this[Keys.UNSUCCESSFUL_TOTALS]).entries.associate { (key, value) ->
    key to repairInt(value, Keys.UNSUCCESSFUL_TOTALS)
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

private fun FeatureTaskRuntimeWorkflowArtifactMap.repairFinalAudits(): List<AcceptanceCriterionRepairFinalAudit> =
  objectList(Keys.FINAL_AUDITS).map { audit ->
    AcceptanceCriterionRepairFinalAudit(
      revision = audit.requiredLong(Keys.REVISION),
      digest = audit.requiredString(Keys.DIGEST),
      catalogId = audit.requiredString(Keys.CATALOG_ID),
      executionId = audit.requiredString(Keys.EXECUTION_ID),
      specDigest = audit.requiredString(Keys.SPEC_DIGEST),
      treeDigest = audit.requiredString(Keys.TREE_DIGEST),
      checkpointId = audit.requiredString(Keys.CHECKPOINT_ID),
      auditedRefs = audit.stringList(Keys.AUDITED_REFS),
      regressedRefs = audit.stringList(Keys.REGRESSED_REFS),
      auditBindingDigest = audit.optionalString(Keys.AUDIT_BINDING_DIGEST),
      dependencies =
        repairObjectList(audit[Keys.DEPENDENCIES], Keys.DEPENDENCIES).map { edge ->
          AcceptanceCriterionRepairDependency(
            edge.requiredString(Keys.DEPENDENT_REF),
            edge.requiredString(Keys.PREREQUISITE_REF),
            edge.requiredString(Keys.REASON),
            edge.requiredString(Keys.DECLARING_OUTPUT_REF),
            edge
              .requiredString(Keys.SOURCE_EVIDENCE_REF),
          )
        },
    )
  }

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
    throw invalidAcceptanceCriterionRepairAccounting("serial-repair", "$key must be a string or null.")
  }
