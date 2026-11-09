package skillbill.workflow.taskruntime.model.audit.history

import skillbill.workflow.model.repair.FocusedRepairStrategy
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionCheckedMutation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionCompletedMutation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAccounting
import skillbill.workflow.taskruntime.model.audit.terminal
import skillbill.workflow.taskruntime.model.audit.validateAcceptanceCriterionEvidence

data class AcceptanceCriterionMigrationContext(
  val family: String,
  val criterionRef: String,
  val budgetIdentity: String,
  val historicalCharges: Int,
  val attributionUnknown: Boolean,
  val reconciliationRequired: Boolean,
  val escalationOwed: Boolean,
) {
  fun admission(
    accounting: AcceptanceCriterionRepairAccounting,
    mutations: List<AcceptanceCriterionCompletedMutation>,
    checks: List<AcceptanceCriterionCheckedMutation>,
    renewedBudget: Boolean = false,
  ): AcceptanceCriterionMigrationAdmission {
    require(accounting.criterionRef == criterionRef)
    validateAcceptanceCriterionEvidence(mutations, checks)
    val charges = if (renewedBudget) 0 else historicalCharges
    return when {
      accounting.pendingCheckAttemptIds.isNotEmpty() -> AcceptanceCriterionMigrationAdmission.CHECK_REQUIRED
      reconciliationRequired || accounting.evidenceMissing ->
        AcceptanceCriterionMigrationAdmission.RECONCILIATION_REQUIRED
      (escalationOwed || accounting.escalatedOpportunityOwed) && !escalationDischarged(mutations, checks) ->
        AcceptanceCriterionMigrationAdmission.ESCALATED_MUTATION
      charges.toLong() + accounting.noProgressOutcomes >= FocusedRepairStrategy.MAX_UNSUCCESSFUL_OUTCOMES ->
        AcceptanceCriterionMigrationAdmission.EXHAUSTED
      else -> AcceptanceCriterionMigrationAdmission.MUTATION
    }
  }

  private fun escalationDischarged(
    mutations: List<AcceptanceCriterionCompletedMutation>,
    checks: List<AcceptanceCriterionCheckedMutation>,
  ): Boolean =
    mutations.any { mutation ->
      mutation.criterionRef == criterionRef && mutation.terminal &&
        mutation.launchAttribution?.let { it.reasoning && it.validFor(mutation.repairAttemptId) } == true &&
        checks.any { check -> check.mutation == mutation }
    }
}

enum class AcceptanceCriterionMigrationAdmission {
  CHECK_REQUIRED,
  RECONCILIATION_REQUIRED,
  ESCALATED_MUTATION,
  EXHAUSTED,
  MUTATION,
}

data class AcceptanceCriterionMigrationStep(val family: String, val sourceVersion: String, val targetVersion: String)

data class AcceptanceCriterionMigrationProvenance(
  val workflowId: String,
  val originalArtifactsJson: String,
  val sourceDigest: String,
  val steps: List<AcceptanceCriterionMigrationStep>,
  val contexts: List<AcceptanceCriterionMigrationContext>,
)
