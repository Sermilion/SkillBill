package skillbill.engine.featuretask.phase.record

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.model.phase.AppendCheckpointIdentityArgs
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.workflowArtifactEntryMap
import skillbill.engine.featuretask.persist.workflowArtifactEntryMaps
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.resolvedBranchFromWorkflowArtifacts
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.persistence.artifact.appendBoundedHistoryBySequence
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
import java.time.Clock

class FeatureTaskRuntimePhaseEvidenceRecorder(
  val database: DatabaseSessionFactory,
  val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  val quarantineValidator: FeatureTaskRuntimeWireArtifactValidator,
  val clock: Clock,
) {
  fun appendLedgerEntry(request: FeatureTaskRuntimePhaseLedgerRequest): Boolean =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, request.workflowId)
          ?: return@transaction false
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
        appendBoundedHistoryBySequence(
          existing = workflowArtifactEntryMaps(existingEntries.map { it.asWorkflowArtifactEntry() }),
          entry = workflowArtifactEntryMap(entry.asWorkflowArtifactEntry()),
          retentionLimit = FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
        )
      workflowPersistence.persistArtifactsPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.entry(updatedLedger)),
      )
      true
    }

  fun appendQuarantineEntry(
    workflowId: String,
    entry: FeatureTaskRuntimeQuarantineEntry,
  ): Boolean =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@transaction false
      val artifacts = record.artifacts
      val existing = quarantineEntriesFrom(artifacts)
      val alreadyRecorded =
        existing.any {
          it.producingPhaseId == entry.producingPhaseId &&
            it.producingIteration == entry.producingIteration &&
            it.regenerationAttempt == entry.regenerationAttempt
        }
      if (alreadyRecorded) {
        return@transaction true
      }
      val wire = (existing + entry).asQuarantineWorkflowArtifactEntry()
      quarantineValidator.validate(
        FeatureTaskRuntimeWireArtifactKind.QUARANTINE_RECORD,
        FeatureTaskRuntimeWorkflowArtifactMap.from(wire),
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS.label(),
      )
      workflowPersistence.persistArtifactsPatch(
        unitOfWork.workflowStates,
        record,
        mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS.entry(wire)),
      )
      true
    }

  fun loadQuarantinedRecords(workflowId: String): List<FeatureTaskRuntimeQuarantineEntry>? =
    database.read { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@read null
      quarantineEntriesFrom(record.artifacts)
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
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@read null
      resolvedBranchFromWorkflowArtifacts(record.artifacts)
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
      true
    }

  fun loadCheckpointIdentities(workflowId: String): List<FeatureTaskRuntimeCheckpointIdentity>? =
    database.read { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@read null
      checkpointIdentitiesFrom(record.artifacts)
    }

  fun recordWorkflowOwnedPaths(
    workflowId: String,
    ownedPaths: List<String>,
  ): Boolean =
    database.transaction { unitOfWork ->
      val record =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: return@transaction false
      val resolved =
        resolvedBranchFromWorkflowArtifacts(record.artifacts)
          ?: return@transaction false
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
      true
    }
}

fun FeatureTaskRuntimePhaseEvidenceRecorder.quarantineEntriesFrom(
  artifacts: Map<String, Any?>,
): List<FeatureTaskRuntimeQuarantineEntry> {
  val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_QUARANTINED_RECORDS
  val raw = family.value(artifacts) ?: return emptyList()
  val map =
    JsonCodec.anyToStringAnyMap(raw)
      ?: throw InvalidWorkflowStateSchemaError("Feature-task-runtime quarantine record must be an object.")
  quarantineValidator.validate(
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
