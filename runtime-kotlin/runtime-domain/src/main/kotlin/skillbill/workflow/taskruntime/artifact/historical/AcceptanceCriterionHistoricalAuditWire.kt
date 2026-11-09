package skillbill.workflow.taskruntime.artifact.historical

import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_INTERACTION_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_PREVIOUS_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidAcceptanceCriterionAuditResultSchema
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionInteractionTrace
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairDependency
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionHistoricalAuditFact
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionHistoricalAuditResult
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionAuditResultMode
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionEntry
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionVerdict
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionRecheckEntry
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionRecheckVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionBudget
import java.math.BigDecimal
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

fun FeatureTaskRuntimeWorkflowArtifactMap.toHistoricalAuditResult(
  sourceLabel: String,
): AcceptanceCriterionHistoricalAuditResult = HistoricalAuditReader(this, sourceLabel).read()

private class HistoricalAuditReader(
  private val payload: FeatureTaskRuntimeWorkflowArtifactMap,
  private val source: String,
) {
  fun read(): AcceptanceCriterionHistoricalAuditResult {
    val version = readVersion()
    val original = version == ACCEPTANCE_CRITERION_AUDIT_RESULT_PREVIOUS_CONTRACT_VERSION
    val mode = token(payload, Keys.MODE, AcceptanceCriterionAuditResultMode.entries) { it.wireValue }
    if (original && mode == AcceptanceCriterionAuditResultMode.INTERACTION_TRACE) {
      invalid("Original audit cannot trace interactions.")
    }
    val criteria = rows(payload, Keys.CRITERIA, AcceptanceCriterionRepairState.MAX_REPAIR_CRITERIA).map(::fact)
    if (criteria.isEmpty() || criteria.map { it.criterionRef }.distinct().size != criteria.size) {
      invalid("Invalid historical criterion catalog.")
    }
    val current = optionalText(payload, Keys.CURRENT_CRITERION_REF)?.also(::criterionRef)
    val reservation = optionalText(payload, Keys.RESERVATION_ID, AcceptanceCriterionRepairState.MAX_REPAIR_ID_LENGTH)
    val revision = optionalRevision()
    requireTargetedBinding(mode, criteria, current, reservation, revision)
    val interactions =
      if (original) {
        null
      } else {
        rows(
          payload,
          Keys.INTERACTIONS,
          AcceptanceCriterionInteractionTrace.MAX_SCENARIO_REFS,
        ).map(::interaction)
      }
    val rechecks =
      if (original) {
        null
      } else {
        rows(
          payload,
          Keys.RECHECKS,
          AcceptanceCriterionRepairState.MAX_REPAIR_CRITERIA,
        ).map(::recheck)
      }
    val regressed = ids(Keys.REGRESSED_REFS)
    val edges =
      rows(payload, Keys.EDGE_CHANGES, AcceptanceCriterionRepairState.MAX_REPAIR_DEPENDENCIES).map(
        ::dependency,
      )
    requireInteractionCoherence(mode, interactions.orEmpty(), rechecks.orEmpty(), regressed, edges)
    return AcceptanceCriterionHistoricalAuditResult(
      contractVersion = version,
      mode = mode,
      catalogId = text(payload, Keys.CATALOG_ID, AcceptanceCriterionRepairState.MAX_REPAIR_ID_LENGTH),
      specDigest = digest(Keys.SPEC_DIGEST),
      executionId = text(payload, Keys.EXECUTION_ID, AcceptanceCriterionRepairState.MAX_REPAIR_ID_LENGTH),
      treeDigest = digest(Keys.TREE_DIGEST),
      checkpointId = text(payload, Keys.CHECKPOINT_ID, AcceptanceCriterionRepairState.MAX_REPAIR_ID_LENGTH),
      producingOutputId = text(payload, Keys.PRODUCING_OUTPUT_ID, AcceptanceCriterionRepairState.MAX_REPAIR_ID_LENGTH),
      producingOutputDigest = digest(Keys.PRODUCING_OUTPUT_DIGEST),
      expectedRevision = revision,
      currentCriterionRef = current,
      reservationId = reservation,
      criteria = criteria,
      dependencies =
        rows(payload, Keys.DEPENDENCIES, AcceptanceCriterionRepairState.MAX_REPAIR_DEPENDENCIES)
          .map(::dependency),
      regressedRefs = regressed,
      edgeChanges = edges,
      interactions = interactions,
      rechecks = rechecks,
    )
  }

  private fun readVersion(): String {
    if (JsonCodec.valueToJsonString(payload).toByteArray(
        Charsets.UTF_8,
      ).size > FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION.maxUtf8Bytes
    ) {
      invalid("Historical audit exceeds capacity.")
    }
    val version = text(payload, Keys.CONTRACT_VERSION)
    val original = version == ACCEPTANCE_CRITERION_AUDIT_RESULT_PREVIOUS_CONTRACT_VERSION
    if (!original && version != ACCEPTANCE_CRITERION_AUDIT_RESULT_INTERACTION_CONTRACT_VERSION) {
      invalid("Unsupported historical audit version.")
    }
    val interactionFields = if (original) emptySet() else setOf(Keys.INTERACTIONS, Keys.RECHECKS)
    shape(
      payload,
      ROOT_FIELDS + interactionFields,
      setOf(
        Keys.EXPECTED_REVISION,
        Keys.CURRENT_CRITERION_REF,
        Keys.RESERVATION_ID,
      ),
    )
    return version
  }

  private fun requireTargetedBinding(
    mode: AcceptanceCriterionAuditResultMode,
    criteria: List<AcceptanceCriterionHistoricalAuditFact>,
    current: String?,
    reservation: String?,
    revision: Long?,
  ) {
    if (mode != AcceptanceCriterionAuditResultMode.CURRENT_CRITERION_TARGETED) return
    if (criteria.singleOrNull()?.criterionRef != current || reservation == null || revision == null) {
      invalid("Historical targeted audit must bind one criterion, reservation and revision.")
    }
  }

  private fun requireInteractionCoherence(
    mode: AcceptanceCriterionAuditResultMode,
    interactions: List<AcceptanceCriterionInteractionEntry>,
    rechecks: List<AcceptanceCriterionRecheckEntry>,
    regressed: List<String>,
    edges: List<AcceptanceCriterionRepairDependency>,
  ) {
    val empty = interactions.isEmpty() && rechecks.isEmpty()
    if (mode != AcceptanceCriterionAuditResultMode.INTERACTION_TRACE) {
      if (!empty) invalid("Interaction observations require interaction-trace mode.")
      return
    }
    val brokenOwners =
      interactions.filter { it.verdict == AcceptanceCriterionInteractionVerdict.BROKEN }
        .mapNotNull { it.ownerRef }
    val regressedCriteria =
      rechecks.filter { it.verdict == AcceptanceCriterionRecheckVerdict.REGRESSED }
        .map { it.criterionRef }
    if (empty || regressed.toSet() != (brokenOwners + regressedCriteria).toSet() || edges.isNotEmpty()) {
      invalid("Historical interaction result has incoherent regressions or edges.")
    }
  }

  private fun fact(row: FeatureTaskRuntimeWorkflowArtifactMap): AcceptanceCriterionHistoricalAuditFact {
    shape(row, FACT_FIELDS)
    return AcceptanceCriterionHistoricalAuditFact(
      text(row, Keys.CANONICAL_REF).also(::criterionRef),
      token(row, Keys.SOURCE_DISPOSITION, AcceptanceCriterionSourceDisposition.entries) { it.wireValue },
      token(row, Keys.VALIDATION_OBLIGATION, AcceptanceCriterionValidationObligation.entries) { it.wireValue },
      row[Keys.VALIDATION_OWNER_AVAILABLE] as? Boolean ?: invalid("Invalid validation owner availability."),
      text(row, Keys.EVIDENCE_REF),
    )
  }

  private fun dependency(row: FeatureTaskRuntimeWorkflowArtifactMap): AcceptanceCriterionRepairDependency {
    shape(row, DEPENDENCY_FIELDS)
    return AcceptanceCriterionRepairDependency(
      text(row, Keys.DEPENDENT_REF).also(::criterionRef),
      text(row, Keys.PREREQUISITE_REF).also(::criterionRef),
      text(row, Keys.REASON, MAX_REASON_LENGTH),
      text(row, Keys.DECLARING_OUTPUT_REF, MAX_DECLARING_OUTPUT_LENGTH),
      text(row, Keys.SOURCE_EVIDENCE_REF),
    )
  }

  private fun interaction(row: FeatureTaskRuntimeWorkflowArtifactMap): AcceptanceCriterionInteractionEntry {
    shape(row, setOf(Keys.SCENARIO_REF, Keys.VERDICT, Keys.OWNER_REF, Keys.TRACE_REF))
    val ref = text(row, Keys.SCENARIO_REF)
    if (!Regex("^(IS-[0-9]{3}|AC-[0-9]{3}\\+AC-[0-9]{3})$").matches(ref)) invalid("Invalid scenario reference.")
    val verdict = token(row, Keys.VERDICT, AcceptanceCriterionInteractionVerdict.entries) { it.wireValue }
    val owner = optionalText(row, Keys.OWNER_REF)?.also(::criterionRef)
    val trace = text(row, Keys.TRACE_REF)
    if ((verdict == AcceptanceCriterionInteractionVerdict.BROKEN) != (owner != null) || trace.isBlank()) {
      invalid("Historical interaction has invalid owner or trace evidence.")
    }
    return AcceptanceCriterionInteractionEntry(ref, verdict, owner, trace)
  }

  private fun recheck(row: FeatureTaskRuntimeWorkflowArtifactMap): AcceptanceCriterionRecheckEntry {
    shape(row, setOf(Keys.CRITERION_REF, Keys.VERDICT, Keys.EVIDENCE_REF))
    return AcceptanceCriterionRecheckEntry(
      text(row, Keys.CRITERION_REF).also(::criterionRef),
      token(row, Keys.VERDICT, AcceptanceCriterionRecheckVerdict.entries) { it.wireValue },
      text(
        row,
        Keys.EVIDENCE_REF,
      ),
    )
  }

  private fun shape(
    row: FeatureTaskRuntimeWorkflowArtifactMap,
    required: Set<String>,
    optional: Set<String> =
      emptySet(),
  ) {
    if (!row.isObject || !row.keys.containsAll(required) || (row.keys - required - optional).isNotEmpty()) {
      invalid("Unexpected or missing historical audit fields.")
    }
  }

  private fun rows(
    row: FeatureTaskRuntimeWorkflowArtifactMap,
    key: String,
    max: Int,
  ): List<FeatureTaskRuntimeWorkflowArtifactMap> {
    val items = row[key] as? List<*> ?: invalid("$key must be an array.")
    if (items.size > max) invalid("$key exceeds capacity.")
    return items.map {
      FeatureTaskRuntimeWorkflowArtifactMap.from(it).also { value ->
        if (!value.isObject) {
          invalid("$key contains a non-object.")
        }
      }
    }
  }

  private fun text(
    row: FeatureTaskRuntimeWorkflowArtifactMap,
    key: String,
    max: Int = Int.MAX_VALUE,
  ): String {
    val value = row[key] as? String ?: invalid("$key must be a string.")
    if (value.isEmpty() || value.length > max) invalid("$key has invalid length.")
    return value
  }

  private fun optionalText(
    row: FeatureTaskRuntimeWorkflowArtifactMap,
    key: String,
    max: Int = Int.MAX_VALUE,
  ): String? = if (row[key] == null) null else text(row, key, max)

  private fun digest(key: String): String =
    text(payload, key).also {
      if (!Regex("^[a-fA-F0-9]{64}$").matches(it)) invalid("$key must be a digest.")
    }

  private fun criterionRef(value: String) {
    if (!Regex("^AC-[0-9]{3}$").matches(value)) invalid("Invalid criterion reference.")
  }

  private fun ids(key: String): List<String> {
    val values = payload[key] as? List<*> ?: invalid("$key must be an array.")
    if (values.size > AcceptanceCriterionRepairState.MAX_REPAIR_CRITERIA || values.distinct().size != values.size) {
      invalid("Invalid $key references.")
    }
    return values.map { (it as? String ?: invalid("Invalid reference.")).also(::criterionRef) }
  }

  private fun optionalRevision(): Long? {
    val raw = payload[Keys.EXPECTED_REVISION] ?: return null
    val number = raw as? Number ?: invalid("Invalid historical revision.")
    val value =
      try {
        BigDecimal(number.toString()).longValueExact()
      } catch (_: ArithmeticException) {
        invalid("Invalid historical revision.")
      } catch (_: NumberFormatException) {
        invalid("Invalid historical revision.")
      }
    return value.takeIf { it >= 0 } ?: invalid("Invalid historical revision.")
  }

  private fun <T> token(
    row: FeatureTaskRuntimeWorkflowArtifactMap,
    key: String,
    values: List<T>,
    wire: (T) -> String,
  ): T = values.firstOrNull { wire(it) == row[key] } ?: invalid("Invalid $key token.")

  private fun invalid(reason: String): Nothing = throw invalidAcceptanceCriterionAuditResultSchema(source, reason)
}

