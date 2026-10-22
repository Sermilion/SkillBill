package skillbill.engine.diagnostics.block

import me.tatarka.inject.annotations.Inject
import skillbill.ports.blockincident.BlockedWorkflowWithoutIncident
import skillbill.ports.blockincident.BlockIncidentRepository
import skillbill.ports.blockincident.BlockIncidentSpool
import skillbill.ports.blockincident.BlockIncidentSpoolEntry
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCorrelatedField
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFieldAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentIdentityAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentReasonRetention
import java.time.Clock
import java.time.Instant

/**
 * Runs one bounded block-incident reconciliation pass when a run entry starts, so incidents left pending or
 * investigating by an earlier process reach their deadline state without a separate command. A failed pass is
 * reported and the run continues.
 */
@Inject
class BlockIncidentEntryReconciler(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
  private val spool: BlockIncidentSpool,
  private val worker: BlockIncidentInvestigationWorker,
  private val capture: BlockIncidentCapture,
) {
  fun reconcileOnEntry() {
    if (!database.databaseExists()) return
    importSpool()
    try {
      val reconciled = database.transaction { unitOfWork ->
        BlockIncidentService.reconcilePending(unitOfWork.blockIncidents, clock, ENTRY_BATCH)
      }
      if (reconciled.isNotEmpty()) diagnostics.info("Reconciled ${reconciled.size} pending block incident(s).")
    } catch (failure: RuntimeException) {
      diagnostics.warning("Block incident reconciliation failed; continuing the run.", failure)
    }
    try {
      worker.runEligible(ENTRY_BATCH)
    } catch (failure: RuntimeException) {
      diagnostics.warning("Block incident investigation failed; continuing the run.", failure)
    }
    reconcileOrphanedBlocks()
  }

  fun investigateAfterEntry(entryStartedAt: Instant) = worker.runEligible(ENTRY_BATCH, entryStartedAt)

  private fun importSpool() {
    val entries = try {
      spool.pending(ENTRY_BATCH)
    } catch (failure: RuntimeException) {
      diagnostics.warning("Block incident spool scan failed; continuing the run.", failure)
      return
    }
    try {
      database.transaction { unit -> entries.forEach { entry -> entry.importInto(unit.blockIncidents) } }
      entries.forEach { entry -> spool.remove(entry.recordId) }
    } catch (failure: RuntimeException) {
      diagnostics.warning("Block incident spool import failed; records remain for retry.", failure)
    }
  }

  private fun reconcileOrphanedBlocks() {
    val activatedAt = try {
      database.read { it.blockIncidents.rolloutActivatedAt() } ?: return
    } catch (failure: RuntimeException) {
      diagnostics.warning("Block incident rollout marker could not be read.", failure)
      return
    }
    val blocked = try {
      database.read { it.blockIncidents.blockedWithoutIncident(activatedAt, ENTRY_BATCH) }
    } catch (failure: RuntimeException) {
      diagnostics.warning("Uncaptured blocked workflow scan failed.", failure)
      return
    }
    blocked.forEach { workflow ->
      val alias = when (workflow.kind) {
        BlockedWorkflowWithoutIncident.Kind.GOAL ->
          BlockIncidentAliases.goal(workflow.workflowId, workflow.phaseId ?: "unknown", workflow.attempt?.toLong() ?: 1L)
        BlockedWorkflowWithoutIncident.Kind.PHASE ->
          BlockIncidentAliases.standalonePhase(workflow.workflowId, workflow.phaseId ?: "unknown")
      }
      capture.capture(
        BlockIncidentObservation(
          workflowId = workflow.workflowId,
          parentWorkflowId = workflow.parentWorkflowId,
          phaseId = workflow.phaseId,
          attempt = workflow.attempt,
          reason = workflow.blockedReason,
          alias = alias,
          generation = null,
          origin = if (workflow.kind == BlockedWorkflowWithoutIncident.Kind.GOAL) {
            BlockIncidentOrigin.ACCEPTED_GOAL_BLOCK
          } else {
            BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK
          },
          exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
          unavailableFields = mapOf(
            BlockIncidentCorrelatedField.PHASE_RUN_ID to BlockIncidentFieldAvailability.NOT_APPLICABLE,
            BlockIncidentCorrelatedField.INVOCATION_ID to BlockIncidentFieldAvailability.NOT_CAPTURED,
            BlockIncidentCorrelatedField.EXECUTION_ID to BlockIncidentFieldAvailability.NOT_APPLICABLE,
            BlockIncidentCorrelatedField.ATTEMPT to if (workflow.attempt == null) {
              BlockIncidentFieldAvailability.NOT_CAPTURED
            } else {
              BlockIncidentFieldAvailability.AVAILABLE
            },
          ),
        ),
      )
    }
  }

  private fun BlockIncidentSpoolEntry.importInto(repository: BlockIncidentRepository) {
    val record = record
    val values = mapOf(
      BlockIncidentCorrelatedField.WORKFLOW_ID to record.workflowId,
      BlockIncidentCorrelatedField.PARENT_WORKFLOW_ID to record.parentWorkflowId,
      BlockIncidentCorrelatedField.PHASE_ID to record.phaseId,
      BlockIncidentCorrelatedField.INVOCATION_ID to record.invocationId,
      BlockIncidentCorrelatedField.ATTEMPT to record.attempt,
      BlockIncidentCorrelatedField.FAILURE_CODE to record.failureCode,
      BlockIncidentCorrelatedField.THROWABLE_TYPE to record.throwableType,
      BlockIncidentCorrelatedField.EXIT_CODE to record.exitCode,
    )
    val fieldAvailability = BlockIncidentCorrelatedField.entries.associateWith { field ->
      if (field == BlockIncidentCorrelatedField.REASON || values[field] != null) {
        BlockIncidentFieldAvailability.AVAILABLE
      } else {
        BlockIncidentFieldAvailability.NOT_CAPTURED
      }
    }
    val availableIdentities = listOf(
      BlockIncidentCorrelatedField.WORKFLOW_ID,
      BlockIncidentCorrelatedField.PARENT_WORKFLOW_ID,
      BlockIncidentCorrelatedField.PHASE_ID,
      BlockIncidentCorrelatedField.INVOCATION_ID,
    ).count { fieldAvailability.getValue(it) == BlockIncidentFieldAvailability.AVAILABLE }
    val occurrence = BlockIncidentOccurrence(
      occurrenceToken = record.occurrenceToken,
      workflowId = record.workflowId,
      parentWorkflowId = record.parentWorkflowId,
      phaseId = record.phaseId,
      phaseRunId = null,
      invocationId = record.invocationId,
      executionId = null,
      attempt = record.attempt,
      generation = null,
      origin = record.origin,
      reason = record.reason,
      failureCode = record.failureCode,
      failureDisposition = record.failureDisposition,
      throwableType = record.throwableType,
      exitCode = record.exitCode,
      observedAt = record.observedAt,
      evidenceAvailability = BlockIncidentEvidenceAvailability.FALLBACK_SPOOL,
      identityAvailability = when (availableIdentities) {
        0 -> BlockIncidentIdentityAvailability.UNAVAILABLE
        4 -> BlockIncidentIdentityAvailability.AVAILABLE
        else -> BlockIncidentIdentityAvailability.PARTIAL
      },
      exitBehavior = when {
        record.exitCode != null -> BlockIncidentExitBehavior.EXITED_WITH_CODE
        record.throwableType != null -> BlockIncidentExitBehavior.THROWN
        else -> BlockIncidentExitBehavior.RETURNED_FAILURE
      },
      fieldAvailability = fieldAvailability,
      reasonRetention = BlockIncidentReasonRetention.COMPLETE,
    )
    repository.createOrLink(
      occurrence,
      record.alias,
      clock.instant().plus(BlockIncidentService.INVESTIGATION_PICKUP_WINDOW),
    )
  }

  private companion object {
    const val ENTRY_BATCH = 16
  }
}
