package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_AUDIT_RESULT_PREVIOUS_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_FIRST_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidAcceptanceCriterionRepairStateSchema
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionAuditResultMode
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

fun FeatureTaskRuntimeWorkflowArtifactMap.supersededAcceptanceCriterionRepairStateVersion(): String? =
  if (!isObject) {
    null
  } else {
    (this[Keys.CONTRACT_VERSION] as? String)
      ?.takeIf {
        it == ACCEPTANCE_CRITERION_REPAIR_STATE_FIRST_CONTRACT_VERSION ||
          it == ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
      }
  }

fun FeatureTaskRuntimeWorkflowArtifactMap.isPreviousVersionAcceptanceCriterionAuditResult(): Boolean =
  isObject && this[Keys.CONTRACT_VERSION] == ACCEPTANCE_CRITERION_AUDIT_RESULT_PREVIOUS_CONTRACT_VERSION

fun FeatureTaskRuntimeWorkflowArtifactMap.upgradeRepairStateFromPrevious(): FeatureTaskRuntimeWorkflowArtifactMap? =
  when (this[Keys.CONTRACT_VERSION]) {
    ACCEPTANCE_CRITERION_REPAIR_STATE_FIRST_CONTRACT_VERSION ->
      upgradeRepairStateFromFirst()?.upgradeRepairStateFromSecond()
    ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION -> upgradeRepairStateFromSecond()
    else -> null
  }

private fun FeatureTaskRuntimeWorkflowArtifactMap.upgradeRepairStateFromFirst():
  FeatureTaskRuntimeWorkflowArtifactMap? {
  val tracePending = this[Keys.STAGE] == AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED.wireValue
  if (containsKey(Keys.INTERACTION_TRACE) || tracePending) return null
  return FeatureTaskRuntimeWorkflowArtifactMap.from(
    this +
      mapOf(
        Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION,
        Keys.INTERACTION_TRACE to emptyInteractionTraceWire(),
      ),
  )
}

private fun FeatureTaskRuntimeWorkflowArtifactMap.upgradeRepairStateFromSecond():
  FeatureTaskRuntimeWorkflowArtifactMap? {
  if (this[Keys.CONTRACT_VERSION] != ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION) return null
  val upgraded = toMutableMap()
  upgraded[Keys.CONTRACT_VERSION] = ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION
  upgraded[Keys.CRITERIA] = (this[Keys.CRITERIA] as? List<*>)?.map { raw ->
    val criterion = raw.asStringKeyedMap() ?: return@map raw
    criterion +
      mapOf(
        Keys.GAPS to emptyList<Any?>(),
        Keys.PLAN_GENERATION to 0,
        Keys.ESCALATED to false,
      )
  } ?: this[Keys.CRITERIA]
  upgraded[Keys.OUTCOMES] = (this[Keys.OUTCOMES] as? List<*>)?.map { raw ->
    val outcome = raw.asStringKeyedMap() ?: return@map raw
    outcome +
      mapOf(
        Keys.CHARGED to false,
        Keys.OPEN_GAPS_BEFORE to 0,
        Keys.OPEN_GAPS_AFTER to 0,
      )
  } ?: this[Keys.OUTCOMES]
  upgraded[Keys.CHARGED_ATTEMPTS] = emptyMap<String, Int>()
  upgraded[Keys.LATE_FINDINGS] = emptyList<Any?>()
  upgraded[Keys.REPAIR_CHANGED_PATHS] = emptyList<String>()
  upgraded.remove(Keys.UNSUCCESSFUL_TOTALS)
  upgraded.remove(Keys.BUDGET_START_OUTCOME_INDEX)
  if (this[Keys.STAGE] in
    setOf(
      AcceptanceCriterionRepairStage.REPAIR_RESERVED.wireValue,
      AcceptanceCriterionRepairStage.REPAIR_COMPLETED_AWAITING_TARGETED_VERIFICATION.wireValue,
      AcceptanceCriterionRepairStage.BUDGET_BLOCKED.wireValue,
    )
  ) {
    val pendingAuditRefs = this[Keys.PENDING_AUDIT_REFS] as? List<*> ?: emptyList<Any?>()
    upgraded[Keys.RESERVATION] = null
    upgraded[Keys.COMPLETED_REPAIR_EVIDENCE_REF] = null
    upgraded[Keys.ADMITTED_TREE_DIGEST] = null
    upgraded[Keys.ADMITTED_CHECKPOINT_ID] = null
    upgraded[Keys.PENDING_CLARIFICATION_QUESTION] = null
    upgraded[Keys.STAGE] =
      if (pendingAuditRefs.isEmpty()) {
        AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED.wireValue
      } else {
        AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED.wireValue
      }
  }
  return FeatureTaskRuntimeWorkflowArtifactMap.from(upgraded)
}

fun FeatureTaskRuntimeWorkflowArtifactMap.toUpgradedAcceptanceCriterionRepairState(
  sourceLabel: String,
): AcceptanceCriterionRepairState {
  if (this[Keys.CONTRACT_VERSION] == ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION) {
    return toAcceptanceCriterionRepairState(sourceLabel)
  }
  val storedVersion = supersededAcceptanceCriterionRepairStateVersion()
  val upgraded = upgradeRepairStateFromPrevious()
  if (upgraded == null) {
    throw invalidAcceptanceCriterionRepairStateSchema(
      sourceLabel,
      "repair state at contract ${storedVersion ?: this[Keys.CONTRACT_VERSION]} cannot be upgraded.",
    )
  }
  return upgraded.toAcceptanceCriterionRepairState(sourceLabel)
}

private fun Any?.asStringKeyedMap(): Map<String, Any?>? {
  val map = this as? Map<*, *> ?: return null
  if (map.keys.any { it !is String }) return null
  return map.entries.associate { (key, value) -> key as String to value }
}

fun FeatureTaskRuntimeWorkflowArtifactMap.upgradeAuditResultFromPrevious(): FeatureTaskRuntimeWorkflowArtifactMap? {
  val carriesCurrentOnlyFacts =
    containsKey(Keys.INTERACTIONS) || containsKey(Keys.RECHECKS) ||
      this[Keys.MODE] == AcceptanceCriterionAuditResultMode.INTERACTION_TRACE.wireValue
  if (!isPreviousVersionAcceptanceCriterionAuditResult() || carriesCurrentOnlyFacts) return null
  return FeatureTaskRuntimeWorkflowArtifactMap.from(
    this +
      mapOf(
        Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_AUDIT_RESULT_CONTRACT_VERSION,
        Keys.INTERACTIONS to emptyList<Any?>(),
        Keys.RECHECKS to emptyList<Any?>(),
      ),
  )
}
