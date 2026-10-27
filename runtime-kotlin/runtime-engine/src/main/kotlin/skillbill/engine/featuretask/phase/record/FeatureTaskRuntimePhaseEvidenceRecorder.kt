package skillbill.engine.featuretask.phase.record

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.featuretask.model.phase.AppendCheckpointIdentityArgs
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.core.resolvedBranchFromWorkflowArtifacts
import skillbill.error.shellcontent.invalidWorkflowStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.taskruntime.artifact.asCheckpointIdentitiesArtifactEntry
import skillbill.workflow.taskruntime.artifact.asQuarantineWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodeCheckpointIdentitiesFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeQuarantineEntriesFromArtifact
import skillbill.workflow.taskruntime.artifact.resolvedBranchFromWorkflowArtifacts
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeAppendCheckpointIdentity
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeCheckpointRefName
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeOwnedPathDigest
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.retainPhaseLedgerEvidence
import java.time.Clock

class FeatureTaskRuntimePhaseEvidenceRecorder(
  val database: DatabaseSessionFactory,
  val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  val quarantineValidator: FeatureTaskRuntimeWireArtifactValidator,
  val clock: Clock,
) {
  fun appendOwnedLedgerEntry(
    request: FeatureTaskRuntimePhaseLedgerRequest,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean =
    database.transaction { unitOfWork ->
      if (!requireCurrentWorker(admitted, unitOfWork.workflowStates, request.workflowId)) {
        return@transaction false
      }
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, request.workflowId)
          ?: return@transaction false
      val previous = decodePhaseRecords(record.artifacts)[request.phaseId]
      if (!phaseCoupledEvidenceWriteAdmissible(request.attemptCount, previous)) {
        return@transaction false
      }
      appendLedgerEntryInTransaction(unitOfWork, record, request)
    }

  fun appendLedgerEntry(request: FeatureTaskRuntimePhaseLedgerRequest): Boolean =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, request.workflowId)
          ?: return@transaction false
      appendLedgerEntryInTransaction(unitOfWork, record, request)
    }

  private fun appendLedgerEntryInTransaction(
    unitOfWork: UnitOfWork,
    record: WorkflowStateSnapshot,
    request: FeatureTaskRuntimePhaseLedgerRequest,
  ): Boolean {
    if (record.workflowStatus in WORKER_TERMINAL_STATUSES) return false
    val artifacts = record.artifacts
    val existingEntries = decodePhaseLedger(artifacts)
    val nextSequence = (existingEntries.maxOfOrNull { it.sequenceNumber } ?: -1) + 1
    val entry =
      FeatureTaskRuntimePhaseLedgerEntry(
        action = request.action,
        sequenceNumber = nextSequence,
        timestamp = clock.instant().toString(),
        phaseId = request.phaseId,
        attemptCount = request.attemptCount,
        resolvedAgentId = request.resolvedAgentId,
        fixLoopIteration = request.fixLoopIteration,
        blockedReason = request.blockedReason,
        loopId = request.loopId,
        edgeIteration = request.edgeIteration,
      )
    val updatedLedger =
      retainPhaseLedgerEvidence(existingEntries + entry, FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT)
        .map { it.asWorkflowArtifactEntry() }
    workflowPersistence.persistArtifactsPatch(
      unitOfWork.workflowStates,
      record,
      mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.entry(updatedLedger)),
    )
    return true
  }

  fun appendOwnedCheckpointIdentity(
    args: AppendCheckpointIdentityArgs,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean =
    database.transaction { unitOfWork ->
      if (!requireCurrentWorker(admitted, unitOfWork.workflowStates, args.workflowId)) {
        return@transaction false
      }
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, args.workflowId)
          ?: return@transaction false
      val previous = decodePhaseRecords(record.artifacts)[args.phaseId]
      if (!checkpointIdentityWriteAdmissible(args.attemptCount, previous)) {
        return@transaction false
      }
      appendCheckpointIdentityInTransaction(unitOfWork, record, args)
    }

  fun appendOwnedQuarantineEntry(
    workflowId: String,
    entry: FeatureTaskRuntimeQuarantineEntry,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean =
    database.transaction { unitOfWork ->
      if (!requireCurrentWorker(admitted, unitOfWork.workflowStates, workflowId)) {
        return@transaction false
      }
      appendQuarantineEntryInTransaction(unitOfWork, workflowId, entry)
    }

  fun appendQuarantineEntry(
    workflowId: String,
    entry: FeatureTaskRuntimeQuarantineEntry,
  ): Boolean =
    database.transaction { unitOfWork ->
      appendQuarantineEntryInTransaction(unitOfWork, workflowId, entry)
    }

  private fun appendQuarantineEntryInTransaction(
    unitOfWork: UnitOfWork,
    workflowId: String,
    entry: FeatureTaskRuntimeQuarantineEntry,
  ): Boolean {
    val record =
      unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
        ?: return false
    val patch = quarantineEntryPatch(record.artifacts, entry, quarantineValidator) ?: return true
    workflowPersistence.persistArtifactsPatch(unitOfWork.workflowStates, record, patch)
    return true
  }

  fun loadQuarantinedRecords(workflowId: String): List<FeatureTaskRuntimeQuarantineEntry>? =
    database.read { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@read null
      quarantineEntriesFrom(record.artifacts)
    }

  fun recordOwnedResolvedBranch(
    workflowId: String,
    resolvedBranch: FeatureTaskRuntimeResolvedBranch,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean =
    database.transaction { unitOfWork ->
      if (!requireCurrentWorker(admitted, unitOfWork.workflowStates, workflowId)) {
        return@transaction false
      }
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@transaction false
      val artifacts = record.artifacts
      if (resolvedBranchFromWorkflowArtifacts(artifacts) != null) {
        return@transaction true
      }
      workflowPersistence.persistArtifactsPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH.entry(
            resolvedBranch.asWorkflowArtifactEntry(),
          ),
        ),
      )
      true
    }

  fun recordResolvedBranch(
    workflowId: String,
    resolvedBranch: FeatureTaskRuntimeResolvedBranch,
  ): Boolean =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@transaction false
      val artifacts = record.artifacts
      if (resolvedBranchFromWorkflowArtifacts(artifacts) != null) {
        return@transaction true
      }
      workflowPersistence.persistArtifactsPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH.entry(
            resolvedBranch.asWorkflowArtifactEntry(),
          ),
        ),
      )
      true
    }

  fun loadResolvedBranch(workflowId: String): FeatureTaskRuntimeResolvedBranch? =
    database.read { unitOfWork ->
      val artifacts =
        unitOfWork.workflowStates.readFeatureTaskArtifacts(
          workflowId,
          setOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH),
        )
          ?: return@read null
      resolvedBranchFromWorkflowArtifacts(artifacts)
    }

  fun loadGoalStartResolvedBranch(parentWorkflowId: String): FeatureTaskRuntimeResolvedBranch? =
    database.read { unitOfWork ->
      unitOfWork.workflowStates.listGoalChildWorkflowIdsByParent(parentWorkflowId)
        .firstNotNullOfOrNull { childWorkflowId ->
          unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, childWorkflowId)
            ?.let { record -> resolvedBranchFromWorkflowArtifacts(record.artifacts) }
        }
    }

  fun appendCheckpointIdentity(args: AppendCheckpointIdentityArgs): Boolean =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, args.workflowId)
          ?: return@transaction false
      appendCheckpointIdentityInTransaction(unitOfWork, record, args)
    }

  private fun appendCheckpointIdentityInTransaction(
    unitOfWork: UnitOfWork,
    record: WorkflowStateSnapshot,
    args: AppendCheckpointIdentityArgs,
  ): Boolean {
    val artifacts = record.artifacts
    val existing = checkpointIdentitiesFrom(artifacts)
    val sequenceNumber = (existing.maxOfOrNull { it.sequenceNumber } ?: -1) + 1
    val entry =
      FeatureTaskRuntimeCheckpointIdentity(
        sequenceNumber = sequenceNumber,
        issueKey = args.issueKey,
        subtaskId = args.subtaskId,
        checkpointRef = featureTaskRuntimeCheckpointRefName(args.issueKey, args.subtaskId, sequenceNumber),
        branch = args.branch,
        phaseId = args.phaseId,
        generation = args.generation,
        ownedPathDigest = featureTaskRuntimeOwnedPathDigest(args.ownedPaths),
        ownedPathCount = args.ownedPaths.filter(String::isNotBlank).distinct().size,
        commitSha = args.commitSha,
        recordedAt = clock.instant().toString(),
        loopId = args.loopId,
        parentSha = args.parentSha,
      )
    val updated = featureTaskRuntimeAppendCheckpointIdentity(existing, entry)
    workflowPersistence.persistArtifactsPatch(
      unitOfWork.workflowStates,
      record,
      mapOf(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES.entry(
          updated.asCheckpointIdentitiesArtifactEntry(),
        ),
      ),
    )
    return true
  }

  fun loadCheckpointIdentities(workflowId: String): List<FeatureTaskRuntimeCheckpointIdentity>? =
    database.read { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@read null
      checkpointIdentitiesFrom(record.artifacts)
    }

  fun recordOwnedWorkflowOwnedPaths(
    workflowId: String,
    ownedPaths: List<String>,
    admitted: AdmittedFeatureTaskRuntimeExecution,
  ): Boolean =
    database.transaction { unitOfWork ->
      if (!requireCurrentWorker(admitted, unitOfWork.workflowStates, workflowId)) {
        return@transaction false
      }
      recordWorkflowOwnedPathsInTransaction(unitOfWork, workflowId, ownedPaths)
    }

  fun recordWorkflowOwnedPaths(
    workflowId: String,
    ownedPaths: List<String>,
  ): Boolean =
    database.transaction { unitOfWork ->
      recordWorkflowOwnedPathsInTransaction(unitOfWork, workflowId, ownedPaths)
    }

  private fun recordWorkflowOwnedPathsInTransaction(
    unitOfWork: UnitOfWork,
    workflowId: String,
    ownedPaths: List<String>,
  ): Boolean {
    val record =
      unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
        ?: return false
    val resolved =
      resolvedBranchFromWorkflowArtifacts(record.artifacts)
        ?: return false
    val updated = resolved.copy(workflowOwnedPaths = ownedPaths.distinct().sorted())
    workflowPersistence.persistArtifactsPatch(
      unitOfWork.workflowStates,
      record,
      mapOf(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RESOLVED_BRANCH.entry(
          updated.asWorkflowArtifactEntry(),
        ),
      ),
    )
    return true
  }
}

