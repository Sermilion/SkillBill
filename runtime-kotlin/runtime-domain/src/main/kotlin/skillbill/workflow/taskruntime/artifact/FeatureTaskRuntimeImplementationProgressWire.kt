package skillbill.workflow.taskruntime.artifact

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidFeatureTaskRuntimeImplementationProgressSchema
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.validAcceptanceCriterionRepairBudgets
import skillbill.workflow.taskruntime.model.audit.validateAcceptanceCriterionEvidence
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import java.math.BigDecimal
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionProgressPayloadKeys as ProgressKeys
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimeImplementationProgressPayloadKeys as Keys

fun FeatureTaskRuntimeImplementationProgress.asImplementationProgressWireMap(): FeatureTaskRuntimeWorkflowArtifactMap =
  FeatureTaskRuntimeWorkflowArtifactMap.from(
    linkedMapOf(
      Keys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_CONTRACT_VERSION,
      ProgressKeys.REPAIR_BUDGETS to repairBudgets.map { AcceptanceCriterionRepairAccountingWire.budget(it) },
      ProgressKeys.REPAIR_ACCOUNTING to
        criterionIds.map {
          AcceptanceCriterionRepairAccountingWire.accounting(accounting(it))
        },
      ProgressKeys.WORKER_RESERVATION_ID to workerReservationId,
      Keys.CRITERION_IDS to criterionIds,
      Keys.CURRENT_INDEX to currentIndex,
      Keys.WORKER_COUNT to workerCount,
      Keys.LAUNCH_COUNT to launchCount,
      Keys.CLOSED_IDS to closedIds,
      ProgressKeys.COMPLETED_MUTATIONS to completedMutations.map { AcceptanceCriterionEvidenceWire.mutation(it) },
      ProgressKeys.INDEPENDENT_CHECKS to independentChecks.map { AcceptanceCriterionEvidenceWire.checked(it) },
    ),
  )

fun FeatureTaskRuntimeWorkflowArtifactMap.toImplementationProgress(
  sourceLabel: String,
): FeatureTaskRuntimeImplementationProgress {
  fun invalid(reason: String): Nothing =
    throw invalidFeatureTaskRuntimeImplementationProgressSchema(sourceLabel, reason)
  if (!isObject) invalid("progress must be an object.")
  val unexpected = keys - PROGRESS_FIELDS
  if (unexpected.isNotEmpty()) invalid("progress carries unsupported fields ${unexpected.sorted()}.")
  val missing = PROGRESS_FIELDS - keys
  if (missing.isNotEmpty()) invalid("progress lacks required fields ${missing.sorted()}.")
  if (this[Keys.CONTRACT_VERSION] != FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS_CONTRACT_VERSION) {
    invalid("progress has no supported contract version.")
  }
  val criterionIds = ids(Keys.CRITERION_IDS) ?: invalid("${Keys.CRITERION_IDS} must be an array of strings.")
  val closedIds = ids(Keys.CLOSED_IDS) ?: invalid("${Keys.CLOSED_IDS} must be an array of strings.")
  val currentIndex = exactInt(Keys.CURRENT_INDEX) ?: invalid("${Keys.CURRENT_INDEX} must be an integer.")
  val workerCount = exactInt(Keys.WORKER_COUNT) ?: invalid("${Keys.WORKER_COUNT} must be an integer.")
  val launchCount = exactInt(Keys.LAUNCH_COUNT) ?: invalid("${Keys.LAUNCH_COUNT} must be an integer.")
  FeatureTaskRuntimeImplementationProgress.violation(criterionIds, currentIndex, workerCount, launchCount)
    ?.let(::invalid)
  if (closedIds != criterionIds.take(currentIndex)) {
    invalid("${Keys.CLOSED_IDS} must equal the ${Keys.CRITERION_IDS} prefix before ${Keys.CURRENT_INDEX}.")
  }
  val mutations = evidenceRows(ProgressKeys.COMPLETED_MUTATIONS).map { AcceptanceCriterionEvidenceWire.mutation(it) }
  val checks = evidenceRows(ProgressKeys.INDEPENDENT_CHECKS).map { AcceptanceCriterionEvidenceWire.checked(it) }
  validateAcceptanceCriterionEvidence(mutations, checks)
  if (mutations.any { it.criterionRef !in criterionIds }) invalid("Retained mutation names an unknown criterion")
  val budgets = AcceptanceCriterionRepairAccountingWire.budgets(this)
  if (!validAcceptanceCriterionRepairBudgets(budgets, mutations, criterionIds)) {
    invalid("Repair budget identity or origin is invalid.")
  }
  val reservationId = workerReservationId(sourceLabel)
  val restored =
    FeatureTaskRuntimeImplementationProgress(
      criterionIds,
      currentIndex,
      workerCount,
      launchCount,
      mutations,
      checks,
      budgets,
      reservationId,
    )
  AcceptanceCriterionRepairAccountingWire.validateAccounting(
    this,
    restored.criterionIds.map { restored.accounting(it) },
  )
  return restored
}

private fun FeatureTaskRuntimeWorkflowArtifactMap.workerReservationId(sourceLabel: String): String? {
  val raw = this[ProgressKeys.WORKER_RESERVATION_ID] ?: return null
  val id = raw as? String
  if (id == null || id.isBlank() || id.length > AcceptanceCriterionRepairState.MAX_REPAIR_ID_LENGTH) {
    throw invalidFeatureTaskRuntimeImplementationProgressSchema(sourceLabel, "Invalid worker reservation identity")
  }
  return id
}

private fun FeatureTaskRuntimeWorkflowArtifactMap.ids(key: String): List<String>? =
  (this[key] as? List<*>)?.map { it as? String ?: return null }

private fun FeatureTaskRuntimeWorkflowArtifactMap.exactInt(key: String): Int? {
  val number = this[key] as? Number ?: return null
  return try {
    BigDecimal(number.toString()).intValueExact()
  } catch (_: NumberFormatException) {
    null
  } catch (_: ArithmeticException) {
    null
  }
}

private val PROGRESS_FIELDS =
  setOf(
    ProgressKeys.REPAIR_BUDGETS,
    ProgressKeys.REPAIR_ACCOUNTING,
    ProgressKeys.WORKER_RESERVATION_ID,
    ProgressKeys.COMPLETED_MUTATIONS,
    ProgressKeys.INDEPENDENT_CHECKS,
    Keys.CONTRACT_VERSION,
    Keys.CRITERION_IDS,
    Keys.CURRENT_INDEX,
    Keys.WORKER_COUNT,
    Keys.LAUNCH_COUNT,
    Keys.CLOSED_IDS,
  )
