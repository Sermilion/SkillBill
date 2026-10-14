package skillbill.engine.featuretask.persist.acceptance

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairBinding
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairCommit
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairHydration
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairMutation
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRefusal
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairWriteResult
import skillbill.engine.featuretask.model.audit.ExpectedAcceptanceCriterionRepairState
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.engine.featuretask.persist.WorkflowRowAdvance
import skillbill.engine.featuretask.persist.sha256Hex
import skillbill.engine.featuretask.persist.stepUpdatesFrom
import skillbill.engine.featuretask.persist.workflowStatusFor
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseStateRecorder
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.invalidAcceptanceCriterionRepairAccounting
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.AcceptanceCriterionRepairProjection
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.AcceptanceCriterionRepairProjectionRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import skillbill.workflow.taskruntime.artifact.toAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairFinalAuditInput
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.recordProseAudit
import skillbill.workflow.taskruntime.model.audit.requireFinalProseAudit
import skillbill.workflow.taskruntime.model.audit.retainProseProgress
import skillbill.workflow.taskruntime.model.audit.retainClarification
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

@Inject
class AcceptanceCriterionRepairPersistence(
  private val boundary: RuntimeOwnedPersistenceBoundary,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  private val phases: FeatureTaskRuntimePhaseStateRecorder,
  private val projection: AcceptanceCriterionRepairProjection,
  private val diagnostics: RuntimeDiagnostics,
  private val clock: Clock,
  private val invariantsSource: FeatureTaskRuntimeRunInvariantsSource,
) {
  fun inspectExisting(
    worker: AcquiredFeatureTaskRuntimeWorker,
    authoredCriteria: List<String>,
    specDigest: String?,
  ): AcceptanceCriterionRepairRunState =
    boundary.requiredRead(
      "serial_repair_startup",
      "catalog-bound recovery",
    ) { work ->
      val row =
        work.workflowStates.get(WorkflowFamily.TASK_RUNTIME, worker.workflowId)
          ?: throw invalidAcceptanceCriterionRepairAccounting(worker.workflowId, "The workflow is missing.")
      val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR
      val binding = binding(worker, authoredCriteria, specDigest)
      val hydration =
        if (!family.contains(row.artifacts)) {
          if (hasHistoricalActivity(row, binding)) {
            AcceptanceCriterionRepairHydration.HistoricalAttributionUnavailable
          } else {
            AcceptanceCriterionRepairHydration.FreshDiscoveryRequired
          }
        } else {
          AcceptanceCriterionRepairHydration.Present(requireNotNull(decode(row.artifacts, binding)))
        }
      AcceptanceCriterionRepairRunState(hydration, binding)
    }

  private fun binding(
    worker: AcquiredFeatureTaskRuntimeWorker,
    authoredCriteria: List<String>,
    specDigest: String?,
  ): AcceptanceCriterionRepairBinding =
    AcceptanceCriterionRepairBinding(
      sha256Hex(JsonCodec.valueToJsonString(authoredCriteria)),
      worker.workflowId,
      specDigest ?: throw invalidAcceptanceCriterionRepairAccounting(
        worker.workflowId,
        "Serial resume requires an admitted spec digest.",
      ),
      authoredCriteria,
      worker.auditStepIds(),
    )

  fun hydrate(
    workflowId: String,
    binding: AcceptanceCriterionRepairBinding,
  ): AcceptanceCriterionRepairHydration =
    boundary.requiredRead("serial_repair_hydrate", "validated authoritative serial state") { work ->
      val record =
        work.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: throw invalidAcceptanceCriterionRepairAccounting(workflowId, "The workflow is missing.")
      val state = decode(record.artifacts, binding)
      if (state != null) {
        signal(workflowId, state, null, "recovery", "retained_state")
        AcceptanceCriterionRepairHydration.Present(state)
      } else if (hasHistoricalActivity(record, binding)) {
        AcceptanceCriterionRepairHydration.HistoricalAttributionUnavailable
      } else {
        AcceptanceCriterionRepairHydration.FreshDiscoveryRequired
      }
    }

  fun write(
    worker: AcquiredFeatureTaskRuntimeWorker,
    binding: AcceptanceCriterionRepairBinding,
    request: AcceptanceCriterionRepairCommit,
    repositoryRoot: Path,
  ): AcceptanceCriterionRepairWriteResult {
    val result =
      try {
        boundary.requiredOwningWrite("serial_repair_write", "owner-fenced serial and phase commit") { work ->
          writeInTransaction(work, worker, binding, request, repositoryRoot)
        }
      } catch (cancelled: CancellationException) {
        signalExpected(worker, request.expected, "cancellation")
        throw cancelled
      } catch (failure: SkillBillRuntimeException) {
        signalExpected(worker, request.expected, "rollback")
        throw failure
      }
    val committed =
      when (result) {
        is OwningTransactionResult.Committed -> result.value
        OwningTransactionResult.ActiveSession ->
          AcceptanceCriterionRepairWriteResult.Refused(owningRefusal())
      }
    if (committed !is AcceptanceCriterionRepairWriteResult.Committed) {
      if (result is OwningTransactionResult.ActiveSession) {
        signalExpected(worker, request.expected, "owning_session_refused")
      }
      if (committed is AcceptanceCriterionRepairWriteResult.Refused) {
        signal(worker.workflowId, null, worker.generation, "refusal", committed.reason.wireValue)
      }
      return committed
    }
    signal(
      worker.workflowId,
      committed.state,
      worker.generation,
      if (committed.replayed) "replay" else "commit",
      "accepted",
    )
    return project(repositoryRoot, worker.workflowId, committed)
  }

  private fun writeInTransaction(
    work: UnitOfWork,
    worker: AcquiredFeatureTaskRuntimeWorker,
    binding: AcceptanceCriterionRepairBinding,
    request: AcceptanceCriterionRepairCommit,
    repositoryRoot: Path,
  ): AcceptanceCriterionRepairWriteResult {
    val states = work.workflowStates
    if (!worker.admits(
        states,
        clock.instant(),
      )
    ) {
      return refuse(worker, null, AcceptanceCriterionRepairRefusal.OWNER_CHANGED)
    }
    val identity =
      request.phase.workflowId == worker.workflowId && binding.executionId == worker.workflowId &&
        worker.ownsAuditStep(
          request.phase.phaseId,
        )
    if (!identity || !sourceMatches(worker, repositoryRoot, binding)) {
      return refuse(worker, null, AcceptanceCriterionRepairRefusal.IDENTITY_CHANGED)
    }
    val record =
      states.get(WorkflowFamily.TASK_RUNTIME, worker.workflowId)
        ?: throw invalidAcceptanceCriterionRepairAccounting(worker.workflowId, "The workflow is missing.")
    val previous = decode(record.artifacts, binding)
    if (previous != null && sameCommittedStart(record.artifacts, request)) {
      return AcceptanceCriterionRepairWriteResult.Committed(detach(previous), true)
    }
    if (previous != null && supersededStart(record.artifacts, request)) {
      return refuse(worker, previous, AcceptanceCriterionRepairRefusal.EXPECTED_STATE_CHANGED)
    }
    val digest = request.evidence?.let { sha256Hex(JsonCodec.valueToJsonString(it.payload)) }
    val next = transition(previous, request.mutation, digest)
    val refusal = candidateRefusal(previous, next, request, record, binding)
    if (refusal != null) return refuse(worker, previous, refusal)
    return commitCandidate(work, worker, record, requireNotNull(next), request)
  }

  private fun sameCommittedStart(
    artifacts: Map<String, Any?>,
    request: AcceptanceCriterionRepairCommit,
  ): Boolean {
    val phase = request.phase
    if (phase.status != WorkflowStepStatus.RUNNING.wireValue) return false
    val record = decodePhaseRecords(artifacts)[phase.phaseId] ?: return false
    val latest = decodePhaseLedger(artifacts).maxByOrNull { it.sequenceNumber } ?: return false
    return record.status == WorkflowStepStatus.RUNNING && record.attemptCount == phase.attemptCount &&
      record.resolvedAgentId == phase.resolvedAgentId && record.loopId == phase.loopId &&
      record.edgeIteration == phase.edgeIteration && latest.action == FeatureTaskRuntimePhaseLedgerAction.START &&
      latest.phaseId == phase.phaseId && latest.attemptCount == phase.attemptCount &&
      latest.resolvedAgentId == phase.resolvedAgentId && latest.loopId == phase.loopId &&
      latest.edgeIteration == phase.edgeIteration
  }

  private fun supersededStart(
    artifacts: Map<String, Any?>,
    request: AcceptanceCriterionRepairCommit,
  ): Boolean {
    val phase = request.phase
    if (phase.status != WorkflowStepStatus.RUNNING.wireValue) return false
    val latest = decodePhaseRecords(artifacts)[phase.phaseId] ?: return false
    return latest.attemptCount > phase.attemptCount ||
      latest.attemptCount == phase.attemptCount &&
      (latest.status != WorkflowStepStatus.RUNNING || !sameCommittedStart(artifacts, request))
  }

  private fun candidateRefusal(
    previous: AcceptanceCriterionRepairState?,
    next: AcceptanceCriterionRepairState?,
    request: AcceptanceCriterionRepairCommit,
    record: WorkflowStateSnapshot,
    binding: AcceptanceCriterionRepairBinding,
  ): AcceptanceCriterionRepairRefusal? {
    if (next == null) return AcceptanceCriterionRepairRefusal.INVALID_TRANSITION
    val replay = previous == next
    val expectedMatches =
      if (replay) {
        replayExpected(
          previous,
          request.expected,
          request.mutation,
        )
      } else {
        matches(previous, request.expected)
      }
    return when {
      !expectedMatches -> AcceptanceCriterionRepairRefusal.EXPECTED_STATE_CHANGED
      !identityMatches(next, binding) -> AcceptanceCriterionRepairRefusal.IDENTITY_CHANGED
      previous == null && hasHistoricalActivity(record, binding) ->
        AcceptanceCriterionRepairRefusal.HISTORICAL_EVIDENCE_UNPROVABLE
      !hasReservationCapacity(previous, request.mutation) -> AcceptanceCriterionRepairRefusal.CAPACITY_EXHAUSTED
      else -> null
    }
  }

  private fun hasReservationCapacity(
    state: AcceptanceCriterionRepairState?,
    mutation: AcceptanceCriterionRepairMutation,
  ): Boolean {
    if (mutation !is AcceptanceCriterionRepairMutation.Reserve || state == null ||
      state.reservation != null
    ) {
      return true
    }
    val used = JsonCodec.valueToJsonString(state.asRepairWireMap()).toByteArray(Charsets.UTF_8).size
    val available =
      AcceptanceCriterionRepairState.MAX_DURABLE_UTF8_BYTES -
        AcceptanceCriterionRepairState.RESERVATION_UTF8_HEADROOM
    return used <= available
  }

  private fun commitCandidate(
    work: UnitOfWork,
    worker: AcquiredFeatureTaskRuntimeWorker,
    record: WorkflowStateSnapshot,
    next: AcceptanceCriterionRepairState,
    request: AcceptanceCriterionRepairCommit,
  ): AcceptanceCriterionRepairWriteResult {
    if (!worker.admits(
        work.workflowStates,
        clock.instant(),
      )
    ) {
      return refuse(worker, next, AcceptanceCriterionRepairRefusal.OWNER_CHANGED)
    }
    val replay =
      decode(
        record.artifacts,
        AcceptanceCriterionRepairBinding(
          next.catalogId,
          next.executionId,
          next.specDigest,
          next.criteria.map {
            it.originalText
          },
        ),
      ) == next
    if (!replay) {
      var patch =
        if (request.mutation == AcceptanceCriterionRepairMutation.RequireFinalAudit &&
          request.phase.status == WorkflowStepStatus.COMPLETED.wireValue
        ) {
          emptyMap()
        } else {
          phases.serialPhasePatch(record.artifacts, request.phase, request.attemptStatus)
        }
      request.additionalPhaseId?.let { phaseId ->
        val prior = decodePhaseRecords(record.artifacts + patch)[phaseId]
          ?: return refuse(worker, next, AcceptanceCriterionRepairRefusal.EXPECTED_STATE_CHANGED)
        val priorEnvelope = prior.outputArtifact?.let { JsonCodec.parseObjectOrNull(it) }
          ?.let { JsonCodec.jsonElementToValue(it) }
          ?.let { JsonCodec.anyToStringAnyMap(it) }
        val normalizedPrior = priorEnvelope?.let {
          NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(FeatureTaskRuntimeWorkflowArtifactMap.from(it))
        }
        if (normalizedPrior == null) {
          return refuse(worker, next, AcceptanceCriterionRepairRefusal.EXPECTED_STATE_CHANGED)
        }
        val additional =
          FeatureTaskRuntimePhaseStateRequest(
            worker.workflowId,
            phaseId,
            WorkflowStepStatus.COMPLETED.wireValue,
            prior.attemptCount,
            prior.resolvedAgentId,
            true,
            outputArtifact = prior.outputArtifact,
            normalizedOutput = normalizedPrior,
            blockedReason = prior.blockedReason,
            failureDisposition = prior.failureDisposition,
            fileManifestBefore = prior.fileManifestBefore,
            fileManifestAfter = prior.fileManifestAfter,
            fileManifestIntroduced = prior.fileManifestIntroduced,
            loopId = prior.loopId,
            edgeIteration = prior.edgeIteration,
            reviewPassNumber = prior.reviewPassNumber,
            repairEvidence = prior.repairEvidence,
            launchedModel = prior.launchedModel,
            launchedEffort = prior.launchedEffort,
            reviewRunId = prior.reviewRunId,
            mutating = true,
          )
        patch = patch + phases.serialPhasePatch(
          record.artifacts + patch,
          additional,
          request.additionalAttemptStatus,
        )
      }
      workflowPersistence.persistAcquiredRepairState(
        work.workflowStates,
        record,
        next,
        patch,
        WorkflowRowAdvance(
          if (request.phase.finished) request.nextSteps[next.stage] ?: request.phase.phaseId else request.phase.phaseId,
          if (next.stage == AcceptanceCriterionRepairStage.BUDGET_BLOCKED) {
            WorkflowStatus.BLOCKED.wireValue
          } else {
            workflowStatusFor(request.phase)
          },
          stepUpdatesFrom(decodePhaseRecords(record.artifacts + patch)),
          clock.instant(),
        ),
      )
    }
    return AcceptanceCriterionRepairWriteResult.Committed(detach(next), replay)
  }

  fun recoverProjection(
    repositoryRoot: Path,
    workflowId: String,
    binding: AcceptanceCriterionRepairBinding,
  ): AcceptanceCriterionRepairWriteResult {
    val result =
      boundary.requiredOwningWrite(
        "serial_repair_projection_recovery",
        "committed authoritative serial snapshot",
      ) { work ->
        val record =
          work.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
            ?: throw invalidAcceptanceCriterionRepairAccounting(workflowId, "The workflow is missing.")
        decode(record.artifacts, binding)
      }
    val state =
      when (result) {
        is OwningTransactionResult.Committed -> result.value
        OwningTransactionResult.ActiveSession -> {
          signal(workflowId, null, null, "projection_refusal", "active_session")
          return AcceptanceCriterionRepairWriteResult.Refused(owningRefusal())
        }
      } ?: run {
        signal(workflowId, null, null, "projection_refusal", "authoritative_state_absent")
        return AcceptanceCriterionRepairWriteResult.Refused(
          AcceptanceCriterionRepairRefusal.HISTORICAL_EVIDENCE_UNPROVABLE,
        )
      }
    return project(repositoryRoot, workflowId, AcceptanceCriterionRepairWriteResult.Committed(state, true))
  }

  private fun owningRefusal(): AcceptanceCriterionRepairRefusal = AcceptanceCriterionRepairRefusal.ACTIVE_SESSION

  private fun project(
    root: Path,
    workflowId: String,
    committed: AcceptanceCriterionRepairWriteResult.Committed,
  ): AcceptanceCriterionRepairWriteResult =
    try {
      projection.write(AcceptanceCriterionRepairProjectionRequest(root, workflowId, committed.state))
      committed
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: IOException) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "seam=serial_repair_projection record_kind=degradation workflow=${workflowId.take(MAX_DIAGNOSTIC_ID)}",
        failure,
      )
      signal(workflowId, committed.state, null, "projection", "committed_with_projection_failure")
      AcceptanceCriterionRepairWriteResult.CommittedWithProjectionFailure(committed.state)
    }

  private fun decode(
    artifacts: Map<String, Any?>,
    binding: AcceptanceCriterionRepairBinding,
  ): AcceptanceCriterionRepairState? {
    val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR
    if (!family.contains(artifacts)) return null
    val wire = FeatureTaskRuntimeWorkflowArtifactMap.from(family.value(artifacts))
    val state = wire.toAcceptanceCriterionRepairState(family.label())
    if (!identityMatches(state, binding)) {
      throw invalidAcceptanceCriterionRepairAccounting(
        family
          .label(),
        "Admitted catalog, spec or execution changed.",
      )
    }
    return state
  }

  private fun detach(state: AcceptanceCriterionRepairState): AcceptanceCriterionRepairState =
    FeatureTaskRuntimeWorkflowArtifactMap.from(state.asRepairWireMap())
      .toAcceptanceCriterionRepairState("committed-serial-state")

  private fun identityMatches(
    state: AcceptanceCriterionRepairState,
    binding: AcceptanceCriterionRepairBinding,
  ): Boolean =
    state.catalogId == binding.catalogId && state.executionId == binding.executionId &&
      sourceIdentityMatches(
        state,
        binding,
      )

  private fun sourceIdentityMatches(
    state: AcceptanceCriterionRepairState,
    binding: AcceptanceCriterionRepairBinding,
  ): Boolean =
    state.specDigest == binding.specDigest && state.criteria.sortedBy {
      it.ordinal
    }.map { it.originalText } == binding.authoredCriteria

  private fun sourceMatches(
    worker: AcquiredFeatureTaskRuntimeWorker,
    repositoryRoot: Path,
    binding: AcceptanceCriterionRepairBinding,
  ): Boolean {
    val read = invariantsSource.read(repositoryRoot.resolve(worker.governedSpecPath))
    return read is FeatureTaskRuntimeRunInvariantsRead.Read && read.sourceDigest == binding.specDigest &&
      read.invariants.acceptanceCriteria == binding.authoredCriteria
  }

  private fun hasHistoricalActivity(
    record: WorkflowStateSnapshot,
    binding: AcceptanceCriterionRepairBinding,
  ): Boolean =
    decodePhaseRecords(record.artifacts).values.any {
      it.attemptCount > 0 && (binding.auditStepIds.isEmpty() || it.phaseId in binding.auditStepIds)
    }

  private fun matches(
    state: AcceptanceCriterionRepairState?,
    expected: ExpectedAcceptanceCriterionRepairState,
  ): Boolean =
    state?.revision == expected.revision && state?.currentCriterionRef == expected.currentCriterionRef &&
      state?.reservation?.reservationId == expected.reservationId

  private fun transition(
    state: AcceptanceCriterionRepairState?,
    mutation: AcceptanceCriterionRepairMutation,
    auditDigest: String?,
  ): AcceptanceCriterionRepairState? =
    when (mutation) {
      is AcceptanceCriterionRepairMutation.Discover -> discoverState(state, mutation)
      AcceptanceCriterionRepairMutation.KeepProgress -> state?.retainProseProgress()
      is AcceptanceCriterionRepairMutation.Clarification -> state?.retainClarification(mutation.question)
      is AcceptanceCriterionRepairMutation.ProseAudit ->
        state?.recordProseAudit(mutation.audit)?.clearPendingClarification()
      AcceptanceCriterionRepairMutation.RequireFinalAudit -> state?.requireFinalProseAudit()
      is AcceptanceCriterionRepairMutation.Reserve -> {
        if (state?.reservation?.reservationId == mutation.reservationId) {
          state
        } else {
          state?.reserve(
            mutation.reservationId,
          )?.clearPendingClarification()
        }
      }
      is AcceptanceCriterionRepairMutation.RepairCompleted -> {
        if (repairCompletedReplays(state, mutation)) {
          state
        } else {
          state?.completeRepairAwaitingTargetedVerification(
            mutation.reservationId,
            mutation.evidenceRef,
            mutation.treeDigest,
            mutation.checkpointId,
          )?.clearPendingClarification()
        }
      }
      is AcceptanceCriterionRepairMutation.Outcome ->
        state?.record(
          mutation.outcome.copy(
            auditBindingDigest = auditDigest,
          ),
        )?.clearPendingClarification()
      is AcceptanceCriterionRepairMutation.FinalAudit ->
        state?.recordFinalFullAudit(
          AcceptanceCriterionRepairFinalAuditInput(
            mutation.auditedRefs,
            mutation.regressedRefs,
            mutation.digest,
            mutation.treeDigest,
            mutation.checkpointId,
            dependencies = mutation.dependencies ?: state.dependencies,
            auditBindingDigest = auditDigest,
          ),
        )?.clearPendingClarification()
    }

  private fun discoverState(
    state: AcceptanceCriterionRepairState?,
    mutation: AcceptanceCriterionRepairMutation.Discover,
  ): AcceptanceCriterionRepairState? {
    val admitted = AcceptanceCriterionRepairState.admit(mutation.admission)
    return if (state == null || state == admitted) admitted else null
  }

  private fun repairCompletedReplays(
    state: AcceptanceCriterionRepairState?,
    mutation: AcceptanceCriterionRepairMutation.RepairCompleted,
  ): Boolean {
    val identity =
      state?.reservation?.reservationId == mutation.reservationId &&
        state.completedRepairEvidenceRef == mutation.evidenceRef
    return identity && state.admittedTreeDigest == mutation.treeDigest &&
      state.admittedCheckpointId == mutation.checkpointId
  }

  private fun replayExpected(
    state: AcceptanceCriterionRepairState?,
    expected: ExpectedAcceptanceCriterionRepairState,
    mutation: AcceptanceCriterionRepairMutation,
  ): Boolean =
    when (mutation) {
      AcceptanceCriterionRepairMutation.KeepProgress -> matches(state, expected)
      is AcceptanceCriterionRepairMutation.Clarification -> matches(state, expected)
      AcceptanceCriterionRepairMutation.RequireFinalAudit -> matches(state, expected)
      is AcceptanceCriterionRepairMutation.ProseAudit -> proseAuditExpectedMatches(state, expected, mutation)
      is AcceptanceCriterionRepairMutation.Reserve ->
        expected.revision == state?.reservation?.revision?.minus(1) &&
          expected.currentCriterionRef == state?.currentCriterionRef && expected.reservationId == null
      is AcceptanceCriterionRepairMutation.RepairCompleted ->
        expected.revision ==
          state
            ?.reservation?.stageRevision?.minus(1) &&
          expected.currentCriterionRef == state?.currentCriterionRef && expected
            .reservationId == state?.reservation?.reservationId
      is AcceptanceCriterionRepairMutation.Discover ->
        expected == ExpectedAcceptanceCriterionRepairState(null, null, null)
      is AcceptanceCriterionRepairMutation.Outcome -> outcomeExpectedMatches(state, expected, mutation)
      is AcceptanceCriterionRepairMutation.FinalAudit -> finalAuditExpectedMatches(state, expected, mutation)
    }

  private fun finalAuditExpectedMatches(
    state: AcceptanceCriterionRepairState?,
    expected: ExpectedAcceptanceCriterionRepairState,
    mutation: AcceptanceCriterionRepairMutation.FinalAudit,
  ): Boolean =
    expected.currentCriterionRef == null && expected.reservationId == null &&
      expected.revision == state?.finalAudits?.singleOrNull { it.digest == mutation.digest }?.revision?.minus(1)

  private fun proseAuditExpectedMatches(
    state: AcceptanceCriterionRepairState?,
    expected: ExpectedAcceptanceCriterionRepairState,
    mutation: AcceptanceCriterionRepairMutation.ProseAudit,
  ): Boolean =
    state?.proseAudits?.singleOrNull { it.id == mutation.audit.id }?.let {
      expected.revision == it.revision - 1 &&
        (expected.currentCriterionRef == it.criterionRef || it.criterionRef == null) &&
        expected.reservationId == null
    } == true

  private fun outcomeExpectedMatches(
    state: AcceptanceCriterionRepairState?,
    expected: ExpectedAcceptanceCriterionRepairState,
    mutation: AcceptanceCriterionRepairMutation.Outcome,
  ): Boolean {
    val outcome = state?.outcomes?.singleOrNull { it.reservationId == mutation.outcome.reservationId } ?: return false
    return expected.revision == outcome.verifiedRevision &&
      expected.currentCriterionRef == outcome.criterionRef && expected.reservationId == outcome.reservationId
  }

  private fun refuse(
    worker: AcquiredFeatureTaskRuntimeWorker,
    state: AcceptanceCriterionRepairState?,
    reason: AcceptanceCriterionRepairRefusal,
  ): AcceptanceCriterionRepairWriteResult.Refused {
    signal(worker.workflowId, state, worker.generation, "refusal", reason.wireValue)
    return AcceptanceCriterionRepairWriteResult.Refused(reason)
  }

  private fun signal(
    workflowId: String,
    state: AcceptanceCriterionRepairState?,
    generation: Long?,
    kind: String,
    reason: String,
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=serial_acceptance_repair workflow=${workflowId.take(MAX_DIAGNOSTIC_ID)} " +
        "criterion=${state?.currentCriterionRef ?: "none"} " +
        "reservation=${state?.reservation?.reservationId?.take(MAX_DIAGNOSTIC_ID) ?: "none"} " +
        "revision=${state?.revision ?: "absent"} owner_generation=${generation ?: "absent"} " +
        "record_kind=$kind value_expected=authoritative_commit value_used=$reason",
    )
  }

  private fun signalExpected(
    worker: AcquiredFeatureTaskRuntimeWorker,
    expected: ExpectedAcceptanceCriterionRepairState,
    kind: String,
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=serial_acceptance_repair workflow=${worker.workflowId.take(MAX_DIAGNOSTIC_ID)} " +
        "criterion=${expected.currentCriterionRef?.take(MAX_CRITERION_ID) ?: "none"} " +
        "reservation=${expected.reservationId?.take(
          MAX_DIAGNOSTIC_ID,
        ) ?: "none"} revision=${expected.revision ?: "absent"} " +
        "owner_generation=${worker.generation} record_kind=$kind " +
        "value_expected=authoritative_commit value_used=not_accepted",
    )
  }

  private companion object {
    const val MAX_DIAGNOSTIC_ID = 128
    const val MAX_CRITERION_ID = 32
  }
}