private val ROOT_FIELDS =
  setOf(
    Keys.CONTRACT_VERSION, Keys.MODE, Keys.CATALOG_ID, Keys.SPEC_DIGEST, Keys.EXECUTION_ID, Keys.TREE_DIGEST,
    Keys.CHECKPOINT_ID, Keys.PRODUCING_OUTPUT_ID, Keys.PRODUCING_OUTPUT_DIGEST, Keys.CRITERIA, Keys.DEPENDENCIES,
    Keys.REGRESSED_REFS, Keys.EDGE_CHANGES,
  )
private val FACT_FIELDS =
  setOf(
    Keys.CANONICAL_REF,
    Keys.SOURCE_DISPOSITION,
    Keys.VALIDATION_OBLIGATION,
    Keys.VALIDATION_OWNER_AVAILABLE,
    Keys.EVIDENCE_REF,
  )
private val DEPENDENCY_FIELDS =
  setOf(
    Keys.DEPENDENT_REF,
    Keys.PREREQUISITE_REF,
    Keys.REASON,
    Keys.DECLARING_OUTPUT_REF,
    Keys.SOURCE_EVIDENCE_REF,
  )

private const val MAX_REASON_LENGTH = 1024
private const val MAX_DECLARING_OUTPUT_LENGTH = 512
