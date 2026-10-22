package skillbill.engine.diagnostics.block.recovery

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.issuekey.normalizeRequiredIssueKey
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.goalrunner.status.releaseExecutionLeaseIfExpired
import skillbill.engine.work.observation.workerOwnership
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.engine.featuretask.lifecycle.remediation.FeatureTaskRuntimeStandaloneIncidentRecovery
import skillbill.engine.goalrunner.manifest.GoalParentProjectionWriter
import skillbill.engine.goalrunner.persist.WorkflowGoalRunnerBlockWrites
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.db.model.FencedTransactionResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.persistence.UnitOfWork
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
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncident
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
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
  decompositionManifestValidator: DecompositionManifestValidator,
) {
  private val engine = WorkflowEngine()
  private val parentProjection = GoalParentProjectionWriter(engine, decompositionManifestValidator)
  private val actions =
    BlockIncidentRecoveryActions(
      WorkflowGoalRunnerBlockWrites(engine, clock),
      FeatureTaskRuntimeStandaloneIncidentRecovery(engine, clock),
    )
  private val resume = BlockIncidentRecoveryResume(database, clock, identifiers, diagnostics)

  /** Read-only twin of [apply]: runs the same target, ownership, facts, and eligibility checks without writing. */
  internal fun preview(request: BlockIncidentRecoveryRequest): BlockIncidentRecoveryResult {
    val key = BlockIncidentRecoveryAttempt.idempotencyKey(
      request.incidentToken,
      request.action,
      request.incidentRevision,
    )
    val result = database.transactionFenced(request.databaseFileObservation) { unit ->
      if (targetMismatch(unit, request)) {
        return@transactionFenced BlockIncidentRecoveryResult.Refused(BlockIncidentRecoveryRefusal.TARGET_MISMATCH)
      }
      unit.blockIncidentRecoveries.byIdempotencyKey(key)?.let {
        return@transactionFenced BlockIncidentRecoveryResult.Existing(it)
      }
      when (val admission = admit(unit, request, clock.instant())) {
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
    val key = BlockIncidentRecoveryAttempt.idempotencyKey(
      request.incidentToken,
      request.action,
      request.incidentRevision,
    )
    var dispatched: BlockIncidentRecoveryAttempt? = null
    val result = try {
      database.transactionFenced(request.databaseFileObservation) { unit ->
        if (targetMismatch(unit, request)) {
          return@transactionFenced BlockIncidentRecoveryResult.Refused(BlockIncidentRecoveryRefusal.TARGET_MISMATCH)
        }
        unit.blockIncidentRecoveries.byIdempotencyKey(key)?.let {
          return@transactionFenced BlockIncidentRecoveryResult.Existing(it)
        }
        val now = clock.instant()
        val admission = when (val admission = admit(unit, request, now)) {
          is BlockIncidentRecoveryAdmission.Rejected -> return@transactionFenced admission.result
          is BlockIncidentRecoveryAdmission.Admitted -> admission
        }
        val incident = admission.incident
        val target = admission.target
        val verifiedGeneration = requireNotNull(admission.facts.currentGeneration) {
          "Eligible recovery target has no verified worker generation."
        }
        val requested = unit.blockIncidentRecoveries.insertIfAbsent(
          BlockIncidentRecoveryAttempt(
            attemptId = ATTEMPT_ID_PREFIX + identifiers.randomToken(),
            occurrenceToken = incident.occurrence.occurrenceToken,
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
            targetWorkflowId = target.launchWorkflowId,
            targetInvocationId = target.invocationId,
          ),
        )
        if (requested.status != BlockIncidentRecoveryAttemptStatus.REQUESTED) {
          return@transactionFenced BlockIncidentRecoveryResult.Existing(requested)
        }
        val ownerToken = identifiers.randomToken()
        val ownerExpiresAt = now.plus(APPLY_LEASE)
        if (!unit.blockIncidentRecoveries.transition(
            requested.attemptId,
            BlockIncidentRecoveryAttemptStatus.REQUESTED,
            BlockIncidentRecoveryAttemptStatus.APPLYING,
            null,
            ownerToken,
            verifiedGeneration,
            ownerExpiresAt,
            now,
          )
        ) {
          return@transactionFenced BlockIncidentRecoveryResult.Existing(
            unit.blockIncidentRecoveries.byIdempotencyKey(key) ?: requested,
          )
        }
        val applying = unit.blockIncidentRecoveries.byIdempotencyKey(key)
          ?.takeIf { it.status == BlockIncidentRecoveryAttemptStatus.APPLYING && it.ownerToken == ownerToken }
          ?: error("Admitted recovery attempt '${requested.attemptId}' was not readable after APPLYING.")
        dispatched = applying
        if (!releaseStoppedWorker(unit, target.mutationWorkflowId, now) ||
          !releaseStoppedGoalOwners(unit, ownershipWorkflowIds(incident, target), now) ||
          !releaseStoppedStandaloneInvocation(unit, target.invocationId, now)
        ) {
          return@transactionFenced settleFailed(
            unit,
            incident,
            applying,
            BlockIncidentRecoveryFailure.OWNERSHIP_MISMATCH,
            now,
          )
        }
        if (actions.apply(unit, request.action, target, applying) != BlockIncidentRecoveryActionResult.Applied) {
          return@transactionFenced settleFailed(unit, incident, applying, BlockIncidentRecoveryFailure.ACTION_FAILED, now)
        }
        if (!blockClearedAtTarget(unit, target)) {
          return@transactionFenced settleFailed(
            unit,
            incident,
            applying,
            BlockIncidentRecoveryFailure.BLOCK_NOT_CLEARED,
            now,
          )
        }
        if (target.executionIdentity.routeScope == FeatureTaskRouteScope.GOAL_CHILD) {
          reopenGoalParentProjection(unit, incident, target)
        }
        check(
          unit.blockIncidentRecoveries.transition(
            applying.attemptId,
            BlockIncidentRecoveryAttemptStatus.APPLYING,
            BlockIncidentRecoveryAttemptStatus.APPLIED,
            null,
            ownerToken,
            applying.ownerGeneration,
            ownerExpiresAt,
            now,
          ),
        ) { "Recovery attempt '${applying.attemptId}' lost ownership while settling APPLIED." }
        BlockIncidentRecoveryResult.Applied(
          unit.blockIncidentRecoveries.byIdempotencyKey(key) ?: error("Applied recovery attempt disappeared."),
        )
      }
    } catch (failure: RuntimeException) {
      if (failure is CancellationException) throw failure
      // No APPLYING row was readable yet, so nothing was admitted and the caller sees the original failure.
      val admitted = dispatched ?: throw failure
      return settleActionException(request, admitted, failure)
    }
    return when (result) {
      FencedTransactionResult.ChangedTarget ->
        BlockIncidentRecoveryResult.Refused(BlockIncidentRecoveryRefusal.CHANGED_DATABASE_FILE)
      is FencedTransactionResult.Committed -> result.value
    }
  }

  /**
   * Claims the APPLIED attempt with [idempotencyKey] for resume. Launch the resumed run only on
   * [BlockIncidentRecoveryResumeClaim.Claimed], then report the launch through [settleResume].
   */
  internal fun claimResume(
    observation: DatabaseFileObservation,
    idempotencyKey: String,
  ): BlockIncidentRecoveryResumeClaim = resume.claim(observation, idempotencyKey)

  /** Settles a claimed resume as RESUMED when [launched], otherwise as FAILED with RESUME_FAILED. */
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

  private fun targetMismatch(unit: UnitOfWork, request: BlockIncidentRecoveryRequest): Boolean {
    val requested = request.requestedTargetId ?: return false
    val occurrence = unit.blockIncidents.find(request.incidentToken)?.occurrence ?: return false
    return requested !in listOfNotNull(occurrence.workflowId, occurrence.parentWorkflowId, occurrence.invocationId)
  }

  /**
   * Admission checks shared by [preview] and [apply]. Every check reads inside the caller's transaction and
   * none writes, so preview and apply agree on the same snapshot.
   */
  private fun admit(
    unit: UnitOfWork,
    request: BlockIncidentRecoveryRequest,
    now: Instant,
  ): BlockIncidentRecoveryAdmission {
      val incident = unit.blockIncidents.find(request.incidentToken)
        ?: return BlockIncidentRecoveryAdmission.Rejected(BlockIncidentRecoveryResult.Refused(BlockIncidentRecoveryRefusal.INCIDENT_NOT_FOUND))
      if (incident.state.revision != request.incidentRevision) {
        return BlockIncidentRecoveryAdmission.Rejected(BlockIncidentRecoveryResult.Refused(BlockIncidentRecoveryRefusal.STALE_INCIDENT))
      }
      val inProgress = unit.blockIncidentRecoveries.inProgress(request.incidentToken)
      if (inProgress != null) {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Block incident recovery refused because attempt '${inProgress.attemptId}' is " +
            "${inProgress.status.wireValue} for occurrence '${incident.occurrence.occurrenceToken}'.",
        )
        return BlockIncidentRecoveryAdmission.Rejected(BlockIncidentRecoveryResult.Refused(BlockIncidentRecoveryRefusal.RECOVERY_IN_PROGRESS))
      }
      val diagnosis = unit.blockIncidents.diagnoses(request.incidentToken).lastOrNull()
        ?: run {
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "Block incident recovery refused because no valid diagnosis exists for " +
              "occurrence '${incident.occurrence.occurrenceToken}'.",
          )
          return BlockIncidentRecoveryAdmission.Rejected(BlockIncidentRecoveryResult.Refused(
            BlockIncidentRecoveryRefusal.DIAGNOSIS_UNAVAILABLE,
          ))
        }
      if (diagnosis.occurrenceToken != incident.occurrence.occurrenceToken) {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Block incident recovery refused because the latest diagnosis belongs to a different occurrence " +
            "than '${incident.occurrence.occurrenceToken}'.",
        )
        return BlockIncidentRecoveryAdmission.Rejected(BlockIncidentRecoveryResult.Refused(
          BlockIncidentRecoveryRefusal.DIAGNOSIS_UNAVAILABLE,
        ))
      }
      if (incident.state.diagnosis == BlockIncidentDiagnosis.PENDING ||
        incident.state.diagnosis == BlockIncidentDiagnosis.INVESTIGATING
      ) {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Block incident recovery refused because diagnosis is not settled for " +
            "occurrence '${incident.occurrence.occurrenceToken}'.",
        )
        return BlockIncidentRecoveryAdmission.Rejected(BlockIncidentRecoveryResult.Refused(
          BlockIncidentRecoveryRefusal.INVESTIGATION_NOT_SETTLED,
        ))
      }
      if (diagnosis.proposedRecoveryAction != request.action) {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Block incident recovery refused because the selected action changed for " +
            "occurrence '${incident.occurrence.occurrenceToken}'.",
        )
        return BlockIncidentRecoveryAdmission.Rejected(BlockIncidentRecoveryResult.Refused(BlockIncidentRecoveryRefusal.ACTION_CHANGED))
      }
      if (request.action == BlockIncidentRecoveryAction.NONE) {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Block incident recovery refused because the diagnosis has no supported action for " +
            "occurrence '${incident.occurrence.occurrenceToken}'.",
        )
        return BlockIncidentRecoveryAdmission.Rejected(BlockIncidentRecoveryResult.Refused(
          BlockIncidentRecoveryRefusal.NO_SUPPORTED_ACTION,
        ))
      }
      val target = when (
        val target = BlockIncidentRecoveryTargetResolver.resolve(
          unit,
          incident,
          executionPlanValidator,
        )
      ) {
        is BlockIncidentRecoveryTargetResolution.Unavailable -> {
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "Block incident recovery refused because target evidence is unavailable " +
              "(${target.failure}) for occurrence '${incident.occurrence.occurrenceToken}'.",
          )
          return BlockIncidentRecoveryAdmission.Rejected(BlockIncidentRecoveryResult.TargetUnavailable(target.failure))
        }
        is BlockIncidentRecoveryTargetResolution.Resolved -> target.target
      }
      val invocationId = target.invocationId
      if (invocationId != null && !standaloneInvocationIsRecorded(unit, invocationId)) {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Block incident recovery refused because standalone invocation evidence is missing " +
            "for occurrence '${incident.occurrence.occurrenceToken}'.",
        )
        return BlockIncidentRecoveryAdmission.Rejected(BlockIncidentRecoveryResult.TargetUnavailable(
          BlockIncidentRecoveryTargetFailure.MISSING_INVOCATION,
        ))
      }
      val ownershipWorkflowIds = ownershipWorkflowIds(incident, target)
      if (!goalOwnersAreStopped(unit, ownershipWorkflowIds, now)) {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Block incident recovery refused because goal execution ownership is live or unverifiable " +
            "for occurrence '${incident.occurrence.occurrenceToken}'.",
        )
        return BlockIncidentRecoveryAdmission.Rejected(BlockIncidentRecoveryResult.Refused(
          BlockIncidentRecoveryRefusal.OWNERSHIP_MISMATCH,
        ))
      }
      if (!workerOwnersAreStopped(unit, ownershipWorkflowIds, now)) {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Block incident recovery refused because worker ownership is live or unverifiable " +
            "for occurrence '${incident.occurrence.occurrenceToken}'.",
        )
        return BlockIncidentRecoveryAdmission.Rejected(BlockIncidentRecoveryResult.Refused(
          BlockIncidentRecoveryRefusal.OWNERSHIP_MISMATCH,
        ))
      }
      if (target.invocationId != null && !standaloneInvocationOwnerIsStopped(unit, target.invocationId, now)) {
        RuntimeDiagnosticsBestEffortWarning.record(
          diagnostics,
          "Block incident recovery refused because standalone invocation ownership is live or unverifiable " +
            "for occurrence '${incident.occurrence.occurrenceToken}'.",
        )
        return BlockIncidentRecoveryAdmission.Rejected(BlockIncidentRecoveryResult.Refused(
          BlockIncidentRecoveryRefusal.OWNERSHIP_MISMATCH,
        ))
      }
      val facts = when (
        val read = BlockIncidentRecoveryFactsReader.read(unit, incident, target, request.confirmedPrerequisites)
      ) {
        is BlockIncidentRecoveryFactsRead.Read -> read.facts
        is BlockIncidentRecoveryFactsRead.Unavailable -> {
          RuntimeDiagnosticsBestEffortWarning.record(
            diagnostics,
            "Block incident recovery refused because authoritative policy facts are unavailable " +
              "(${read.reason}) for occurrence '${incident.occurrence.occurrenceToken}'.",
          )
          return BlockIncidentRecoveryAdmission.Rejected(BlockIncidentRecoveryResult.Refused(
            BlockIncidentRecoveryRefusal.POLICY_FACTS_UNAVAILABLE,
          ))
        }
      }
      when (val eligibility = BlockIncidentRecoveryEligibility.evaluate(incident, diagnosis, facts)) {
        is BlockIncidentRecoveryEligibilityResult.Unavailable ->
          return BlockIncidentRecoveryAdmission.Rejected(BlockIncidentRecoveryResult.Ineligible(eligibility.reason))
        is BlockIncidentRecoveryEligibilityResult.NeedsPrerequisites -> {
          val confirmable = diagnosis.prerequisites
            .filter { it.check == BlockIncidentPrerequisiteCheck.USER_CONFIRMED }
            .map { it.kind }
          val planned = BlockIncidentRecoveryEligibility.evaluate(
            incident,
            diagnosis,
            facts.copy(confirmedPrerequisites = facts.confirmedPrerequisites + confirmable),
          ) as? BlockIncidentRecoveryEligibilityResult.Available
          return BlockIncidentRecoveryAdmission.Rejected(
            BlockIncidentRecoveryResult.PrerequisitesRequired(eligibility.instructions, planned?.preview),
          )
        }
        is BlockIncidentRecoveryEligibilityResult.Available ->
          return BlockIncidentRecoveryAdmission.Admitted(incident, target, facts, eligibility.preview)
      }
  }

  /**
   * The dispatch transaction rolled back, so the APPLYING row is gone. Replays the admitted attempt through the
   * legal REQUESTED, APPLYING, FAILED path in a new transaction fenced to the same database observation, and
   * records the incident summary against the admitted revision. A row that already exists wins, so an APPLIED
   * attempt whose commit raised afterwards is reported as applied, never downgraded.
   */
  private fun settleActionException(
    request: BlockIncidentRecoveryRequest,
    admitted: BlockIncidentRecoveryAttempt,
    failure: RuntimeException,
  ): BlockIncidentRecoveryResult {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Block incident recovery attempt '${admitted.attemptId}' failed while applying " +
        "${admitted.action.wireValue}: ${failure::class.simpleName}.",
    )
    val settled = database.transactionFenced(request.databaseFileObservation) { unit ->
      unit.blockIncidentRecoveries.byIdempotencyKey(admitted.idempotencyKey)?.let { stored ->
        return@transactionFenced if (stored.status == BlockIncidentRecoveryAttemptStatus.APPLIED) {
          BlockIncidentRecoveryResult.Applied(stored)
        } else {
          BlockIncidentRecoveryResult.Existing(stored)
        }
      }
      val incident = unit.blockIncidents.find(admitted.occurrenceToken)
        ?.takeIf { it.state.revision == admitted.incidentRevision }
        ?: return@transactionFenced BlockIncidentRecoveryResult.Refused(BlockIncidentRecoveryRefusal.STALE_INCIDENT)
      val now = clock.instant()
      val requested = unit.blockIncidentRecoveries.insertIfAbsent(
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
          requested.attemptId,
          BlockIncidentRecoveryAttemptStatus.REQUESTED,
          BlockIncidentRecoveryAttemptStatus.APPLYING,
          null,
          admitted.ownerToken,
          admitted.ownerGeneration,
          admitted.ownerExpiresAt,
          now,
        ),
      ) { "Recovery attempt '${admitted.attemptId}' could not reclaim APPLYING while settling an action exception." }
      val applying = unit.blockIncidentRecoveries.byIdempotencyKey(admitted.idempotencyKey)
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
        applying.attemptId,
        BlockIncidentRecoveryAttemptStatus.APPLYING,
        BlockIncidentRecoveryAttemptStatus.FAILED,
        failure,
        applying.ownerToken,
        applying.ownerGeneration,
        null,
        now,
      ),
    ) { "Recovery attempt '${applying.attemptId}' lost ownership while settling ${failure.wireValue}." }
    recordBlockIncidentRecoveryOutcome(unit, incident, BlockIncidentRecovery.FAILED)
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "Block incident recovery attempt '${applying.attemptId}' failed with ${failure.wireValue} " +
        "for occurrence '${incident.occurrence.occurrenceToken}'.",
    )
    return BlockIncidentRecoveryResult.Failed(
      unit.blockIncidentRecoveries.byIdempotencyKey(applying.idempotencyKey) ?: error("Failed recovery attempt disappeared."),
    )
  }

  /**
   * Runs after the attempt owns APPLYING. Admission already proved the lease expired and the process is gone,
   * so this only clears that exact expired lease; a lease that changed since admission fails the attempt.
   */
  private fun releaseStoppedWorker(unit: UnitOfWork, workflowId: String, now: Instant): Boolean {
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

  /**
   * Runs after the attempt owns APPLYING. Clears each expired goal execution lease that admission proved has no
   * live process; a lease that changed or renewed since admission fails the attempt.
   */
  private fun releaseStoppedGoalOwners(unit: UnitOfWork, workflowIds: List<String>, now: Instant): Boolean {
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

  /**
   * Runs after the attempt owns APPLYING. Settles the standalone invocation whose expired lease and stopped process
   * admission observed; a lease or revision that changed since admission fails the attempt.
   */
  private fun releaseStoppedStandaloneInvocation(unit: UnitOfWork, invocationId: String?, now: Instant): Boolean {
    if (invocationId == null) return true
    if (!standaloneInvocationOwnerIsStopped(unit, invocationId, now)) return false
    val invocation = unit.standalonePhaseStatuses.readByInvocation(invocationId) ?: return false
    return when (unit.standalonePhaseStatuses.releaseExpiredLease(invocation, now)) {
      StandalonePhaseStatusUpdateResult.ACCEPTED, StandalonePhaseStatusUpdateResult.IDEMPOTENT -> true
      else -> false
    }
  }

  /**
   * Confirms the action cleared the exact blocked phase: the same workflow and identity, no longer BLOCKED,
   * and the incident phase step and phase record both left BLOCKED.
   */
  private fun blockClearedAtTarget(unit: UnitOfWork, target: BlockIncidentRecoveryTarget): Boolean {
    val reread = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, target.mutationWorkflowId) ?: return false
    if (reread.workflowStatus == WorkflowStatus.BLOCKED) return false
    if (unit.workflowStates.getFeatureTaskExecutionIdentity(target.mutationWorkflowId) != target.executionIdentity) {
      return false
    }
    val step = reread.steps.firstOrNull { it.stepId == target.phaseId } ?: return false
    val phase = decodePhaseRecords(reread.artifacts)[target.phaseId] ?: return false
    return step.status != WorkflowStepStatus.BLOCKED && phase.status != WorkflowStepStatus.BLOCKED
  }

  /**
   * Goal route only. The child reopen leaves the parent decomposition projection reporting the subtask as blocked.
   * This moves that subtask back to in progress, clears the parent block when no other subtask is still blocked,
   * and rereads the parent to confirm. Any mismatch throws, which rolls back the child reopen in the same
   * transaction and settles the attempt through [settleActionException].
   */
  private fun reopenGoalParentProjection(
    unit: UnitOfWork,
    incident: BlockIncident,
    target: BlockIncidentRecoveryTarget,
  ) {
    val parentWorkflowId = checkNotNull(incident.occurrence.parentWorkflowId) {
      "Goal-child recovery target '${target.mutationWorkflowId}' has no parent workflow."
    }
    val parent = checkNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId)) {
      "Goal parent workflow '$parentWorkflowId' disappeared during recovery."
    }
    val manifest = checkNotNull(parent.decompositionRuntime()) {
      "Goal parent workflow '$parentWorkflowId' has no decomposition manifest."
    }
    val subtask = checkNotNull(manifest.subtasks.singleOrNull { it.workflowId == target.mutationWorkflowId }) {
      "Goal parent workflow '$parentWorkflowId' has no single subtask for child '${target.mutationWorkflowId}'."
    }
    val subtasks = manifest.subtasks.map {
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
    val reopened = manifest.copy(
      subtasks = subtasks,
      status = if (!parentStillBlocked && manifest.status.decompositionStatus() == DecompositionStatus.BLOCKED) {
        DecompositionStatus.IN_PROGRESS.wireValue
      } else {
        manifest.status
      },
    )
    val parentStatus = if (!parentStillBlocked && parent.workflowStatus == WorkflowStatus.BLOCKED) {
      WorkflowStatus.RUNNING
    } else {
      parent.workflowStatus
    }
    if (reopened != manifest || parentStatus != parent.workflowStatus) {
      val updated = engine.updateRecord(
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
    val reread = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId)
    val rereadSubtask = reread?.decompositionRuntime()?.subtasks?.singleOrNull {
      it.workflowId == target.mutationWorkflowId
    }
    check(
      reread != null && rereadSubtask != null &&
        rereadSubtask.status.decompositionStatus() != DecompositionStatus.BLOCKED &&
        (parentStillBlocked || reread.workflowStatus != WorkflowStatus.BLOCKED),
    ) { "Goal parent workflow '$parentWorkflowId' still reports child '${target.mutationWorkflowId}' as blocked." }
  }

  private fun ownershipWorkflowIds(incident: BlockIncident, target: BlockIncidentRecoveryTarget): List<String> =
    listOfNotNull(incident.occurrence.parentWorkflowId, target.mutationWorkflowId).distinct()

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

  private fun standaloneInvocationIsRecorded(unit: UnitOfWork, invocationId: String): Boolean =
    unit.standalonePhaseStatuses.readByInvocation(invocationId) != null

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
