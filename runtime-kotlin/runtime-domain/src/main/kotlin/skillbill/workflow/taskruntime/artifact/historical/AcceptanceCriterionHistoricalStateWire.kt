package skillbill.workflow.taskruntime.artifact.historical

import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_INTERACTION_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_PREVIOUS_CONTRACT_VERSION
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.invalidAcceptanceCriterionRepairStateSchema
import skillbill.error.shellcontent.invalidFeatureTaskRuntimeImplementationProgressSchema
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import skillbill.workflow.taskruntime.artifact.decodeRepairStateFields
import skillbill.workflow.taskruntime.artifact.exactInt
import skillbill.workflow.taskruntime.artifact.ids
import skillbill.workflow.taskruntime.artifact.requireKnownRepairFields
import skillbill.workflow.taskruntime.artifact.validateDurableSize
import skillbill.workflow.taskruntime.artifact.validateImplementationSize
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionCompletedMutation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCoherence
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionHistoricalCheck
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionHistoricalImplementationProgress
import skillbill.workflow.taskruntime.model.audit.history.AcceptanceCriterionHistoricalRepairState
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionProgressPayloadKeys as ProgressKeys
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimeImplementationProgressPayloadKeys as ImplKeys

fun FeatureTaskRuntimeWorkflowArtifactMap.toHistoricalRepairState(
  sourceLabel: String,
): AcceptanceCriterionHistoricalRepairState {
  validateDurableSize(sourceLabel)
  val version = historicalRepairVersion(sourceLabel)
  val original = version == ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
  val state: AcceptanceCriterionRepairState
  val mutations: List<AcceptanceCriterionCompletedMutation>
  val checks: List<AcceptanceCriterionHistoricalCheck>
  try {
    state = decodeRepairStateFields(historical = true)
    mutations = AcceptanceCriterionHistoricalEvidenceWire.mutations(this)
    checks = AcceptanceCriterionHistoricalEvidenceWire.checks(this)
    AcceptanceCriterionHistoricalEvidenceWire.validate(mutations, checks)
  } catch (cause: SkillBillRuntimeException) {
    throw invalidAcceptanceCriterionRepairStateSchema(sourceLabel, "Invalid historical repair facts.", cause)
  }
  requireHistoricalRepairShape(state, version, sourceLabel)
  if (original && state.stage == AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED ||
    !AcceptanceCriterionRepairCoherence.historicalIsCoherent(state)
  ) {
    throw invalidAcceptanceCriterionRepairStateSchema(sourceLabel, "Historical repair state is incoherent.")
  }
  requireHistoricalEvidenceOwner(state, mutations, sourceLabel)
  return AcceptanceCriterionHistoricalRepairState(
    version,
    state,
    mutations.takeIf { containsKey(ProgressKeys.COMPLETED_MUTATIONS) },
    checks.takeIf { containsKey(ProgressKeys.INDEPENDENT_CHECKS) },
  )
}

private fun FeatureTaskRuntimeWorkflowArtifactMap.historicalRepairVersion(sourceLabel: String): String {
  val version = this[Keys.CONTRACT_VERSION] as? String
  val original = version == ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
  if (!isObject || (!original && version != ACCEPTANCE_CRITERION_REPAIR_STATE_INTERACTION_CONTRACT_VERSION)) {
    throw invalidAcceptanceCriterionRepairStateSchema(sourceLabel, "Unsupported historical repair-state version.")
  }
  val required = HISTORICAL_REPAIR_FIELDS - if (original) setOf(Keys.INTERACTION_TRACE) else emptySet()
  if (!keys.containsAll(required) || original && containsKey(Keys.INTERACTION_TRACE)) {
    throw invalidAcceptanceCriterionRepairStateSchema(sourceLabel, "Historical repair-state fields are invalid.")
  }
  return version
}

private fun FeatureTaskRuntimeWorkflowArtifactMap.requireHistoricalRepairShape(
  state: AcceptanceCriterionRepairState,
  version: String,
  sourceLabel: String,
) {
  val original = version == ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
  val expected = state.asRepairWireMap().toMutableMap()
  expected[Keys.CONTRACT_VERSION] = version
  expected.remove(ProgressKeys.REPAIR_BUDGETS)
  expected.remove(ProgressKeys.REPAIR_ACCOUNTING)
  if (!original) {
    if (containsKey(ProgressKeys.COMPLETED_MUTATIONS)) {
      expected[ProgressKeys.COMPLETED_MUTATIONS] =
        this[ProgressKeys.COMPLETED_MUTATIONS]
    }
    if (containsKey(ProgressKeys.INDEPENDENT_CHECKS)) {
      expected[ProgressKeys.INDEPENDENT_CHECKS] =
        this[ProgressKeys.INDEPENDENT_CHECKS]
    }
  }
  if (original) {
    expected.remove(Keys.INTERACTION_TRACE)
    expected.remove(ProgressKeys.COMPLETED_MUTATIONS)
    expected.remove(ProgressKeys.INDEPENDENT_CHECKS)
  }
  val reserved = expected[Keys.RESERVATION] as? Map<*, *>
  if (reserved != null) expected[Keys.RESERVATION] = reserved - Keys.REASONING_REQUIRED
  requireKnownRepairFields(FeatureTaskRuntimeWorkflowArtifactMap.from(expected), sourceLabel)
  expected.filterKeys { containsKey(it) }.forEach { (key, value) ->
    requireHistoricalValue(this[key], value, key, sourceLabel)
  }
}

