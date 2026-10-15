package skillbill.engine.featuretask.persist.acceptance

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairAdoptionResult
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairBinding
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairCommit
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairHydration
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairLegacyProof
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairMutation
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRefusal
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairWriteResult
import skillbill.engine.featuretask.model.audit.ExpectedAcceptanceCriterionRepairState
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.engine.featuretask.persist.WorkflowRowAdvance
import skillbill.engine.featuretask.persist.sha256Hex
import skillbill.engine.featuretask.persist.stepUpdatesFrom
import skillbill.engine.featuretask.persist.workflowStatusFor
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseStateRecorder
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanAdmissionError
import skillbill.error.shellcontent.invalidAcceptanceCriterionRepairStateSchema
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.AcceptanceCriterionRepairProjection
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.taskruntime.model.AcceptanceCriterionRepairProjectionRequest
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import skillbill.workflow.taskruntime.artifact.toAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairFinalAuditInput
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.recordProseAudit
import skillbill.workflow.taskruntime.model.audit.requireFinalProseAudit
import skillbill.workflow.taskruntime.model.audit.retainClarification
import skillbill.workflow.taskruntime.model.audit.retainProseProgress
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

@Inject
class AcceptanceCriterionRepairPersistence(
  private val boundary: RuntimeOwnedPersistenceBoundary,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  private val phases: FeatureTaskRuntimePhaseStateRecorder,
  private val validator: FeatureTaskRuntimeWireArtifactValidator,
  private val projection: AcceptanceCriterionRepairProjection,
  private val diagnostics: RuntimeDiagnostics,
  private val clock: Clock,
  private val adoption: AcceptanceCriterionRepairLegacyAdoption,
) {
  fun inspectExisting(
    worker: AcquiredFeatureTaskRuntimeWorker,
    authoredCriteria: List<String>,
    specDigest: String?,
  ): AcceptanceCriterionRepairHydration =
    boundary.requiredRead(
      "serial_repair_startup",
      "catalog-bound recovery",
    ) { work ->
      val row =
        work.workflowStates.get(WorkflowFamily.TASK_RUNTIME, worker.workflowId)
          ?: throw invalidAcceptanceCriterionRepairStateSchema(worker.workflowId, "The workflow is missing.")
      val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR
      workflowPersistence.validateSerialArtifacts(row.artifacts)
      if (!family.contains(row.artifacts)) {
        if (adoption.hasAuditHistory(row)) {
          AcceptanceCriterionRepairHydration.LegacyAdoptionRequired
        } else {
          AcceptanceCriterionRepairHydration.FreshDiscoveryRequired
        }
      } else {
        val admittedDigest =
          specDigest ?: throw invalidAcceptanceCriterionRepairStateSchema(
            worker
              .workflowId,
            "Serial resume requires an admitted spec digest.",
          )
        val binding =
          AcceptanceCriterionRepairBinding(
            sha256Hex(JsonCodec.valueToJsonString(authoredCriteria)),
            worker.workflowId,
            admittedDigest,
            authoredCriteria,
          )
        AcceptanceCriterionRepairHydration.Present(requireNotNull(decode(row.artifacts, binding)))
      }
    }

  fun adoptLegacy(
    worker: AcquiredFeatureTaskRuntimeWorker,
    binding: AcceptanceCriterionRepairBinding,
    treeDigest: String,
    checkpointId: String,
    repositoryRoot: Path,
  ): AcceptanceCriterionRepairAdoptionResult {
    if (worker.previousOwnerGeneration > 0 && !worker.previousOwnerStopped) {
      return refuseAdoption(worker, AcceptanceCriterionRepairRefusal.OWNER_CHANGED)
    }
    val proof = AcceptanceCriterionRepairLegacyProof(treeDigest, checkpointId, worker.previousOwnerGeneration)
    val result =
      boundary.requiredOwningWrite("serial_repair_adoption", "stopped-pristine historical evidence") { work ->
        adoptInTransaction(work, worker, binding, proof, repositoryRoot)
      }
    val committed =
      when (result) {
        is OwningTransactionResult.Committed -> result.value
        is OwningTransactionResult.Refused ->
          AcceptanceCriterionRepairAdoptionResult.Refused(owningRefusal(result.reason))
      }
    if (committed is AcceptanceCriterionRepairAdoptionResult.Adopted) {
      signal(
        worker.workflowId,
        null,
        worker.generation,
        "adoption",
        "committed",
      )
    } else if (committed is AcceptanceCriterionRepairAdoptionResult.Refused) {
      signal(worker.workflowId, null, worker.generation, "refusal", committed.reason.wireValue)
    }
    return committed
  }

  private fun adoptInTransaction(
    work: UnitOfWork,
    worker: AcquiredFeatureTaskRuntimeWorker,
    binding: AcceptanceCriterionRepairBinding,
    proof: AcceptanceCriterionRepairLegacyProof,
    root: Path,
  ): AcceptanceCriterionRepairAdoptionResult {
    val preflightRefusal =
      when {
        !worker.admits(work.workflowStates, clock.instant()) || binding.executionId != worker.workflowId ->
          AcceptanceCriterionRepairRefusal.OWNER_CHANGED
        !adoption.sourceMatches(worker, root, binding) -> AcceptanceCriterionRepairRefusal.IDENTITY_CHANGED
        else -> null
      }
    if (preflightRefusal != null) return refuseAdoption(worker, preflightRefusal)
    val row = requireNotNull(work.workflowStates.get(WorkflowFamily.TASK_RUNTIME, worker.workflowId))
    workflowPersistence.validateSerialArtifacts(row.artifacts)
    val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR_ADOPTION
    if (family.contains(row.artifacts)) return replayAdoption(worker, row, binding, proof, root)
    val original = worker.originalSnapshot(work.workflowStates)
    if (original != null && !adoption.historicalEvidenceMatches(original, worker, binding, proof, root)) {
      return refuseAdoption(worker, AcceptanceCriterionRepairRefusal.HISTORICAL_EVIDENCE_UNPROVABLE)
    }
    val receipt = original?.let { admitLegacy(worker, it, binding, proof, root) }
    val stillOwned = worker.admits(work.workflowStates, clock.instant())
    return if (receipt == null || !stillOwned) {
      val refusal =
        if (receipt == null) {
          AcceptanceCriterionRepairRefusal.UNSAFE_LEGACY_ADOPTION
        } else {
          AcceptanceCriterionRepairRefusal.OWNER_CHANGED
        }
      refuseAdoption(worker, refusal)
    } else {
      validator.validate(
        FeatureTaskRuntimeWireArtifactKind.ACCEPTANCE_CRITERION_REPAIR_ADOPTION,
        receipt,
        "serial-legacy-adoption",
      )
      workflowPersistence.persistAcquiredRepairAdoption(work.workflowStates, row, receipt)
      AcceptanceCriterionRepairAdoptionResult.Adopted(receipt)
    }
  }

  private fun admitLegacy(
    worker: AcquiredFeatureTaskRuntimeWorker,
    original: WorkflowStateSnapshot,
    binding: AcceptanceCriterionRepairBinding,
    proof: AcceptanceCriterionRepairLegacyProof,
    root: Path,
  ): FeatureTaskRuntimeWorkflowArtifactMap? =
    try {
      adoption.admit(
        AcceptanceCriterionRepairLegacyAdoption.LegacyAdoptionRequest(
          original,
          worker.identity,
          worker.effectiveInputs,
          binding,
          proof,
          root,
        ),
      )
    } catch (failure: FeatureTaskRuntimeExecutionPlanAdmissionError) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "seam=serial_repair_adoption record_kind=refusal workflow=${worker.workflowId.take(MAX_DIAGNOSTIC_ID)}",
        failure,
      )
      null
    }

  private fun replayAdoption(
    worker: AcquiredFeatureTaskRuntimeWorker,
    row: WorkflowStateSnapshot,
    binding: AcceptanceCriterionRepairBinding,
    proof: AcceptanceCriterionRepairLegacyProof,
    root: Path,
  ): AcceptanceCriterionRepairAdoptionResult {
    val retained =
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR_ADOPTION.value(row.artifacts),
      )
    val identity = adoptionMatches(row.artifacts, binding, worker, root, row)
    return if (identity && retained[Keys.TREE_DIGEST] == proof.treeDigest &&
      retained[Keys.CHECKPOINT_ID] == proof.checkpointId
    ) {
      AcceptanceCriterionRepairAdoptionResult.Adopted(retained)
    } else {
      refuseAdoption(worker, AcceptanceCriterionRepairRefusal.UNSAFE_LEGACY_ADOPTION)
    }
  }

  private fun refuseAdoption(
    worker: AcquiredFeatureTaskRuntimeWorker,
    reason: AcceptanceCriterionRepairRefusal,
  ): AcceptanceCriterionRepairAdoptionResult.Refused {
    signal(worker.workflowId, null, worker.generation, "refusal", reason.wireValue)
    return AcceptanceCriterionRepairAdoptionResult.Refused(reason)
  }

  fun hydrate(
    workflowId: String,
    binding: AcceptanceCriterionRepairBinding,
  ): AcceptanceCriterionRepairHydration =
    boundary.requiredRead("serial_repair_hydrate", "validated authoritative serial state") { work ->
      val record =
        work.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
          ?: throw invalidAcceptanceCriterionRepairStateSchema(workflowId, "The workflow is missing.")
      val state = decode(record.artifacts, binding)
      workflowPersistence.validateSerialArtifacts(record.artifacts)
      if (state != null) {
        signal(workflowId, state, null, "recovery", "retained_state")
        AcceptanceCriterionRepairHydration.Present(state)
      } else if (adoption.hasAuditHistory(record)) {
        AcceptanceCriterionRepairHydration.LegacyAdoptionRequired
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
        is OwningTransactionResult.Refused -> AcceptanceCriterionRepairWriteResult.Refused(owningRefusal(result.reason))
      }
    if (committed !is AcceptanceCriterionRepairWriteResult.Committed) {
      if (result is OwningTransactionResult.Refused) {
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
    if (!identity ||
      !adoption.sourceMatches(
        worker,
        repositoryRoot,
        binding,
      )
    ) {
      return refuse(worker, null, AcceptanceCriterionRepairRefusal.IDENTITY_CHANGED)
    }
    val record =
      states.get(WorkflowFamily.TASK_RUNTIME, worker.workflowId)
        ?: throw invalidAcceptanceCriterionRepairStateSchema(worker.workflowId, "The workflow is missing.")
    workflowPersistence.validateSerialArtifacts(record.artifacts)
    val previous = decode(record.artifacts, binding)
    val digest = request.evidence?.let { sha256Hex(JsonCodec.valueToJsonString(it.payload)) }
    val next = transition(previous, request.mutation, digest)
    val legacySafe =
      previous != null || !adoption.hasAuditHistory(record) ||
        freshCurrentAuditDiscovery(record, request) ||
        adoptionMatches(record.artifacts, binding, worker, repositoryRoot, record)
    val refusal = candidateRefusal(previous, next, request, binding, legacySafe)
    if (refusal != null) return refuse(worker, previous, refusal)
    return commitCandidate(work, worker, record, requireNotNull(next), request)
  }

  private fun freshCurrentAuditDiscovery(
    record: WorkflowStateSnapshot,
    request: AcceptanceCriterionRepairCommit,
  ): Boolean {
    val discovery = request.mutation is AcceptanceCriterionRepairMutation.Discover && request.phase.attemptCount == 1
    val currentStep = record.workflowStatus == WorkflowStatus.RUNNING && record.currentStepId == request.phase.phaseId
    val current = decodePhaseRecords(record.artifacts)[request.phase.phaseId]
    val currentPhaseOpen = current == null || current.attemptCount == 1 && current.finishedAt == null
    val stepOpen =
      record.steps.none {
        it.stepId == request.phase.phaseId &&
          (it.attemptCount > 1 || it.status !in setOf(WorkflowStepStatus.PENDING, WorkflowStepStatus.RUNNING))
      }
    val ledger = decodePhaseLedger(record.artifacts)
    val auditLedger = ledger.filter { it.phaseId == request.phase.phaseId }
    val currentLedger =
      auditLedger.all {
        it.attemptCount == 1 && it.action == FeatureTaskRuntimePhaseLedgerAction.START
      }
    val ledgerIsFresh = auditLedger.isEmpty() || currentLedger && auditLedger.size == 1
    return discovery && currentStep && currentPhaseOpen && stepOpen && ledgerIsFresh
  }

  private fun candidateRefusal(
    previous: AcceptanceCriterionRepairState?,
    next: AcceptanceCriterionRepairState?,
    request: AcceptanceCriterionRepairCommit,
    binding: AcceptanceCriterionRepairBinding,
    legacySafe: Boolean,
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
      !legacySafe -> AcceptanceCriterionRepairRefusal.UNSAFE_LEGACY_ADOPTION
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
      val patch = phases.serialPhasePatch(record.artifacts, request.phase, request.attemptStatus)
      workflowPersistence.persistAcquiredRepairState(
        work.workflowStates,
        record,
        next,
        patch,
        WorkflowRowAdvance(
          request.nextSteps[next.stage] ?: request.phase.phaseId,
          workflowStatusFor(request.phase),
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
            ?: throw invalidAcceptanceCriterionRepairStateSchema(workflowId, "The workflow is missing.")
        workflowPersistence.validateSerialArtifacts(record.artifacts)
        decode(record.artifacts, binding)
      }
    val state =
      when (result) {
        is OwningTransactionResult.Committed -> result.value
        is OwningTransactionResult.Refused -> {
          signal(workflowId, null, null, "projection_refusal", result.reason.name.lowercase())
          return AcceptanceCriterionRepairWriteResult.Refused(owningRefusal(result.reason))
        }
      } ?: return AcceptanceCriterionRepairWriteResult.Refused(AcceptanceCriterionRepairRefusal.UNSAFE_LEGACY_ADOPTION)
    return project(repositoryRoot, workflowId, AcceptanceCriterionRepairWriteResult.Committed(state, true))
  }

  private fun owningRefusal(reason: OwningTransactionResult.Refusal): AcceptanceCriterionRepairRefusal =
    when (reason) {
      OwningTransactionResult.Refusal.ACTIVE_SESSION -> AcceptanceCriterionRepairRefusal.ACTIVE_SESSION
      OwningTransactionResult.Refusal.UNSUPPORTED -> AcceptanceCriterionRepairRefusal.OWNING_SESSION_UNAVAILABLE
    }

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
    validator.validate(FeatureTaskRuntimeWireArtifactKind.ACCEPTANCE_CRITERION_REPAIR_STATE, wire, family.label())
    val state = wire.toAcceptanceCriterionRepairState(family.label())
    if (!identityMatches(state, binding)) {
      throw invalidAcceptanceCriterionRepairStateSchema(
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
      is AcceptanceCriterionRepairMutation.ProseAudit,
      AcceptanceCriterionRepairMutation.KeepProgress,
      is AcceptanceCriterionRepairMutation.Clarification,
      AcceptanceCriterionRepairMutation.RequireFinalAudit,
      -> proseTransition(state, mutation)
      else -> repairTransition(state, mutation, auditDigest)
    }

  private fun proseTransition(
    state: AcceptanceCriterionRepairState?,
    mutation: AcceptanceCriterionRepairMutation,
  ): AcceptanceCriterionRepairState? =
    when (mutation) {
      is AcceptanceCriterionRepairMutation.ProseAudit -> state?.recordProseAudit(mutation.audit)
      AcceptanceCriterionRepairMutation.KeepProgress -> state?.retainProseProgress()
      is AcceptanceCriterionRepairMutation.Clarification -> state?.retainClarification(mutation.question)
      AcceptanceCriterionRepairMutation.RequireFinalAudit -> state?.requireFinalProseAudit()
      else -> null
    }

  private fun repairTransition(
    state: AcceptanceCriterionRepairState?,
    mutation: AcceptanceCriterionRepairMutation,
    auditDigest: String?,
  ): AcceptanceCriterionRepairState? =
    when (mutation) {
      is AcceptanceCriterionRepairMutation.Discover -> {
        val admitted = AcceptanceCriterionRepairState.admit(mutation.admission)
        if (state == null || state == admitted) admitted else null
      }
      is AcceptanceCriterionRepairMutation.Reserve -> {
        if (state?.reservation?.reservationId == mutation.reservationId) {
          state
        } else {
          state?.reserve(
            mutation.reservationId,
          )
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
          )
        }
      }
      is AcceptanceCriterionRepairMutation.Outcome ->
        state?.record(
          mutation.outcome.copy(
            auditBindingDigest = auditDigest,
          ),
        )
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
        )
      is AcceptanceCriterionRepairMutation.ProseAudit,
      AcceptanceCriterionRepairMutation.KeepProgress,
      is AcceptanceCriterionRepairMutation.Clarification,
      AcceptanceCriterionRepairMutation.RequireFinalAudit,
      -> null
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
      is AcceptanceCriterionRepairMutation.ProseAudit,
      AcceptanceCriterionRepairMutation.KeepProgress,
      is AcceptanceCriterionRepairMutation.Clarification,
      AcceptanceCriterionRepairMutation.RequireFinalAudit,
      -> proseReplayExpected(state, expected, mutation)
      else -> repairReplayExpected(state, expected, mutation)
    }

  private fun proseReplayExpected(
    state: AcceptanceCriterionRepairState?,
    expected: ExpectedAcceptanceCriterionRepairState,
    mutation: AcceptanceCriterionRepairMutation,
  ): Boolean =
    when (mutation) {
      is AcceptanceCriterionRepairMutation.ProseAudit ->
        expected.revision == mutation.audit.revision - 1 &&
          expected.currentCriterionRef == mutation.audit.criterionRef &&
          expected.reservationId == state?.reservation?.reservationId &&
          state?.proseAudits?.any { it == mutation.audit } == true
      AcceptanceCriterionRepairMutation.KeepProgress,
      is AcceptanceCriterionRepairMutation.Clarification,
      AcceptanceCriterionRepairMutation.RequireFinalAudit,
      -> expected.revision == state?.revision?.minus(1)
      else -> false
    }

  private fun repairReplayExpected(
    state: AcceptanceCriterionRepairState?,
    expected: ExpectedAcceptanceCriterionRepairState,
    mutation: AcceptanceCriterionRepairMutation,
  ): Boolean =
    when (mutation) {
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
      is AcceptanceCriterionRepairMutation.FinalAudit ->
        expected.currentCriterionRef == null && expected.reservationId == null &&
          expected.revision == state?.finalAudits?.singleOrNull { it.digest == mutation.digest }?.revision?.minus(1)
      is AcceptanceCriterionRepairMutation.ProseAudit,
      AcceptanceCriterionRepairMutation.KeepProgress,
      is AcceptanceCriterionRepairMutation.Clarification,
      AcceptanceCriterionRepairMutation.RequireFinalAudit,
      -> false
    }

  private fun outcomeExpectedMatches(
    state: AcceptanceCriterionRepairState?,
    expected: ExpectedAcceptanceCriterionRepairState,
    mutation: AcceptanceCriterionRepairMutation.Outcome,
  ): Boolean {
    val outcome = state?.outcomes?.singleOrNull { it.reservationId == mutation.outcome.reservationId } ?: return false
    return expected.revision == outcome.verifiedRevision &&
      expected.currentCriterionRef == outcome.criterionRef && expected.reservationId == outcome.reservationId
  }

  private fun adoptionMatches(
    artifacts: Map<String, Any?>,
    binding: AcceptanceCriterionRepairBinding,
    worker: AcquiredFeatureTaskRuntimeWorker,
    repositoryRoot: Path,
    current: WorkflowStateSnapshot,
  ): Boolean {
    val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR_ADOPTION
    if (!family.contains(artifacts)) return false
    val receipt = FeatureTaskRuntimeWorkflowArtifactMap.from(family.value(artifacts))
    validator.validate(FeatureTaskRuntimeWireArtifactKind.ACCEPTANCE_CRITERION_REPAIR_ADOPTION, receipt, family.label())
    val identity =
      receipt[Keys.CATALOG_ID] == binding.catalogId && receipt[Keys.SPEC_DIGEST] == binding.specDigest &&
        receipt[Keys.EXECUTION_ID] == binding.executionId
    return identity && adoption.retainedProofMatches(receipt, worker, binding, repositoryRoot, current)
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
