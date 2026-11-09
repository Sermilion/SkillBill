package skillbill.workflow.taskruntime.model.audit.history

import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairDependency
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionAuditResultMode
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionEntry
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionRecheckEntry

data class AcceptanceCriterionHistoricalAuditResult(
  val contractVersion: String,
  val mode: AcceptanceCriterionAuditResultMode,
  val catalogId: String,
  val specDigest: String,
  val executionId: String,
  val treeDigest: String,
  val checkpointId: String,
  val producingOutputId: String,
  val producingOutputDigest: String,
  val expectedRevision: Long?,
  val currentCriterionRef: String?,
  val reservationId: String?,
  val criteria: List<AcceptanceCriterionHistoricalAuditFact>,
  val dependencies: List<AcceptanceCriterionRepairDependency>,
  val regressedRefs: List<String>,
  val edgeChanges: List<AcceptanceCriterionRepairDependency>,
  val interactions: List<AcceptanceCriterionInteractionEntry>?,
  val rechecks: List<AcceptanceCriterionRecheckEntry>?,
)

data class AcceptanceCriterionHistoricalAuditFact(
  val criterionRef: String,
  val sourceDisposition: AcceptanceCriterionSourceDisposition,
  val validationObligation: AcceptanceCriterionValidationObligation,
  val validationOwnerAvailable: Boolean,
  val evidenceRef: String,
)
