package skillbill.engine.featuretask.slot.attempt

import skillbill.agent.model.PhaseOutput
import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointMessage
import skillbill.engine.featuretask.lifecycle.checkpoint.FeatureTaskRuntimeCheckpointMetadata
import skillbill.engine.featuretask.lifecycle.subtask.FeatureTaskRuntimeSubtaskFinalisation
import skillbill.engine.featuretask.lifecycle.subtask.FeatureTaskRuntimeSubtaskFinaliseRequest
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.review.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeCommitPushHandoff
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeCommitPushHandoffInvalid
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeCommitPushHandoffValid
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskCommitIdentity
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskFinalisationBlocked
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskFinalisationResult
import skillbill.engine.featuretask.model.subtask.FeatureTaskRuntimeSubtaskFinalised
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.runloop.attempt.FeatureTaskRuntimeRunLoopHookViews.stepOutputContext
import skillbill.engine.featuretask.runloop.attempt.phaseAttemptContext
import skillbill.engine.featuretask.runloop.attempt.settlementCoupling
import skillbill.engine.featuretask.runloop.checkpoint.FeatureTaskRuntimeRunLoopCheckpoint
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.CapturedPhaseOutput
import skillbill.engine.featuretask.runloop.core.CommitPushBlocked
import skillbill.engine.featuretask.runloop.core.CommitPushFinalisation
import skillbill.engine.featuretask.runloop.core.CommitPushNotApplicable
import skillbill.engine.featuretask.runloop.core.CommitPushSettled
import skillbill.engine.featuretask.runloop.core.CompletionProjectionRejectionArgs
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSubtaskCommit
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunSessionObservations
import skillbill.engine.featuretask.runloop.core.FinalizeValidatedOutputAcceptanceArgs
import skillbill.engine.featuretask.runloop.core.PersistAcceptedOutputArgs
import skillbill.engine.featuretask.runloop.core.PhaseAttemptContext
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.RecordFinalisedCheckpointIdentityArgs
import skillbill.engine.featuretask.runloop.core.RecordRejectedOutputArgs
import skillbill.engine.featuretask.runloop.core.RejectedOutputTargeting
import skillbill.engine.featuretask.runloop.core.RejectedOutputTargetingArgs
import skillbill.engine.featuretask.runloop.core.RejectedOutputTargetingOverrides
import skillbill.engine.featuretask.runloop.core.SettleValidatedOutput
import skillbill.engine.featuretask.runloop.core.SettleValidatedOutputAfterFingerprintArgs
import skillbill.engine.featuretask.runloop.core.SettleValidatedOutputPauseArgs
import skillbill.engine.featuretask.runloop.core.SubtaskCommitLedgerState
import skillbill.engine.featuretask.runloop.core.TerminalOutputAttemptArgs
import skillbill.engine.featuretask.runloop.core.UnownedWorktreeCommitShaArgs
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.runloop.core.defaultRejectedOutputTargetingArgs
import skillbill.engine.featuretask.runloop.core.phaseBlockArgs
import skillbill.engine.featuretask.runloop.core.withDisposition
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.output.FeatureTaskRuntimeRunLoopOutputPersistence
import skillbill.engine.featuretask.runloop.output.FeatureTaskRuntimeRunLoopOutputVerification
import skillbill.engine.featuretask.runloop.output.payloadFreeRejectionReason
import skillbill.engine.featuretask.runloop.output.payloadFreeSemanticGateConstraint
import skillbill.engine.featuretask.runloop.output.rejectionPath
import skillbill.engine.featuretask.runloop.output.retryRejectionReason
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.state.FEATURE_TASK_RUNTIME_PROCESS_FAILURE_RULE
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeChildOutput
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.runner.boundedSchemaGateDetail
import skillbill.engine.featuretask.runner.terminalBlockedReasonFrom
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.engine.featuretask.slot.state.PhaseSettledEnvelopeRead
import skillbill.engine.featuretask.slot.state.PhaseStepBinding
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock

