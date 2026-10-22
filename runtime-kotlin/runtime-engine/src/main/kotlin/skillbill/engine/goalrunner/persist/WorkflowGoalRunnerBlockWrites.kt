package skillbill.engine.goalrunner.persist

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.featuretask.FeatureTaskRuntimePhasePayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.engine.diagnostics.block.BlockIncidentAliases
import skillbill.engine.diagnostics.block.BlockIncidentFailureFacts
import skillbill.engine.diagnostics.block.BlockIncidentIdentity
import skillbill.engine.diagnostics.block.BlockIncidentObservation
import skillbill.engine.diagnostics.block.OperatorBlockRetryArtifactKeys
import skillbill.engine.goalrunner.model.GoalRunnerBlockRequest
import skillbill.engine.goalrunner.model.GoalRunnerBlockWrite
import skillbill.engine.goalrunner.model.GoalRunnerBlockWriteResult
import skillbill.engine.goalrunner.persist.blockincident.WorkflowGoalRunnerAcceptedBlockOutcomeBinding
import skillbill.engine.goalrunner.persist.blockincident.WorkflowGoalRunnerHistoricalBlockObservation
import skillbill.engine.goalrunner.persist.blockincident.availableWhen
import skillbill.engine.goalrunner.persist.blockincident.blockExitBehavior
import skillbill.engine.goalrunner.persist.blockincident.blockOrigin
import skillbill.engine.goalrunner.persist.blockincident.goalParentWorkflowId
import skillbill.engine.workflow.support.workflowFamilyFor
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.MissingFeatureTaskRuntimeExecutionPlanError
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
import skillbill.goalrunner.toPersistenceWire
import skillbill.ports.blockincident.BlockIncidentRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.blockedStepId
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.engine.model.isTerminalStatus
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.phaseLedgerFromWorkflowArtifacts
import skillbill.workflow.taskruntime.artifact.phaseRecordsFromWorkflowArtifacts
import skillbill.workflow.taskruntime.artifact.toAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCorrelatedField
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFieldAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.phase.retainPhaseLedgerEvidence
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.phaseartifacts.asPendingForOperatorResume
import java.time.Clock
import java.time.ZoneOffset

