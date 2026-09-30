package skillbill.engine.featuretask.slot.attempt

import skillbill.application.diagnostics.model.RejectedOutputDiagnosticRequest
import skillbill.contracts.JsonCodec
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
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseGates
import skillbill.engine.featuretask.runloop.checkpoint.FeatureTaskRuntimeRunLoopCheckpoint
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.CapturedPhaseOutput
import skillbill.engine.featuretask.runloop.core.CommitPushBlocked
import skillbill.engine.featuretask.runloop.core.CommitPushFinalisation
import skillbill.engine.featuretask.runloop.core.CommitPushNotApplicable
import skillbill.engine.featuretask.runloop.core.CommitPushSettled
import skillbill.engine.featuretask.runloop.core.CompletionProjectionRejectionArgs
import skillbill.engine.featuretask.runloop.core.CorrectiveRepairRejectionArgs
import skillbill.engine.featuretask.runloop.core.CorrectiveRepairRejectionDetail
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSubtaskCommit
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
import skillbill.engine.featuretask.runloop.core.SettledOutputContext
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
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.runner.boundedSchemaGateDetail
import skillbill.engine.featuretask.runner.terminalBlockedReasonFrom
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.slot.state.PhaseRunSettlements
import skillbill.engine.featuretask.slot.state.PhaseSettledEnvelopeRead
import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureKind
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeValidationEvidenceSchemaError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputValidator
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.envelopeWireMap
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.repair.CorrectiveRepairCapturedResponse
import skillbill.workflow.taskruntime.model.repair.CorrectiveRepairDiagnosticLocator
import skillbill.workflow.taskruntime.model.repair.task.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock

internal data class SettleValidatedOutputCommitArgs(
  val request: FeatureTaskRuntimeRunFacts,
  val state: FeatureTaskRuntimeRunState,
  val recorder: PhaseRunRecords,
  val diagnostics: RuntimeDiagnostics,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val phaseGates: FeatureTaskRuntimePhaseGates,
  val goalContinuationRecorder: PhaseRunGoal,
  val session: FeatureTaskRuntimeRunLoopSession,
  val run: PhaseRun,
  val capture: ValidatedOutputCapture,
  val attested: NormalizedFeatureTaskRuntimePhaseOutput,
  val observability: FeatureTaskRuntimeRunObservability,
)