private fun requireHistoricalEvidenceOwner(
  state: AcceptanceCriterionRepairState,
  mutations: List<AcceptanceCriterionCompletedMutation>,
  sourceLabel: String,
) {
  val refs = state.criteria.map { it.canonicalRef }.toSet()
  if (mutations.any {
      val identity =
        it.catalogId == state.catalogId && it.executionId == state.executionId && it.specDigest ==
          state.specDigest
      it.criterionRef !in refs || !identity
    }
  ) {
    throw invalidAcceptanceCriterionRepairStateSchema(
      sourceLabel,
      "Historical evidence belongs to a different catalog.",
    )
  }
}

fun FeatureTaskRuntimeWorkflowArtifactMap.toHistoricalImplementationProgress(
  sourceLabel: String,
): AcceptanceCriterionHistoricalImplementationProgress {
  fun invalid(reason: String): Nothing =
    throw invalidFeatureTaskRuntimeImplementationProgressSchema(
      sourceLabel,
      reason,
    )
  validateImplementationSize(sourceLabel)
  requireHistoricalProgressShape(sourceLabel)
  val ids = ids(ImplKeys.CRITERION_IDS) ?: invalid("Invalid historical criterion IDs.")
  val closed = ids(ImplKeys.CLOSED_IDS) ?: invalid("Invalid historical closed IDs.")
  val index = exactInt(ImplKeys.CURRENT_INDEX) ?: invalid("Invalid historical cursor.")
  val workers = exactInt(ImplKeys.WORKER_COUNT) ?: invalid("Invalid historical worker ordinal.")
  val launches = exactInt(ImplKeys.LAUNCH_COUNT) ?: invalid("Invalid historical launch ordinal.")
  FeatureTaskRuntimeImplementationProgress.violation(ids, index, workers, launches)?.let(::invalid)
  if (ids.size > HISTORICAL_MAX_CRITERIA || workers > HISTORICAL_WORKER_LIMIT || closed != ids.take(index)) {
    invalid("Incoherent historical cursor or ordinal.")
  }
  val mutations: List<AcceptanceCriterionCompletedMutation>
  val checks: List<AcceptanceCriterionHistoricalCheck>
  try {
    mutations = AcceptanceCriterionHistoricalEvidenceWire.mutations(this)
    checks = AcceptanceCriterionHistoricalEvidenceWire.checks(this)
    AcceptanceCriterionHistoricalEvidenceWire.validate(mutations, checks)
  } catch (cause: SkillBillRuntimeException) {
    throw invalidFeatureTaskRuntimeImplementationProgressSchema(sourceLabel, "Invalid historical evidence.", cause)
  }
  if (mutations.any { it.criterionRef !in ids }) invalid("Historical evidence names an unknown criterion.")
  return AcceptanceCriterionHistoricalImplementationProgress(
    ids,
    index,
    workers,
    launches,
    closed,
    mutations.takeIf { containsKey(ProgressKeys.COMPLETED_MUTATIONS) },
    checks.takeIf { containsKey(ProgressKeys.INDEPENDENT_CHECKS) },
  )
}

private fun FeatureTaskRuntimeWorkflowArtifactMap.requireHistoricalProgressShape(sourceLabel: String) {
  val versionMatches =
    isObject && this[ImplKeys.CONTRACT_VERSION] ==
      FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_PREVIOUS_CONTRACT_VERSION
  val fieldsMatch =
    keys.containsAll(HISTORICAL_PROGRESS_FIELDS) &&
      (keys - HISTORICAL_PROGRESS_FIELDS - HISTORICAL_EVIDENCE_FIELDS).isEmpty()
  if (!versionMatches || !fieldsMatch) {
    throw invalidFeatureTaskRuntimeImplementationProgressSchema(
      sourceLabel,
      "Unsupported historical implementation-progress shape or version.",
    )
  }
}

