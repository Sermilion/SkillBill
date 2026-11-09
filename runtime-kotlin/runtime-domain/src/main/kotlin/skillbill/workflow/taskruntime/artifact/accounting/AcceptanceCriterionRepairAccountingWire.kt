package skillbill.workflow.taskruntime.artifact.accounting

import skillbill.error.shellcontent.invalidAcceptanceCriterionRepairStateSchema
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAccounting
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairBudget
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairLaunch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionProgressPayloadKeys as ProgressKeys
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

internal object AcceptanceCriterionRepairAccountingWire {
  fun budget(value: AcceptanceCriterionRepairBudget): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(
      mapOf(
        Keys.CRITERION_REF to value.criterionRef,
        ProgressKeys.RETRY_IDENTITY to value.retryIdentity,
        ProgressKeys.START_MUTATION_INDEX to value.startMutationIndex,
      ),
    )

  fun budgets(value: FeatureTaskRuntimeWorkflowArtifactMap): List<AcceptanceCriterionRepairBudget> =
    value.evidenceRows(ProgressKeys.REPAIR_BUDGETS).map {
      it.closed(setOf(Keys.CRITERION_REF, ProgressKeys.RETRY_IDENTITY, ProgressKeys.START_MUTATION_INDEX))
      AcceptanceCriterionRepairBudget(
        it.text(Keys.CRITERION_REF),
        it.text(ProgressKeys.RETRY_IDENTITY),
        it.integer(ProgressKeys.START_MUTATION_INDEX),
      )
    }

  fun accounting(value: AcceptanceCriterionRepairAccounting): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(
      mapOf(
        Keys.CRITERION_REF to value.criterionRef,
        ProgressKeys.BUDGET_IDENTITY to value.budgetIdentity,
        ProgressKeys.COMPLETED_ATTEMPT_IDS to value.completedAttemptIds,
        ProgressKeys.NO_PROGRESS_OUTCOMES to value.noProgressOutcomes,
        ProgressKeys.PENDING_CHECK_ATTEMPT_IDS to value.pendingCheckAttemptIds,
        ProgressKeys.ESCALATION_REQUIRED to value.escalationRequired,
        ProgressKeys.TRIGGERING_ATTEMPT_IDS to value.triggeringAttemptIds,
        ProgressKeys.ESCALATED_ATTEMPT_IDS to value.escalatedAttemptIds,
        ProgressKeys.EVIDENCE_MISSING to value.evidenceMissing,
      ),
    )

  fun validateAccounting(
    value: FeatureTaskRuntimeWorkflowArtifactMap,
    expected: List<AcceptanceCriterionRepairAccounting>,
  ) {
    val actual =
      value.evidenceRows(ProgressKeys.REPAIR_ACCOUNTING).map {
        it.closed(
          setOf(
            Keys.CRITERION_REF,
            ProgressKeys.BUDGET_IDENTITY,
            ProgressKeys.COMPLETED_ATTEMPT_IDS,
            ProgressKeys.NO_PROGRESS_OUTCOMES,
            ProgressKeys.PENDING_CHECK_ATTEMPT_IDS,
            ProgressKeys.ESCALATION_REQUIRED,
            ProgressKeys.TRIGGERING_ATTEMPT_IDS,
            ProgressKeys.ESCALATED_ATTEMPT_IDS,
            ProgressKeys.EVIDENCE_MISSING,
          ),
        )
        AcceptanceCriterionRepairAccounting(
          it.text(Keys.CRITERION_REF),
          it.text(ProgressKeys.BUDGET_IDENTITY),
          it.strings(ProgressKeys.COMPLETED_ATTEMPT_IDS),
          it.integer(ProgressKeys.NO_PROGRESS_OUTCOMES),
          it.strings(ProgressKeys.PENDING_CHECK_ATTEMPT_IDS),
          it.flag(ProgressKeys.ESCALATION_REQUIRED),
          it.strings(ProgressKeys.TRIGGERING_ATTEMPT_IDS),
          it.strings(ProgressKeys.ESCALATED_ATTEMPT_IDS),
          it.flag(ProgressKeys.EVIDENCE_MISSING),
        )
      }
    if (actual != expected) invalid("Accounting does not match retained terminal and independent-check evidence")
  }

  fun launch(value: AcceptanceCriterionRepairLaunch): FeatureTaskRuntimeWorkflowArtifactMap =
    FeatureTaskRuntimeWorkflowArtifactMap.from(
      mapOf(
        ProgressKeys.REPAIR_ATTEMPT_ID to value.attemptId,
        ProgressKeys.PROVIDER to value.provider,
        ProgressKeys.EFFECTIVE_MODEL to value.effectiveModel,
        ProgressKeys.EFFECTIVE_EFFORT to value.effectiveEffort,
        ProgressKeys.EXECUTION_PLAN_REF to value.executionPlanRef,
        ProgressKeys.LAUNCH_REF to value.launchRef,
        ProgressKeys.REASONING to value.reasoning,
      ),
    )

  fun launch(value: FeatureTaskRuntimeWorkflowArtifactMap): AcceptanceCriterionRepairLaunch {
    value.closed(
      setOf(
        ProgressKeys.REPAIR_ATTEMPT_ID,
        ProgressKeys.PROVIDER,
        ProgressKeys.EFFECTIVE_MODEL,
        ProgressKeys.EFFECTIVE_EFFORT,
        ProgressKeys.EXECUTION_PLAN_REF,
        ProgressKeys.LAUNCH_REF,
        ProgressKeys.REASONING,
      ),
    )
    return AcceptanceCriterionRepairLaunch(
      value.text(ProgressKeys.REPAIR_ATTEMPT_ID),
      value.text(ProgressKeys.PROVIDER),
      value.text(ProgressKeys.EFFECTIVE_MODEL),
      value.text(ProgressKeys.EFFECTIVE_EFFORT),
      value.text(ProgressKeys.EXECUTION_PLAN_REF),
      value.text(ProgressKeys.LAUNCH_REF),
      value.flag(ProgressKeys.REASONING),
    )
  }

  private fun FeatureTaskRuntimeWorkflowArtifactMap.closed(fields: Set<String>) {
    if (keys != fields) invalid("Unexpected or missing accounting fields")
  }

  private fun FeatureTaskRuntimeWorkflowArtifactMap.text(key: String): String =
    this[key] as? String ?: invalid("$key must be a string")

  private fun FeatureTaskRuntimeWorkflowArtifactMap.integer(key: String): Int {
    val raw = this[key]
    return when (raw) {
      is Int -> raw
      is Long -> raw.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
      else -> null
    } ?: invalid("$key must be an integer")
  }

  private fun FeatureTaskRuntimeWorkflowArtifactMap.flag(key: String): Boolean =
    this[key] as? Boolean ?: invalid("$key must be a boolean")

  private fun FeatureTaskRuntimeWorkflowArtifactMap.strings(key: String): List<String> =
    (this[key] as? List<*>)?.map { it as? String ?: invalid("$key must contain strings") }
      ?: invalid("$key must be an array")

  private fun invalid(reason: String): Nothing =
    throw invalidAcceptanceCriterionRepairStateSchema("repair-accounting", reason)
}

internal fun FeatureTaskRuntimeWorkflowArtifactMap.evidenceRows(
  key: String,
): List<FeatureTaskRuntimeWorkflowArtifactMap> =
  if (!containsKey(key)) {
    emptyList()
  } else {
    (this[key] as? List<*>)?.map { FeatureTaskRuntimeWorkflowArtifactMap.from(it) }
      ?: throw invalidAcceptanceCriterionRepairStateSchema("independent-evidence", "$key must be an array")
  }
