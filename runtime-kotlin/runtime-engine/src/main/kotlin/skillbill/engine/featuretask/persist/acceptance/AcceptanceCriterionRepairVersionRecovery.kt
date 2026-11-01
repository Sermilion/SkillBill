package skillbill.engine.featuretask.persist.acceptance

import me.tatarka.inject.annotations.Inject
import skillbill.application.diagnostics.RejectedOutputDiagnosticService
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRefusal
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairVersionPlan
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairVersionRecoveryResult
import skillbill.engine.featuretask.model.audit.toRepairRefusal
import skillbill.engine.featuretask.model.review.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.nextQuarantineRegenerationAttempt
import skillbill.engine.featuretask.phase.record.quarantineEntryPatch
import skillbill.error.shellcontent.invalidAcceptanceCriterionRepairStateSchema
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.toAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.audit.QUARANTINE_REJECTION_CLASS_ACCEPTANCE_REPAIR_VERSION
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot

@Inject
public class AcceptanceCriterionRepairVersionRecovery(
  private val boundary: RuntimeOwnedPersistenceBoundary,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  private val validator: FeatureTaskRuntimeWireArtifactValidator,
  private val adoption: AcceptanceCriterionRepairLegacyAdoption,
  private val recorder: FeatureTaskRuntimePhaseRecorder,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun recoverSupersededVersion(
    worker: AcquiredFeatureTaskRuntimeWorker,
  ): AcceptanceCriterionRepairVersionRecoveryResult {
    val plan = pendingPlan(worker) ?: return AcceptanceCriterionRepairVersionRecoveryResult.Unchanged
    val retained = retainOriginals(worker, plan)
    val result =
      boundary.requiredOwningWrite("serial_repair_version_recovery", "owner-fenced version regeneration") { work ->
        recoverInTransaction(work, worker, retained)
      }
    val committed =
      when (result) {
        is OwningTransactionResult.Committed -> result.value
        is OwningTransactionResult.Refused ->
          AcceptanceCriterionRepairVersionRecoveryResult.Refused(result.reason.toRepairRefusal())
      }
    if (committed is AcceptanceCriterionRepairVersionRecoveryResult.Recovered) {
      warn(worker, "regenerated", committed.families, retained.diagnosticIdentity == null)
    } else if (committed is AcceptanceCriterionRepairVersionRecoveryResult.Refused) {
      warn(worker, "refusal:${committed.reason.wireValue}", plan.families, retained.diagnosticIdentity == null)
    }
    return committed
  }

  private fun pendingPlan(worker: AcquiredFeatureTaskRuntimeWorker): AcceptanceCriterionRepairVersionPlan.Recovery? =
    boundary.requiredRead("serial_repair_version_probe", "stored contract versions") { work ->
      val row =
        work.workflowStates.get(WorkflowFamily.TASK_RUNTIME, worker.workflowId)
          ?: throw invalidAcceptanceCriterionRepairStateSchema(worker.workflowId, "The workflow is missing.")
      requireRecoverable(worker, adoption.versionRecovery(row.artifacts))
    }

  private fun requireRecoverable(
    worker: AcquiredFeatureTaskRuntimeWorker,
    plan: AcceptanceCriterionRepairVersionPlan,
  ): AcceptanceCriterionRepairVersionPlan.Recovery? =
    when (plan) {
      AcceptanceCriterionRepairVersionPlan.NotSuperseded -> null
      is AcceptanceCriterionRepairVersionPlan.Unrecoverable ->
        throw invalidAcceptanceCriterionRepairStateSchema(
          plan.family,
          "Stored contract version ${plan.version} cannot be regenerated for workflow ${worker.workflowId}.",
        )
      is AcceptanceCriterionRepairVersionPlan.Recovery -> plan
    }

  private fun retainOriginals(
    worker: AcquiredFeatureTaskRuntimeWorker,
    plan: AcceptanceCriterionRepairVersionPlan.Recovery,
  ): RetainedOriginals {
    val auditStep =
      worker.admittedExecution.plan.selectedStrategies.singleOrNull { it.slot == PhaseSlot.AUDIT }?.entryStep
        ?: worker.currentStepId
    val sha256 = RejectedOutputDiagnosticService.sha256(plan.rejectedRecord)
    val producer =
      boundary.requiredRead("serial_repair_version_producer", "audit phase attribution") { work ->
        work.workflowStates.get(WorkflowFamily.TASK_RUNTIME, worker.workflowId)
          ?.let { decodePhaseRecords(it.artifacts)[auditStep] }
      }
    val producingIteration = (producer?.attemptCount ?: 1).coerceAtLeast(1)
    val written =
      producer?.let {
        recorder.recordOwnedRejectedOutput(
          RejectedOutputDiagnosticRequest(
            workflowId = worker.workflowId,
            phaseId = auditStep,
            attempt = producingIteration,
            rule = DIAGNOSTIC_RULE,
            path = DIAGNOSTIC_PATH,
            reason = plan.detail,
            agentId = it.resolvedAgentId,
            model = it.launchedModel ?: UNKNOWN_MODEL,
            rawResponse = plan.rejectedRecord,
          ),
          worker.generation.toInt(),
          worker.admittedExecution,
        )
      }
    return RetainedOriginals(
      auditStep,
      producingIteration,
      sha256,
      plan.rejectedRecord.size.toLong(),
      (written as? FeatureTaskRuntimeRejectedOutputWrite.Written)?.identity,
    )
  }

  private fun recoverInTransaction(
    work: UnitOfWork,
    worker: AcquiredFeatureTaskRuntimeWorker,
    retained: RetainedOriginals,
  ): AcceptanceCriterionRepairVersionRecoveryResult {
    val states = work.workflowStates
    if (!worker.admits(states)) {
      return AcceptanceCriterionRepairVersionRecoveryResult.Refused(AcceptanceCriterionRepairRefusal.OWNER_CHANGED)
    }
    val record =
      states.get(WorkflowFamily.TASK_RUNTIME, worker.workflowId)
        ?: throw invalidAcceptanceCriterionRepairStateSchema(worker.workflowId, "The workflow is missing.")
    val plan =
      requireRecoverable(worker, adoption.versionRecovery(record.artifacts))
        ?: return AcceptanceCriterionRepairVersionRecoveryResult.Unchanged
    if (RejectedOutputDiagnosticService.sha256(plan.rejectedRecord) != retained.sha256) {
      return AcceptanceCriterionRepairVersionRecoveryResult.Refused(
        AcceptanceCriterionRepairRefusal.SUPERSEDED_VERSION_CHANGED,
      )
    }
    plan.families.forEach { validateRegenerated(plan, it) }
    val attempt = record.step(worker.currentStepId)?.attemptCount ?: 1
    val entry =
      FeatureTaskRuntimeQuarantineEntry(
        producingPhaseId = retained.auditStep,
        consumingPhaseId = worker.currentStepId,
        producingIteration = retained.producingIteration,
        rejectionClass = QUARANTINE_REJECTION_CLASS_ACCEPTANCE_REPAIR_VERSION,
        rejectionDetail = plan.detail,
        regenerationAttempt =
          nextQuarantineRegenerationAttempt(
            record.artifacts,
            validator,
            retained.auditStep,
            retained.producingIteration,
            QUARANTINE_REJECTION_CLASS_ACCEPTANCE_REPAIR_VERSION,
          ),
        quarantinedAtIteration = attempt.coerceAtLeast(1),
        diagnosticIdentity = retained.diagnosticIdentity,
        rejectedRecordByteSize = retained.byteSize,
        rejectedRecordSha256 = retained.sha256,
        diagnosticDegraded = retained.diagnosticIdentity == null,
      )
    val patch = quarantineEntryPatch(record.artifacts, entry, validator)
    workflowPersistence.persistAcquiredRepairVersionRecovery(states, record, plan, patch)
    return AcceptanceCriterionRepairVersionRecoveryResult.Recovered(plan.families)
  }

  private fun validateRegenerated(
    plan: AcceptanceCriterionRepairVersionPlan.Recovery,
    label: String,
  ) {
    val wire = FeatureTaskRuntimeWorkflowArtifactMap.from(plan.regenerated[label])
    val repair = label == DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR.label()
    val kind =
      if (repair) {
        FeatureTaskRuntimeWireArtifactKind.ACCEPTANCE_CRITERION_REPAIR_STATE
      } else {
        FeatureTaskRuntimeWireArtifactKind.ACCEPTANCE_CRITERION_AUDIT_RESULT
      }
    validator.validate(kind, wire, label)
    if (repair) wire.toAcceptanceCriterionRepairState(label)
  }

  private fun warn(
    worker: AcquiredFeatureTaskRuntimeWorker,
    kind: String,
    families: List<String>,
    degraded: Boolean,
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=serial_repair_version_recovery workflow=${worker.workflowId.take(MAX_DIAGNOSTIC_ID)} " +
        "record_kind=$kind families=${families.joinToString(",")} " +
        "value_expected=$ACCEPTANCE_CRITERION_REPAIR_STATE_CONTRACT_VERSION " +
        "value_used=$ACCEPTANCE_CRITERION_REPAIR_STATE_PREVIOUS_CONTRACT_VERSION diagnostic_degraded=$degraded",
    )
  }

  private class RetainedOriginals(
    val auditStep: String,
    val producingIteration: Int,
    val sha256: String,
    val byteSize: Long,
    val diagnosticIdentity: String?,
  )

  private companion object {
    const val DIAGNOSTIC_RULE = "reconciliation-acceptance_criterion_repair_contract_version"
    const val DIAGNOSTIC_PATH = "/contract_version"
    const val UNKNOWN_MODEL = "unknown"
    const val MAX_DIAGNOSTIC_ID = 128
  }
}