private val HISTORICAL_REPAIR_FIELDS =
  setOf(
    Keys.CONTRACT_VERSION, Keys.CATALOG_ID, Keys.EXECUTION_ID, Keys.SPEC_DIGEST, Keys.CRITERIA, Keys.DEPENDENCIES,
    Keys.UNRESOLVED_QUEUE, Keys.CURRENT_CRITERION_REF, Keys.RESERVATION, Keys.OUTCOMES, Keys.UNSUCCESSFUL_TOTALS,
    Keys.REVISION, Keys.MODE, Keys.STAGE, Keys.COMPLETED_REPAIR_EVIDENCE_REF, Keys.ADMITTED_TREE_DIGEST,
    Keys.ADMITTED_CHECKPOINT_ID, Keys.SOURCE_TRANSITIONS, Keys.INTERACTION_TRACE,
  )
private val HISTORICAL_PROGRESS_FIELDS =
  setOf(
    ImplKeys.CONTRACT_VERSION,
    ImplKeys.CRITERION_IDS,
    ImplKeys.CURRENT_INDEX,
    ImplKeys.WORKER_COUNT,
    ImplKeys.LAUNCH_COUNT,
    ImplKeys.CLOSED_IDS,
  )
private val HISTORICAL_EVIDENCE_FIELDS = setOf(ProgressKeys.COMPLETED_MUTATIONS, ProgressKeys.INDEPENDENT_CHECKS)

private fun requireHistoricalValue(
  actual: Any?,
  expected: Any?,
  key: String,
  source: String,
) {
  val valid =
    when (expected) {
      null -> actual == null
      is Number ->
        actual is Number && actual.toString().toBigDecimalOrNull()?.compareTo(expected.toString().toBigDecimal()) ==
          0
      is String -> historicalTextMatches(actual, expected, key)
      is Boolean -> actual == expected
      is List<*> -> historicalListMatches(actual, expected, key, source)
      is Map<*, *> -> historicalObjectMatches(actual, expected, source)
      else -> false
    }
  if (!valid) throw invalidAcceptanceCriterionRepairStateSchema(source, "Invalid historical $key shape or value.")
}

private fun historicalTextMatches(
  actual: Any?,
  expected: String,
  key: String,
): Boolean {
  if (actual != expected) return false
  val maximum =
    when (key) {
      Keys.ORIGINAL_TEXT -> HISTORICAL_TEXT_LENGTH
      Keys.ORIGINAL_ALIAS -> HISTORICAL_ALIAS_LENGTH
      Keys.REASON -> HISTORICAL_REASON_LENGTH
      Keys.DECLARING_OUTPUT_REF -> HISTORICAL_OUTPUT_REF_LENGTH
      in HISTORICAL_ID_FIELDS -> AcceptanceCriterionRepairState.MAX_REPAIR_ID_LENGTH
      else -> Int.MAX_VALUE
    }
  val digest = key !in HISTORICAL_DIGEST_FIELDS || Regex("^[a-fA-F0-9]{64}$").matches(expected)
  return expected.length <= maximum && digest
}

private fun historicalListMatches(
  actual: Any?,
  expected: List<*>,
  key: String,
  source: String,
): Boolean {
  if (actual !is List<*> || actual.size != expected.size) return false
  expected.forEachIndexed { index, value -> requireHistoricalValue(actual[index], value, key, source) }
  return true
}

private fun historicalObjectMatches(
  actual: Any?,
  expected: Map<*, *>,
  source: String,
): Boolean {
  if (actual !is Map<*, *>) return false
  return expected.all { (nestedKey, value) ->
    val name = nestedKey as? String ?: return@all false
    if (name !in actual && name != Keys.AUDIT_BINDING_DIGEST) return@all false
    if (name in actual) requireHistoricalValue(actual[name], value, name, source)
    true
  }
}

private val HISTORICAL_ID_FIELDS =
  setOf(
    Keys.CATALOG_ID,
    Keys.EXECUTION_ID,
    Keys.RESERVATION_ID,
    Keys.OUTCOME_ID,
    Keys.ID,
    Keys.CHECKPOINT_ID,
    Keys.ADMITTED_CHECKPOINT_ID,
    Keys.CLOSURE_CHECKPOINT_ID,
  )
private val HISTORICAL_DIGEST_FIELDS =
  setOf(
    Keys.SPEC_DIGEST,
    Keys.TREE_DIGEST,
    Keys.DIGEST,
    Keys.PRODUCING_DIGEST,
    Keys.AUDIT_BINDING_DIGEST,
    Keys.ADMITTED_TREE_DIGEST,
    Keys.CLOSURE_TREE_DIGEST,
  )

private const val HISTORICAL_MAX_CRITERIA = 999
private const val HISTORICAL_WORKER_LIMIT = 3
private const val HISTORICAL_TEXT_LENGTH = 8192
private const val HISTORICAL_ALIAS_LENGTH = 128
private const val HISTORICAL_REASON_LENGTH = 1024
private const val HISTORICAL_OUTPUT_REF_LENGTH = 512