@Inject
class WorkflowGoalRunnerBlockWrites(
  private val clock: Clock,
) {
  private val engine = WorkflowEngine()

  internal fun markBlocked(
    request: GoalRunnerBlockRequest,
    workflowStates: WorkflowStateRepository,
    blockIncidents: BlockIncidentRepository,
  ): GoalRunnerBlockWriteResult? {
    val family = workflowFamilyFor(workflowStates, request.workflowId) ?: return null
    val record = workflowStates.get(family, request.workflowId) ?: return null
    return markBlocked(
      GoalRunnerBlockWrite(
        family = family,
        record = record,
        blockedReason = request.blockedReason,
        lastResumableStep = request.lastResumableStep,
        supervisionEvent = request.supervisionEvent,
        causingChildWorkflowId = request.causingChildWorkflowId,
        executionGeneration = request.executionGeneration,
        failureExitCode = request.failureExitCode,
        failureThrowableType = request.failureThrowableType,
        causingAcceptedBlockReference = request.causingAcceptedBlockReference,
        historicalBlockReference = request.historicalBlockReference,
        stopReason = request.stopReason,
        failureCode = request.failureCode,
        failureDisposition = request.failureDisposition,
      ),
      workflowStates,
      blockIncidents,
    )
  }

  internal fun markBlocked(
    write: GoalRunnerBlockWrite,
    workflowStates: WorkflowStateRepository,
    blockIncidents: BlockIncidentRepository,
  ): GoalRunnerBlockWriteResult {
    write.historicalBlockReference
      ?.takeIf { it.sourceWorkflowId == write.record.workflowId }
      ?.let { return markHistoricalBlocked(write, it, workflowStates) }
    val candidate = blockCandidate(write, workflowStates)
    val existingOwnerToken = blockIncidents.findByAlias(candidate.alias)?.occurrence?.occurrenceToken
    val blockIdentity =
      candidate.identity(existingOwnerToken).let { identity ->
        if (identity.incidentToken == candidate.retainedIdentity?.incidentToken) {
          identity
        } else {
          identity.copy(
            failureFacts =
              BlockIncidentFailureFacts(
                write.failureCode ?: write.stopReason?.name,
                write.failureDisposition,
              ),
          )
        }
      }
    persistBlock(write, blockIdentity, blockIdentity.attempt ?: candidate.stepAttemptCount ?: 0, workflowStates)
    return GoalRunnerBlockWriteResult(
      blockIdentity.stepId,
      acceptedObservation(write, blockIdentity, candidate.acceptedCause),
      blockIdentity.acceptedReference(write.record.workflowId),
    )
  }

  private fun blockCandidate(
    write: GoalRunnerBlockWrite,
    workflowStates: WorkflowStateRepository,
  ): GoalBlockCandidate {
    val steps = write.record.steps
    val savedIdentity = BlockIncidentIdentity.fromArtifacts(write.record.artifacts)
    val executionGeneration =
      write.executionGeneration
        ?: workflowStates.getFeatureTaskRuntimeWorkerOwnership(write.record.workflowId)?.generation
    val retainable =
      savedIdentity?.takeIf {
        write.record.workflowStatus == WorkflowStatus.BLOCKED &&
          (executionGeneration == null || executionGeneration == it.generation)
      }
    val standingStepId =
      retainable?.takeIf { identity ->
        identity.stepId == write.record.currentStepId &&
          steps.firstOrNull { it.stepId == identity.stepId }?.attemptCount?.takeIf { it > 0 } == identity.attempt
      }?.stepId
    val stepId = standingStepId ?: blockedStepId(write.record, steps, write.lastResumableStep, definitionStepIds(write))
    val stepAttemptCount = steps.firstOrNull { it.stepId == stepId }?.attemptCount
    val acceptedAttempt = stepAttemptCount?.takeIf { it > 0 }
    val acceptedCause =
      (write.causingAcceptedBlockReference ?: write.historicalBlockReference)
        ?.takeIf { it.sourceWorkflowId == write.causingChildWorkflowId }
    val childToken = acceptedCause?.incidentToken
    val causalChildWorkflowId = childToken?.let { acceptedCause.sourceWorkflowId }
    return GoalBlockCandidate(
      stepId = stepId,
      stepAttemptCount = stepAttemptCount,
      acceptedAttempt = acceptedAttempt,
      executionGeneration = executionGeneration,
      causalChildWorkflowId = causalChildWorkflowId,
      acceptedCause = acceptedCause,
      retainedIdentity =
        retainable?.takeIf {
          it.stepId == stepId && it.attempt == acceptedAttempt &&
            it.childWorkflowId == causalChildWorkflowId && it.childIncidentToken == childToken
        },
      alias =
        BlockIncidentAliases.goal(write.record.workflowId, stepId, executionGeneration, acceptedAttempt, childToken),
    )
  }

  private fun definitionStepIds(write: GoalRunnerBlockWrite): List<String> =
    if (write.family == WorkflowFamily.TASK_RUNTIME) {
      write.family.definition.stepIds.filterNot {
        it in write.family.loopOnlyStepIds || it == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_INTAKE
      }
    } else {
      emptyList()
    }

  private fun acceptedObservation(
    write: GoalRunnerBlockWrite,
    blockIdentity: BlockIncidentIdentity,
    acceptedCause: GoalRunnerAcceptedBlockReference?,
  ): BlockIncidentObservation {
    val parentWorkflowId = write.goalParentWorkflowId()
    val phaseId = blockIdentity.stepId.takeIf { write.family == WorkflowFamily.TASK_RUNTIME }
    return BlockIncidentObservation(
      workflowId = write.record.workflowId,
      parentWorkflowId = parentWorkflowId,
      phaseId = phaseId,
      attempt = blockIdentity.attempt,
      reason = write.blockedReason,
      alias = blockIdentity.alias,
      linkOccurrenceToken = blockIdentity.incidentToken,
      linkedAcceptedBlockReference = acceptedCause,
      additionalAliases =
        listOfNotNull(
          blockIdentity.sourceAlias,
          blockIdentity.childIncidentToken?.let(BlockIncidentAliases::childCause),
        ),
      generation = blockIdentity.generation,
      origin = write.blockOrigin(),
      failureCode = blockIdentity.failureCode,
      failureDisposition = blockIdentity.failureDisposition?.wireValue,
      exitCode = write.failureExitCode,
      throwableType = write.failureThrowableType,
      exitBehavior = write.blockExitBehavior(),
      unavailableFields =
        acceptedUnavailableFields(
          write,
          phaseId,
          blockIdentity.attempt,
          parentWorkflowId,
          blockIdentity.failureCode,
        ),
    )
  }

  private fun acceptedUnavailableFields(
    write: GoalRunnerBlockWrite,
    phaseId: String?,
    attempt: Int?,
    parentWorkflowId: String?,
    failureCode: String?,
  ): Map<BlockIncidentCorrelatedField, BlockIncidentFieldAvailability> =
    buildMap {
      put(BlockIncidentCorrelatedField.PHASE_RUN_ID, BlockIncidentFieldAvailability.NOT_APPLICABLE)
      put(BlockIncidentCorrelatedField.THROWABLE_TYPE, availableWhen(write.failureThrowableType != null))
      put(BlockIncidentCorrelatedField.EXIT_CODE, availableWhen(write.failureExitCode != null))
      put(
        BlockIncidentCorrelatedField.FAILURE_CODE,
        if (failureCode != null) {
          BlockIncidentFieldAvailability.AVAILABLE
        } else {
          BlockIncidentFieldAvailability.NOT_CAPTURED
        },
      )
      put(BlockIncidentCorrelatedField.GENERATION, BlockIncidentFieldAvailability.NOT_APPLICABLE)
      put(BlockIncidentCorrelatedField.ATTEMPT, availableWhen(phaseId != null && attempt != null))
      put(BlockIncidentCorrelatedField.PHASE_ID, availableWhen(phaseId != null))
      GOAL_NOT_APPLICABLE_FIELDS.forEach { put(it, BlockIncidentFieldAvailability.NOT_APPLICABLE) }
      if (parentWorkflowId == null) {
        put(BlockIncidentCorrelatedField.PARENT_WORKFLOW_ID, BlockIncidentFieldAvailability.NOT_APPLICABLE)
      }
    }

  private fun markHistoricalBlocked(
    write: GoalRunnerBlockWrite,
    reference: GoalRunnerAcceptedBlockReference,
    workflowStates: WorkflowStateRepository,
  ): GoalRunnerBlockWriteResult {
    val canonical =
      BlockIncidentAliases.goal(
        reference.sourceWorkflowId,
        reference.blockedStepId,
        reference.executionGeneration,
        reference.attempt,
        reference.childIncidentToken,
      )
    val referenceAlias =
      BlockIncidentAliasKind.entries.firstOrNull { it.wireValue == reference.aliasKind }
        ?.let { BlockIncidentAlias(it, reference.aliasValue) }
    val referenceSourceAlias =
      reference.sourceAliasValue?.let { value ->
        BlockIncidentAliasKind.entries.firstOrNull { it.wireValue == reference.sourceAliasKind }
          ?.let { BlockIncidentAlias(it, value) }
      }
    val savedIdentity = BlockIncidentIdentity.fromArtifacts(write.record.artifacts)
    val referencedIdentity =
      savedIdentity?.takeIf {
        it.incidentToken == reference.incidentToken &&
          it.stepId == reference.blockedStepId &&
          it.attempt == reference.attempt &&
          it.generation == reference.executionGeneration
      }
    val blockIdentity =
      BlockIncidentIdentity(
        stepId = reference.blockedStepId,
        attempt = reference.attempt,
        generation = reference.executionGeneration,
        childWorkflowId = referencedIdentity?.childWorkflowId,
        childIncidentToken = reference.childIncidentToken,
        incidentToken = reference.incidentToken,
        alias = canonical,
        sourceAlias = referenceSourceAlias ?: referenceAlias?.takeIf { it != canonical },
        failureFacts = referencedIdentity?.failureFacts,
      )
    val currentAttemptCount = write.record.steps.firstOrNull { it.stepId == reference.blockedStepId }?.attemptCount
    persistBlock(
      write,
      blockIdentity,
      maxOf(currentAttemptCount ?: 0, reference.attempt ?: 0),
      workflowStates,
    )
    val sourceFacts = historicalSourceFacts(workflowStates, reference)
    return WorkflowGoalRunnerHistoricalBlockObservation.build(
      write.copy(
        failureCode = sourceFacts?.failureCode,
        failureDisposition = sourceFacts?.failureDisposition,
        stopReason = null,
      ),
      reference,
      blockIdentity.sourceAlias,
    )
  }

  private fun historicalSourceFacts(
    workflowStates: WorkflowStateRepository,
    reference: GoalRunnerAcceptedBlockReference,
  ): BlockIncidentIdentity? =
    workflowFamilyFor(workflowStates, reference.sourceWorkflowId)
      ?.let { family -> workflowStates.get(family, reference.sourceWorkflowId) }
      ?.let { source -> BlockIncidentIdentity.fromArtifacts(source.artifacts) }
      ?.takeIf {
        it.incidentToken == reference.incidentToken &&
          it.stepId == reference.blockedStepId &&
          it.attempt == reference.attempt &&
          it.generation == reference.executionGeneration
      }

  private fun persistBlock(
    write: GoalRunnerBlockWrite,
    blockIdentity: BlockIncidentIdentity,
    attemptCount: Int,
    workflowStates: WorkflowStateRepository,
  ) {
    val updated =
      engine.updateRecord(
        write.family.definition,
        write.record,
        WorkflowUpdateInput(
          terminalInstant = clock.instant(),
          workflowStatus = WorkflowStatus.BLOCKED,
          currentStepId = blockIdentity.stepId,
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf(
                  SharedPayloadKeys.STEP_ID to blockIdentity.stepId,
                  SharedPayloadKeys.STATUS to WorkflowStepStatus.BLOCKED.wireValue,
                  WorkflowWirePayloadKeys.ATTEMPT_COUNT to attemptCount,
                ),
              ),
            ),
          artifactsPatch =
            WorkflowArtifactPatch.from(
              buildMap {
                put(DecompositionManifestPayloadKeys.BLOCKED_REASON, write.blockedReason)
                write.supervisionEvent?.let { event -> put("supervision_event", event.toPersistenceWire()) }
                putAll(mapOf(blockIdentity.artifactEntry()))
                putAll(WorkflowGoalRunnerAcceptedBlockOutcomeBinding.patch(write.record, blockIdentity))
              },
            ),
          sessionId = write.record.sessionId.orEmpty(),
        ),
      )
    workflowStates.save(write.family, updated)
  }

  internal fun reopenBlockedPhaseForOperatorResume(
    unitOfWork: UnitOfWork,
    request: OperatorBlockedPhaseReopenRequest,
    expectedIdentity: FeatureTaskExecutionIdentity,
    expectedExecutionPlan: ValidatedFeatureTaskRuntimeExecutionPlan,
  ): Boolean {
    val existing =
      unitOfWork.workflowStates.get(
        WorkflowFamily.TASK_RUNTIME,
        expectedIdentity.workflowId,
      ) ?: return false
    validateReopenPlan(unitOfWork, existing, expectedIdentity, expectedExecutionPlan)
    if (!canReopen(unitOfWork, existing, request)) return false
    val repairBudgetPatch =
      if (request.recoveryAttempt != null) {
        emptyMap()
      } else {
        repairBudgetResetPatch(
          existing.artifacts,
        )
      }
    val blockedRecord =
      reopenableRecord(existing, request, repairBudgetPatch.isNotEmpty())
        ?: return request.recoveryAttempt == null
    return if (request.recoveryAttempt != null && !blockedStepMatches(existing, blockedRecord)) {
      false
    } else {
      unitOfWork.workflowStates.save(
        WorkflowFamily.TASK_RUNTIME,
        engine.updateRecord(
          WorkflowFamily.TASK_RUNTIME.definition,
          existing,
          operatorBlockedPhaseReopenUpdate(existing, blockedRecord, request, repairBudgetPatch),
        ),
      )
      true
    }
  }

  private fun validateReopenPlan(
    unit: UnitOfWork,
    existing: WorkflowStateSnapshot,
    expectedIdentity: FeatureTaskExecutionIdentity,
    expectedPlan: ValidatedFeatureTaskRuntimeExecutionPlan,
  ) {
    val identity = unit.workflowStates.getFeatureTaskExecutionIdentity(existing.workflowId) ?: missingPlan()
    FeatureTaskExecutionIdentityPolicy.validate(identity)
    if (identity != expectedIdentity || identity.mode != FeatureTaskWorkflowMode.RUNTIME ||
      identity.routeScope != FeatureTaskRouteScope.GOAL_CHILD
    ) {
      incompatiblePlan()
    }
    val storedPlan =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(existing.artifacts)
        ?: missingPlan()
    if (storedPlan != JsonCodec.parseValue(expectedPlan.encoded().toString(Charsets.UTF_8))) incompatiblePlan()
    if (unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(existing.workflowId) != null) incompatiblePlan()
  }

  private fun canReopen(
    unit: UnitOfWork,
    existing: WorkflowStateSnapshot,
    request: OperatorBlockedPhaseReopenRequest,
  ): Boolean {
    if (WorkflowFamily.TASK_RUNTIME.definition.isTerminalStatus(existing.workflowStatus)) return false
    if (request.recoveryAttempt == null) return true
    val reasonValid = request.reason.isNotBlank() && request.reason.length <= MAX_RECOVERY_REASON_CHARS
    val targetValid =
      existing.workflowStatus == WorkflowStatus.BLOCKED && existing.currentStepId == request.phaseId &&
        !request.targetWorkflowId.isNullOrBlank()
    return reasonValid && targetValid && recoveryAdmissionMatches(unit, existing, request)
  }

  private fun recoveryAdmissionMatches(
    unit: UnitOfWork,
    existing: WorkflowStateSnapshot,
    request: OperatorBlockedPhaseReopenRequest,
  ): Boolean {
    val admitted = requireNotNull(request.recoveryAttempt)
    val owned =
      admitted.status == BlockIncidentRecoveryAttemptStatus.APPLYING &&
        !admitted.ownerToken.isNullOrBlank() && admitted.ownerGeneration != null &&
        admitted.ownerExpiresAt?.isAfter(clock.instant()) == true
    val targetMatches =
      admitted.targetWorkflowId == request.targetWorkflowId && admitted.targetInvocationId == null &&
        admitted.occurrenceToken == BlockIncidentIdentity.fromArtifacts(existing.artifacts)?.incidentToken
    return owned && targetMatches && admitted.attemptId.isNotBlank() &&
      unit.blockIncidentRecoveries.byIdempotencyKey(admitted.idempotencyKey) == admitted
  }

  private fun reopenableRecord(
    existing: WorkflowStateSnapshot,
    request: OperatorBlockedPhaseReopenRequest,
    budgetReset: Boolean,
  ): FeatureTaskRuntimePhaseRecord? {
    val records = phaseRecordsFromWorkflowArtifacts(existing.artifacts)
    return if (request.recoveryAttempt != null) {
      records[request.phaseId]?.takeIf { it.status == WorkflowStepStatus.BLOCKED }
    } else {
      operatorReopenablePhaseRecord(records, request.phaseId, existing.workflowStatus)
        ?: records[request.phaseId]?.takeIf { budgetReset }
    }
  }

  private fun blockedStepMatches(
    existing: WorkflowStateSnapshot,
    blocked: FeatureTaskRuntimePhaseRecord,
  ): Boolean =
    existing.steps.firstOrNull { it.stepId == blocked.phaseId }?.let {
      it.status == WorkflowStepStatus.BLOCKED && it.attemptCount == blocked.attemptCount
    } == true

  private fun repairBudgetResetPatch(artifacts: Map<String, Any?>): Map<String, Any?> {
    val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR
    if (!family.contains(artifacts)) return emptyMap()
    val state =
      FeatureTaskRuntimeWorkflowArtifactMap.from(family.value(artifacts))
        .toAcceptanceCriterionRepairState(family.label())
    val resumed = state.resetBudgetForOperatorResume()
    return if (resumed == state) emptyMap() else mapOf(family.entry(resumed.asRepairWireMap()))
  }

  private fun operatorReopenablePhaseRecord(
    phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
    preferredPhaseId: String,
    workflowStatus: WorkflowStatus,
  ): FeatureTaskRuntimePhaseRecord? {
    val preferred = phaseRecords[preferredPhaseId]
    return preferred?.takeIf { it.status == WorkflowStepStatus.BLOCKED }
      ?: phaseRecords.values.firstOrNull { it.status == WorkflowStepStatus.BLOCKED }
      ?: preferred?.takeIf {
        workflowStatus == WorkflowStatus.BLOCKED && it.status == WorkflowStepStatus.RUNNING
      }
  }

  private fun operatorBlockedPhaseReopenUpdate(
    record: WorkflowStateSnapshot,
    blockedRecord: FeatureTaskRuntimePhaseRecord,
    request: OperatorBlockedPhaseReopenRequest,
    repairBudgetPatch: Map<String, Any?>,
  ): WorkflowUpdateInput {
    val phaseRecords = phaseRecordsFromWorkflowArtifacts(record.artifacts)
    val preserveAttempts = request.recoveryAttempt != null
    val recoveryAttemptId = request.recoveryAttempt?.attemptId
    val reason = request.reason
    val artifacts = record.artifacts
    val ledger = phaseLedgerFromWorkflowArtifacts(artifacts)
    val reopened =
      LinkedHashMap(phaseRecords).apply {
        this[blockedRecord.phaseId] = blockedRecord.asPendingForOperatorResume()
      }
    val retryEntry = retryLedgerEntry(blockedRecord, ledger, request)
    return WorkflowUpdateInput(
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = blockedRecord.phaseId,
      stepUpdates =
        WorkflowStepUpdates.from(
          listOf(
            mapOf(
              SharedPayloadKeys.STEP_ID to blockedRecord.phaseId,
              SharedPayloadKeys.STATUS to WorkflowStepStatus.PENDING.wireValue,
              FeatureTaskRuntimePhasePayloadKeys.ATTEMPT_COUNT to
                if (preserveAttempts) {
                  record.steps.firstOrNull { it.stepId == blockedRecord.phaseId }?.attemptCount
                    ?: blockedRecord.attemptCount
                } else {
                  0
                },
            ),
          ),
        ),
      artifactsPatch =
        WorkflowArtifactPatch.from(
          mapOf(
            DecompositionManifestPayloadKeys.BLOCKED_REASON to null,
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
              reopened.mapValues { (_, record) -> record.asWorkflowArtifactEntry() },
            ),
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.entry(
              retainPhaseLedgerEvidence(ledger + retryEntry, FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT)
                .map { it.asWorkflowArtifactEntry() },
            ),
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY.entry(
              buildMap {
                put(SharedPayloadKeys.PHASE_ID, blockedRecord.phaseId)
                put(OperatorBlockRetryArtifactKeys.REASON, reason)
                put(OperatorBlockRetryArtifactKeys.RETRIED_AT, clock.instant().atOffset(ZoneOffset.UTC).toString())
                put(
                  OperatorBlockRetryArtifactKeys.PREVIOUS_BLOCKED_REASON,
                  blockedRecord.blockedReason ?: artifacts[DecompositionManifestPayloadKeys.BLOCKED_REASON]?.toString(),
                )
                put(OperatorBlockRetryArtifactKeys.PREVIOUS_BLOCKED_RECORD, blockedRecord.asWorkflowArtifactEntry())
                recoveryAttemptId?.let {
                  put(FeatureTaskRuntimePhasePayloadKeys.RECOVERY_ATTEMPT_ID, it)
                  put(FeatureTaskRuntimePhasePayloadKeys.PRESERVE_BUDGET, true)
                }
              },
            ),
          ) + repairBudgetPatch +
            WorkflowGoalRunnerAcceptedBlockOutcomeBinding.patch(record, BlockIncidentIdentity.fromArtifacts(artifacts)),
        ),
      sessionId = "",
    )
  }

  private fun retryLedgerEntry(
    blockedRecord: FeatureTaskRuntimePhaseRecord,
    ledger: List<FeatureTaskRuntimePhaseLedgerEntry>,
    request: OperatorBlockedPhaseReopenRequest,
  ): FeatureTaskRuntimePhaseLedgerEntry {
    val recoveryAttemptId = request.recoveryAttempt?.attemptId
    val preserveAttempts = request.recoveryAttempt != null
    return FeatureTaskRuntimePhaseLedgerEntry(
      action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
      sequenceNumber = Math.addExact(ledger.maxOfOrNull { it.sequenceNumber } ?: -1, 1),
      timestamp = clock.instant().atOffset(ZoneOffset.UTC).toString(),
      phaseId = blockedRecord.phaseId,
      attemptCount = blockedRecord.attemptCount,
      resolvedAgentId = blockedRecord.resolvedAgentId,
      recoveryAttemptId = recoveryAttemptId,
      preserveBudget = preserveAttempts && recoveryAttemptId != null,
    )
  }

  private companion object {
    const val MAX_RECOVERY_REASON_CHARS = 256
    val GOAL_NOT_APPLICABLE_FIELDS =
      listOf(
        BlockIncidentCorrelatedField.INVOCATION_ID,
        BlockIncidentCorrelatedField.EXECUTION_ID,
        BlockIncidentCorrelatedField.REPAIR_TURN,
        BlockIncidentCorrelatedField.OUTPUT_GENERATION,
        BlockIncidentCorrelatedField.MONITOR_SELECTOR,
        BlockIncidentCorrelatedField.PULL_REQUEST_ID,
        BlockIncidentCorrelatedField.PULL_REQUEST_URL,
        BlockIncidentCorrelatedField.HEAD_REPOSITORY,
        BlockIncidentCorrelatedField.HEAD_BRANCH,
        BlockIncidentCorrelatedField.WORKTREE_PATH,
      )
  }

  private fun missingPlan(): Nothing = throw MissingFeatureTaskRuntimeExecutionPlanError()

  private fun incompatiblePlan(): Nothing = throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
}