fun FeatureTaskRuntimePhaseEvidenceRecorder.quarantineEntriesFrom(
  artifacts: Map<String, Any?>,
): List<FeatureTaskRuntimeQuarantineEntry> = decodeQuarantineEntries(artifacts, quarantineValidator)

internal fun quarantineEntryPatch(
  artifacts: Map<String, Any?>,
  entry: FeatureTaskRuntimeQuarantineEntry,
  validator: FeatureTaskRuntimeWireArtifactValidator,
): Map<String, Any?>? {
  val existing = decodeQuarantineEntries(artifacts, validator)
  val alreadyRecorded =
    existing.any {
      it.producingPhaseId == entry.producingPhaseId &&
        it.producingIteration == entry.producingIteration &&
        it.regenerationAttempt == entry.regenerationAttempt &&
        it.rejectionClass == entry.rejectionClass
    }
  if (alreadyRecorded) return null
  val wire = (existing + entry).asQuarantineWorkflowArtifactEntry()
  validator.validate(
    FeatureTaskRuntimeWireArtifactKind.QUARANTINE_RECORD,
    FeatureTaskRuntimeWorkflowArtifactMap.from(wire),
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS.label(),
  )
  return mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS.entry(wire))
}

internal fun nextQuarantineRegenerationAttempt(
  artifacts: Map<String, Any?>,
  validator: FeatureTaskRuntimeWireArtifactValidator,
  producingPhaseId: String,
  producingIteration: Int,
  rejectionClass: String,
): Int =
  decodeQuarantineEntries(artifacts, validator)
    .filter {
      it.producingPhaseId == producingPhaseId &&
        it.producingIteration == producingIteration &&
        it.rejectionClass == rejectionClass
    }
    .maxOfOrNull { it.regenerationAttempt }
    ?.plus(1) ?: 1

private fun decodeQuarantineEntries(
  artifacts: Map<String, Any?>,
  validator: FeatureTaskRuntimeWireArtifactValidator,
): List<FeatureTaskRuntimeQuarantineEntry> {
  val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS
  val raw = family.value(artifacts) ?: return emptyList()
  val map =
    JsonCodec.anyToStringAnyMap(raw)
      ?: throw invalidWorkflowStateSchemaError("Feature-task-runtime quarantine record must be an object.")
  validator.validate(
    FeatureTaskRuntimeWireArtifactKind.QUARANTINE_RECORD,
    FeatureTaskRuntimeWorkflowArtifactMap.from(map),
    family.label(),
  )
  return decodeQuarantineEntriesFromArtifact(raw)
}

fun FeatureTaskRuntimePhaseEvidenceRecorder.checkpointIdentitiesFrom(
  artifacts: Map<String, Any?>,
): List<FeatureTaskRuntimeCheckpointIdentity> =
  decodeCheckpointIdentitiesFromArtifact(
    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES.value(artifacts),
  )
