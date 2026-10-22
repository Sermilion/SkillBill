package skillbill.engine.diagnostics.block.recovery

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.issuekey.normalizeRequiredIssueKey
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.diagnostics.block.BlockIncidentAliases
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.goalrunner.manifest.GoalParentProjectionWriter
import skillbill.engine.goalrunner.status.releaseExecutionLeaseIfExpired
import skillbill.engine.work.observation.workerOwnership
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.blockincident.model.BlockIncidentRecoveryTransition
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeExecutionPlanValidator
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentPrerequisiteCheck
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentPrerequisiteKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecovery
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAction
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryEligibility
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryEligibilityResult
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryFacts
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryFailure
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryPrerequisiteInstruction
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryPreview
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryUnavailableReason
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException

@Inject
class BlockIncidentRecoveryService(
  private val database: DatabaseSessionFactory,
  private val clock: Clock,
  private val executionPlanValidator: FeatureTaskRuntimeExecutionPlanValidator,
  private val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor,
  private val diagnostics: RuntimeDiagnostics,
  private val identifiers: IdentifierGeneratorPort,
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val actions: BlockIncidentRecoveryActions,
) {
  private val engine = WorkflowEngine()
  private val parentProjection = GoalParentProjectionWriter(engine, decompositionManifestValidator)
  private val resume = BlockIncidentRecoveryResume(database, clock, identifiers, diagnostics)

  internal fun preview(request: BlockIncidentRecoveryRequest): BlockIncidentRecoveryResult {
    val key =
      BlockIncidentRecoveryAttempt.idempotencyKey(
        request.incidentToken,
        request.action,
        request.incidentRevision,
      )
    val result =
      database.transactionFenced(request.databaseFileObservation) { unit ->
        if (targetMismatch(unit, request)) {
          return@transactionFenced BlockIncidentRecoveryResult.Refused(BlockIncidentRecoveryRefusal.TARGET_MISMATCH)
        }
        unit.blockIncidentRecoveries.byIdempotencyKey(key)?.let {
          return@transactionFenced BlockIncidentRecoveryResult.Existing(it)
        }
        when (val admission = admit(unit, request)) {
          is BlockIncidentRecoveryAdmission.Rejected -> admission.result
          is BlockIncidentRecoveryAdmission.Admitted -> BlockIncidentRecoveryResult.Previewed(admission.preview)
        }
      }
    return when (result) {
      FencedTransactionResult.ChangedTarget ->
        BlockIncidentRecoveryResult.Refused(BlockIncidentRecoveryRefusal.CHANGED_DATABASE_FILE)
      is FencedTransactionResult.Committed -> result.value
    }
  }

  internal fun apply(request: BlockIncidentRecoveryRequest): BlockIncidentRecoveryResult {
    val key =
      BlockIncidentRecoveryAttempt.idempotencyKey(
        request.incidentToken,
        request.action,
        request.incidentRevision,
      )
    var dispatched: BlockIncidentRecoveryAttempt? = null
    val result =
      runCatching {
        database.transactionFenced(request.databaseFileObservation) { unit ->
          applyInTransaction(unit, request, key, clock.instant()) { dispatched = it }
        }
      }.getOrElse { failure ->
        if (failure is CancellationException) throw failure
        val admitted = dispatched ?: throw failure
        return settleActionException(request, admitted, failure)
      }
    return when (result) {
      FencedTransactionResult.ChangedTarget ->
        BlockIncidentRecoveryResult.Refused(BlockIncidentRecoveryRefusal.CHANGED_DATABASE_FILE)
      is FencedTransactionResult.Committed -> result.value
    }
  }

  private fun applyInTransaction(
    unit: UnitOfWork,
    request: BlockIncidentRecoveryRequest,
    key: String,
    now: Instant,
    dispatched: (BlockIncidentRecoveryAttempt) -> Unit,
  ): BlockIncidentRecoveryResult {
    if (targetMismatch(unit, request)) {
      return BlockIncidentRecoveryResult.Refused(BlockIncidentRecoveryRefusal.TARGET_MISMATCH)
    }
    unit.blockIncidentRecoveries.byIdempotencyKey(key)?.let { return BlockIncidentRecoveryResult.Existing(it) }
    return when (val admission = admit(unit, request)) {
      is BlockIncidentRecoveryAdmission.Rejected -> admission.result
      is BlockIncidentRecoveryAdmission.Admitted -> {
        val requested = requestAttempt(unit, request, admission, key, now)
        val applying = claimApplying(unit, requested, admission.facts, now)
        if (applying == null) {
          BlockIncidentRecoveryResult.Existing(unit.blockIncidentRecoveries.byIdempotencyKey(key) ?: requested)
        } else {
          dispatched(applying)
          applyAdmitted(unit, admission, applying, now)
        }
      }
    }
  }

  private fun requestAttempt(
    unit: UnitOfWork,
    request: BlockIncidentRecoveryRequest,
    admission: BlockIncidentRecoveryAdmission.Admitted,
    key: String,
    now: Instant,
  ): BlockIncidentRecoveryAttempt =
    unit.blockIncidentRecoveries.insertIfAbsent(
      BlockIncidentRecoveryAttempt(
        attemptId = ATTEMPT_ID_PREFIX + identifiers.randomToken(),
        occurrenceToken = admission.incident.occurrence.occurrenceToken,
        action = request.action,
        incidentRevision = request.incidentRevision,
        idempotencyKey = key,
        status = BlockIncidentRecoveryAttemptStatus.REQUESTED,
        failure = null,
        ownerToken = null,
        ownerGeneration = null,
        ownerExpiresAt = null,
        requestedAt = now,
        updatedAt = now,
        targetWorkflowId = admission.target.launchWorkflowId,
        targetInvocationId = admission.target.invocationId,
      ),
    )

  private fun claimApplying(
    unit: UnitOfWork,
    requested: BlockIncidentRecoveryAttempt,
    facts: BlockIncidentRecoveryFacts,
    now: Instant,
  ): BlockIncidentRecoveryAttempt? {
    if (requested.status != BlockIncidentRecoveryAttemptStatus.REQUESTED) return null
    val ownerToken = identifiers.randomToken()
    val generation = requireNotNull(facts.currentGeneration) { "Eligible recovery target has no worker generation." }
    val moved =
      unit.blockIncidentRecoveries.transition(
        BlockIncidentRecoveryTransition(
          requested.attemptId,
          BlockIncidentRecoveryAttemptStatus.REQUESTED,
          BlockIncidentRecoveryAttemptStatus.APPLYING,
          null,
          ownerToken,
          generation,
          now.plus(APPLY_LEASE),
          now,
        ),
      )
    if (!moved) return null
    return unit.blockIncidentRecoveries.byIdempotencyKey(requested.idempotencyKey)
      ?.takeIf { it.status == BlockIncidentRecoveryAttemptStatus.APPLYING && it.ownerToken == ownerToken }
      ?: error("Admitted recovery attempt '${requested.attemptId}' was not readable after APPLYING.")
  }

  private fun applyAdmitted(
    unit: UnitOfWork,
    admission: BlockIncidentRecoveryAdmission.Admitted,
    applying: BlockIncidentRecoveryAttempt,
    now: Instant,
  ): BlockIncidentRecoveryResult {
    val target = admission.target
    val incident = admission.incident
    val failure =
      when {
        !releaseStoppedWorker(unit, target.mutationWorkflowId, now) ||
          !releaseStoppedGoalOwners(unit, ownershipWorkflowIds(incident, target), now) ||
          !releaseStoppedStandaloneInvocation(unit, target.invocationId, now) ->
          BlockIncidentRecoveryFailure.OWNERSHIP_MISMATCH
        actions.apply(unit, applying.action, target, applying) != BlockIncidentRecoveryActionResult.Applied ->
          BlockIncidentRecoveryFailure.ACTION_FAILED
        !blockClearedAtTarget(unit, target) -> BlockIncidentRecoveryFailure.BLOCK_NOT_CLEARED
        else -> null
      }
    if (failure != null) return settleFailed(unit, incident, applying, failure, now)
    if (target.executionIdentity.routeScope == FeatureTaskRouteScope.GOAL_CHILD) {
      reopenGoalParentProjection(unit, incident, target)
    }
    check(
      unit.blockIncidentRecoveries.transition(
        BlockIncidentRecoveryTransition(
          applying.attemptId,
          BlockIncidentRecoveryAttemptStatus.APPLYING,
          BlockIncidentRecoveryAttemptStatus.APPLIED,
          null,
          applying.ownerToken,
          applying.ownerGeneration,
          applying.ownerExpiresAt,
          now,
        ),
      ),
    ) { "Recovery attempt '${applying.attemptId}' lost ownership while settling APPLIED." }
    return BlockIncidentRecoveryResult.Applied(
      unit.blockIncidentRecoveries.byIdempotencyKey(applying.idempotencyKey)
        ?: error("Applied recovery attempt disappeared."),
    )
  }

  internal fun claimResume(
    observation: DatabaseFileObservation,
    idempotencyKey: String,
  ): BlockIncidentRecoveryResumeClaim = resume.claim(observation, idempotencyKey)

  internal fun settleResume(
    observation: DatabaseFileObservation,
    claim: BlockIncidentRecoveryResumeClaim.Claimed,
    launched: Boolean,
  ): BlockIncidentRecoveryResult = resume.settle(observation, claim, launched)

  internal fun requestBasis(incidentToken: String): BlockIncidentRecoveryBasis? =
    database.read { unit ->
      unit.blockIncidents.find(incidentToken)?.let { incident ->
        BlockIncidentRecoveryBasis(
          unit.blockIncidents.diagnoses(incidentToken).lastOrNull()?.proposedRecoveryAction
            ?: BlockIncidentRecoveryAction.NONE,
          incident.state.revision,
        )
      }
    }

  private fun targetMismatch(
    unit: UnitOfWork,
    request: BlockIncidentRecoveryRequest,
  ): Boolean {
    val requested = request.requestedTargetId ?: return false
    val occurrence = unit.blockIncidents.find(request.incidentToken)?.occurrence ?: return false
    return if (requested in
      listOfNotNull(
        occurrence.workflowId,
        occurrence.parentWorkflowId,
        occurrence.invocationId,
      )
    ) {
      false
    } else {
      !matchesStandaloneTarget(unit, requested, occurrence)
    }
  }

  private fun matchesStandaloneTarget(
    unit: UnitOfWork,
    requested: String,
    occurrence: BlockIncidentOccurrence,
  ): Boolean {
    val phaseId = occurrence.phaseId ?: return false
    val invocation = unit.standalonePhaseStatuses.readByInvocation(requested) ?: return false
    val alias = BlockIncidentAliases.standalonePhase(requested, phaseId)
    return invocation.workflowId == occurrence.workflowId && invocation.phaseId == phaseId &&
      unit.blockIncidents.findByAlias(alias)?.occurrence?.occurrenceToken == occurrence.occurrenceToken
  }

  private fun admit(
    unit: UnitOfWork,
    request: BlockIncidentRecoveryRequest,
  ): BlockIncidentRecoveryAdmission {
    val incident =
      unit.blockIncidents.find(request.incidentToken)
        ?: return refused(request, BlockIncidentRecoveryRefusal.INCIDENT_NOT_FOUND)
    val requestRefusal = requestRefusal(unit, request, incident)
    if (requestRefusal != null) return refused(request, requestRefusal)
    val diagnosis = unit.blockIncidents.diagnoses(request.incidentToken).lastOrNull()
    val diagnosisRefusal = diagnosisRefusal(request, incident, diagnosis)
    if (diagnosisRefusal != null) return refused(request, diagnosisRefusal)
    return when (val resolved = BlockIncidentRecoveryTargetResolver.resolve(unit, incident, executionPlanValidator)) {
      is BlockIncidentRecoveryTargetResolution.Unavailable -> {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Block incident recovery target unavailable for '${request.incidentToken}': ${resolved.failure}.",
        )
        BlockIncidentRecoveryAdmission.Rejected(BlockIncidentRecoveryResult.TargetUnavailable(resolved.failure))
      }
      is BlockIncidentRecoveryTargetResolution.Resolved ->
        admitTarget(unit, request, incident, requireNotNull(diagnosis), resolved.target)
    }
  }

  private fun requestRefusal(
    unit: UnitOfWork,
    request: BlockIncidentRecoveryRequest,
    incident: BlockIncident,
  ): BlockIncidentRecoveryRefusal? =
    when {
      incident.state.revision != request.incidentRevision -> BlockIncidentRecoveryRefusal.STALE_INCIDENT
      unit.blockIncidentRecoveries.inProgress(request.incidentToken) != null ->
        BlockIncidentRecoveryRefusal.RECOVERY_IN_PROGRESS
      else -> null
    }

  private fun diagnosisRefusal(
    request: BlockIncidentRecoveryRequest,
    incident: BlockIncident,
    diagnosis: BlockIncidentDiagnosisRecord?,
  ): BlockIncidentRecoveryRefusal? =
    when {
      diagnosis == null || diagnosis.occurrenceToken != incident.occurrence.occurrenceToken ->
        BlockIncidentRecoveryRefusal.DIAGNOSIS_UNAVAILABLE
      incident.state.diagnosis == BlockIncidentDiagnosis.PENDING ||
        incident.state.diagnosis == BlockIncidentDiagnosis.INVESTIGATING ->
        BlockIncidentRecoveryRefusal.INVESTIGATION_NOT_SETTLED
      diagnosis.proposedRecoveryAction != request.action -> BlockIncidentRecoveryRefusal.ACTION_CHANGED
      request.action == BlockIncidentRecoveryAction.NONE -> BlockIncidentRecoveryRefusal.NO_SUPPORTED_ACTION
      else -> null
    }

  private fun refused(
    request: BlockIncidentRecoveryRequest,
    refusal: BlockIncidentRecoveryRefusal,
  ): BlockIncidentRecoveryAdmission.Rejected {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Block incident recovery refused for '${request.incidentToken}': $refusal.",
    )
    return BlockIncidentRecoveryAdmission.Rejected(BlockIncidentRecoveryResult.Refused(refusal))
  }

  private fun admitTarget(
    unit: UnitOfWork,
    request: BlockIncidentRecoveryRequest,
    incident: BlockIncident,
    diagnosis: BlockIncidentDiagnosisRecord,
    target: BlockIncidentRecoveryTarget,
  ): BlockIncidentRecoveryAdmission {
    val now = clock.instant()
    val invocationId = target.invocationId
    if (invocationId != null && !standaloneInvocationIsRecorded(unit, invocationId)) {
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "Block incident recovery invocation evidence missing for '${request.incidentToken}'.",
      )
      return BlockIncidentRecoveryAdmission.Rejected(
        BlockIncidentRecoveryResult.TargetUnavailable(BlockIncidentRecoveryTargetFailure.MISSING_INVOCATION),
      )
    }
    if (!ownersAreStopped(unit, incident, target, now)) {
      return refused(request, BlockIncidentRecoveryRefusal.OWNERSHIP_MISMATCH)
    }
    return when (
      val read =
        BlockIncidentRecoveryFactsReader.read(
          unit,
          incident,
          target,
          request.confirmedPrerequisites,
        )
    ) {
      is BlockIncidentRecoveryFactsRead.Unavailable -> {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Block incident recovery policy facts unavailable for '${request.incidentToken}': ${read.reason}.",
        )
        BlockIncidentRecoveryAdmission.Rejected(
          BlockIncidentRecoveryResult.Refused(BlockIncidentRecoveryRefusal.POLICY_FACTS_UNAVAILABLE),
        )
      }
      is BlockIncidentRecoveryFactsRead.Read -> admitPolicy(incident, diagnosis, target, read.facts)
    }
  }

  private fun ownersAreStopped(
    unit: UnitOfWork,
    incident: BlockIncident,
    target: BlockIncidentRecoveryTarget,
    now: Instant,
  ): Boolean {
    val ids = ownershipWorkflowIds(incident, target)
    return goalOwnersAreStopped(unit, ids, now) && workerOwnersAreStopped(unit, ids, now) &&
      (target.invocationId == null || standaloneInvocationOwnerIsStopped(unit, target.invocationId, now))
  }

  private fun admitPolicy(
    incident: BlockIncident,
    diagnosis: BlockIncidentDiagnosisRecord,
    target: BlockIncidentRecoveryTarget,
    facts: BlockIncidentRecoveryFacts,
  ): BlockIncidentRecoveryAdmission =
    when (val eligibility = BlockIncidentRecoveryEligibility.evaluate(incident, diagnosis, facts)) {
      is BlockIncidentRecoveryEligibilityResult.Unavailable ->
        BlockIncidentRecoveryAdmission.Rejected(BlockIncidentRecoveryResult.Ineligible(eligibility.reason))
      is BlockIncidentRecoveryEligibilityResult.NeedsPrerequisites -> {
        val confirmable =
          diagnosis.prerequisites
            .filter { it.check == BlockIncidentPrerequisiteCheck.USER_CONFIRMED }.map { it.kind }
        val planned =
          BlockIncidentRecoveryEligibility.evaluate(
            incident,
            diagnosis,
            facts.copy(confirmedPrerequisites = facts.confirmedPrerequisites + confirmable),
          ) as? BlockIncidentRecoveryEligibilityResult.Available
        BlockIncidentRecoveryAdmission.Rejected(
          BlockIncidentRecoveryResult.PrerequisitesRequired(eligibility.instructions, planned?.preview),
        )
      }
      is BlockIncidentRecoveryEligibilityResult.Available ->
        BlockIncidentRecoveryAdmission.Admitted(incident, target, facts, eligibility.preview)
    }

  private fun settleActionException(
    request: BlockIncidentRecoveryRequest,
    admitted: BlockIncidentRecoveryAttempt,
    failure: Throwable,
  ): BlockIncidentRecoveryResult {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Block incident recovery attempt '${admitted.attemptId}' failed while applying " +
        "${admitted.action.wireValue}: ${failure::class.simpleName}.",
    )
    val settled =
      database.transactionFenced(request.databaseFileObservation) { unit ->
        unit.blockIncidentRecoveries.byIdempotencyKey(admitted.idempotencyKey)?.let { stored ->
          return@transactionFenced if (stored.status == BlockIncidentRecoveryAttemptStatus.APPLIED) {
            BlockIncidentRecoveryResult.Applied(stored)
          } else {
            BlockIncidentRecoveryResult.Existing(stored)
          }
        }
        val incident =
          unit.blockIncidents.find(admitted.occurrenceToken)
            ?.takeIf { it.state.revision == admitted.incidentRevision }
            ?: return@transactionFenced BlockIncidentRecoveryResult.Refused(BlockIncidentRecoveryRefusal.STALE_INCIDENT)
        val now = clock.instant()
        val requested =
          unit.blockIncidentRecoveries.insertIfAbsent(
            admitted.copy(
              status = BlockIncidentRecoveryAttemptStatus.REQUESTED,
              failure = null,
              ownerToken = null,
              ownerGeneration = null,
              ownerExpiresAt = null,
              updatedAt = now,
            ),
          )
        if (requested.status != BlockIncidentRecoveryAttemptStatus.REQUESTED) {
          return@transactionFenced BlockIncidentRecoveryResult.Existing(requested)
        }
        check(
          unit.blockIncidentRecoveries.transition(
            BlockIncidentRecoveryTransition(
              requested.attemptId,
              BlockIncidentRecoveryAttemptStatus.REQUESTED,
              BlockIncidentRecoveryAttemptStatus.APPLYING,
              null,
              admitted.ownerToken,
              admitted.ownerGeneration,
              admitted.ownerExpiresAt,
              now,
            ),
          ),
        ) { "Recovery attempt '${admitted.attemptId}' could not reclaim APPLYING while settling an action exception." }
        val applying =
          unit.blockIncidentRecoveries.byIdempotencyKey(admitted.idempotencyKey)
            ?: error("Recovery attempt '${admitted.attemptId}' disappeared while settling an action exception.")
        settleFailed(unit, incident, applying, BlockIncidentRecoveryFailure.ACTION_FAILED, now)
      }
    return when (settled) {
      FencedTransactionResult.ChangedTarget ->
        BlockIncidentRecoveryResult.Refused(BlockIncidentRecoveryRefusal.CHANGED_DATABASE_FILE)
      is FencedTransactionResult.Committed -> settled.value
    }
  }

  private fun settleFailed(
    unit: UnitOfWork,
    incident: BlockIncident,
    applying: BlockIncidentRecoveryAttempt,
    failure: BlockIncidentRecoveryFailure,
    now: Instant,
  ): BlockIncidentRecoveryResult {
    check(
      unit.blockIncidentRecoveries.transition(
        BlockIncidentRecoveryTransition(
          applying.attemptId,
          BlockIncidentRecoveryAttemptStatus.APPLYING,
          BlockIncidentRecoveryAttemptStatus.FAILED,
          failure,
          applying.ownerToken,
          applying.ownerGeneration,
          null,
          now,
        ),
      ),
    ) { "Recovery attempt '${applying.attemptId}' lost ownership while settling ${failure.wireValue}." }
    recordBlockIncidentRecoveryOutcome(unit, incident, BlockIncidentRecovery.FAILED)
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Block incident recovery attempt '${applying.attemptId}' failed with ${failure.wireValue} " +
        "for occurrence '${incident.occurrence.occurrenceToken}'.",
    )
    return BlockIncidentRecoveryResult.Failed(
      unit.blockIncidentRecoveries.byIdempotencyKey(
        applying.idempotencyKey,
      ) ?: error("Failed recovery attempt disappeared."),
    )
  }

  private fun releaseStoppedWorker(
    unit: UnitOfWork,
    workflowId: String,
    now: Instant,
  ): Boolean {
    val ownership = unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId) ?: return true
    if (ownership.expiresAtInstant.isAfter(now) ||
      workerSupervisor.inspect(ownership) != FeatureTaskRuntimeProcessInspection.NotRunning
    ) {
      return false
    }
    return unit.workflowStates.releaseFeatureTaskRuntimeWorkerIfExpired(
      workflowId,
      ownership.ownerToken,
      ownership.generation,
      now.toString(),
    )
  }

  private fun releaseStoppedGoalOwners(
    unit: UnitOfWork,
    workflowIds: List<String>,
    now: Instant,
  ): Boolean {
    for (workflowId in workflowIds) {
      val lease = unit.goalRunnerControls.controlState(workflowId).executionLease ?: continue
      if (!goalOwnersAreStopped(unit, listOf(workflowId), now) ||
        !unit.goalRunnerControls.releaseExecutionLeaseIfExpired(
          workflowId,
          lease.ownerToken,
          lease.generation,
          now.toString(),
        )
      ) {
        return false
      }
    }
    return true
  }

  private fun releaseStoppedStandaloneInvocation(
    unit: UnitOfWork,
    invocationId: String?,
    now: Instant,
  ): Boolean {
    if (invocationId == null) return true
    if (!standaloneInvocationOwnerIsStopped(unit, invocationId, now)) return false
    val invocation = unit.standalonePhaseStatuses.readByInvocation(invocationId) ?: return false
    return when (unit.standalonePhaseStatuses.releaseExpiredLease(invocation, now)) {
      StandalonePhaseStatusUpdateResult.ACCEPTED, StandalonePhaseStatusUpdateResult.IDEMPOTENT -> true
      else -> false
    }
  }

  private fun blockClearedAtTarget(
    unit: UnitOfWork,
    target: BlockIncidentRecoveryTarget,
  ): Boolean {
    val reread = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, target.mutationWorkflowId) ?: return false
    val identityMatches =
      unit.workflowStates.getFeatureTaskExecutionIdentity(
        target.mutationWorkflowId,
      ) == target.executionIdentity
    val step = reread.steps.firstOrNull { it.stepId == target.phaseId }
    val phase = decodePhaseRecords(reread.artifacts)[target.phaseId]
    val phaseCleared =
      step != null && phase != null && step.status != WorkflowStepStatus.BLOCKED &&
        phase.status != WorkflowStepStatus.BLOCKED
    return reread.workflowStatus != WorkflowStatus.BLOCKED && identityMatches && phaseCleared
  }

  private fun reopenGoalParentProjection(
    unit: UnitOfWork,
    incident: BlockIncident,
    target: BlockIncidentRecoveryTarget,
  ) {
    val parentWorkflowId =
      checkNotNull(incident.occurrence.parentWorkflowId) {
        "Goal-child recovery target '${target.mutationWorkflowId}' has no parent workflow."
      }
    val parent =
      checkNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId)) {
        "Goal parent workflow '$parentWorkflowId' disappeared during recovery."
      }
    val manifest =
      checkNotNull(parent.decompositionRuntime()) {
        "Goal parent workflow '$parentWorkflowId' has no decomposition manifest."
      }
    val subtask =
      checkNotNull(manifest.subtasks.singleOrNull { it.workflowId == target.mutationWorkflowId }) {
        "Goal parent workflow '$parentWorkflowId' has no single subtask for child '${target.mutationWorkflowId}'."
      }
    val subtasks =
      manifest.subtasks.map {
        if (it.id == subtask.id && it.status.decompositionStatus() == DecompositionStatus.BLOCKED) {
          it.copy(
            status = DecompositionStatus.IN_PROGRESS.wireValue,
            blockedReason = null,
            lastResumableStep = target.phaseId,
          )
        } else {
          it
        }
      }
    val parentStillBlocked = subtasks.any { it.status.decompositionStatus() == DecompositionStatus.BLOCKED }
    val reopened =
      manifest.copy(
        subtasks = subtasks,
        status =
          if (!parentStillBlocked && manifest.status.decompositionStatus() == DecompositionStatus.BLOCKED) {
            DecompositionStatus.IN_PROGRESS.wireValue
          } else {
            manifest.status
          },
      )
    val parentStatus =
      if (!parentStillBlocked && parent.workflowStatus == WorkflowStatus.BLOCKED) {
        WorkflowStatus.RUNNING
      } else {
        parent.workflowStatus
      }
    if (reopened != manifest || parentStatus != parent.workflowStatus) {
      val updated =
        engine.updateRecord(
          WorkflowFamily.TASK_RUNTIME.definition,
          parent,
          WorkflowUpdateInput(
            workflowStatus = parentStatus,
            currentStepId = parent.currentStepId,
            stepUpdates = null,
            artifactsPatch = parentProjection.artifacts(reopened, parent.artifacts),
            sessionId = parent.sessionId,
            replaceArtifacts = true,
          ),
        )
      unit.workflowStates.saveRecord(
        WorkflowFamily.TASK_RUNTIME,
        updated.toRecord().copy(issueKey = normalizeRequiredIssueKey(reopened.issueKey)),
      )
    }
    verifyParentProjection(unit, parentWorkflowId, target, parentStillBlocked)
  }

  private fun verifyParentProjection(
    unit: UnitOfWork,
    parentWorkflowId: String,
    target: BlockIncidentRecoveryTarget,
    parentStillBlocked: Boolean,
  ) {
    val reread = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId)
    val rereadSubtask =
      reread?.decompositionRuntime()?.subtasks?.singleOrNull {
        it.workflowId == target.mutationWorkflowId
      }
    check(
      reread != null && rereadSubtask != null &&
        rereadSubtask.status.decompositionStatus() != DecompositionStatus.BLOCKED &&
        (parentStillBlocked || reread.workflowStatus != WorkflowStatus.BLOCKED),
    ) { "Goal parent workflow '$parentWorkflowId' still reports child '${target.mutationWorkflowId}' as blocked." }
  }

  private fun ownershipWorkflowIds(
    incident: BlockIncident,
    target: BlockIncidentRecoveryTarget,
  ): List<String> = listOfNotNull(incident.occurrence.parentWorkflowId, target.mutationWorkflowId).distinct()

  private fun workerOwnersAreStopped(
    unit: UnitOfWork,
    workflowIds: List<String>,
    now: Instant,
  ): Boolean {
    for (workflowId in workflowIds) {
      val ownership = unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId) ?: continue
      if (ownership.expiresAtInstant.isAfter(now)) return false
      if (workerSupervisor.inspect(ownership) != FeatureTaskRuntimeProcessInspection.NotRunning) return false
    }
    return true
  }

  private fun goalOwnersAreStopped(
    unit: UnitOfWork,
    workflowIds: List<String>,
    now: Instant,
  ): Boolean {
    for (workflowId in workflowIds) {
      val lease = unit.goalRunnerControls.controlState(workflowId).executionLease ?: continue
      val workflow = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId) ?: return false
      val phaseId = workflow.currentStepId
      val phaseAttempt = workflow.steps.firstOrNull { it.stepId == phaseId }?.attemptCount ?: return false
      val ownership = lease.asWorkerOwnership(workflowId, phaseId, phaseAttempt)
      if (lease.expiresAtInstant.isAfter(now) ||
        workerSupervisor.inspect(ownership) != FeatureTaskRuntimeProcessInspection.NotRunning
      ) {
        return false
      }
    }
    return true
  }

  private fun standaloneInvocationOwnerIsStopped(
    unit: UnitOfWork,
    invocationId: String,
    now: Instant,
  ): Boolean {
    val invocation = unit.standalonePhaseStatuses.readByInvocation(invocationId) ?: return false
    if (invocation.leaseExpiresAt.isAfter(now)) return false
    val ownership = invocation.workerOwnership() ?: return false
    return workerSupervisor.inspect(ownership) == FeatureTaskRuntimeProcessInspection.NotRunning
  }

  private fun standaloneInvocationIsRecorded(
    unit: UnitOfWork,
    invocationId: String,
  ): Boolean = unit.standalonePhaseStatuses.readByInvocation(invocationId) != null

  private companion object {
    const val ATTEMPT_ID_PREFIX = "bra_"
    val APPLY_LEASE: Duration = Duration.ofMinutes(5)
  }

  private fun GoalRunnerExecutionLease.asWorkerOwnership(
    workflowId: String,
    phaseId: String,
    phaseAttempt: Int,
  ): FeatureTaskRuntimeWorkerOwnership =
    FeatureTaskRuntimeWorkerOwnership(
      workflowId = workflowId,
      generation = generation,
      ownerToken = ownerToken,
      hostIdentity = hostIdentity,
      bootIdentity = bootIdentity,
      pid = pid,
      processBirthToken = processBirthToken,
      leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
      heartbeatAt = heartbeatAt.toString(),
      expiresAt = expiresAt.toString(),
      phaseId = phaseId,
      phaseAttempt = phaseAttempt,
    )
}