internal data class SettleValidatedOutputCommitArgs(
  val request: FeatureTaskRuntimeRunFacts,
  val coupledProgress: FeatureTaskRuntimeProgressSnapshotAccess,
  val coupledTransitions: FeatureTaskRuntimeRunTransitionOwner,
  val progress: FeatureTaskRuntimeProgressSnapshotAccess,
  val recorder: PhaseRunRecords,
  val diagnostics: RuntimeDiagnostics,
  val gitOperations: WorkflowGitOperations,
  val goalContinuationRecorder: PhaseRunGoal,
  val session: FeatureTaskRuntimeRunSessionObservations,
  val run: PhaseRun,
  val capture: ValidatedOutputCapture,
  val attested: NormalizedFeatureTaskRuntimePhaseOutput,
  val observability: FeatureTaskRuntimeRunObservability,
)

internal data class FinaliseSubtaskCommitArgs(
  val request: FeatureTaskRuntimeRunFacts,
  val progress: FeatureTaskRuntimeProgressSnapshotAccess,
  val recorder: PhaseRunRecords,
  val diagnostics: RuntimeDiagnostics,
  val gitOperations: WorkflowGitOperations,
  val session: FeatureTaskRuntimeRunSessionObservations,
  val run: PhaseRun,
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
)

object PhaseOutputGate {
  internal fun rejectedOutputTargeting(args: RejectedOutputTargetingArgs): RejectedOutputTargeting =
    RejectedOutputTargeting(
      phaseId = args.phaseId,
      agentId = args.agentId,
      model = args.model,
      path = args.path,
      repairTurn = args.repairTurn,
      generationScoped = args.generationScoped,
    )

  internal fun gateOutput(args: GateOutput): AttemptResult {
    gateOutputEarlyExit(args)?.let { return it }
    val normalized =
      settledRecord(args)
        ?: proseOutput(args)
        ?: return blankOutputProcessFailure(args)
    return args.settleAcceptedOutput(normalized, args.observability)
  }

  private fun settledRecord(args: GateOutput): NormalizedFeatureTaskRuntimePhaseOutput? =
    when (val settled = args.settledEnvelope) {
      PhaseSettledEnvelopeRead.None -> null
      is PhaseSettledEnvelopeRead.Found -> NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(settled.envelope)
      is PhaseSettledEnvelopeRead.Failed -> {
        clearAndRecordPersistedEvidenceFailure(
          args.progress,
          args.recorder,
          args.phaseSettlementService,
          args,
          settled.error,
        )
        null
      }
    }

  private fun proseOutput(args: GateOutput): NormalizedFeatureTaskRuntimePhaseOutput? {
    val text = args.captured.text
    if (text.isBlank() || args.captured.truncated) return null
    return NormalizedFeatureTaskRuntimePhaseOutput(
      phaseId = args.run.phaseId,
      status = STATUS_COMPLETED,
      summary = proseSummary(text),
      output = PhaseOutput(value = text),
    )
  }

  private fun proseSummary(text: String): String {
    val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    if (firstLine.length <= PROSE_SUMMARY_MAX_CHARS) return firstLine
    return firstLine.take(PROSE_SUMMARY_MAX_CHARS) + "..."
  }

  private fun blankOutputProcessFailure(args: GateOutput): AttemptResult =
    unadmittableOutputProcessFailure(
      args,
      if (args.captured.truncated) {
        "Phase '${args.run.phaseId}' exited without settling and its captured output was truncated; " +
          "an incomplete capture cannot be admitted as the phase output."
      } else {
        "Phase '${args.run.phaseId}' exited without settling and without any output; " +
          "the agent process produced nothing to admit."
      },
    )

