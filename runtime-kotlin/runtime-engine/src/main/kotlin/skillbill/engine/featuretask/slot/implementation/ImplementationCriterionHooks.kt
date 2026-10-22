package skillbill.engine.featuretask.slot.implementation

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeImplementationTurn
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.slot.PhaseLaunchAdmission
import skillbill.engine.featuretask.slot.PhaseStepHookContextKind
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseImplementationLaunchContext
import skillbill.engine.featuretask.slot.attempt.PhaseImplementationOutputContext
import skillbill.engine.featuretask.slot.attempt.PhaseImplementationTurnAccess
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.state.FeatureTaskRuntimeImplementationTurnWrite
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.error.core.SkillBillRuntimeException
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationCheckDecision
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationTurnMarker
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationTurnRecord
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationTurnRole
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationTurnState
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeImplementationTurnRecords
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeImplementationTurnState
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeLaunchedImplementationTurn
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object ImplementationCriterionHooks : PhaseStepHooks {
  override val contextKind: PhaseStepHookContextKind = PhaseStepHookContextKind.IMPLEMENTATION

  private const val CHECK_VERDICT_REJECTION: String =
    "A criterion check must settle through feature_task_phase_complete with verdict `satisfied` or " +
      "`unsatisfied` and a short prose value naming the gap when unsatisfied."

  override fun admitLaunch(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
    state: PhaseAcceptedStepExecution,
    iteration: Int,
  ): PhaseLaunchAdmission {
    val access = context as? PhaseImplementationLaunchContext ?: return PhaseLaunchAdmission.Launch
    return when (val read = ledgerOf(run, access)) {
      null -> PhaseLaunchAdmission.Launch
      is ImplementationCriterionLedgerRead.Unreadable -> processFailure(read.reason)
      is ImplementationCriterionLedgerRead.Read -> admitTurn(LaunchedTurn(run, access, read.ledger, iteration), state)
    }
  }

  private fun admitTurn(
    loaded: LaunchedTurn,
    step: PhaseAcceptedStepExecution,
  ): PhaseLaunchAdmission {
    val ledger =
      when (val grant = grantReopen(loaded)) {
        is RequiredPhaseWrite.Rejected -> return PhaseLaunchAdmission.WriteRejected(grant)
        RequiredPhaseWrite.Acknowledged -> loaded.ledger.copy(stored = loaded.ledger.progress.reopenCurrent())
        null -> loaded.ledger
      }
    val turn = LaunchedTurn(loaded.run, loaded.access, ledger, loaded.iteration)
    val run = loaded.run
    return when (val state = featureTaskRuntimeImplementationTurnState(ledger.progress, ledger.records)) {
      FeatureTaskRuntimeImplementationTurnState.Complete -> complete(run, step, ledger)
      is FeatureTaskRuntimeImplementationTurnState.WorkerNeeded ->
        launchTurn(
          turn,
          FeatureTaskRuntimeImplementationTurnMarker(
            FeatureTaskRuntimeImplementationTurnRole.WORKER_LAUNCH,
            state.criterionId,
            state.worker,
          ),
          ledger.progress.reserveWorker(),
        )
      is FeatureTaskRuntimeImplementationTurnState.CheckOwed ->
        launchTurn(
          turn,
          FeatureTaskRuntimeImplementationTurnMarker(
            FeatureTaskRuntimeImplementationTurnRole.CHECK_LAUNCH,
            state.criterionId,
            state.worker,
          ),
          ledger.progress,
        )
      is FeatureTaskRuntimeImplementationTurnState.Exhausted -> exhausted(state)
      is FeatureTaskRuntimeImplementationTurnState.Incoherent -> processFailure(state.reason)
    }
  }

  private fun exhausted(state: FeatureTaskRuntimeImplementationTurnState.Exhausted): PhaseLaunchAdmission =
    PhaseLaunchAdmission.Block(
      "Acceptance criterion ${state.criterionId} used all " +
        "${FeatureTaskRuntimeImplementationProgress.MAX_WORKERS_PER_CRITERION} workers without a satisfied check. " +
        "Latest gap: ${state.latestGap.trim().removeSuffix(".")}. Resume the workflow explicitly to give " +
        "${state.criterionId} new workers; closed criteria stay closed.",
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
    )

  override fun implementationTurn(
    run: PhaseRun,
    context: PhaseAttemptLaunchHookContext,
    iteration: Int,
  ): FeatureTaskRuntimeImplementationTurn? {
    val access = context as? PhaseImplementationLaunchContext ?: return null
    val records = turnRecords(access, run.request.workflowId)
    val marker = featureTaskRuntimeLaunchedImplementationTurn(records, iteration) ?: return null
    val catalog = ImplementationCriterionCatalog.of(run, access) as? ImplementationCriterionCatalog.Criteria
    val criterion = catalog?.criterion(marker.criterionId) ?: return null
    return FeatureTaskRuntimeImplementationTurn(
      role = marker.role,
      criterionId = criterion.id,
      criterion = criterion.text,
      worker = marker.worker,
      specReference = run.request.runInvariants.specReference,
      latestGap = latestGap(records, criterion.id).takeIf { !marker.role.isCheck },
    )
  }

  override fun settleCompletedRound(
    context: PhaseStepOutputContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): AttemptResult? {
    val access = context as? PhaseImplementationOutputContext ?: return null
    val workflowId = capture.run.request.workflowId
    val marker =
      featureTaskRuntimeLaunchedImplementationTurn(turnRecords(access, workflowId), capture.iteration) ?: return null
    return when (val progress = launchedProgress(access, workflowId, marker)) {
      is LaunchedProgress.Moved -> access.blockImplementationTurn(capture, progress.reason)
      is LaunchedProgress.Current -> {
        val settled = SettledTurn(access, capture, progress.progress, marker, producedValue(outputMap))
        when (marker.role) {
          FeatureTaskRuntimeImplementationTurnRole.WORKER_LAUNCH -> settleWorker(settled)
          FeatureTaskRuntimeImplementationTurnRole.CHECK_LAUNCH -> settleCheck(settled, outputMap)
          else -> error("A launched implementation turn must be a worker or check launch, was ${marker.role}.")
        }
      }
    }
  }

  private fun launchedProgress(
    access: PhaseImplementationOutputContext,
    workflowId: String,
    marker: FeatureTaskRuntimeImplementationTurnMarker,
  ): LaunchedProgress {
    val progress =
      try {
        access.implementationRecords.loadImplementationProgress(workflowId)
      } catch (error: SkillBillRuntimeException) {
        return LaunchedProgress.Moved(error.message.orEmpty())
      }
    return if (progress != null && progress.currentId == marker.criterionId && progress.workerCount == marker.worker) {
      LaunchedProgress.Current(progress)
    } else {
      LaunchedProgress.Moved(
        "The criterion progress no longer matches the launched turn ${marker.encode()}; " +
          "implement keeps the recorded progress and does not settle this turn.",
      )
    }
  }

  override fun acceptedOutput(
    context: PhaseStepOutputContext,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val access = context as? PhaseImplementationOutputContext ?: return attested
    val workflowId = capture.run.request.workflowId
    val records = turnRecords(access, workflowId)
    val marker = featureTaskRuntimeLaunchedImplementationTurn(records, capture.iteration) ?: return attested
    val progress = access.implementationRecords.loadImplementationProgress(workflowId)
    return if (marker.role.isCheck && progress?.complete == true) receipt(progress, records) else attested
  }

  private fun ledgerOf(
    run: PhaseRun,
    access: PhaseImplementationTurnAccess,
  ): ImplementationCriterionLedgerRead? =
    when (val catalog = ImplementationCriterionCatalog.of(run, access)) {
      ImplementationCriterionCatalog.Legacy -> null
      is ImplementationCriterionCatalog.Invalid -> ImplementationCriterionLedgerRead.Unreadable(catalog.reason)
      is ImplementationCriterionCatalog.Criteria ->
        ImplementationCriterionLedger.load(catalog, access, run.request.workflowId)
    }

  private fun grantReopen(turn: LaunchedTurn): RequiredPhaseWrite? {
    val identity = reopenIdentity(turn) ?: return null
    val progress = turn.ledger.progress
    val reopened = progress.reopenCurrent() ?: return null
    val criterionId = progress.currentId ?: return null
    return turn.access.record(
      turn,
      reopened,
      FeatureTaskRuntimeImplementationTurnMarker(
        FeatureTaskRuntimeImplementationTurnRole.REOPEN,
        criterionId,
        progress.workerCount,
        retryIdentity = identity,
      ),
      "The operator resumed the workflow, so $criterionId gets new workers. Closed criteria stay closed.",
    )
  }

  private fun reopenIdentity(turn: LaunchedTurn): String? {
    val session = turn.access.session
    val retry =
      session.operatorBlockRetry
        ?.takeIf { it.phaseId == turn.run.phaseId && !session.operatorBlockRetryCompleted }
        ?: return null
    if (retry.preserveBudget) return null
    return retry.retriedAt.trim().replace(WHITESPACE, "_").takeIf(String::isNotEmpty)?.takeUnless { identity ->
      turn.ledger.records.any {
        it.marker.role == FeatureTaskRuntimeImplementationTurnRole.REOPEN && it.marker.retryIdentity == identity
      }
    }
  }

  private fun launchTurn(
    turn: LaunchedTurn,
    marker: FeatureTaskRuntimeImplementationTurnMarker,
    progress: FeatureTaskRuntimeImplementationProgress?,
  ): PhaseLaunchAdmission {
    progress ?: return processFailure("No worker slot is left for ${marker.criterionId} to launch ${marker.encode()}.")
    val value =
      if (marker.role.isCheck) {
        "Fresh read-only check of ${marker.criterionId} after worker ${marker.worker} launched."
      } else {
        "Worker ${marker.worker} of ${marker.criterionId} launched."
      }
    return when (val write = turn.access.record(turn, progress.launched(), marker, value)) {
      RequiredPhaseWrite.Acknowledged -> PhaseLaunchAdmission.Launch
      is RequiredPhaseWrite.Rejected -> PhaseLaunchAdmission.WriteRejected(write)
    }
  }

  private fun complete(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
    ledger: ImplementationCriterionLedger,
  ): PhaseLaunchAdmission {
    val output = receipt(ledger.progress, ledger.records)
    val iteration = state.nextStepIteration()
    return try {
      state.settleRuntimeAuthoredCompletion(run.phaseId, output)
      PhaseLaunchAdmission.Settled(
        PhaseOutcome.completed(
          FeatureTaskRuntimePhaseOutput(run.phaseId, iteration, output.canonicalJson, output, null),
        ),
      )
    } catch (error: IllegalStateException) {
      processFailure("Every criterion closed but the implementation receipt could not persist: ${error.message}")
    }
  }

  private fun settleWorker(turn: SettledTurn): AttemptResult? {
    val marker = turn.marker
    val value = turn.value ?: "Worker ${marker.worker} of ${marker.criterionId} settled without prose."
    return turn.recordThen(
      turn.progress,
      marker.copy(role = FeatureTaskRuntimeImplementationTurnRole.WORKER_SETTLED),
      value,
    ) {
      AttemptResult.criterionTurn(
        "Worker ${marker.worker} of ${marker.criterionId} settled; a fresh read-only check of " +
          "${marker.criterionId} follows.",
        turn.capture.fileManifest,
      )
    }
  }

  private fun settleCheck(
    turn: SettledTurn,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
  ): AttemptResult? {
    val decision =
      FeatureTaskRuntimeImplementationCheckDecision.fromWireValue(outputMap[SharedPayloadKeys.VERDICT] as? String)
    val marker = turn.marker
    val edited = turn.capture.fileManifest.introduced
    if (decision == null && edited.isEmpty()) return recheck(turn)
    val closes = decision == FeatureTaskRuntimeImplementationCheckDecision.SATISFIED && edited.isEmpty()
    val next = if (closes) turn.progress.closeCurrent() else turn.progress
    next ?: return turn.access.blockImplementationTurn(turn.capture, "${marker.criterionId} cannot close.")
    val recorded =
      if (closes) {
        FeatureTaskRuntimeImplementationCheckDecision.SATISFIED
      } else {
        FeatureTaskRuntimeImplementationCheckDecision.UNSATISFIED
      }
    val value =
      when {
        edited.isNotEmpty() ->
          "The check of ${marker.criterionId} edited $edited, so it cannot close the criterion. " +
            turn.value.orEmpty()
        else -> turn.value ?: "The check of ${marker.criterionId} decided ${recorded.wireValue}."
      }.trim()
    return turn.recordThen(
      next,
      marker.copy(role = FeatureTaskRuntimeImplementationTurnRole.CHECK_SETTLED, decision = recorded),
      value,
    ) {
      if (next.complete) {
        null
      } else {
        AttemptResult.criterionTurn(
          if (closes) {
            "${marker.criterionId} closed; the next criterion follows."
          } else {
            "${marker.criterionId} is not satisfied yet; a fresh worker gets the latest gap."
          },
          turn.capture.fileManifest,
        )
      }
    }
  }

  private fun recheck(turn: SettledTurn): AttemptResult {
    val marker = turn.marker
    val checks =
      turnRecords(turn.access, turn.capture.run.request.workflowId).count {
        it.marker.role == FeatureTaskRuntimeImplementationTurnRole.CHECK_LAUNCH &&
          it.marker.criterionId == marker.criterionId &&
          it.marker.worker == marker.worker
      }
    return if (checks < MAX_CHECKS_PER_WORKER) {
      AttemptResult.criterionTurn(
        "The check of ${marker.criterionId} settled without a recognized verdict; a fresh read-only check follows.",
        turn.capture.fileManifest,
      )
    } else {
      AttemptResult.schemaInvalid(CHECK_VERDICT_REJECTION, turn.capture.fileManifest)
    }
  }

  private fun receipt(
    progress: FeatureTaskRuntimeImplementationProgress,
    records: List<FeatureTaskRuntimeImplementationTurnRecord>,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    val summary = "Implement closed ${progress.criterionIds.size} acceptance criteria one at a time."
    val sections =
      progress.criterionIds.joinToString("\n\n") { id ->
        val workers =
          records.filter {
            it.marker.criterionId == id && it.marker.role == FeatureTaskRuntimeImplementationTurnRole.WORKER_SETTLED
          }
        val body =
          workers.joinToString("\n\n") { "Worker ${it.marker.worker}: ${it.attempt.value}" }
            .ifEmpty { "No worker summary is retained for this criterion." }
        "$id\n$body"
      }
    val value = "$summary Each criterion closed after a fresh read-only check found it satisfied.\n\n$sections"
    return NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        linkedMapOf<String, Any?>(
          SharedPayloadKeys.CONTRACT_VERSION to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
          SharedPayloadKeys.PHASE_ID to FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
          SharedPayloadKeys.STATUS to STATUS_COMPLETED,
          SharedPayloadKeys.SUMMARY to summary,
          SharedPayloadKeys.PRODUCED_OUTPUTS to linkedMapOf<String, Any?>(SharedPayloadKeys.VALUE to value),
        ),
      ),
    )
  }

  private fun latestGap(
    records: List<FeatureTaskRuntimeImplementationTurnRecord>,
    criterionId: String,
  ): String? =
    records.lastOrNull {
      it.marker.criterionId == criterionId &&
        it.marker.decision == FeatureTaskRuntimeImplementationCheckDecision.UNSATISFIED
    }?.attempt?.value

  private fun turnRecords(
    access: PhaseImplementationTurnAccess,
    workflowId: String,
  ): List<FeatureTaskRuntimeImplementationTurnRecord> =
    featureTaskRuntimeImplementationTurnRecords(
      access.implementationRecords.loadImplementationAttempts(workflowId).orEmpty(),
    )

  private fun producedValue(outputMap: FeatureTaskRuntimeWorkflowArtifactMap): String? {
    val produced = JsonCodec.anyToStringAnyMap(outputMap[SharedPayloadKeys.PRODUCED_OUTPUTS])
    return (produced?.get(SharedPayloadKeys.VALUE) as? String)?.trim()?.takeIf(String::isNotEmpty)
  }

  private fun processFailure(reason: String): PhaseLaunchAdmission =
    PhaseLaunchAdmission.Block(
      "$reason Implement keeps the recorded criterion progress and launches no further turn.",
      FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
    )

  private fun PhaseImplementationTurnAccess.record(
    turn: LaunchedTurn,
    progress: FeatureTaskRuntimeImplementationProgress,
    marker: FeatureTaskRuntimeImplementationTurnMarker,
    value: String,
  ): RequiredPhaseWrite =
    implementationRecords.recordImplementationTurn(
      FeatureTaskRuntimeImplementationTurnWrite(
        workflowId = turn.run.request.workflowId,
        phaseId = turn.run.phaseId,
        attemptNumber = turn.iteration,
        agentId = turn.run.resolvedAgent.resolvedAgentId,
        expectedProgress = turn.ledger.stored,
        progress = progress,
        marker = marker,
        value = value,
      ),
    )

  private val FeatureTaskRuntimeImplementationTurnRole.isCheck: Boolean
    get() = this == FeatureTaskRuntimeImplementationTurnRole.CHECK_LAUNCH

  private val WHITESPACE = Regex("\\s+")

  private const val MAX_CHECKS_PER_WORKER: Int = 3

  private sealed interface LaunchedProgress {
    data class Current(
      val progress: FeatureTaskRuntimeImplementationProgress,
    ) : LaunchedProgress

    data class Moved(
      val reason: String,
    ) : LaunchedProgress
  }

  private class LaunchedTurn(
    val run: PhaseRun,
    val access: PhaseImplementationLaunchContext,
    val ledger: ImplementationCriterionLedger,
    val iteration: Int,
  )

  private class SettledTurn(
    val access: PhaseImplementationOutputContext,
    val capture: ValidatedOutputCapture,
    val progress: FeatureTaskRuntimeImplementationProgress,
    val marker: FeatureTaskRuntimeImplementationTurnMarker,
    val value: String?,
  ) {
    fun recordThen(
      next: FeatureTaskRuntimeImplementationProgress,
      settled: FeatureTaskRuntimeImplementationTurnMarker,
      value: String,
      onAcknowledged: () -> AttemptResult?,
    ): AttemptResult? =
      when (
        val write =
          access.implementationRecords.recordImplementationTurn(
            FeatureTaskRuntimeImplementationTurnWrite(
              workflowId = capture.run.request.workflowId,
              phaseId = capture.run.phaseId,
              attemptNumber = capture.iteration,
              agentId = capture.run.resolvedAgent.resolvedAgentId,
              expectedProgress = progress,
              progress = next,
              marker = settled,
              value = value,
            ),
          )
      ) {
        RequiredPhaseWrite.Acknowledged -> onAcknowledged()
        is RequiredPhaseWrite.Rejected -> access.blockImplementationTurn(capture, write.message)
      }
  }
}