private data class GoalBlockCandidate(
  val stepId: String,
  val stepAttemptCount: Int?,
  val acceptedAttempt: Int?,
  val executionGeneration: Long?,
  val causalChildWorkflowId: String?,
  val acceptedCause: GoalRunnerAcceptedBlockReference?,
  val retainedIdentity: BlockIncidentIdentity?,
  val alias: BlockIncidentAlias,
) {
  internal fun identity(existingOwnerToken: String?): BlockIncidentIdentity {
    val ownerIdentity =
      when {
        existingOwnerToken != null && existingOwnerToken == retainedIdentity?.incidentToken -> retainedIdentity
        existingOwnerToken != null && existingOwnerToken == acceptedCause?.incidentToken -> null
        else -> retainedIdentity
      }
    val sourceAlias = ownerIdentity?.sourceAlias ?: ownerIdentity?.alias?.takeIf { it != alias }
    return ownerIdentity?.copy(alias = alias, sourceAlias = sourceAlias)
      ?: BlockIncidentIdentity(
        stepId = stepId,
        attempt = acceptedAttempt,
        generation = executionGeneration,
        childWorkflowId = causalChildWorkflowId,
        childIncidentToken = acceptedCause?.incidentToken,
        incidentToken =
          existingOwnerToken
            ?.takeIf { owner -> owner == retainedIdentity?.incidentToken || owner == acceptedCause?.incidentToken }
            ?: acceptedCause?.incidentToken
            ?: BlockIncidentAliases.token(alias),
        alias = alias,
        sourceAlias = sourceAlias,
      )
  }
}

internal data class OperatorBlockedPhaseReopenRequest(
  val phaseId: String,
  val reason: String,
  val recoveryAttempt: BlockIncidentRecoveryAttempt? = null,
  val targetWorkflowId: String? = null,
)