  private fun unadmittableOutputProcessFailure(
    args: GateOutput,
    reason: String,
  ): AttemptResult {
    return AttemptResult.settled(
      FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
        args.progress,
        args.coupledRunTransitions,
        args.recorder,
        PhaseBlockRequest(
          run = args.run,
          attemptCount = args.iteration,
          reason = reason,
          observability = args.observability,
          payload = BlockAndPersistPayload(fileManifest = args.fileManifest),
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        ),
      ),
    )
  }

  private const val PROSE_SUMMARY_MAX_CHARS = 240

  internal fun recordRejectedOutput(
    state: FeatureTaskRuntimeProgressSnapshotAccess,
    recorder: PhaseRunRecords,
    args: RecordRejectedOutputArgs,
  ): FeatureTaskRuntimeRejectedOutputWrite {
    val run = args.run
    val captured = args.captured
    val targeting = args.targeting
    return recorder.recordRejectedOutput(
      RejectedOutputDiagnosticRequest(
        workflowId = run.request.workflowId,
        phaseId = targeting.phaseId,
        attempt = args.iteration.coerceAtLeast(1),
        rule = args.rule,
        path = targeting.path,
        reason = args.reason,
        agentId = targeting.agentId,
        model = targeting.model,
        rawResponse = captured.bytes,
        observedByteSize = captured.byteSize,
        observedSha256 = captured.sha256,
        truncated = captured.truncated,
        repairTurn = targeting.repairTurn,
        exhaustedFixLoop = args.exhaustedFixLoop,
      ),
      (if (targeting.generationScoped) state.reviewEvidenceGeneration else 0),
    )
  }

  internal fun persistChildProcessFailureOutput(
    context: PhaseOutputSettlementContext,
    run: PhaseRun,
    iteration: Int,
    reason: String,
    childOutput: FeatureTaskRuntimeChildOutput?,
  ) {
    val output = childOutput ?: return
    runCatching {
      recordRejectedOutput(
        context.progress,
        context.recorder,
        RecordRejectedOutputArgs(
          run = run,
          iteration = iteration,
          rule = FEATURE_TASK_RUNTIME_PROCESS_FAILURE_RULE,
          reason = boundedSchemaGateDetail(reason),
          captured = CapturedPhaseOutput.fromBytes(output.storedBody().encodeToByteArray()),
          targeting = rejectedOutputTargeting(defaultRejectedOutputTargetingArgs(run)),
        ),
      )
    }.onFailure { error ->
      RuntimeDiagnosticsBestEffortWarning.record(
        context.diagnostics,
        "Feature-task-runtime could not persist the child process-failure diagnostic for issue " +
          "${context.request.issueKey}, workflow ${context.request.workflowId}, phase '${run.phaseId}'. The block " +
          "reason keeps its bounded excerpt; the full child output is lost.",
        error,
      )
    }
  }

  internal fun settleValidatedOutput(args: SettleValidatedOutput): AttemptResult {
    val run = args.run
    val iteration = args.iteration
    val attested = args.output.normalizedOutput
    val capture =
      ValidatedOutputCapture(
        run = run,
        iteration = iteration,
        captured = args.output.captured,
        repairEvidence = args.output.repairEvidence,
        fileManifest = args.output.fileManifest,
      )
    return settleValidatedOutputWithEvidence(
      args,
      capture,
      attested,
    )
  }

  private fun settleValidatedOutputWithEvidence(
    args: SettleValidatedOutput,
    capture: ValidatedOutputCapture,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
  ): AttemptResult {
    val state = args.progress
    val recorder = args.recorder
    val gitOperations = args.gitOperations
    val observability = args.observability
    val outputMap = attested.envelopeWireMap()

    fun reject(
      rule: String,
      detail: String,
    ): AttemptResult =
      rejectValidatedOutput(
        args,
        capture,
        outputMap,
        rule,
        detail,
      )
    val context = args.settlementContext
    val hooks = args.stepHooks
    val boundStep = args.boundStep
    with(context) {
      settleStepOutputCheck(
        hooks.checkValidatedOutput(
          capture.run,
          context.stepOutputContext(capture.run, hooks),
          boundStep as PhaseAcceptedStepExecution,
          outputMap,
        ),
        capture,
        ::reject,
      )
    }?.let { return it }
    val fingerprintResolution =
      PhaseOutputGate.resolveRepositoryFingerprint(
        context.phaseAttemptContext(
          run = capture.run,
          iteration = capture.iteration,
          observability = observability,
        ),
        recorder,
        gitOperations,
        capture.fileManifest,
        hooks.fingerprintsCompletedRepository,
      )
    fingerprintResolution.blocked?.let { return it }
    return with(args.settlementContext) {
      settleValidatedOutputAfterFingerprint(
        SettleValidatedOutputAfterFingerprintArgs(
          capture = capture,
          attested = attested,
          repairEvidence = capture.repairEvidence,
          observability = observability,
          repositoryFingerprint = fingerprintResolution.fingerprint,
          boundStep = args.boundStep,
          stepHooks = args.stepHooks,
          reject = ::reject,
        ),
      )
    }
  }

  private fun clearAndRecordPersistedEvidenceFailure(
    state: FeatureTaskRuntimeProgressSnapshotAccess,
    recorder: PhaseRunRecords,
    phaseSettlementService: PhaseRunSettlements,
    args: GateOutput,
    error: InvalidFeatureTaskRuntimeValidationEvidenceSchemaError,
  ) {
    val run = args.run
    phaseSettlementService.clear(
      workflowId = run.request.workflowId,
      phaseId = run.phaseId,
      attempt = args.iteration,
    )
    PhaseOutputGate.recordRejectedOutput(
      state,
      recorder,
      RecordRejectedOutputArgs(
        run = run,
        iteration = args.iteration,
        rule = "phase-settlement-validation-evidence",
        reason = error.message.orEmpty(),
        captured = args.captured,
        targeting =
          PhaseOutputGate.rejectedOutputTargeting(
            defaultRejectedOutputTargetingArgs(run),
          ),
        exhaustedFixLoop = args.rejectionExhaustsFixLoop,
      ),
    )
  }

  internal fun gateOutputEarlyExit(args: GateOutput): AttemptResult? =
    args.stepHooks
      .earlyOutput(args.run, args.iteration, args.captured.text)
      ?.let(AttemptResult::settled)

  internal data class RepositoryFingerprintResolution(
    val fingerprint: String?,
    val blocked: AttemptResult?,
  )

  internal fun resolveRepositoryFingerprint(
    context: PhaseAttemptContext,
    recorder: PhaseRunRecords,
    gitOperations: WorkflowGitOperations,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
    fingerprintsCompletedRepository: Boolean,
  ): RepositoryFingerprintResolution {
    if (!fingerprintsCompletedRepository) return RepositoryFingerprintResolution(null, null)
    val request = context.run.request
    val result = gitOperations.repositoryFingerprint(request.repoRoot)
    if (result !is WorkflowGitOperationResult.Ok) {
      val blocked =
        AttemptResult.settled(
          FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
            context.state,
            context.loopTransitions,
            recorder,
            PhaseBlockRequest(
              run = context.run,
              attemptCount = context.iteration,
              reason =
                "Completed-phase repository fingerprinting failed for '${context.run.phaseId}': ${result.error}",
              observability = context.observability,
              payload = BlockAndPersistPayload(fileManifest = fileManifest),
              failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
            ),
          ),
        )
      return RepositoryFingerprintResolution(fingerprint = null, blocked = blocked)
    }
    return RepositoryFingerprintResolution(result.value, null)
  }

  private fun PhaseOutputSettlementContext.settleStepOutputCheck(
    check: PhaseStepOutputCheck,
    capture: ValidatedOutputCapture,
    reject: (
      String,
      String,
    ) -> AttemptResult,
  ): AttemptResult? =
    when (check) {
      is PhaseStepOutputCheck.Reject -> reject(check.rule, check.reason)
      is PhaseStepOutputCheck.Redeliver -> AttemptResult.boundaryBodyDelivery(check.reason, capture.fileManifest)
      is PhaseStepOutputCheck.Block -> {
        val coupling = settlementCoupling()
        AttemptResult.settled(
          FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
            coupling.progress,
            coupling.transitions,
            recorder,
            PhaseBlockRequest(
              run = capture.run,
              attemptCount = capture.iteration,
              reason = check.reason,
              observability = observability,
              payload = BlockAndPersistPayload(fileManifest = capture.fileManifest),
              failureDisposition = check.disposition,
            ),
          ),
        )
      }
      is PhaseStepOutputCheck.Accept -> null
    }

  internal fun settleValidatedOutputPauseOrTerminal(
    progress: FeatureTaskRuntimeProgressSnapshotAccess,
    loopTransitions: FeatureTaskRuntimeRunTransitionOwner,
    session: FeatureTaskRuntimeRunSessionObservations,
    recorder: PhaseRunRecords,
    args: SettleValidatedOutputPauseArgs,
  ): AttemptResult? {
    val observability = args.observability
    val capture = args.capture
    val outputMap = args.attested.envelopeWireMap()
    val attested = args.attested
    val repairEvidence = args.repairEvidence
    val repositoryFingerprint = args.repositoryFingerprint
    val run = capture.run
    val blockedDisposition = args.blockedDisposition
    terminalBlockedReasonFrom(run.phaseId, outputMap, blockedDisposition)?.let { reason ->
      return FeatureTaskRuntimeRunLoopOutputVerification.terminalOutputAttempt(
        progress,
        loopTransitions,
        recorder,
        TerminalOutputAttemptArgs(
          run = run,
          iteration = capture.iteration,
          reason = reason,
          normalizedOutput = attested,
          repairEvidence = repairEvidence,
          observability = observability,
          fileManifest = capture.fileManifest,
          session = session,
        ),
        blockedDisposition,
      )
    }
    return null
  }

  internal fun settleValidatedOutputCommit(
    args: SettleValidatedOutputCommitArgs,
  ): Pair<NormalizedFeatureTaskRuntimePhaseOutput, AttemptResult?> =
    when (
      val finalisation =
        finaliseSubtaskCommit(
          FinaliseSubtaskCommitArgs(
            request = args.request,
            progress = args.progress,
            recorder = args.recorder,
            diagnostics = args.diagnostics,
            gitOperations = args.gitOperations,
            session = args.session,
            run = args.run,
            normalizedOutput = args.attested,
          ),
        )
    ) {
      is CommitPushNotApplicable -> args.attested to null
      is CommitPushSettled -> finalisation.output to null
      is CommitPushBlocked ->
        args.attested to
          AttemptResult.settled(
            FeatureTaskRuntimeRunLoopPhaseBlocking.blockAndPersistInPhase(
              args.coupledProgress,
              args.coupledTransitions,
              args.recorder,
              args.goalContinuationRecorder,
              phaseBlockArgs(
                args.run,
                args.capture.iteration,
                finalisation.reason,
                args.observability,
                payload = BlockAndPersistPayload(fileManifest = args.capture.fileManifest),
              ).withDisposition(FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION),
            ),
          )
    }

  internal fun PhaseOutputSettlementContext.settleValidatedOutputAfterFingerprint(
    args: SettleValidatedOutputAfterFingerprintArgs,
  ): AttemptResult {
    val coupling = settlementCoupling()
    return settleValidatedOutputPauseOrTerminal(
      coupling.progress,
      coupling.transitions,
      coupling.session,
      recorder,
      SettleValidatedOutputPauseArgs(
        capture = args.capture,
        attested = args.attested,
        repairEvidence = args.repairEvidence,
        observability = observability,
        repositoryFingerprint = args.repositoryFingerprint,
        blockedDisposition = args.stepHooks.blockedOutputDisposition,
      ),
    ) ?: settleValidatedOutputAfterPause(args)
  }

  private fun PhaseOutputSettlementContext.settleValidatedOutputAfterPause(
    args: SettleValidatedOutputAfterFingerprintArgs,
  ): AttemptResult {
    val run = args.capture.run
    val boundStep = args.boundStep
    val rejection =
      FeatureTaskRuntimeRunLoopOutputVerification.completionProjectionRejection(
        this,
        CompletionProjectionRejectionArgs(
          run = run,
          normalizedOutput = args.attested,
          iteration = args.capture.iteration,
          repairEvidence = args.repairEvidence,
          repositoryFingerprint = args.repositoryFingerprint,
          checksImmediateConsumerProjection = args.stepHooks.checksImmediateConsumerProjection,
        ),
      ) ?: stepCompletionRejection(run, args.attested, boundStep, args.stepHooks)
    return rejection?.let { (rule, reason) -> args.reject(rule, reason) }
      ?: settleStepOutputCheck(
        args.stepHooks.settleCompletedOutput(
          run,
          stepOutputContext(run, args.stepHooks),
          boundStep as PhaseAcceptedStepExecution,
          args.attested.envelopeWireMap(),
        ),
        args.capture,
        args.reject,
      )
      ?: args.stepHooks.settleCompletedRound(
        stepOutputContext(run, args.stepHooks),
        args.capture,
        args.attested,
        args.attested.envelopeWireMap(),
      )
      ?: finalizeValidatedOutputAcceptance(
        FinalizeValidatedOutputAcceptanceArgs(
          capture = args.capture,
          attested =
            args.stepHooks.interpretedOutput(
              run,
              stepOutputContext(run, args.stepHooks),
              boundStep as PhaseAcceptedStepExecution,
              args.stepHooks.acceptedOutput(
                stepOutputContext(run, args.stepHooks),
                args.capture,
                args.attested,
                args.attested.envelopeWireMap(),
              ),
            ),
          repairEvidence = args.repairEvidence,
          observability = observability,
          repositoryFingerprint = args.repositoryFingerprint,
          boundStep = boundStep,
          stepHooks = args.stepHooks,
        ),
      )
  }

  private fun PhaseOutputSettlementContext.stepCompletionRejection(
    run: PhaseRun,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
    boundStep: PhaseStepBinding,
    stepHooks: PhaseStepHooks,
  ): Pair<String, String>? =
    stepHooks
      .completionRejection(
        run,
        stepOutputContext(run, stepHooks),
        boundStep as PhaseAcceptedStepExecution,
        attested.envelopeWireMap(),
      )?.let {
        "output-verification" to it
      }

  internal fun PhaseOutputSettlementContext.finalizeValidatedOutputAcceptance(
    args: FinalizeValidatedOutputAcceptanceArgs,
  ): AttemptResult {
    val capture = args.capture
    val attested = args.attested
    val repairEvidence = args.repairEvidence
    val repositoryFingerprint = args.repositoryFingerprint
    val run = capture.run
    val coupling = settlementCoupling()
    val (finalised, commitBlocked) =
      settleValidatedOutputCommit(
        SettleValidatedOutputCommitArgs(
          request = request,
          coupledProgress = coupling.progress,
          coupledTransitions = coupling.transitions,
          progress = progress,
          recorder = recorder,
          diagnostics = diagnostics,
          gitOperations = gitOperations,
          goalContinuationRecorder = goalContinuationRecorder,
          session = blockingSessionForPhaseEffects,
          run = run,
          capture = capture,
          attested = attested,
          observability = observability,
        ),
      )
    commitBlocked?.let { return it }
    PhaseOutputGate.retainSettledProducerOutput(request, progress, recorder, clock, capture)
    args.stepHooks.recordAcceptedOutput(
      run,
      stepOutputContext(run, args.stepHooks),
      args.boundStep,
      finalised.envelopeWireMap(),
    )
    return with(FeatureTaskRuntimeRunLoopOutputVerification) {
      FeatureTaskRuntimeRunLoopOutputVerification.persistAcceptedOutput(
        this@finalizeValidatedOutputAcceptance,
        PersistAcceptedOutputArgs(
          run = run,
          iteration = capture.iteration,
          normalizedOutput = finalised,
          repairEvidence = repairEvidence,
          observability = observability,
          fileManifest = capture.fileManifest,
          repositoryFingerprint = repositoryFingerprint,
        ),
      )
    }
  }

  internal fun rejectValidatedOutput(
    settlement: SettleValidatedOutput,
    capture: ValidatedOutputCapture,
    outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
    rule: String,
    detail: String,
  ): AttemptResult {
    val state = settlement.progress
    val recorder = settlement.recorder
    val diagnosticRule = rule
    val path = rejectionPath(detail)
    val reason = payloadFreeRejectionReason(rule, path)
    val retryFacingConstraint =
      payloadFreeSemanticGateConstraint(
        rule,
        detail,
        outputMap,
      )
    val retryReason = retryRejectionReason(reason, retryFacingConstraint)
    PhaseOutputGate.recordRejectedOutput(
      state,
      recorder,
      RecordRejectedOutputArgs(
        run = capture.run,
        iteration = capture.iteration,
        rule = diagnosticRule,
        reason = detail,
        captured = capture.captured,
        targeting =
          PhaseOutputGate.rejectedOutputTargeting(
            defaultRejectedOutputTargetingArgs(capture.run, RejectedOutputTargetingOverrides(path = path)),
          ),
      ),
    )
    return FeatureTaskRuntimeRunLoopOutputPersistence.schemaInvalidAttempt(
      reason,
      capture.fileManifest,
      retryReason = retryReason,
    )
  }

  internal fun retainSettledProducerOutput(
    request: FeatureTaskRuntimeRunFacts,
    state: FeatureTaskRuntimeProgressSnapshotAccess,
    recorder: PhaseRunRecords,
    clock: Clock,
    capture: ValidatedOutputCapture,
  ) {
    val run = capture.run
    recorder.retainProducerOutput(
      ProducerOutputEvidence(
        workflowId = request.workflowId,
        phaseId = run.phaseId,
        attempt = capture.iteration,
        agentId = run.resolvedAgent.resolvedAgentId,
        model = run.modelDirective?.model ?: "unspecified",
        recordedAt = clock.instant(),
        byteSize = capture.outputByteSize,
        sha256 = capture.outputSha256,
        payload = capture.outputBytes.takeUnless { capture.outputTruncated },
        generation = (if (run.policy.generationScoped) state.reviewEvidenceGeneration else 0),
        repairTurn = run.validationGateRepairTurn,
      ),
    )
  }

  internal fun finaliseSubtaskCommit(args: FinaliseSubtaskCommitArgs): CommitPushFinalisation {
    if (
      args.run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH ||
      (args.normalizedOutput.envelopeWireMap()[SharedPayloadKeys.STATUS] as? String)
        .workflowStepStatus() != WorkflowStepStatus.COMPLETED
    ) {
      return CommitPushNotApplicable
    }
    val subtaskCommit = FeatureTaskRuntimeRunLoopSubtaskCommit
    val branch =
      subtaskCommit.finalisationBranch(args.request, args.session, args.gitOperations)
        ?: return subtaskCommit.unownedWorktreeCommitSha(
          UnownedWorktreeCommitShaArgs(
            args.request,
            args.diagnostics,
            args.gitOperations,
            args.run,
            args.normalizedOutput,
          ),
        )
    val handoff =
      when (
        val read = FeatureTaskRuntimeSubtaskFinalisation.readHandoff(args.normalizedOutput.envelopeWireMap())
      ) {
        is FeatureTaskRuntimeCommitPushHandoffInvalid -> return CommitPushBlocked(read.reason)
        is FeatureTaskRuntimeCommitPushHandoffValid -> read.handoff
      }
    val identity = FeatureTaskRuntimeRunLoopCheckpoint.subtaskCommitIdentity(args.request)
    val ledger =
      FeatureTaskRuntimeRunLoopCheckpoint.subtaskCommitLedgerState(
        args.request,
        args.recorder,
        args.diagnostics,
        identity,
      )
    return finaliseSubtaskCommitResult(args, branch, handoff, identity, ledger)
  }

  private fun finaliseSubtaskCommitResult(
    args: FinaliseSubtaskCommitArgs,
    branch: String,
    handoff: FeatureTaskRuntimeCommitPushHandoff,
    identity: FeatureTaskRuntimeSubtaskCommitIdentity,
    ledger: SubtaskCommitLedgerState,
  ): CommitPushFinalisation {
    val outcome =
      finaliseSubtaskCommitOutcome(
        FinaliseSubtaskCommitOutcomeArgs(
          request = args.request,
          progress = args.progress,
          recorder = args.recorder,
          diagnostics = args.diagnostics,
          gitOperations = args.gitOperations,
          phase = args.run,
          branch = branch,
          handoff = handoff,
          identity = identity,
          ledger = ledger,
        ),
      )
    return when (outcome) {
      is FeatureTaskRuntimeSubtaskFinalisationBlocked -> CommitPushBlocked(outcome.reason)
      is FeatureTaskRuntimeSubtaskFinalised ->
        CommitPushSettled(
          FeatureTaskRuntimeRunLoopSubtaskCommit.revalidated(
            FeatureTaskRuntimeSubtaskFinalisation.withCommitSha(
              args.normalizedOutput.envelopeWireMap(),
              outcome.commitSha,
            ),
          ),
        )
    }
  }

  private fun finaliseSubtaskCommitOutcome(
    args: FinaliseSubtaskCommitOutcomeArgs,
  ): FeatureTaskRuntimeSubtaskFinalisationResult {
    val request = args.request
    val state = args.progress
    val recorder = args.recorder
    val diagnostics = args.diagnostics
    val gitOperations = args.gitOperations
    return FeatureTaskRuntimeSubtaskFinalisation(
      gitOperations = gitOperations,
      repoRoot = request.repoRoot,
      record = { record -> RuntimeDiagnosticsBestEffortWarning.record(diagnostics, record) },
      recordCommit = { commitSha, stagedPaths ->
        FeatureTaskRuntimeRunLoopSubtaskCommit.recordFinalisedCheckpointIdentity(
          request,
          state,
          recorder,
          diagnostics,
          RecordFinalisedCheckpointIdentityArgs(
            args.phase.phaseId,
            args.branch,
            args.ledger,
            commitSha,
            stagedPaths,
          ),
        )
      },
    ).finalise(
      FeatureTaskRuntimeSubtaskFinaliseRequest(
        identity = args.identity,
        durableCommitSha = args.ledger.commitSha,
        sequenceNumber = args.ledger.nextSequenceNumber,
        handoff = args.handoff,
        metadata =
          FeatureTaskRuntimeCheckpointMetadata(
            phaseId = args.phase.phaseId,
            loopId = null,
            generation = FeatureTaskRuntimeRunLoopCheckpoint.checkpointGeneration(state, null),
            branch = args.branch,
            intent = FeatureTaskRuntimeCheckpointMessage.INTENT_FINALISED_SUBTASK,
          ),
        manifestCommitSha = null,
      ),
    )
  }
}

private data class FinaliseSubtaskCommitOutcomeArgs(
  val request: FeatureTaskRuntimeRunFacts,
  val progress: FeatureTaskRuntimeProgressSnapshotAccess,
  val recorder: PhaseRunRecords,
  val diagnostics: RuntimeDiagnostics,
  val gitOperations: WorkflowGitOperations,
  val phase: PhaseRun,
  val branch: String,
  val handoff: FeatureTaskRuntimeCommitPushHandoff,
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val ledger: SubtaskCommitLedgerState,
)
