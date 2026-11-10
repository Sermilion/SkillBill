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
import skillbill.engine.featuretask.model.audit.stepId
import skillbill.engine.featuretask.model.audit.toRepairRefusal
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.persist.RuntimeOwnedPersistenceBoundary
import skillbill.engine.featuretask.persist.WorkflowRowAdvance
import skillbill.engine.featuretask.persist.sha256Hex
import skillbill.engine.featuretask.persist.stepUpdatesFrom
import skillbill.engine.featuretask.persist.workflowStatusFor
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
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionGapLimits
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionGapOrigin
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionGapReportParser
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionGapStatus
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceGapVerificationResolver
import skillbill.workflow.taskruntime.model.audit.AcceptanceRepairDecision
import skillbill.workflow.taskruntime.model.audit.AcceptanceRepairStep
import skillbill.workflow.taskruntime.model.audit.admitQaReaudit
import skillbill.workflow.taskruntime.model.audit.interaction.recordInteractionTrace
import skillbill.workflow.taskruntime.model.audit.recordProseAudit
import skillbill.workflow.taskruntime.model.audit.retainClarification
import skillbill.workflow.taskruntime.model.audit.retainProseProgress
import skillbill.workflow.taskruntime.model.audit.sameProducerAs
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

@Inject
public class AcceptanceCriterionRepairPersistence(
  private val boundary: RuntimeOwnedPersistenceBoundary,
  private val workflowPersistence: FeatureTaskRuntimeWorkflowPersistence,
  private val phases: FeatureTaskRuntimePhaseStateRecorder,
  private val validator: FeatureTaskRuntimeWireArtifactValidator,
  private val projection: AcceptanceCriterionRepairProjection,
  private val diagnostics: RuntimeDiagnostics,
  private val clock: Clock,
  private val adoption: AcceptanceCriterionRepairLegacyAdoption,
  private val admission: AcceptanceCriterionRepairAuditAdmission,
) {
  internal fun inspectExisting(
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

  internal fun adoptLegacy(
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
        !worker.admits(work.workflowStates) || binding.executionId != worker.workflowId ->
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
    val stillOwned = worker.admits(work.workflowStates)
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

  internal fun hydrate(
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

  internal fun write(
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
    if (committed.interactionTraceSkipped && !committed.replayed) {
      AcceptanceCriterionInteractionTargets.recordSkip(diagnostics, worker.workflowId)
    }
    recordDiscoveryDiagnostics(worker.workflowId, request, committed)
    recordPostCommitDiagnostics(worker.workflowId, request, committed)
    AcceptanceCriterionTraceDiagnostics.record(diagnostics, worker.workflowId, request.mutation, committed)
    return project(repositoryRoot, worker.workflowId, committed)
  }

  private fun recordDiscoveryDiagnostics(
    workflowId: String,
    request: AcceptanceCriterionRepairCommit,
    committed: AcceptanceCriterionRepairWriteResult.Committed,
  ) {
    if (committed.replayed) return
    val prose =
      when (val mutation = request.mutation) {
        is AcceptanceCriterionRepairMutation.Discover -> mutation.initialAudit?.prose
        is AcceptanceCriterionRepairMutation.ProseAudit -> mutation.audit.prose
        else -> null
      } ?: return
    val fallbackRefs =
      committed.state.criteria.filter { criterion ->
        criterion.canonicalRef in request.discoveryFallbackCriteria &&
          criterion.evidenceRef == prose &&
          criterion.gaps.any { gap ->
            gap.origin == AcceptanceCriterionGapOrigin.DISCOVERY &&
              gap.text == AcceptanceCriterionGapReportParser.FALLBACK_GAP_TEXT &&
              gap.citation == "spec:${criterion.canonicalRef}"
          }
      }.map { it.canonicalRef }
    if (fallbackRefs.isNotEmpty()) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Workflow $workflowId used acceptance gap fallback for ${fallbackRefs.joinToString(", ")}.",
      )
    }
    val stray = request.discoveryStrayCriteria
    if (stray.isNotEmpty()) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Workflow $workflowId ignored malformed acceptance gap observations for ${stray.joinToString(", ")}.",
      )
    }
  }

  private fun recordPostCommitDiagnostics(
    workflowId: String,
    request: AcceptanceCriterionRepairCommit,
    committed: AcceptanceCriterionRepairWriteResult.Committed,
  ) {
    val mutation = request.mutation
    if (committed.replayed) return
    if (mutation is AcceptanceCriterionRepairMutation.Outcome && mutation.outcome.kind in
      setOf(
        AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS,
        AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE,
      )
    ) {
      val outcome = mutation.outcome
      val stillOpen =
        committed.state.criteria.singleOrNull { it.canonicalRef == outcome.criterionRef }
          ?.gaps?.filter { it.status == AcceptanceCriterionGapStatus.OPEN }?.map { it.id }.orEmpty().toSet()
      val missing =
        AcceptanceGapVerificationResolver.missingGapIds(outcome.evidenceRef, request.verificationGapIds)
          .filter { it in stillOpen }
      if (missing.isNotEmpty()) {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Workflow $workflowId left acceptance gaps open without status reports: ${missing.joinToString(", ")}.",
        )
      }
    }
    if (committed.droppedLateFindingCount > 0) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Workflow $workflowId dropped ${committed.droppedLateFindingCount} acceptance late findings " +
          "at the 256-record limit.",
      )
    }
    if (request.repairManifestUnavailable && mutation is AcceptanceCriterionRepairMutation.RepairCompleted) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Workflow $workflowId completed repair admission without a retained repair manifest; " +
          "no changed-file evidence was available.",
      )
    }
  }

  internal fun prepareBoundInteractionTrace(
    worker: AcquiredFeatureTaskRuntimeWorker,
    binding: AcceptanceCriterionRepairBinding,
    repositoryRoot: Path,
  ): AcceptanceCriterionRepairWriteResult {
    val result =
      boundary.requiredOwningWrite(
        "serial_repair_trace_preparation",
        "owner-fenced interaction trace preparation",
      ) { work ->
        prepareInTransaction(work, worker, binding, repositoryRoot)
      }
    val committed =
      when (result) {
        is OwningTransactionResult.Committed -> result.value
        is OwningTransactionResult.Refused -> AcceptanceCriterionRepairWriteResult.Refused(owningRefusal(result.reason))
      }
    if (committed is AcceptanceCriterionRepairWriteResult.Refused) {
      signal(worker.workflowId, null, worker.generation, "refusal", committed.reason.wireValue)
    } else if (committed is AcceptanceCriterionRepairWriteResult.Committed && !committed.replayed) {
      signal(worker.workflowId, committed.state, worker.generation, "commit", "accepted")
      if (committed.interactionTraceSkipped) {
        AcceptanceCriterionInteractionTargets.recordSkip(diagnostics, worker.workflowId)
      }
    }
    return committed
  }

  private fun prepareInTransaction(
    work: UnitOfWork,
    worker: AcquiredFeatureTaskRuntimeWorker,
    binding: AcceptanceCriterionRepairBinding,
    repositoryRoot: Path,
  ): AcceptanceCriterionRepairWriteResult {
    val states = work.workflowStates
    val refusal =
      when {
        !worker.admits(states) || binding.executionId != worker.workflowId ->
          AcceptanceCriterionRepairRefusal.OWNER_CHANGED
        !adoption.sourceMatches(worker, repositoryRoot, binding) ->
          AcceptanceCriterionRepairRefusal.SPEC_SOURCE_CHANGED
        else -> null
      }
    if (refusal != null) return refuse(worker, null, refusal)
    val record =
      states.get(WorkflowFamily.TASK_RUNTIME, worker.workflowId)
        ?: throw invalidAcceptanceCriterionRepairStateSchema(worker.workflowId, "The workflow is missing.")
    workflowPersistence.validateSerialArtifacts(record.artifacts)
    val previous =
      decode(record.artifacts, binding)
        ?: return refuse(worker, null, AcceptanceCriterionRepairRefusal.UNSAFE_LEGACY_ADOPTION)
    val next = AcceptanceCriterionInteractionTargets.prepare(previous, binding)
    return when {
      next == previous -> AcceptanceCriterionRepairWriteResult.Committed(detach(previous), true)
      !admission.matches(next, AcceptanceCriterionRepairMutation.PrepareInteractionTrace, null) ->
        refuse(worker, previous, AcceptanceCriterionRepairRefusal.AUDIT_BINDING_CHANGED)
      else -> {
        workflowPersistence.persistAcquiredRepairState(
          states,
          record,
          next,
          emptyMap(),
          WorkflowRowAdvance.keepFrom(record),
        )
        AcceptanceCriterionInteractionTargets.recordDroppedPairs(
          diagnostics,
          worker.workflowId,
          previous,
          next,
          binding,
        )
        AcceptanceCriterionRepairWriteResult.Committed(
          detach(next),
          false,
          AcceptanceCriterionInteractionTargets.skipped(
            previous,
            next,
            AcceptanceCriterionRepairMutation.PrepareInteractionTrace,
          ),
        )
      }
    }
  }

  private fun writeInTransaction(
    work: UnitOfWork,
    worker: AcquiredFeatureTaskRuntimeWorker,
    binding: AcceptanceCriterionRepairBinding,
    request: AcceptanceCriterionRepairCommit,
    repositoryRoot: Path,
  ): AcceptanceCriterionRepairWriteResult {
    val states = work.workflowStates
    if (!worker.admits(states)
    ) {
      return refuse(worker, null, AcceptanceCriterionRepairRefusal.OWNER_CHANGED)
    }
    val identity = phaseIdentityMatches(worker, binding, request.phase)
    if (!identity) {
      return refuse(worker, null, AcceptanceCriterionRepairRefusal.IDENTITY_CHANGED)
    }
    if (!adoption.sourceMatches(
        worker,
        repositoryRoot,
        binding,
      )
    ) {
      return refuse(worker, null, AcceptanceCriterionRepairRefusal.SPEC_SOURCE_CHANGED)
    }
    val record =
      states.get(WorkflowFamily.TASK_RUNTIME, worker.workflowId)
        ?: throw invalidAcceptanceCriterionRepairStateSchema(worker.workflowId, "The workflow is missing.")
    workflowPersistence.validateSerialArtifacts(record.artifacts)
    val previous = decode(record.artifacts, binding)
    return when {
      previous != null && AcceptanceCriterionRepairStartLedger.sameCommittedStart(record.artifacts, request) ->
        AcceptanceCriterionRepairWriteResult.Committed(detach(previous), true)
      previous != null && AcceptanceCriterionRepairStartLedger.supersededStart(record.artifacts, request) ->
        refuse(worker, previous, AcceptanceCriterionRepairRefusal.EXPECTED_STATE_CHANGED)
      else -> {
        val digest = auditEvidenceDigest(request)
        val raw = transition(previous, request.mutation, digest, binding)
        val update = AcceptanceCriterionInteractionTraceRound.recheckUpdate(previous, raw, request.repairChangedPaths)
        val next = AcceptanceCriterionInteractionTraceRound.scheduleRechecks(raw, update, binding)
        val legacySafe =
          previous != null || mayAdopt(record, request, binding, worker, repositoryRoot)
        val refusal =
          candidateRefusal(previous, next, request, binding, legacySafe) ?: admissionRefusal(next, request)
        if (refusal != null) {
          refuse(worker, previous, refusal)
        } else {
          val droppedLateFindingCount = droppedLateFindings(previous, request.mutation)
          val committed = commitCandidate(work, worker, record, requireNotNull(next), request)
          val flagged = AcceptanceCriterionInteractionTargets.flagSkipped(committed, previous, request.mutation)
          val committedWithDropCount =
            if (flagged is AcceptanceCriterionRepairWriteResult.Committed) {
              flagged.copy(droppedLateFindingCount = droppedLateFindingCount)
            } else {
              flagged
            }
          AcceptanceCriterionInteractionTraceRound.annotateRechecks(
            committedWithDropCount,
            update,
          )
        }
      }
    }
  }

  private fun mayAdopt(
    record: WorkflowStateSnapshot,
    request: AcceptanceCriterionRepairCommit,
    binding: AcceptanceCriterionRepairBinding,
    worker: AcquiredFeatureTaskRuntimeWorker,
    repositoryRoot: Path,
  ): Boolean =
    !adoption.hasAuditHistory(
      record,
    ) || AcceptanceCriterionRepairStartLedger.freshCurrentAuditDiscovery(record, request) ||
      adoptionMatches(record.artifacts, binding, worker, repositoryRoot, record)

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

  private fun admissionRefusal(
    next: AcceptanceCriterionRepairState?,
    request: AcceptanceCriterionRepairCommit,
  ): AcceptanceCriterionRepairRefusal? =
    AcceptanceCriterionRepairRefusal.AUDIT_BINDING_CHANGED.takeUnless {
      admission.matches(requireNotNull(next), request.mutation, request.evidence)
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
    if (!worker.admits(work.workflowStates)
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
      val patch =
        settlementPatch(worker.workflowId, record.artifacts, request)
          ?: return refuse(worker, next, AcceptanceCriterionRepairRefusal.EXPECTED_STATE_CHANGED)
      workflowPersistence.persistAcquiredRepairState(
        work.workflowStates,
        record,
        next,
        patch,
        WorkflowRowAdvance(
          if (request.phase.finished) {
            request.stepRoles?.let { AcceptanceRepairDecision.next(next).stepId(it) } ?: request.phase.phaseId
          } else {
            request.phase.phaseId
          },
          if (AcceptanceRepairDecision.next(next) is AcceptanceRepairStep.Blocked) {
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

  private fun settlementPatch(
    workflowId: String,
    artifacts: Map<String, Any?>,
    request: AcceptanceCriterionRepairCommit,
  ): Map<String, Any?>? {
    val patch = phases.serialPhasePatch(artifacts, request.phase, request.attemptStatus)
    val additionalPhaseId = request.additionalPhaseId ?: return patch
    val additional = restoreCompletedPhase(workflowId, additionalPhaseId, artifacts + patch) ?: return null
    return patch + phases.serialPhasePatch(artifacts + patch, additional, request.additionalAttemptStatus)
  }

  private fun restoreCompletedPhase(
    workflowId: String,
    phaseId: String,
    artifacts: Map<String, Any?>,
  ): FeatureTaskRuntimePhaseStateRequest? {
    val prior = decodePhaseRecords(artifacts)[phaseId] ?: return null
    val priorEnvelope =
      prior.outputArtifact?.let { JsonCodec.parseObjectOrNull(it) }
        ?.let { JsonCodec.jsonElementToValue(it) }
        ?.let { JsonCodec.anyToStringAnyMap(it) }
    val normalizedPrior =
      priorEnvelope?.let {
        NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(FeatureTaskRuntimeWorkflowArtifactMap.from(it))
      } ?: return null
    return FeatureTaskRuntimePhaseStateRequest(
      workflowId,
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
  }

  internal fun recoverProjection(
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
      } ?: run {
        signal(workflowId, null, null, "projection_refusal", "authoritative_state_absent")
        return AcceptanceCriterionRepairWriteResult.Refused(AcceptanceCriterionRepairRefusal.UNSAFE_LEGACY_ADOPTION)
      }
    return project(repositoryRoot, workflowId, AcceptanceCriterionRepairWriteResult.Committed(state, true))
  }

  private fun owningRefusal(reason: OwningTransactionResult.Refusal): AcceptanceCriterionRepairRefusal =
    reason.toRepairRefusal()

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
    binding: AcceptanceCriterionRepairBinding,
  ): AcceptanceCriterionRepairState? =
    when (mutation) {
      is AcceptanceCriterionRepairMutation.ProseAudit,
      AcceptanceCriterionRepairMutation.KeepProgress,
      is AcceptanceCriterionRepairMutation.Clarification,
      is AcceptanceCriterionRepairMutation.QaReaudit,
      is AcceptanceCriterionRepairMutation.InteractionTrace,
      AcceptanceCriterionRepairMutation.PrepareInteractionTrace,
      -> proseTransition(state, mutation)
      else -> repairTransition(state, mutation, auditDigest, binding)
    }

  private fun proseTransition(
    state: AcceptanceCriterionRepairState?,
    mutation: AcceptanceCriterionRepairMutation,
  ): AcceptanceCriterionRepairState? =
    when (mutation) {
      is AcceptanceCriterionRepairMutation.ProseAudit ->
        state?.recordProseAudit(mutation.audit)?.clearPendingClarification()
      AcceptanceCriterionRepairMutation.KeepProgress -> state?.retainProseProgress()
      is AcceptanceCriterionRepairMutation.QaReaudit -> state?.admitQaReaudit(mutation.criterionRefs)
      is AcceptanceCriterionRepairMutation.Clarification -> state?.retainClarification(mutation.question)
      is AcceptanceCriterionRepairMutation.InteractionTrace ->
        state?.recordInteractionTrace(mutation.settlement)?.clearPendingClarification()
      AcceptanceCriterionRepairMutation.PrepareInteractionTrace -> state
      else -> null
    }

  private fun repairTransition(
    state: AcceptanceCriterionRepairState?,
    mutation: AcceptanceCriterionRepairMutation,
    auditDigest: String?,
    binding: AcceptanceCriterionRepairBinding,
  ): AcceptanceCriterionRepairState? =
    when (mutation) {
      is AcceptanceCriterionRepairMutation.Discover -> discoveryTransition(state, mutation, binding)
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
            mutation.fileManifestIntroduced,
          )?.clearPendingClarification()
        }
      }
      is AcceptanceCriterionRepairMutation.Outcome ->
        state?.record(
          mutation.outcome.copy(
            auditBindingDigest = auditDigest,
          ),
        )?.clearPendingClarification()
      is AcceptanceCriterionRepairMutation.ProseAudit,
      AcceptanceCriterionRepairMutation.KeepProgress,
      is AcceptanceCriterionRepairMutation.Clarification,
      is AcceptanceCriterionRepairMutation.QaReaudit,
      is AcceptanceCriterionRepairMutation.InteractionTrace,
      AcceptanceCriterionRepairMutation.PrepareInteractionTrace,
      -> null
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
      is AcceptanceCriterionRepairMutation.QaReaudit,
      is AcceptanceCriterionRepairMutation.InteractionTrace,
      AcceptanceCriterionRepairMutation.PrepareInteractionTrace,
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
          expected.currentCriterionRef?.let { ref -> state?.criteria?.any { it.canonicalRef == ref } } == true &&
          state?.proseAudits?.any { mutation.audit.sameProducerAs(it) } == true &&
          (
            if (mutation.audit.criterionRef == null) {
              expected.reservationId == null
            } else {
              expected.currentCriterionRef == mutation.audit.criterionRef &&
                expected.reservationId == state.reservation?.reservationId
            }
          )
      AcceptanceCriterionRepairMutation.KeepProgress,
      is AcceptanceCriterionRepairMutation.Clarification,
      AcceptanceCriterionRepairMutation.PrepareInteractionTrace,
      -> expected.revision == state?.revision?.minus(1)
      is AcceptanceCriterionRepairMutation.QaReaudit ->
        expected.revision == state?.revision?.minus(1) && state?.pendingAuditRefs == mutation.criterionRefs
      is AcceptanceCriterionRepairMutation.InteractionTrace -> expected.revision == state?.revision?.minus(1)
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
      is AcceptanceCriterionRepairMutation.ProseAudit,
      AcceptanceCriterionRepairMutation.KeepProgress,
      is AcceptanceCriterionRepairMutation.Clarification,
      is AcceptanceCriterionRepairMutation.QaReaudit,
      is AcceptanceCriterionRepairMutation.InteractionTrace,
      AcceptanceCriterionRepairMutation.PrepareInteractionTrace,
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

private fun repairCompletedReplays(
  state: AcceptanceCriterionRepairState?,
  mutation: AcceptanceCriterionRepairMutation.RepairCompleted,
): Boolean {
  val identity =
    state?.reservation?.reservationId == mutation.reservationId &&
      state.completedRepairEvidenceRef == mutation.evidenceRef
  return identity && state.admittedTreeDigest == mutation.treeDigest &&
    state.admittedCheckpointId == mutation.checkpointId &&
    state.repairChangedPaths ==
    mutation.fileManifestIntroduced.distinct().sorted().take(
      AcceptanceCriterionGapLimits.MAX_CHANGED_PATHS,
    )
}

private fun discoveryTransition(
  state: AcceptanceCriterionRepairState?,
  mutation: AcceptanceCriterionRepairMutation.Discover,
  binding: AcceptanceCriterionRepairBinding,
): AcceptanceCriterionRepairState? {
  val admitted = AcceptanceCriterionRepairState.admit(mutation.admission)
  val replayed = admitted?.let { AcceptanceCriterionInteractionTargets.prepare(it, binding) }
  val seeded =
    admitted?.let { current ->
      val audited = mutation.initialAudit?.let(current::recordProseAudit) ?: current
      val clarified = mutation.initialClarification?.let(audited::retainClarification) ?: audited
      AcceptanceCriterionInteractionTargets.prepare(clarified, binding)
    }
  val seededReplay =
    replayed?.let { current ->
      val audited = mutation.initialAudit?.let(current::recordProseAudit) ?: current
      val clarified = mutation.initialClarification?.let(audited::retainClarification) ?: audited
      AcceptanceCriterionInteractionTargets.prepare(clarified, binding)
    }
  return if (state == null) {
    seeded
  } else if (
    state in listOf(admitted, replayed, seeded, seededReplay)
  ) {
    seededReplay
  } else {
    null
  }
}

private fun droppedLateFindings(
  state: AcceptanceCriterionRepairState?,
  mutation: AcceptanceCriterionRepairMutation,
): Int {
  val outcome = (mutation as? AcceptanceCriterionRepairMutation.Outcome)?.outcome ?: return 0
  val criterion = state?.criteria?.singleOrNull { it.canonicalRef == outcome.criterionRef } ?: return 0
  return when (outcome.kind) {
    AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS,
    AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE,
    ->
      AcceptanceGapVerificationResolver.resolve(
        state,
        criterion,
        outcome.evidenceRef,
        state.admittedTreeDigest == outcome.treeDigest,
        legacySatisfied = outcome.successful,
      ).droppedLateFindingCount
    else -> 0
  }
}

private fun phaseIdentityMatches(
  worker: AcquiredFeatureTaskRuntimeWorker,
  binding: AcceptanceCriterionRepairBinding,
  phase: FeatureTaskRuntimePhaseStateRequest,
): Boolean =
  phase.workflowId == worker.workflowId && binding.executionId == worker.workflowId &&
    worker.ownsAuditStep(phase.phaseId)

private fun auditEvidenceDigest(request: AcceptanceCriterionRepairCommit): String? =
  request.evidence?.let { sha256Hex(JsonCodec.valueToJsonString(it.payload)) }