internal data class BlockIncidentRecoveryRequest(
  val databaseFileObservation: DatabaseFileObservation,
  val incidentToken: String,
  val incidentRevision: Long,
  val action: BlockIncidentRecoveryAction,
  val confirmedPrerequisites: Set<BlockIncidentPrerequisiteKind>,
  val requestedTargetId: String? = null,
)

internal data class BlockIncidentRecoveryBasis(
  val action: BlockIncidentRecoveryAction,
  val incidentRevision: Long,
)

internal sealed interface BlockIncidentRecoveryResult {
  data class Existing(val attempt: BlockIncidentRecoveryAttempt) : BlockIncidentRecoveryResult

  data class Refused(val reason: BlockIncidentRecoveryRefusal) : BlockIncidentRecoveryResult

  data class TargetUnavailable(val reason: BlockIncidentRecoveryTargetFailure) : BlockIncidentRecoveryResult

  data class Ineligible(val reason: BlockIncidentRecoveryUnavailableReason) : BlockIncidentRecoveryResult

  data class PrerequisitesRequired(
    val instructions: List<BlockIncidentRecoveryPrerequisiteInstruction>,
    val preview: BlockIncidentRecoveryPreview? = null,
  ) : BlockIncidentRecoveryResult

  data class Applied(val attempt: BlockIncidentRecoveryAttempt) : BlockIncidentRecoveryResult

