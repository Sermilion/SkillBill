package skillbill.engine.goalrunner.persist

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.error.core.BlockIncidentFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.engine.diagnostics.block.BlockIncidentAliases
import skillbill.engine.diagnostics.block.BlockIncidentIdentity
import skillbill.engine.diagnostics.block.BlockIncidentObservation
import skillbill.engine.goalrunner.execution.support.workflowFamilyFor
import skillbill.engine.goalrunner.model.GoalRunnerBlockWrite
import skillbill.engine.goalrunner.model.GoalRunnerBlockWriteResult
import skillbill.goalrunner.model.GoalRunnerAcceptedBlockReference
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.featuretask.MissingFeatureTaskRuntimeExecutionPlanError
import skillbill.goalrunner.model.GoalRunnerSupervisionEvent
import skillbill.goalrunner.toPersistenceWire
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.blockincident.BlockIncidentRepository
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.blockedStepId
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
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
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.phaseartifacts.asPendingForOperatorResume
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCorrelatedField
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFieldAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import java.time.Clock
import java.time.ZoneOffset

internal class WorkflowGoalRunnerBlockWrites(
  private val engine: WorkflowEngine,
  private val clock: Clock,
) {
  fun markBlocked(
    workflowId: String,
    blockedReason: String,
    lastResumableStep: String,
    supervisionEvent: GoalRunnerSupervisionEvent?,
    workflowStates: WorkflowStateRepository,
    blockIncidents: BlockIncidentRepository,
    causingChildWorkflowId: String? = null,
    executionGeneration: Long? = null,
    failureExitCode: Int? = null,
    failureThrowableType: String? = null,
    causingIncidentToken: String? = null,
    causingAcceptedBlockReference: GoalRunnerAcceptedBlockReference? = null,
    historicalBlockReference: GoalRunnerAcceptedBlockReference? = null,
  ): GoalRunnerBlockWriteResult? {
    val family = workflowFamilyFor(workflowStates, workflowId) ?: return null
    val record = workflowStates.get(family, workflowId) ?: return null
    return markBlocked(
      GoalRunnerBlockWrite(
        family = family,
        record = record,
        blockedReason = blockedReason,
        lastResumableStep = lastResumableStep,
        supervisionEvent = supervisionEvent,
        causingChildWorkflowId = causingChildWorkflowId,
        executionGeneration = executionGeneration,
        failureExitCode = failureExitCode,
        failureThrowableType = failureThrowableType,
        causingIncidentToken = causingIncidentToken,
        causingAcceptedBlockReference = causingAcceptedBlockReference,
        historicalBlockReference = historicalBlockReference,
      ),
      workflowStates,
      blockIncidents,
    )
  }

  fun markBlocked(
    write: GoalRunnerBlockWrite,
    workflowStates: WorkflowStateRepository,
    blockIncidents: BlockIncidentRepository,
  ): GoalRunnerBlockWriteResult {
    write.historicalBlockReference
      ?.takeIf { it.sourceWorkflowId == write.record.workflowId }
      ?.let { return historicalObservation(write, it, blockIncidents) }
    val steps = write.record.steps
    val definitionStepIds =
      if (write.family == WorkflowFamily.TASK_RUNTIME) {
        write.family.definition.stepIds.filterNot {
          it in write.family.loopOnlyStepIds || it == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_INTAKE
        }
      } else {
        emptyList()
      }
    val savedIdentity = BlockIncidentIdentity.fromArtifacts(write.record.artifacts)
    val executionGeneration = write.executionGeneration
      ?: workflowStates.getFeatureTaskRuntimeWorkerOwnership(write.record.workflowId)?.generation
    val canRetainIdentity = write.record.workflowStatus == WorkflowStatus.BLOCKED &&
      (executionGeneration == null || executionGeneration == savedIdentity?.generation)
    val standingIdentity = savedIdentity?.takeIf {
      canRetainIdentity && it.stepId == write.record.currentStepId &&
        steps.firstOrNull { step -> step.stepId == it.stepId }?.attemptCount == it.attempt
    }
    val stepId = standingIdentity?.stepId
      ?: blockedStepId(write.record, steps, write.lastResumableStep, definitionStepIds)
    val currentAttempt = (steps.firstOrNull { it.stepId == stepId }?.attemptCount ?: 1).coerceAtLeast(1)
    val acceptedCause = write.causingAcceptedBlockReference ?: write.historicalBlockReference
    val acceptedCauseIncident = acceptedCause?.let { reference ->
      val tokenIncident = blockIncidents.find(reference.incidentToken)
      val aliasKind = BlockIncidentAliasKind.entries.firstOrNull { it.wireValue == reference.aliasKind }
        ?: throw SkillBillRuntimeException(
          BlockIncidentFailureCode.IMMUTABLE_CONFLICT,
          "Accepted child block reference contains an unknown alias kind.",
        )
      val aliasIncident = blockIncidents.findByAlias(BlockIncidentAlias(aliasKind, reference.aliasValue))
      val exact = tokenIncident ?: aliasIncident
      if (exact == null) {
        throw SkillBillRuntimeException(
          BlockIncidentFailureCode.IMMUTABLE_CONFLICT,
          "Accepted child block reference does not resolve to a durable incident.",
        )
      } else {
        if (!(acceptedCause.sourceWorkflowId == write.causingChildWorkflowId &&
          exact.occurrence.workflowId == acceptedCause.sourceWorkflowId &&
          (exact.occurrence.phaseId == null || exact.occurrence.phaseId == acceptedCause.blockedStepId) &&
          exact.occurrence.attempt == acceptedCause.attempt &&
          exact.occurrence.generation == acceptedCause.executionGeneration &&
          exact.occurrence.occurrenceToken == acceptedCause.incidentToken &&
          aliasIncident != null &&
          aliasIncident.occurrence.occurrenceToken == reference.incidentToken &&
          (tokenIncident == null || tokenIncident.occurrence.occurrenceToken == aliasIncident.occurrence.occurrenceToken)
        )) {
          throw SkillBillRuntimeException(
            BlockIncidentFailureCode.IMMUTABLE_CONFLICT,
            "Accepted child block reference does not match its durable incident.",
          )
        }
        exact
      }
    }
    val childToken = acceptedCauseIncident?.occurrence?.occurrenceToken
    val causalChildWorkflowId = childToken?.let { write.causingChildWorkflowId }
    val retainedIdentity = savedIdentity?.takeIf {
      canRetainIdentity && it.stepId == stepId &&
        it.attempt == currentAttempt &&
        it.childWorkflowId == causalChildWorkflowId &&
        it.childIncidentToken == childToken
    }
    val retainedGoalIdentity = retainedIdentity?.takeIf {
      it.alias == BlockIncidentAliases.goal(write.record.workflowId, it.stepId, it.aliasSequence)
    }
    val sourceAlias = retainedIdentity?.sourceAlias ?: retainedIdentity?.alias?.takeIf {
      it != BlockIncidentAliases.goal(write.record.workflowId, retainedIdentity.stepId, retainedIdentity.aliasSequence)
    }
    val previousSequence = savedIdentity?.aliasSequence ?: 0L
    var aliasSequence = retainedGoalIdentity?.aliasSequence ?: maxOf(
      previousSequence + 1,
      executionGeneration ?: currentAttempt.toLong(),
    )
    if (retainedGoalIdentity == null) {
      while (true) {
        val candidate = BlockIncidentAliases.goal(write.record.workflowId, stepId, aliasSequence)
        val owner = blockIncidents.findByAlias(candidate)
        if (owner == null || owner.occurrence.occurrenceToken == childToken) break
        aliasSequence++
      }
    }
    val retainedAlias = retainedGoalIdentity?.alias?.takeIf {
      it == BlockIncidentAliases.goal(write.record.workflowId, stepId, aliasSequence)
    }
    val alias = retainedAlias ?: BlockIncidentAliases.goal(write.record.workflowId, stepId, aliasSequence)
    val blockIdentity = retainedIdentity?.copy(aliasSequence = aliasSequence, alias = alias, sourceAlias = sourceAlias) ?: BlockIncidentIdentity(
      stepId = stepId,
      attempt = currentAttempt,
      generation = executionGeneration,
      childWorkflowId = causalChildWorkflowId,
      childIncidentToken = childToken,
      incidentToken = retainedIdentity?.incidentToken ?: acceptedCauseIncident?.occurrence?.occurrenceToken ?: childToken ?: BlockIncidentAliases.token(alias),
      aliasSequence = aliasSequence,
      alias = alias,
      sourceAlias = sourceAlias,
    )
    val attemptCount = blockIdentity.attempt
    val updated =
      engine.updateRecord(
        write.family.definition,
        write.record,
        WorkflowUpdateInput(
          terminalInstant = clock.instant(),
          workflowStatus = WorkflowStatus.BLOCKED,
          currentStepId = stepId,
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf(
                  SharedPayloadKeys.STEP_ID to stepId,
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
              },
            ),
          sessionId = write.record.sessionId.orEmpty(),
        ),
      )
    workflowStates.save(write.family, updated)
    val parentWorkflowId = write.record.artifacts.goalContinuationArtifact()?.parentWorkflowId
    val phaseId = stepId.takeIf { write.family == WorkflowFamily.TASK_RUNTIME }
    val unavailableFields = mapOf(
      BlockIncidentCorrelatedField.PHASE_RUN_ID to BlockIncidentFieldAvailability.NOT_APPLICABLE,
      BlockIncidentCorrelatedField.THROWABLE_TYPE to
        if (write.failureThrowableType == null) BlockIncidentFieldAvailability.NOT_APPLICABLE
        else BlockIncidentFieldAvailability.AVAILABLE,
      BlockIncidentCorrelatedField.EXIT_CODE to
        if (write.failureExitCode == null) BlockIncidentFieldAvailability.NOT_APPLICABLE
        else BlockIncidentFieldAvailability.AVAILABLE,
      BlockIncidentCorrelatedField.FAILURE_CODE to BlockIncidentFieldAvailability.NOT_APPLICABLE,
      BlockIncidentCorrelatedField.GENERATION to BlockIncidentFieldAvailability.NOT_APPLICABLE,
      BlockIncidentCorrelatedField.PHASE_ID to
        if (phaseId == null) {
          BlockIncidentFieldAvailability.NOT_APPLICABLE
        } else {
          BlockIncidentFieldAvailability.AVAILABLE
        },
      BlockIncidentCorrelatedField.INVOCATION_ID to BlockIncidentFieldAvailability.NOT_APPLICABLE,
      BlockIncidentCorrelatedField.EXECUTION_ID to BlockIncidentFieldAvailability.NOT_APPLICABLE,
      BlockIncidentCorrelatedField.REPAIR_TURN to BlockIncidentFieldAvailability.NOT_APPLICABLE,
      BlockIncidentCorrelatedField.OUTPUT_GENERATION to BlockIncidentFieldAvailability.NOT_APPLICABLE,
      BlockIncidentCorrelatedField.MONITOR_SELECTOR to BlockIncidentFieldAvailability.NOT_APPLICABLE,
      BlockIncidentCorrelatedField.PULL_REQUEST_ID to BlockIncidentFieldAvailability.NOT_APPLICABLE,
      BlockIncidentCorrelatedField.PULL_REQUEST_URL to BlockIncidentFieldAvailability.NOT_APPLICABLE,
      BlockIncidentCorrelatedField.HEAD_REPOSITORY to BlockIncidentFieldAvailability.NOT_APPLICABLE,
      BlockIncidentCorrelatedField.HEAD_BRANCH to BlockIncidentFieldAvailability.NOT_APPLICABLE,
      BlockIncidentCorrelatedField.WORKTREE_PATH to BlockIncidentFieldAvailability.NOT_APPLICABLE,
    ) + if (parentWorkflowId == null) {
      mapOf(BlockIncidentCorrelatedField.PARENT_WORKFLOW_ID to BlockIncidentFieldAvailability.NOT_APPLICABLE)
    } else {
      emptyMap()
    }
    return GoalRunnerBlockWriteResult(
      stepId,
      BlockIncidentObservation(
        workflowId = write.record.workflowId,
        parentWorkflowId = parentWorkflowId,
        phaseId = phaseId,
        attempt = blockIdentity.attempt,
        reason = write.blockedReason,
        alias = alias,
        linkOccurrenceToken = blockIdentity.incidentToken,
        additionalAliases = blockIdentity.childIncidentToken?.let { listOf(BlockIncidentAliases.childCause(it)) }.orEmpty(),
        generation = blockIdentity.generation,
        origin =
          if (write.failureExitCode == null && write.failureThrowableType == null) {
            BlockIncidentOrigin.ACCEPTED_GOAL_BLOCK
          } else {
            BlockIncidentOrigin.ENTRY_FAILURE
          },
        exitCode = write.failureExitCode,
        throwableType = write.failureThrowableType,
        exitBehavior = when {
          write.failureThrowableType != null -> BlockIncidentExitBehavior.THROWN
          write.failureExitCode != null -> BlockIncidentExitBehavior.EXITED_WITH_CODE
          else -> BlockIncidentExitBehavior.RETURNED_FAILURE
        },
        unavailableFields = unavailableFields,
      ),
      blockIdentity.acceptedReference(write.record.workflowId),
    )
  }

  private fun historicalObservation(
    write: GoalRunnerBlockWrite,
    reference: GoalRunnerAcceptedBlockReference,
    blockIncidents: BlockIncidentRepository,
  ): GoalRunnerBlockWriteResult {
    val kind = BlockIncidentAliasKind.entries.firstOrNull { it.wireValue == reference.aliasKind }
      ?: throw SkillBillRuntimeException(
        BlockIncidentFailureCode.IMMUTABLE_CONFLICT,
        "Historical block reference contains an unknown alias kind.",
      )
    val alias = BlockIncidentAlias(kind, reference.aliasValue)
    val tokenIncident = blockIncidents.find(reference.incidentToken)
    val aliasIncident = blockIncidents.findByAlias(alias)
    val incident = tokenIncident?.takeIf {
      aliasIncident?.occurrence?.occurrenceToken == it.occurrence.occurrenceToken &&
        it.occurrence.workflowId == reference.sourceWorkflowId &&
        (it.occurrence.phaseId == null || it.occurrence.phaseId == reference.blockedStepId) &&
        it.occurrence.attempt == reference.attempt &&
        it.occurrence.generation == reference.executionGeneration
    } ?: throw SkillBillRuntimeException(
      BlockIncidentFailureCode.IMMUTABLE_CONFLICT,
      "Historical block reference does not match its durable incident.",
    )
    val goalAlias = BlockIncidentAliases.goal(reference.sourceWorkflowId, reference.blockedStepId, reference.aliasSequence)
    val sourceAlias = when {
      alias != goalAlias -> alias
      reference.sourceAliasKind != null && reference.sourceAliasValue != null -> {
        val sourceKind = BlockIncidentAliasKind.entries.firstOrNull { it.wireValue == reference.sourceAliasKind }
          ?: throw SkillBillRuntimeException(
            BlockIncidentFailureCode.IMMUTABLE_CONFLICT,
            "Historical block reference contains an unknown source alias kind.",
          )
        BlockIncidentAlias(sourceKind, reference.sourceAliasValue)
      }
      else -> null
    }
    val childToken = reference.childIncidentToken
    val observation = BlockIncidentObservation(
      workflowId = reference.sourceWorkflowId,
      parentWorkflowId = write.record.artifacts.goalContinuationArtifact()?.parentWorkflowId,
      phaseId = reference.blockedStepId.takeIf { write.family == WorkflowFamily.TASK_RUNTIME },
      attempt = reference.attempt,
      reason = write.blockedReason,
      alias = goalAlias,
      linkOccurrenceToken = incident.occurrence.occurrenceToken,
      additionalAliases = listOfNotNull(sourceAlias, childToken?.let { token -> BlockIncidentAliases.childCause(token) }),
      generation = reference.executionGeneration,
      origin = BlockIncidentOrigin.ACCEPTED_GOAL_BLOCK,
      exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
    )
    return GoalRunnerBlockWriteResult(reference.blockedStepId, observation, reference)
  }

  fun reopenBlockedPhaseForOperatorResume(
    unitOfWork: UnitOfWork,
    preferredPhaseId: String,
    reason: String,
    expectedIdentity: FeatureTaskExecutionIdentity,
    expectedExecutionPlan: ValidatedFeatureTaskRuntimeExecutionPlan,
  ): Boolean {
    val workflowId = expectedIdentity.workflowId
    val family = WorkflowFamily.TASK_RUNTIME
    val existing = unitOfWork.workflowStates.get(family, workflowId) ?: return false
    val identity =
      unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(workflowId)
        ?: missingPlan()
    FeatureTaskExecutionIdentityPolicy.validate(identity)
    if (identity != expectedIdentity || identity.mode != FeatureTaskWorkflowMode.RUNTIME ||
      identity.routeScope != FeatureTaskRouteScope.GOAL_CHILD
    ) {
      incompatiblePlan()
    }
    val storedPlan =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(existing.artifacts)
        ?: missingPlan()
    if (storedPlan != JsonCodec.parseValue(expectedExecutionPlan.encoded().toString(Charsets.UTF_8))) {
      incompatiblePlan()
    }
    if (unitOfWork.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId) != null) {
      incompatiblePlan()
    }
    if (family.definition.isTerminalStatus(existing.workflowStatus)) {
      return false
    }
    val artifacts = existing.artifacts
    val phaseRecords = phaseRecordsFromWorkflowArtifacts(artifacts)
    val repairBudgetPatch = repairBudgetResetPatch(artifacts)
    val blockedRecord =
      operatorReopenablePhaseRecord(
        phaseRecords,
        preferredPhaseId,
        existing.workflowStatus,
      ) ?: phaseRecords[preferredPhaseId]?.takeIf { repairBudgetPatch.isNotEmpty() }
        ?: return true
    unitOfWork.workflowStates.save(
      family,
      engine.updateRecord(
        family.definition,
        existing,
        operatorBlockedPhaseReopenUpdate(
          blockedRecord,
          phaseRecords,
          artifacts,
          reason,
          repairBudgetPatch,
        ),
      ),
    )
    return true
  }

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
    blockedRecord: FeatureTaskRuntimePhaseRecord,
    phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord>,
    artifacts: Map<String, Any?>,
    reason: String,
    repairBudgetPatch: Map<String, Any?>,
  ): WorkflowUpdateInput {
    val ledger = phaseLedgerFromWorkflowArtifacts(artifacts)
    val reopened =
      LinkedHashMap(phaseRecords).apply {
        this[blockedRecord.phaseId] = blockedRecord.asPendingForOperatorResume()
      }
    val retryEntry =
      FeatureTaskRuntimePhaseLedgerEntry(
        action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
        sequenceNumber = (ledger.maxOfOrNull { it.sequenceNumber } ?: -1) + 1,
        timestamp = clock.instant().atOffset(ZoneOffset.UTC).toString(),
        phaseId = blockedRecord.phaseId,
        attemptCount = blockedRecord.attemptCount,
        resolvedAgentId = blockedRecord.resolvedAgentId,
      )
    return WorkflowUpdateInput(
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = blockedRecord.phaseId,
      stepUpdates =
        WorkflowStepUpdates.from(
          listOf(
            mapOf(
              SharedPayloadKeys.STEP_ID to blockedRecord.phaseId,
              SharedPayloadKeys.STATUS to "pending",
              "attempt_count" to 0,
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
              (ledger.map { it.asWorkflowArtifactEntry() } + retryEntry.asWorkflowArtifactEntry()).takeLast(
                FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT,
              ),
            ),
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY.entry(
              mapOf(
                SharedPayloadKeys.PHASE_ID to blockedRecord.phaseId,
                "reason" to reason,
                "retried_at" to clock.instant().atOffset(ZoneOffset.UTC).toString(),
                "previous_blocked_reason" to (
                  blockedRecord.blockedReason ?: artifacts[DecompositionManifestPayloadKeys.BLOCKED_REASON]?.toString()
                ),
                "previous_blocked_record" to blockedRecord.asWorkflowArtifactEntry(),
              ),
            ),
          ) + repairBudgetPatch,
        ),
      sessionId = "",
    )
  }

  private fun missingPlan(): Nothing = throw MissingFeatureTaskRuntimeExecutionPlanError()

  private fun incompatiblePlan(): Nothing = throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
}