internal data class FinaliseSubtaskCommitArgs(
  val request: FeatureTaskRuntimeRunFacts,
  val state: FeatureTaskRuntimeRunState,
  val recorder: PhaseRunRecords,
  val diagnostics: RuntimeDiagnostics,
  val outputValidator: FeatureTaskRuntimePhaseOutputValidator,
  val phaseGates: FeatureTaskRuntimePhaseGates,
  val session: FeatureTaskRuntimeRunLoopSession,
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
    settleFromPersistedEnvelope(args)?.let { return it }
    return try {
      val run = args.run
      val acceptedOutput = args.call.description.decoder.decode(args.outputValidator, args.captured.text, run.phaseId)
      settleValidatedOutput(
        SettleValidatedOutput(
          run = run,
          iteration = args.iteration,
          output =
            SettledOutputContext(
              normalizedOutput = acceptedOutput.normalizedOutput,
              repairEvidence = acceptedOutput.repairEvidence,
              observability = args.observability,
              fileManifest = args.fileManifest,
              captured = args.captured,
            ),
          settlementContext = args.settlementContext,
        ),
      )
    } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
      gateOutputSchemaInvalid(args.state, args.recorder, args, error)
    }
  }

  internal fun correctiveRepairContextForRejection(
    args: CorrectiveRepairRejectionArgs,
  ): FeatureTaskRuntimeCorrectiveRepairContext {
    val run = args.run
    val iteration = args.iteration
    val captured = args.captured
    val diagnosticWrite = args.diagnosticWrite
    val rejection = args.rejection
    val utf8ByteCount = captured.byteSize.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    val capturedResponse =
      if (captured.truncated) {
        CorrectiveRepairCapturedResponse.AlreadyTruncated(
          utf8ByteCount = utf8ByteCount,
          digestSha256 = captured.sha256,
        )
      } else {
        CorrectiveRepairCapturedResponse.classify(
          body = captured.text,
          alreadyTruncated = false,
          knownUtf8ByteCount = utf8ByteCount,
          knownDigestSha256 = captured.sha256,
        )
      }
    val repairEvidence = rejection.structuralRepairEvidence
    val locator =
      (diagnosticWrite as? FeatureTaskRuntimeRejectedOutputWrite.Written)?.let {
        CorrectiveRepairDiagnosticLocator(it.identity)
      }
    val degradationClass = (diagnosticWrite as? FeatureTaskRuntimeRejectedOutputWrite.Degraded)?.failureClass
    return FeatureTaskRuntimeCorrectiveRepairContext(
      phaseId = run.phaseId,
      attempt = iteration.coerceAtLeast(1),
      repairTurn = run.validationGateRepairTurn.takeIf { it > 0 },
      rejectionRule = rejection.rule,
      rejectionPath = rejection.path,
      payloadFreeConstraint = rejection.payloadFreeConstraint,
      diagnosticLocator = locator,
      captured = capturedResponse,
      acceptedAfterStructuralRepair = rejection.acceptedAfterStructuralRepair || repairEvidence != null,
      structuralRepairEvidence = repairEvidence,
      diagnosticDegradationClass = degradationClass,
    )
  }

  internal fun recordRejectedOutput(
    state: FeatureTaskRuntimeRunState,
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
      state.evidenceGeneration(targeting.generationScoped),
    )
  }

  internal fun persistChildProcessFailureOutput(
    context: PhaseAttemptEnvironment,
    run: PhaseRun,
    iteration: Int,
    reason: String,
    childOutput: FeatureTaskRuntimeChildOutput?,
  ) {
    val output = childOutput ?: return
    runCatching {
      recordRejectedOutput(
        context.state,
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
    val state = args.state
    val recorder = args.recorder
    val phaseGates = args.phaseGates
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
    val hooks = context.stepHooks(capture.run)
    with(context) {
      settleStepOutputCheck(
        hooks.checkValidatedOutput(capture.run, context, stepState(capture.run), outputMap),
        capture,
        ::reject,
      )
    }?.let { return it }
    FeatureTaskRuntimeRunLoopOutputVerification.firstValidatedOutputRejection(
      capture.run.phaseId,
      capture.run.policy.mutating,
      outputMap,
    )?.let {
        (
          rule,
          reason,
        ),
      ->
      return reject(rule, reason)
    }
    val fingerprintResolution =
      PhaseOutputGate.resolveRepositoryFingerprint(
        PhaseAttemptContext(
          run = capture.run,
          state = state,
          iteration = capture.iteration,
          observability = observability,
        ),
        recorder,
        phaseGates,
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
          reject = ::reject,
        ),
      )
    }
  }

  internal fun settleFromPersistedEnvelope(args: GateOutput): AttemptResult? {
    val settlementEnvelope =
      when (val settled = args.settledEnvelope) {
        PhaseSettledEnvelopeRead.None -> null
        is PhaseSettledEnvelopeRead.Found -> settled.envelope
        is PhaseSettledEnvelopeRead.Failed -> {
          clearAndRecordPersistedEvidenceFailure(
            args.state,
            args.recorder,
            args.phaseSettlementService,
            args,
            settled.error,
          )
          null
        }
      } ?: return null
    return settlePersistedEnvelope(args, settlementEnvelope)
  }

  private fun settlePersistedEnvelope(
    args: GateOutput,
    settlementEnvelope: FeatureTaskRuntimeWorkflowArtifactMap,
  ): AttemptResult? {
    val run = args.run
    return try {
      val acceptedOutput =
        args.call.description.decoder.decode(
          args.outputValidator,
          JsonCodec.mapToJsonString(settlementEnvelope),
          run.phaseId,
        )
      settleValidatedOutput(
        SettleValidatedOutput(
          run = run,
          iteration = args.iteration,
          output =
            SettledOutputContext(
              normalizedOutput = acceptedOutput.normalizedOutput,
              repairEvidence = acceptedOutput.repairEvidence,
              observability = args.observability,
              fileManifest = args.fileManifest,
              captured = args.captured,
            ),
          settlementContext = args.settlementContext,
        ),
      )
    } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
      rejectPersistedEnvelopeSchema(
        PersistedEnvelopeSchemaRejection(
          state = args.state,
          recorder = args.recorder,
          phaseSettlementService = args.phaseSettlementService,
          args = args,
          error = error,
        ),
      )
      null
    } catch (error: InvalidFeatureTaskRuntimeValidationEvidenceSchemaError) {
      clearAndRecordPersistedEvidenceFailure(args.state, args.recorder, args.phaseSettlementService, args, error)
      null
    }
  }

  private fun clearAndRecordPersistedEvidenceFailure(
    state: FeatureTaskRuntimeRunState,
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

  private fun rejectPersistedEnvelopeSchema(args: PersistedEnvelopeSchemaRejection) {
    val state = args.state
    val recorder = args.recorder
    val phaseSettlementService = args.phaseSettlementService
    val output = args.args
    val run = output.run
    val error = args.error
    phaseSettlementService.clear(
      workflowId = run.request.workflowId,
      phaseId = run.phaseId,
      attempt = output.iteration,
    )
    val context = output.settlementContext
    context.stepHooks(run).retainSchemaRejectedOutput(context.stepState(run), output.captured.text)
    PhaseOutputGate.recordRejectedOutput(
      state,
      recorder,
      RecordRejectedOutputArgs(
        run = run,
        iteration = output.iteration,
        rule = "phase-settlement-schema",
        reason = error.reason,
        captured = output.captured,
        targeting =
          PhaseOutputGate.rejectedOutputTargeting(
            defaultRejectedOutputTargetingArgs(
              run,
              RejectedOutputTargetingOverrides(
                path = rejectionPath(error.reason),
              ),
            ),
          ),
        exhaustedFixLoop = output.rejectionExhaustsFixLoop,
      ),
    )
  }

  private data class PersistedEnvelopeSchemaRejection(
    val state: FeatureTaskRuntimeRunState,
    val recorder: PhaseRunRecords,
    val phaseSettlementService: PhaseRunSettlements,
    val args: GateOutput,
    val error: InvalidFeatureTaskRuntimePhaseOutputSchemaError,
  )

  internal fun gateOutputEarlyExit(args: GateOutput): AttemptResult? =
    args.settlementContext.stepHooks(args.run)
      .earlyOutput(args.run, args.iteration, args.captured.text)
      ?.let(AttemptResult::settled)

  internal fun gateOutputSchemaInvalid(
    state: FeatureTaskRuntimeRunState,
    recorder: PhaseRunRecords,
    args: GateOutput,
    error: InvalidFeatureTaskRuntimePhaseOutputSchemaError,
  ): AttemptResult {
    val run = args.run
    val context = args.settlementContext
    context.stepHooks(run).retainSchemaRejectedOutput(context.stepState(run), args.captured.text)
    val path = rejectionPath(error.reason)
    val reason = payloadFreeRejectionReason("phase-output-schema", path)
    val diagnosticWrite =
      PhaseOutputGate.recordRejectedOutput(
        state,
        recorder,
        RecordRejectedOutputArgs(
          run = run,
          iteration = args.iteration,
          rule = "phase-output-schema",
          reason = error.reason,
          captured = args.captured,
          targeting =
            PhaseOutputGate.rejectedOutputTargeting(
              defaultRejectedOutputTargetingArgs(run, RejectedOutputTargetingOverrides(path = path)),
            ),
          exhaustedFixLoop = args.rejectionExhaustsFixLoop,
        ),
      )
    val repairEvidence =
      FeatureTaskRuntimeRunLoopOutputVerification
        .structuralRepairEvidenceFromSchemaError(error)
    return FeatureTaskRuntimeRunLoopOutputPersistence.schemaInvalidAttempt(
      reason,
      args.fileManifest,
      malformedOutput = error.failureKind == FeatureTaskRuntimePhaseOutputFailureKind.MALFORMED,
      retryReason = retryRejectionReason(reason, error.payloadFreeReason),
      correctiveRepairContext =
        PhaseOutputGate.correctiveRepairContextForRejection(
          CorrectiveRepairRejectionArgs(
            run = run,
            iteration = args.iteration,
            captured = args.captured,
            diagnosticWrite = diagnosticWrite,
            rejection =
              CorrectiveRepairRejectionDetail(
                rule = "phase-output-schema",
                path = path,
                payloadFreeConstraint = error.payloadFreeReason.orEmpty(),
                acceptedAfterStructuralRepair = error.acceptedAfterStructuralRepair,
                structuralRepairEvidence = repairEvidence,
              ),
          ),
        ),
    )
  }

  internal data class RepositoryFingerprintResolution(
    val fingerprint: String?,
    val blocked: AttemptResult?,
  )

  internal fun resolveRepositoryFingerprint(
    context: PhaseAttemptContext,
    recorder: PhaseRunRecords,
    phaseGates: FeatureTaskRuntimePhaseGates,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
    fingerprintsCompletedRepository: Boolean,
  ): RepositoryFingerprintResolution {
    if (!fingerprintsCompletedRepository) return RepositoryFingerprintResolution(null, null)
    val request = context.run.request
    val result = phaseGates.gitOperations.repositoryFingerprint(request.repoRoot)
    if (result !is WorkflowGitOperationResult.Ok) {
      val blocked =
        AttemptResult.settled(
          FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
            request,
            context.state,
            recorder,
            context.observability,
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

  private fun PhaseAttemptEnvironment.settleStepOutputCheck(
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
      is PhaseStepOutputCheck.Block ->
        AttemptResult.settled(
          FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
            request,
            state,
            recorder,
            observability,
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
      PhaseStepOutputCheck.Accept -> null
    }

  internal fun settleValidatedOutputPauseOrTerminal(
    request: FeatureTaskRuntimeRunFacts,
    state: FeatureTaskRuntimeRunState,
    recorder: PhaseRunRecords,
    observability: FeatureTaskRuntimeRunObservability,
    args: SettleValidatedOutputPauseArgs,
  ): AttemptResult? {
    val capture = args.capture
    val outputMap = args.attested.envelopeWireMap()
    val attested = args.attested
    val repairEvidence = args.repairEvidence
    val repositoryFingerprint = args.repositoryFingerprint
    val run = capture.run
    val blockedDisposition = args.blockedDisposition
    terminalBlockedReasonFrom(run.phaseId, outputMap, blockedDisposition)?.let { reason ->
      return FeatureTaskRuntimeRunLoopOutputVerification.terminalOutputAttempt(
        request,
        state,
        recorder,
        TerminalOutputAttemptArgs(
          run = run,
          iteration = capture.iteration,
          reason = reason,
          normalizedOutput = attested,
          repairEvidence = repairEvidence,
          observability = observability,
          fileManifest = capture.fileManifest,
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
            state = args.state,
            recorder = args.recorder,
            diagnostics = args.diagnostics,
            outputValidator = args.outputValidator,
            phaseGates = args.phaseGates,
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
              args.request,
              args.state,
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

  internal fun PhaseAttemptEnvironment.settleValidatedOutputAfterFingerprint(
    args: SettleValidatedOutputAfterFingerprintArgs,
  ): AttemptResult {
    return settleValidatedOutputPauseOrTerminal(
      request,
      state,
      recorder,
      observability,
      SettleValidatedOutputPauseArgs(
        capture = args.capture,
        attested = args.attested,
        repairEvidence = args.repairEvidence,
        observability = observability,
        repositoryFingerprint = args.repositoryFingerprint,
        blockedDisposition = stepHooks(args.capture.run).blockedOutputDisposition,
      ),
    ) ?: settleValidatedOutputAfterPause(args)
  }

  private fun PhaseAttemptEnvironment.settleValidatedOutputAfterPause(
    args: SettleValidatedOutputAfterFingerprintArgs,
  ): AttemptResult {
    val run = args.capture.run
    val rejection =
      FeatureTaskRuntimeRunLoopOutputVerification.completionProjectionRejection(
        this,
        CompletionProjectionRejectionArgs(
          run = run,
          normalizedOutput = args.attested,
          iteration = args.capture.iteration,
          repairEvidence = args.repairEvidence,
          repositoryFingerprint = args.repositoryFingerprint,
        ),
      ) ?: stepCompletionRejection(run, args.attested)
    return rejection?.let { (rule, reason) -> args.reject(rule, reason) }
      ?: settleStepOutputCheck(
        stepHooks(run).settleCompletedOutput(run, this, stepState(run), args.attested.envelopeWireMap()),
        args.capture,
        args.reject,
      )
      ?: stepHooks(run).settleCompletedRound(
        this,
        args.capture,
        args.attested,
        args.attested.envelopeWireMap(),
      )
      ?: finalizeValidatedOutputAcceptance(
        FinalizeValidatedOutputAcceptanceArgs(
          capture = args.capture,
          attested = stepHooks(run).acceptedOutput(this, args.capture, args.attested, args.attested.envelopeWireMap()),
          repairEvidence = args.repairEvidence,
          observability = observability,
          repositoryFingerprint = args.repositoryFingerprint,
        ),
      )
  }

  private fun PhaseAttemptEnvironment.stepCompletionRejection(
    run: PhaseRun,
    attested: NormalizedFeatureTaskRuntimePhaseOutput,
  ): Pair<String, String>? =
    stepHooks(run).completionRejection(run, this, stepState(run), attested.envelopeWireMap())?.let {
      "output-verification" to it
    }

  internal fun PhaseAttemptEnvironment.finalizeValidatedOutputAcceptance(
    args: FinalizeValidatedOutputAcceptanceArgs,
  ): AttemptResult {
    val capture = args.capture
    val attested = args.attested
    val repairEvidence = args.repairEvidence
    val repositoryFingerprint = args.repositoryFingerprint
    val run = capture.run
    val (finalised, commitBlocked) =
      settleValidatedOutputCommit(
        SettleValidatedOutputCommitArgs(
          request = request,
          state = state,
          recorder = recorder,
          diagnostics = diagnostics,
          outputValidator = outputValidator,
          phaseGates = phaseGates,
          goalContinuationRecorder = goalContinuationRecorder,
          session = session,
          run = run,
          capture = capture,
          attested = attested,
          observability = observability,
        ),
      )
    commitBlocked?.let { return it }
    PhaseOutputGate.retainSettledProducerOutput(request, state, recorder, clock, capture)
    stepHooks(run).recordAcceptedOutput(run, this, stepState(run), finalised.envelopeWireMap())
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
    val state = settlement.state
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
    val diagnosticWrite =
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
      correctiveRepairContext =
        PhaseOutputGate.correctiveRepairContextForRejection(
          CorrectiveRepairRejectionArgs(
            run = capture.run,
            iteration = capture.iteration,
            captured = capture.captured,
            diagnosticWrite = diagnosticWrite,
            rejection =
              CorrectiveRepairRejectionDetail(
                rule = diagnosticRule,
                path = path,
                payloadFreeConstraint = retryFacingConstraint ?: reason,
                acceptedAfterStructuralRepair = capture.repairEvidence != null,
                structuralRepairEvidence = capture.repairEvidence,
              ),
          ),
        ),
    )
  }

  internal fun retainSettledProducerOutput(
    request: FeatureTaskRuntimeRunFacts,
    state: FeatureTaskRuntimeRunState,
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
        generation = state.evidenceGeneration(run.policy.generationScoped),
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
      subtaskCommit.finalisationBranch(args.request, args.session, args.phaseGates)
        ?: return subtaskCommit.unownedWorktreeCommitSha(
          UnownedWorktreeCommitShaArgs(
            args.request,
            args.outputValidator,
            args.diagnostics,
            args.phaseGates,
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
          state = args.state,
          recorder = args.recorder,
          diagnostics = args.diagnostics,
          phaseGates = args.phaseGates,
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
            args.outputValidator,
            args.run.phaseId,
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
    val state = args.state
    val recorder = args.recorder
    val diagnostics = args.diagnostics
    val phaseGates = args.phaseGates
    return FeatureTaskRuntimeSubtaskFinalisation(
      gitOperations = phaseGates.gitOperations,
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
  val state: FeatureTaskRuntimeRunState,
  val recorder: PhaseRunRecords,
  val diagnostics: RuntimeDiagnostics,
  val phaseGates: FeatureTaskRuntimePhaseGates,
  val phase: PhaseRun,
  val branch: String,
  val handoff: FeatureTaskRuntimeCommitPushHandoff,
  val identity: FeatureTaskRuntimeSubtaskCommitIdentity,
  val ledger: SubtaskCommitLedgerState,
)