  data class Failed(val attempt: BlockIncidentRecoveryAttempt) : BlockIncidentRecoveryResult

  data class Resumed(val attempt: BlockIncidentRecoveryAttempt) : BlockIncidentRecoveryResult

  data class Previewed(val preview: BlockIncidentRecoveryPreview) : BlockIncidentRecoveryResult
}

private sealed interface BlockIncidentRecoveryAdmission {
  data class Rejected(val result: BlockIncidentRecoveryResult) : BlockIncidentRecoveryAdmission

  data class Admitted(
    val incident: BlockIncident,
    val target: BlockIncidentRecoveryTarget,
    val facts: BlockIncidentRecoveryFacts,
    val preview: BlockIncidentRecoveryPreview,
  ) : BlockIncidentRecoveryAdmission
}

internal enum class BlockIncidentRecoveryRefusal {
  INCIDENT_NOT_FOUND,
  STALE_INCIDENT,
  RECOVERY_IN_PROGRESS,
  DIAGNOSIS_UNAVAILABLE,
  INVESTIGATION_NOT_SETTLED,
  ACTION_CHANGED,
  NO_SUPPORTED_ACTION,
  OWNERSHIP_MISMATCH,
  POLICY_FACTS_UNAVAILABLE,
  CHANGED_DATABASE_FILE,
  TARGET_MISMATCH,
}
