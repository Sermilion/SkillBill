package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.error.shellcontent.invalidAcceptanceCriterionAuditResultSchema
import skillbill.error.shellcontent.invalidAcceptanceCriterionRepairStateSchema
import skillbill.workflow.taskruntime.artifact.accounting.AcceptanceCriterionRepairAccountingWire
import skillbill.workflow.taskruntime.artifact.accounting.evidenceRows
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionAccountingPolicy
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionCheckedMutation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionCompletedMutation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionEstablishedObligation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionIndependentProgress
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionInteractionTrace
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionMutationDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionPreservedObligation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProgressClassification
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProgressContext
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
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionResolvedObligation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.audit.repairAccounting
import skillbill.workflow.taskruntime.model.audit.validateAcceptanceCriterionEvidence
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.math.BigDecimal
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionProgressPayloadKeys as ProgressKeys
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

fun AcceptanceCriterionRepairState.asRepairWireMap(): FeatureTaskRuntimeWorkflowArtifactMap {
  require(accountingPolicy == AcceptanceCriterionAccountingPolicy.VERIFIED_EVIDENCE)
  return repairWireFields()
}

internal fun AcceptanceCriterionRepairState.repairWireFields(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(
    mapOf(
      Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION,
      ProgressKeys.REPAIR_BUDGETS to repairBudgets.map { AcceptanceCriterionRepairAccountingWire.budget(it) },
      ProgressKeys.REPAIR_ACCOUNTING to
        criteria.map {
          AcceptanceCriterionRepairAccountingWire.accounting(repairAccounting(it.canonicalRef))
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
      ProgressKeys.COMPLETED_MUTATIONS to completedMutations.map { AcceptanceCriterionEvidenceWire.mutation(it) },
      ProgressKeys.INDEPENDENT_CHECKS to independentChecks.map { AcceptanceCriterionEvidenceWire.checked(it) },
      Keys.PENDING_CLARIFICATION_QUESTION to pendingClarificationQuestion,
      Keys.INTERACTION_TRACE to interactionTraceWire(interactionTrace),
    ),
  ).also { it.validateDurableSize("serial-repair") }

fun FeatureTaskRuntimeWorkflowArtifactMap.isPreContractAcceptanceCriterionRepairState(): Boolean =
  isObject && !containsKey(Keys.CONTRACT_VERSION)

fun FeatureTaskRuntimeWorkflowArtifactMap.toAcceptanceCriterionRepairState(
  sourceLabel: String,
): AcceptanceCriterionRepairState =
  try {
    decodeCurrentRepairState(sourceLabel)
  } catch (cause: SkillBillRuntimeException) {
    if (cause.code != FeatureTaskRuntimeFailureCode.INVALID_ACCEPTANCE_CRITERION_AUDIT_RESULT_SCHEMA) throw cause
    throw invalidAcceptanceCriterionRepairStateSchema(sourceLabel, cause.message.orEmpty(), cause)
  }

private fun FeatureTaskRuntimeWorkflowArtifactMap.decodeCurrentRepairState(
  sourceLabel: String,
): AcceptanceCriterionRepairState {
  validateDurableSize(sourceLabel)
  if (!isObject || this[Keys.CONTRACT_VERSION] != ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION) {
    throw invalidAcceptanceCriterionRepairStateSchema(
      sourceLabel,
      "repair state must be an object with the supported contract version.",
    )
  }
  requireRepairEvidenceFields(sourceLabel)
  val restored = decodeRepairStateFields()
  requireKnownRepairFields(restored.asRepairWireMap(), sourceLabel)
  validateAcceptanceCriterionEvidence(restored.completedMutations, restored.independentChecks)
  restored.validateEvidenceOwner(sourceLabel)
  AcceptanceCriterionRepairAccountingWire.validateAccounting(
    this,
    restored.criteria.map { restored.repairAccounting(it.canonicalRef) },
  )
  return AcceptanceCriterionRepairState.restore(restored) ?: throw invalidAcceptanceCriterionRepairStateSchema(
    sourceLabel,
    "repair state is incoherent.",
  )
}

internal fun FeatureTaskRuntimeWorkflowArtifactMap.decodeRepairStateFields(
  historical: Boolean = false,
): AcceptanceCriterionRepairState =
  AcceptanceCriterionRepairState(
    catalogId = string(Keys.CATALOG_ID),
    executionId = string(Keys.EXECUTION_ID),
    specDigest = string(Keys.SPEC_DIGEST),
    criteria = repairCriteria(),
    dependencies = repairDependencies(),
    unresolvedQueue = stringList(Keys.UNRESOLVED_QUEUE),
    currentCriterionRef = optionalString(Keys.CURRENT_CRITERION_REF),
    reservation = repairReservation(historical),
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
    interactionTrace =
      if (historical &&
        !containsKey(
          Keys.INTERACTION_TRACE,
        )
      ) {
        AcceptanceCriterionInteractionTrace()
      } else {
        repairInteractionTrace()
      },
    repairBudgets = if (historical) emptyList() else AcceptanceCriterionRepairAccountingWire.budgets(this),
    completedMutations =
      if (historical) {
        emptyList()
      } else {
        evidenceRows(ProgressKeys.COMPLETED_MUTATIONS).map {
          AcceptanceCriterionEvidenceWire.mutation(it)
        }
      },
    independentChecks =
      if (historical) {
        emptyList()
      } else {
        evidenceRows(
          ProgressKeys.INDEPENDENT_CHECKS,
        ).map { AcceptanceCriterionEvidenceWire.checked(it) }
      },
  )

internal fun FeatureTaskRuntimeWorkflowArtifactMap.requireKnownRepairFields(
  expected: FeatureTaskRuntimeWorkflowArtifactMap,
  sourceLabel: String,
) {
  if ((keys - expected.keys).isNotEmpty()) {
    throw invalidAcceptanceCriterionRepairStateSchema(sourceLabel, "Unexpected repair-state fields.")
  }
  expected.forEach { (key, value) ->
    val actual = this[key]
    when {
      value is Map<*, *> && actual is Map<*, *> ->
        FeatureTaskRuntimeWorkflowArtifactMap.from(actual).requireKnownRepairFields(
          FeatureTaskRuntimeWorkflowArtifactMap.from(value),
          "$sourceLabel.$key",
        )
      value is List<*> && actual is List<*> ->
        value.forEachIndexed { index, row ->
          if (row is Map<*, *>) {
            FeatureTaskRuntimeWorkflowArtifactMap.from(actual.getOrNull(index)).requireKnownRepairFields(
              FeatureTaskRuntimeWorkflowArtifactMap.from(row),
              "$sourceLabel.$key[$index]",
            )
          }
        }
    }
  }
}

private fun FeatureTaskRuntimeWorkflowArtifactMap.requireRepairEvidenceFields(sourceLabel: String) {
  val fields =
    setOf(
      ProgressKeys.COMPLETED_MUTATIONS,
      ProgressKeys.INDEPENDENT_CHECKS,
      ProgressKeys.REPAIR_BUDGETS,
      ProgressKeys.REPAIR_ACCOUNTING,
    )
  if (!keys.containsAll(fields)) {
    throw invalidAcceptanceCriterionRepairStateSchema(sourceLabel, "Current repair evidence fields are required.")
  }
}

private fun AcceptanceCriterionRepairState.validateEvidenceOwner(sourceLabel: String) {
  val ownerMatches =
    completedMutations.all {
      val identity = it.catalogId == catalogId && it.executionId == executionId
      identity && it.specDigest == specDigest
    }
  val criterionRefs = criteria.map { it.canonicalRef }.toSet()
  if (!ownerMatches || completedMutations.any { it.criterionRef !in criterionRefs }) {
    throw invalidAcceptanceCriterionRepairStateSchema(
      sourceLabel,
      "Retained mutation does not belong to this criterion catalog",
    )
  }
}

private fun FeatureTaskRuntimeWorkflowArtifactMap.repairBudgetStartOutcomeIndex(): Int =
  if (containsKey(Keys.BUDGET_START_OUTCOME_INDEX)) {
    requiredLong(Keys.BUDGET_START_OUTCOME_INDEX).takeIf { it in 0..Int.MAX_VALUE }?.toInt()
      ?: throw invalidAcceptanceCriterionRepairStateSchema("serial-repair", "Budget start outcome index is invalid.")
  } else {
    0
  }

internal fun FeatureTaskRuntimeWorkflowArtifactMap.validateDurableSize(sourceLabel: String) {
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
  (this[key] as? Number)?.toLongIfExact() ?: throw invalidAcceptanceCriterionRepairStateSchema(
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
    Keys.REASONING_REQUIRED to value.reasoningRequired,
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

private fun FeatureTaskRuntimeWorkflowArtifactMap.repairReservation(
  historical: Boolean,
): AcceptanceCriterionRepairReservation? =
  this[Keys.RESERVATION]?.let { raw ->
    val reservation = repairObject(raw)
    AcceptanceCriterionRepairReservation(
      reservationId = reservation.requiredString(Keys.RESERVATION_ID),
      criterionRef = reservation.requiredString(Keys.CRITERION_REF),
      revision = reservation.requiredLong(Keys.REVISION),
      stageRevision = reservation.requiredLong(Keys.STAGE_REVISION),
      reasoningRequired = if (historical) false else reservation.requiredBoolean(Keys.REASONING_REQUIRED),
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
  if (!containsKey(Keys.PROSE_AUDITS)) {
    emptyList()
  } else {
    objectList(Keys.PROSE_AUDITS).map { audit ->
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
    }
  }

private fun interactionTraceWire(value: AcceptanceCriterionInteractionTrace): Map<String, Any?> =
  mapOf(
    Keys.PENDING_SCENARIO_REFS to value.pendingScenarioRefs,
    Keys.TRACED_SCENARIO_REFS to value.tracedScenarioRefs,
    Keys.BROKEN_SCENARIO_REFS to value.brokenScenarioRefs,
    Keys.REPAIRED_SCENARIO_REFS to value.repairedScenarioRefs,
    Keys.UNKNOWN_SCENARIO_REFS to value.unknownScenarioRefs,
    Keys.RECHECK_REFS to value.recheckRefs,
    Keys.RECHECK_ANY_CHANGE_REFS to value.recheckAnyChangeRefs,
    Keys.TRACED_SPEC_DIGEST to value.tracedSpecDigest,
  )

internal fun emptyInteractionTraceWire(): Map<String, Any?> =
  interactionTraceWire(
    AcceptanceCriterionInteractionTrace.EMPTY,
  )

private fun FeatureTaskRuntimeWorkflowArtifactMap.repairInteractionTrace(): AcceptanceCriterionInteractionTrace {
  val trace = repairObject(this[Keys.INTERACTION_TRACE])
  return AcceptanceCriterionInteractionTrace(
    pendingScenarioRefs = trace.stringList(Keys.PENDING_SCENARIO_REFS),
    tracedScenarioRefs = trace.stringList(Keys.TRACED_SCENARIO_REFS),
    brokenScenarioRefs = trace.stringList(Keys.BROKEN_SCENARIO_REFS),
    repairedScenarioRefs = trace.stringList(Keys.REPAIRED_SCENARIO_REFS),
    unknownScenarioRefs = trace.stringList(Keys.UNKNOWN_SCENARIO_REFS),
    recheckRefs = trace.stringList(Keys.RECHECK_REFS),
    recheckAnyChangeRefs = trace.stringList(Keys.RECHECK_ANY_CHANGE_REFS),
    tracedSpecDigest = trace.optionalString(Keys.TRACED_SPEC_DIGEST),
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
    throw invalidAcceptanceCriterionRepairStateSchema("serial-repair", "$key must be a string or null.")
  }

object AcceptanceCriterionEvidenceWire {
  fun mutation(value: AcceptanceCriterionCompletedMutation): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(
      mapOf(
        Keys.CATALOG_ID to value.catalogId,
        Keys.SPEC_DIGEST to value.specDigest,
        Keys.EXECUTION_ID to value.executionId,
        Keys.CRITERION_REF to value.criterionRef,
        ProgressKeys.REPAIR_ATTEMPT_ID to value.repairAttemptId,
        ProgressKeys.WORKER_EXECUTION_ID to value.workerExecutionId,
        ProgressKeys.WORKER_OUTPUT_ID to value.workerOutputId,
        ProgressKeys.WORKER_OUTPUT_DIGEST to value.workerOutputDigest,
        ProgressKeys.TERMINAL_DISPOSITION to value.terminalDisposition.wireValue,
        ProgressKeys.LAUNCH_ATTRIBUTION to
          value.launchAttribution?.let { AcceptanceCriterionRepairAccountingWire.launch(it) },
        ProgressKeys.BASELINE_TREE_DIGEST to value.baselineTreeDigest,
        ProgressKeys.BASELINE_CHECKPOINT_ID to value.baselineCheckpointId,
        ProgressKeys.CURRENT_TREE_DIGEST to value.currentTreeDigest,
        ProgressKeys.CURRENT_CHECKPOINT_ID to value.currentCheckpointId,
        ProgressKeys.ESTABLISHED_OBLIGATIONS to value.establishedObligations.map { established(it) },
      ),
    )

  fun mutation(value: FeatureTaskRuntimeWorkflowArtifactMap): AcceptanceCriterionCompletedMutation {
    closed(
      value,
      setOf(
        Keys.CATALOG_ID,
        Keys.SPEC_DIGEST,
        Keys.EXECUTION_ID,
        Keys.CRITERION_REF,
        ProgressKeys.REPAIR_ATTEMPT_ID,
        ProgressKeys.WORKER_EXECUTION_ID,
        ProgressKeys.WORKER_OUTPUT_ID,
        ProgressKeys.WORKER_OUTPUT_DIGEST,
        ProgressKeys.TERMINAL_DISPOSITION,
        ProgressKeys.LAUNCH_ATTRIBUTION,
        ProgressKeys.BASELINE_TREE_DIGEST,
        ProgressKeys.BASELINE_CHECKPOINT_ID,
        ProgressKeys.CURRENT_TREE_DIGEST,
        ProgressKeys.CURRENT_CHECKPOINT_ID,
        ProgressKeys.ESTABLISHED_OBLIGATIONS,
      ),
    )
    return AcceptanceCriterionCompletedMutation(
      catalogId = text(value, Keys.CATALOG_ID),
      specDigest = text(value, Keys.SPEC_DIGEST),
      executionId = text(value, Keys.EXECUTION_ID),
      criterionRef = text(value, Keys.CRITERION_REF),
      repairAttemptId = text(value, ProgressKeys.REPAIR_ATTEMPT_ID),
      workerExecutionId = text(value, ProgressKeys.WORKER_EXECUTION_ID),
      workerOutputId = text(value, ProgressKeys.WORKER_OUTPUT_ID),
      workerOutputDigest = text(value, ProgressKeys.WORKER_OUTPUT_DIGEST),
      terminalDisposition =
        AcceptanceCriterionMutationDisposition.entries.firstOrNull {
          it.wireValue == text(value, ProgressKeys.TERMINAL_DISPOSITION)
        } ?: throw invalidAcceptanceCriterionAuditResultSchema("completed-mutation", "Invalid terminal disposition"),
      launchAttribution =
        value[ProgressKeys.LAUNCH_ATTRIBUTION]?.let {
          AcceptanceCriterionRepairAccountingWire.launch(FeatureTaskRuntimeWorkflowArtifactMap.from(repairObject(it)))
        },
      baselineTreeDigest = text(value, ProgressKeys.BASELINE_TREE_DIGEST),
      baselineCheckpointId = text(value, ProgressKeys.BASELINE_CHECKPOINT_ID),
      currentTreeDigest = text(value, ProgressKeys.CURRENT_TREE_DIGEST),
      currentCheckpointId = text(value, ProgressKeys.CURRENT_CHECKPOINT_ID),
      establishedObligations = rows(value, ProgressKeys.ESTABLISHED_OBLIGATIONS).map { established(it) },
    ).also { it.validate() }
  }

  fun comparison(value: AcceptanceCriterionIndependentProgress): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(
      mapOf(
        Keys.CRITERION_REF to value.criterionRef,
        ProgressKeys.REPAIR_ATTEMPT_ID to value.repairAttemptId,
        ProgressKeys.BASELINE_TREE_DIGEST to value.baselineTreeDigest,
        ProgressKeys.CURRENT_TREE_DIGEST to value.currentTreeDigest,
        ProgressKeys.BASELINE_CHECKPOINT_ID to value.baselineCheckpointId,
        ProgressKeys.CURRENT_CHECKPOINT_ID to value.currentCheckpointId,
        ProgressKeys.VERIFICATION_REVISION to value.verificationRevision,
        Keys.AUDIT_BINDING_DIGEST to value.auditBindingDigest,
        ProgressKeys.RESOLVED_OBLIGATIONS to value.resolvedObligations.map { resolved(it) },
        ProgressKeys.PRESERVED_OBLIGATIONS to value.preservedObligations.map { preserved(it) },
        ProgressKeys.REMAINING_GAPS to value.remainingGaps.toList(),
        ProgressKeys.CLASSIFICATION to value.classification.wireValue,
      ),
    )

  fun comparison(value: FeatureTaskRuntimeWorkflowArtifactMap): AcceptanceCriterionIndependentProgress {
    closed(
      value,
      setOf(
        Keys.CRITERION_REF,
        ProgressKeys.REPAIR_ATTEMPT_ID,
        ProgressKeys.BASELINE_TREE_DIGEST,
        ProgressKeys.CURRENT_TREE_DIGEST,
        ProgressKeys.BASELINE_CHECKPOINT_ID,
        ProgressKeys.CURRENT_CHECKPOINT_ID,
        ProgressKeys.VERIFICATION_REVISION,
        Keys.AUDIT_BINDING_DIGEST,
        ProgressKeys.RESOLVED_OBLIGATIONS,
        ProgressKeys.PRESERVED_OBLIGATIONS,
        ProgressKeys.REMAINING_GAPS,
        ProgressKeys.CLASSIFICATION,
      ),
    )
    return AcceptanceCriterionIndependentProgress(
      criterionRef = text(value, Keys.CRITERION_REF),
      repairAttemptId = text(value, ProgressKeys.REPAIR_ATTEMPT_ID),
      baselineTreeDigest = text(value, ProgressKeys.BASELINE_TREE_DIGEST),
      currentTreeDigest = text(value, ProgressKeys.CURRENT_TREE_DIGEST),
      baselineCheckpointId = text(value, ProgressKeys.BASELINE_CHECKPOINT_ID),
      currentCheckpointId = text(value, ProgressKeys.CURRENT_CHECKPOINT_ID),
      verificationRevision = number(value, ProgressKeys.VERIFICATION_REVISION),
      auditBindingDigest = text(value, Keys.AUDIT_BINDING_DIGEST),
      resolvedObligations = rows(value, ProgressKeys.RESOLVED_OBLIGATIONS).map { resolved(it) },
      preservedObligations = rows(value, ProgressKeys.PRESERVED_OBLIGATIONS).map { preserved(it) },
      remainingGaps = texts(value, ProgressKeys.REMAINING_GAPS),
      classification =
        AcceptanceCriterionProgressClassification.entries.firstOrNull {
          it.wireValue == value[ProgressKeys.CLASSIFICATION]
        } ?: invalid("Invalid classification"),
    )
  }

  fun context(value: AcceptanceCriterionProgressContext): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(
      mapOf(
        Keys.CATALOG_ID to value.catalogId,
        Keys.SPEC_DIGEST to value.specDigest,
        Keys.EXECUTION_ID to value.executionId,
        Keys.CRITERION_REF to value.criterionRef,
        Keys.RESERVATION_ID to value.reservationId,
        ProgressKeys.WORKER_EXECUTION_ID to value.workerExecutionId,
        ProgressKeys.WORKER_OUTPUT_DIGEST to value.workerOutputDigest,
        ProgressKeys.BASELINE_TREE_DIGEST to value.baselineTreeDigest,
        ProgressKeys.BASELINE_CHECKPOINT_ID to value.baselineCheckpointId,
        ProgressKeys.CURRENT_TREE_DIGEST to value.currentTreeDigest,
        ProgressKeys.CURRENT_CHECKPOINT_ID to value.currentCheckpointId,
        ProgressKeys.VERIFICATION_REVISION to value.verificationRevision,
        Keys.AUDIT_BINDING_DIGEST to value.auditBindingDigest,
        ProgressKeys.CHECKER_EXECUTION_ID to value.checkerExecutionId,
        ProgressKeys.CHECKER_OUTPUT_ID to value.checkerOutputId,
        ProgressKeys.CHECKER_OUTPUT_DIGEST to value.checkerOutputDigest,
        ProgressKeys.SOURCE_UNCHANGED_DURING_CHECK to value.sourceUnchangedDuringCheck,
        ProgressKeys.BASELINE_CITATIONS to value.baselineCitations.toList(),
        ProgressKeys.CURRENT_CITATIONS to value.currentCitations.toList(),
        ProgressKeys.ESTABLISHED_OBLIGATIONS to value.establishedObligations.map { established(it) },
        ProgressKeys.VERIFIED_RESOLVED_OBLIGATIONS to value.verifiedResolvedObligations.map { resolved(it) },
        ProgressKeys.VERIFIED_PRESERVED_OBLIGATIONS to value.verifiedPreservedObligations.map { preserved(it) },
      ),
    )

  fun context(value: FeatureTaskRuntimeWorkflowArtifactMap): AcceptanceCriterionProgressContext {
    closed(
      value,
      setOf(
        Keys.CATALOG_ID,
        Keys.SPEC_DIGEST,
        Keys.EXECUTION_ID,
        Keys.CRITERION_REF,
        Keys.RESERVATION_ID,
        ProgressKeys.WORKER_EXECUTION_ID,
        ProgressKeys.WORKER_OUTPUT_DIGEST,
        ProgressKeys.BASELINE_TREE_DIGEST,
        ProgressKeys.BASELINE_CHECKPOINT_ID,
        ProgressKeys.CURRENT_TREE_DIGEST,
        ProgressKeys.CURRENT_CHECKPOINT_ID,
        ProgressKeys.VERIFICATION_REVISION,
        Keys.AUDIT_BINDING_DIGEST,
        ProgressKeys.CHECKER_EXECUTION_ID,
        ProgressKeys.CHECKER_OUTPUT_ID,
        ProgressKeys.CHECKER_OUTPUT_DIGEST,
        ProgressKeys.SOURCE_UNCHANGED_DURING_CHECK,
        ProgressKeys.BASELINE_CITATIONS,
        ProgressKeys.CURRENT_CITATIONS,
        ProgressKeys.ESTABLISHED_OBLIGATIONS,
        ProgressKeys.VERIFIED_RESOLVED_OBLIGATIONS,
        ProgressKeys.VERIFIED_PRESERVED_OBLIGATIONS,
      ),
    )
    return AcceptanceCriterionProgressContext(
      catalogId = text(value, Keys.CATALOG_ID),
      specDigest = text(value, Keys.SPEC_DIGEST),
      executionId = text(value, Keys.EXECUTION_ID),
      criterionRef = text(value, Keys.CRITERION_REF),
      reservationId = text(value, Keys.RESERVATION_ID),
      workerExecutionId = text(value, ProgressKeys.WORKER_EXECUTION_ID),
      workerOutputDigest = text(value, ProgressKeys.WORKER_OUTPUT_DIGEST),
      baselineTreeDigest = text(value, ProgressKeys.BASELINE_TREE_DIGEST),
      baselineCheckpointId = text(value, ProgressKeys.BASELINE_CHECKPOINT_ID),
      currentTreeDigest = text(value, ProgressKeys.CURRENT_TREE_DIGEST),
      currentCheckpointId = text(value, ProgressKeys.CURRENT_CHECKPOINT_ID),
      verificationRevision = number(value, ProgressKeys.VERIFICATION_REVISION),
      auditBindingDigest = text(value, Keys.AUDIT_BINDING_DIGEST),
      checkerExecutionId = text(value, ProgressKeys.CHECKER_EXECUTION_ID),
      checkerOutputId = text(value, ProgressKeys.CHECKER_OUTPUT_ID),
      checkerOutputDigest = text(value, ProgressKeys.CHECKER_OUTPUT_DIGEST),
      sourceUnchangedDuringCheck =
        value[ProgressKeys.SOURCE_UNCHANGED_DURING_CHECK] as? Boolean ?: invalid(
          "Source stability must be a Boolean",
        ),
      baselineCitations = texts(value, ProgressKeys.BASELINE_CITATIONS).toSet(),
      currentCitations = texts(value, ProgressKeys.CURRENT_CITATIONS).toSet(),
      establishedObligations = rows(value, ProgressKeys.ESTABLISHED_OBLIGATIONS).map { established(it) },
      verifiedResolvedObligations = rows(value, ProgressKeys.VERIFIED_RESOLVED_OBLIGATIONS).map { resolved(it) },
      verifiedPreservedObligations = rows(value, ProgressKeys.VERIFIED_PRESERVED_OBLIGATIONS).map { preserved(it) },
    )
  }

  private fun established(value: AcceptanceCriterionEstablishedObligation): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(
      mapOf(
        ProgressKeys.OBLIGATION_ID to value.obligationId,
        ProgressKeys.OBLIGATION to value.obligation,
        ProgressKeys.CURRENT_CITATIONS to value.currentCitations.toList(),
      ),
    )

  private fun established(value: FeatureTaskRuntimeWorkflowArtifactMap): AcceptanceCriterionEstablishedObligation {
    closed(value, setOf(ProgressKeys.OBLIGATION_ID, ProgressKeys.OBLIGATION, ProgressKeys.CURRENT_CITATIONS))
    return AcceptanceCriterionEstablishedObligation(
      obligationId = text(value, ProgressKeys.OBLIGATION_ID),
      obligation = text(value, ProgressKeys.OBLIGATION),
      currentCitations = texts(value, ProgressKeys.CURRENT_CITATIONS),
    )
  }

  private fun resolved(value: AcceptanceCriterionResolvedObligation): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(
      mapOf(
        ProgressKeys.OBLIGATION_ID to value.obligationId,
        ProgressKeys.OBLIGATION to value.obligation,
        ProgressKeys.BASELINE_CITATIONS to value.baselineCitations.toList(),
        ProgressKeys.CURRENT_CITATIONS to value.currentCitations.toList(),
      ),
    )

  private fun resolved(value: FeatureTaskRuntimeWorkflowArtifactMap): AcceptanceCriterionResolvedObligation {
    closed(
      value,
      setOf(
        ProgressKeys.OBLIGATION_ID,
        ProgressKeys.OBLIGATION,
        ProgressKeys.BASELINE_CITATIONS,
        ProgressKeys.CURRENT_CITATIONS,
      ),
    )
    return AcceptanceCriterionResolvedObligation(
      obligationId = text(value, ProgressKeys.OBLIGATION_ID),
      obligation = text(value, ProgressKeys.OBLIGATION),
      baselineCitations = texts(value, ProgressKeys.BASELINE_CITATIONS),
      currentCitations = texts(value, ProgressKeys.CURRENT_CITATIONS),
    )
  }

  private fun preserved(value: AcceptanceCriterionPreservedObligation): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(
      mapOf(
        ProgressKeys.OBLIGATION_ID to value.obligationId,
        ProgressKeys.OBLIGATION to value.obligation,
        ProgressKeys.CURRENT_CITATIONS to value.currentCitations.toList(),
      ),
    )

  private fun preserved(value: FeatureTaskRuntimeWorkflowArtifactMap): AcceptanceCriterionPreservedObligation {
    closed(value, setOf(ProgressKeys.OBLIGATION_ID, ProgressKeys.OBLIGATION, ProgressKeys.CURRENT_CITATIONS))
    return AcceptanceCriterionPreservedObligation(
      obligationId = text(value, ProgressKeys.OBLIGATION_ID),
      obligation = text(value, ProgressKeys.OBLIGATION),
      currentCitations = texts(value, ProgressKeys.CURRENT_CITATIONS),
    )
  }

  fun checked(value: AcceptanceCriterionCheckedMutation): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(
      mapOf(
        ProgressKeys.MUTATION to mutation(value.mutation),
        ProgressKeys.COMPARISON to comparison(value.comparison),
        ProgressKeys.CONTEXT to context(value.context),
      ),
    )

  fun checked(value: FeatureTaskRuntimeWorkflowArtifactMap): AcceptanceCriterionCheckedMutation {
    closed(value, setOf(ProgressKeys.MUTATION, ProgressKeys.COMPARISON, ProgressKeys.CONTEXT))
    val completed = mutation(FeatureTaskRuntimeWorkflowArtifactMap.from(value[ProgressKeys.MUTATION]))
    return AcceptanceCriterionCheckedMutation(
      completed,
      comparison(FeatureTaskRuntimeWorkflowArtifactMap.from(value[ProgressKeys.COMPARISON])),
      context(
        FeatureTaskRuntimeWorkflowArtifactMap.from(value[ProgressKeys.CONTEXT]),
      ).copy(completedMutation = completed),
    ).also { it.validate() }
  }

  private fun closed(
    value: FeatureTaskRuntimeWorkflowArtifactMap,
    fields: Set<String>,
  ) {
    if (!value.isObject || value.keys != fields) invalid("Evidence fields do not match the contract")
  }

  private fun text(
    value: FeatureTaskRuntimeWorkflowArtifactMap,
    key: String,
  ): String = value[key] as? String ?: invalid("$key must be a string")

  private fun texts(
    value: FeatureTaskRuntimeWorkflowArtifactMap,
    key: String,
  ): List<String> =
    (value[key] as? List<*>)?.map { it as? String ?: invalid("$key contains a non-string") }
      ?: invalid("$key must be an array")

  private fun rows(
    value: FeatureTaskRuntimeWorkflowArtifactMap,
    key: String,
  ): List<FeatureTaskRuntimeWorkflowArtifactMap> =
    (value[key] as? List<*>)?.map { FeatureTaskRuntimeWorkflowArtifactMap.from(it) }
      ?: invalid("$key must be an array")

  private fun number(
    value: FeatureTaskRuntimeWorkflowArtifactMap,
    key: String,
  ): Long = (value[key] as? Number)?.toLongIfExact() ?: invalid("$key must be an exact integer")

  private fun invalid(reason: String): Nothing =
    throw invalidAcceptanceCriterionAuditResultSchema(
      "independent-evidence",
      reason,
    )
}
