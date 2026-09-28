package skillbill.engine.featuretask.slot.attempt

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProducerOutputRead
import skillbill.engine.featuretask.model.phase.ProducerOutputQueryArgs
import skillbill.engine.featuretask.model.review.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.engine.featuretask.phase.prompt.directives.PriorAttemptCorrection
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.CapturedPhaseOutput
import skillbill.engine.featuretask.runloop.core.FixLoopBranchContext
import skillbill.engine.featuretask.runloop.core.MissingProducerAgentBlockArgs
import skillbill.engine.featuretask.runloop.core.MissingProducerAgentResolutionArgs
import skillbill.engine.featuretask.runloop.core.PhaseAttemptContext
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ProducerEvidenceRecordRejectionArgs
import skillbill.engine.featuretask.runloop.core.QuarantineRecordRejectionArgs
import skillbill.engine.featuretask.runloop.core.RecordRejectedOutputArgs
import skillbill.engine.featuretask.runloop.core.RecordRejection
import skillbill.engine.featuretask.runloop.core.RejectedOutputTargetingOverrides
import skillbill.engine.featuretask.runloop.core.SettleRecordRejectionArgs
import skillbill.engine.featuretask.runloop.core.UnattributableRecordRejectionArgs
import skillbill.engine.featuretask.runloop.core.WriteQuarantineRejectedOutputArgs
import skillbill.engine.featuretask.runloop.core.WriteUnattributableRejectedEvidenceArgs
import skillbill.engine.featuretask.runloop.core.defaultRejectedOutputTargetingArgs
import skillbill.engine.featuretask.runloop.core.phaseBlockArgs
import skillbill.engine.featuretask.runloop.core.withDisposition
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeContinuationKind
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.observability.continuation
import skillbill.engine.featuretask.runloop.observability.fixLoopIteration
import skillbill.engine.featuretask.runloop.output.payloadFreeRejectionReason
import skillbill.engine.featuretask.runloop.output.rejectionPath
import skillbill.engine.featuretask.runloop.output.retryRejectionReason
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeAttemptBudgets
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.runner.STATUS_RUNNING
import skillbill.engine.featuretask.runner.nonRetryingPhaseSchemaBlockReason
import skillbill.engine.featuretask.runner.withSchemaGateDetail
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

object PhaseAttemptContinuations {
  internal fun settleIncompleteWork(
    request: FeatureTaskRuntimeRunFacts,
    state: FeatureTaskRuntimeRunState,
    recorder: PhaseRunRecords,
    observability: FeatureTaskRuntimeRunObservability,
    context: FixLoopBranchContext,
  ): PhaseOutcome? {
    val run = context.run
    val attempt = context.attempt
    val loop = context.loop
    val observability = context.observability
    val agentId = context.agentId
    loop.continuationSegmentCount += 1
    if (!PhaseAttemptContinuations.recordIncompleteAttempt(recorder, run, loop.iteration, attempt)) {
      return FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
        request,
        state,
        recorder,
        observability,
        PhaseBlockRequest(
          run = run,
          attemptCount = loop.iteration,
          reason =
            "Feature-task-runtime phase '${run.phaseId}' could not durably append its incomplete implementation " +
              "attempt (segment ${loop.continuationSegmentCount}). Continuing would lose the continuation " +
              "projection, so the run stops here rather than retrying against persistence.state that was never " +
              "persisted.",
          observability = observability,
          payload = BlockAndPersistPayload(fileManifest = attempt.fileManifest),
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        ),
      )
    }
    if (run.policy.singleAgentSession) {
      return FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
        request,
        state,
        recorder,
        observability,
        PhaseBlockRequest(
          run = run,
          attemptCount = loop.iteration,
          reason =
            "Phase '${run.phaseId}' ended its single agent session with unfinished work. " +
              "${requireNotNull(attempt.retryableOperatorReason)} " +
              "Resume explicitly after addressing the remaining gaps.",
          observability = observability,
          payload =
            BlockAndPersistPayload(
              fileManifest = attempt.fileManifest,
              normalizedOutput = attempt.incompleteWorkOutput,
            ),
          failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        ),
      )
    }
    loop.iteration += 1
    loop.priorCorrection = null
    observability.continuation(
      run.phaseId,
      agentId,
      loop.iteration,
      loop.continuationSegmentCount,
      FeatureTaskRuntimeContinuationKind.IMPLEMENTATION_CONTINUATION,
    )
    return null
  }

  internal fun settleBoundaryBodyDelivery(
    observability: FeatureTaskRuntimeRunObservability,
    context: FixLoopBranchContext,
  ): PhaseOutcome? {
    val run = context.run
    val loop = context.loop
    val observability = context.observability
    val agentId = context.agentId
    loop.continuationSegmentCount += 1
    loop.iteration += 1
    loop.priorCorrection = null
    observability.continuation(
      run.phaseId,
      agentId,
      loop.iteration,
      loop.continuationSegmentCount,
      FeatureTaskRuntimeContinuationKind.VERIFICATION_BODY_DELIVERY,
    )
    return null
  }

  internal fun settleMalformedOutput(
    request: FeatureTaskRuntimeRunFacts,
    state: FeatureTaskRuntimeRunState,
    recorder: PhaseRunRecords,
    observability: FeatureTaskRuntimeRunObservability,
    context: FixLoopBranchContext,
  ): PhaseOutcome? {
    val run = context.run
    val attempt = context.attempt
    val loop = context.loop
    val observability = context.observability
    val agentId = context.agentId
    if (run.policy.singleAgentSession) {
      return blockSingleAgentMalformedOutput(request, state, recorder, context)
    }
    loop.outputGateFailures += 1
    loop.malformedAttemptCount += 1
    val formatBlock =
      FeatureTaskRuntimeAttemptBudgets.outputGateBlockReason(
        run.phaseId,
        run.policy,
        loop.outputGateFailures,
      )
    if (formatBlock == null) {
      loop.iteration += 1
      loop.priorCorrection =
        PriorAttemptCorrection.schemaGate(
          requireNotNull(attempt.schemaInvalidRetryReason),
          correctiveRepairContext = attempt.correctiveRepairContext,
        )
      observability.fixLoopIteration(run.phaseId, agentId, loop.iteration, loop.malformedAttemptCount)
      return null
    }
    return FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
      request,
      state,
      recorder,
      observability,
      PhaseBlockRequest(
        run = run,
        attemptCount = loop.iteration,
        reason = withSchemaGateDetail(formatBlock, requireNotNull(attempt.schemaInvalidOperatorReason)),
        observability = observability,
        payload =
          BlockAndPersistPayload(
            fileManifest = attempt.fileManifest,
            rejectedOutput = attempt.rejectedOutput,
          ),
        failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
      ),
    )
  }

  private fun blockSingleAgentMalformedOutput(
    request: FeatureTaskRuntimeRunFacts,
    state: FeatureTaskRuntimeRunState,
    recorder: PhaseRunRecords,
    context: FixLoopBranchContext,
  ): PhaseOutcome {
    val attempt = context.attempt
    return FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
      request,
      state,
      recorder,
      context.observability,
      PhaseBlockRequest(
        run = context.run,
        attemptCount = context.loop.iteration,
        reason =
          withSchemaGateDetail(
            nonRetryingPhaseSchemaBlockReason(context.run.phaseId),
            requireNotNull(attempt.schemaInvalidOperatorReason),
          ),
        observability = context.observability,
        payload =
          BlockAndPersistPayload(
            fileManifest = attempt.fileManifest,
            rejectedOutput = attempt.rejectedOutput,
          ),
        failureDisposition = FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT,
      ),
    )
  }

  internal fun settleRetryableTerminal(
    request: FeatureTaskRuntimeRunFacts,
    state: FeatureTaskRuntimeRunState,
    recorder: PhaseRunRecords,
    observability: FeatureTaskRuntimeRunObservability,
    context: FixLoopBranchContext,
  ): PhaseOutcome? {
    val run = context.run
    val attempt = context.attempt
    val loop = context.loop
    val observability = context.observability
    val agentId = context.agentId
    if (!run.policy.relaunchOnInvalidOutput) {
      return blockNonRelaunchableTerminal(request, state, recorder, context)
    }
    val continuationOutput = attempt.retryableTerminalOutput
    val reportPersisted =
      continuationOutput == null || recordIncompleteAttempt(recorder, run, loop.iteration, attempt)
    val exhausted = loop.semanticIteration >= FeatureTaskRuntimeAttemptBudgets.MAX_PROCESS_FAILURE_ATTEMPTS
    if (!reportPersisted || exhausted) {
      val reason =
        if (!reportPersisted) {
          "Phase '${run.phaseId}' could not durably save its retryable repair report; " +
            "continuing would lose work evidence."
        } else {
          "Phase '${run.phaseId}' exhausted its retryable-failure budget after ${loop.semanticIteration} attempts. " +
            requireNotNull(attempt.retryableOperatorReason)
        }
      return FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
        request,
        state,
        recorder,
        observability,
        PhaseBlockRequest(
          run = run,
          attemptCount = loop.iteration,
          reason = reason,
          observability = observability,
          payload =
            BlockAndPersistPayload(
              fileManifest = attempt.fileManifest,
              normalizedOutput = continuationOutput,
            ),
          failureDisposition =
            if (reportPersisted) {
              requireNotNull(
                attempt.retryableTerminalDisposition,
              )
            } else {
              FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE
            },
        ),
      )
    }
    val failedIteration = loop.semanticIteration
    loop.iteration += 1
    loop.semanticIteration += 1
    loop.priorCorrection =
      PriorAttemptCorrection.retryableTerminal(requireNotNull(attempt.retryableTerminalRetryReason))
    observability.continuation(
      run.phaseId,
      agentId,
      loop.iteration,
      failedIteration,
      FeatureTaskRuntimeContinuationKind.PROCESS_RETRY,
    )
    return null
  }

  private fun blockNonRelaunchableTerminal(
    request: FeatureTaskRuntimeRunFacts,
    state: FeatureTaskRuntimeRunState,
    recorder: PhaseRunRecords,
    context: FixLoopBranchContext,
  ): PhaseOutcome =
    FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
      request,
      state,
      recorder,
      context.observability,
      PhaseBlockRequest(
        run = context.run,
        attemptCount = context.loop.iteration,
        reason = requireNotNull(context.attempt.retryableOperatorReason),
        observability = context.observability,
        payload =
          BlockAndPersistPayload(
            fileManifest = context.attempt.fileManifest,
            normalizedOutput = context.attempt.retryableTerminalOutput,
          ),
        failureDisposition = requireNotNull(context.attempt.retryableTerminalDisposition),
      ),
    )

  internal fun recordIncompleteAttempt(
    recorder: PhaseRunRecords,
    run: PhaseRun,
    iteration: Int,
    attempt: AttemptResult,
  ): Boolean {
    val normalized = attempt.incompleteWorkOutput ?: attempt.retryableTerminalOutput ?: return false
    return recorder.recordIncompleteImplementationAttempt(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = run.request.workflowId,
        phaseId = run.phaseId,
        status = STATUS_RUNNING,
        attemptCount = iteration.coerceAtLeast(1),
        resolvedAgentId = run.resolvedAgent.resolvedAgentId,
        finished = false,
        normalizedOutput = normalized,
        failureDisposition = attempt.retryableTerminalDisposition,
        loopId = run.reentry?.loopId,
        edgeIteration = run.reentry?.edgeIteration,
        mutating = run.policy.mutating,
      ),
    )
  }

  internal fun blockUnattributableRecordRejection(
    request: FeatureTaskRuntimeRunFacts,
    recorder: PhaseRunRecords,
    args: UnattributableRecordRejectionArgs,
    generationScoped: (String) -> Boolean,
  ): PhaseOutcome {
    val run = args.context.run
    val state = args.context.state
    val iteration = args.context.iteration
    val observability = args.context.observability
    val rejection = args.rejection
    val producer = args.producer
    val detail =
      payloadFreeRejectionReason(
        "reconciliation-${rejection.rejectionClass}",
        rejectionPath(rejection.rejectionDetail),
      )
    recordUnattributableRejectedEvidence(request, recorder, args, generationScoped)
    return FeatureTaskRuntimeRunLoopPhaseBlocking.blockAndPersistInPhase(
      request,
      state,
      recorder,
      null,
      phaseBlockArgs(
        run = run,
        attemptCount = iteration,
        reason = unattributableRecordRejectionReason(run.phaseId, rejection, producer, detail),
        observability = observability,
        payload = BlockAndPersistPayload(childNeverLaunched = true),
      ).withDisposition(FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION),
    )
  }

  internal fun recordUnattributableRejectedEvidence(
    request: FeatureTaskRuntimeRunFacts,
    recorder: PhaseRunRecords,
    args: UnattributableRecordRejectionArgs,
    generationScoped: (String) -> Boolean,
  ) {
    val run = args.context.run
    val state = args.context.state
    val rejection = args.rejection
    val detail =
      payloadFreeRejectionReason(
        "reconciliation-${rejection.rejectionClass}",
        rejectionPath(rejection.rejectionDetail),
      )
    val rejectedOutput =
      run.declaration.projectionDeclarations
        .asSequence()
        .map { it.producerIteration.phaseId }
        .distinct()
        .mapNotNull { phaseId -> state.outputFor(phaseId) }
        .firstOrNull()
    val outputGenerationScoped = rejectedOutput?.let { generationScoped(it.phaseId) } ?: false
    val evidence =
      rejectedOutput?.let { output ->
        unattributableProducerEvidence(request, recorder, state, output, outputGenerationScoped)
      }
    evidence?.let {
      writeUnattributableRejectedEvidence(
        WriteUnattributableRejectedEvidenceArgs(
          state,
          recorder,
          run,
          rejection,
          detail,
          it,
          outputGenerationScoped,
        ),
      )
    }
  }

  private fun unattributableProducerEvidence(
    request: FeatureTaskRuntimeRunFacts,
    recorder: PhaseRunRecords,
    state: FeatureTaskRuntimeRunState,
    output: FeatureTaskRuntimePhaseOutput,
    generationScoped: Boolean,
  ): ProducerOutputEvidence? {
    val agentId = state.recordFor(output.phaseId)?.resolvedAgentId ?: return null
    return when (
      val read =
        recorder.producerOutput(
          ProducerOutputQueryArgs(
            workflowId = request.workflowId,
            phaseId = output.phaseId,
            attempt = output.iteration.coerceAtLeast(1),
            agentId = agentId,
            generation = state.evidenceGeneration(generationScoped),
          ),
        )
    ) {
      is FeatureTaskRuntimeProducerOutputRead.Found -> read.evidence
      is FeatureTaskRuntimeProducerOutputRead.Absent,
      is FeatureTaskRuntimeProducerOutputRead.Unreadable,
      -> null
    }
  }

  private fun writeUnattributableRejectedEvidence(args: WriteUnattributableRejectedEvidenceArgs) {
    val state = args.state
    val recorder = args.recorder
    val run = args.run
    val rejection = args.rejection
    val detail = args.detail
    val evidence = args.evidence
    val payload = evidence.payload ?: byteArrayOf()
    PhaseOutputGate.recordRejectedOutput(
      state,
      recorder,
      RecordRejectedOutputArgs(
        run = run,
        iteration = evidence.attempt,
        rule = "reconciliation-${rejection.rejectionClass}",
        reason = retryRejectionReason(detail, rejection.rejectionDetail),
        captured =
          CapturedPhaseOutput(
            text = payload.decodeToString(),
            bytes = payload,
            truncated = evidence.payload == null,
            byteSize = evidence.byteSize,
            sha256 = evidence.sha256,
          ),
        targeting =
          PhaseOutputGate.rejectedOutputTargeting(
            defaultRejectedOutputTargetingArgs(
              run,
              RejectedOutputTargetingOverrides(
                phaseId = evidence.phaseId,
                agentId = evidence.agentId,
                model = evidence.model,
                path = rejectionPath(rejection.rejectionDetail),
                repairTurn = evidence.repairTurn,
                generationScoped = args.generationScoped,
              ),
            ),
          ),
      ),
    )
  }

  internal fun unattributableRecordRejectionReason(
    consumerPhaseId: String,
    rejection: RecordRejection,
    producer: String?,
    detail: String,
  ): String =
    if (producer == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD ||
      producer == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
    ) {
      "Feature-task-runtime phase '$consumerPhaseId' rejected gate evidence produced by '$producer'. " +
        "Preserve the run and inspect its bounded diagnostics with a compatible runtime or a separately reviewed " +
        "recovery mapping. Automatic regeneration is blocked because retained command semantics are unproven. " +
        "Detail: $detail"
    } else if (producer == null) {
      "Feature-task-runtime phase '$consumerPhaseId' rejected an upstream durable record " +
        "(${rejection.rejectionClass}) it cannot attribute to a producing phase, so no regeneration edge " +
        "applies; the run blocks durably. Retain the workflow and its evidence. Inspect status and diagnostics " +
        "with a compatible runtime or use a separately reviewed semantic mapping. Detail: $detail"
    } else {
      "Feature-task-runtime phase '$consumerPhaseId' rejected the durable record produced by '$producer', but " +
        "'$producer' is absent from this run's resolved pipeline (a goal-continuation truncation dropped it), " +
        "so it cannot be regenerated in-band; the run blocks durably. Retain the workflow and its evidence. " +
        "Inspect status and diagnostics with a compatible runtime or use a separately reviewed semantic mapping. Detail: $detail"
    }

  internal fun PhaseAttemptEnvironment.settleRecordRejection(args: SettleRecordRejectionArgs): PhaseOutcome {
    val run = args.run
    val state = args.state
    val iteration = args.iteration
    val observability = args.observability
    val rejection = args.rejection
    val regeneration = PhaseAttemptContinuations.recordRejectionRegenerationEdge(transitions, run.phaseId)
    if (regeneration == null) {
      return PhaseAttemptContinuations.blockUnattributableRecordRejection(
        request,
        recorder,
        UnattributableRecordRejectionArgs(
          context = PhaseAttemptContext(run, state, iteration, observability),
          rejection = rejection,
          producer = FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_PRODUCER_BY_CONSUMER[run.phaseId],
        ),
        generationScoped = { stepPolicy(it).generationScoped },
      )
    }
    if (
      regeneration.producer == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD ||
      regeneration.producer == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE
    ) {
      return PhaseAttemptContinuations.blockUnattributableRecordRejection(
        request,
        recorder,
        UnattributableRecordRejectionArgs(
          context = PhaseAttemptContext(run, state, iteration, observability),
          rejection = rejection,
          producer = regeneration.producer,
        ),
        generationScoped = { stepPolicy(it).generationScoped },
      )
    }
    val attemptContext = PhaseAttemptContext(run, state, iteration, observability)
    val evidenceResolution =
      PhaseAttemptContinuations
        .readProducerEvidenceForRecordRejection(
          request,
          state,
          recorder,
          observability,
          ProducerEvidenceRecordRejectionArgs(
            context = attemptContext,
            producer = regeneration.producer,
            consumer = run.phaseId,
            producerGenerationScoped = stepPolicy(regeneration.producer).generationScoped,
          ),
        )
    return when (evidenceResolution) {
      is PhaseAttemptContinuations
        .RecordRejectionEvidenceResolution.Settled,
      -> evidenceResolution.outcome
      is PhaseAttemptContinuations.RecordRejectionEvidenceResolution.Ready ->
        PhaseAttemptContinuations.quarantineRecordRejection(
          request,
          state,
          recorder,
          QuarantineRecordRejectionArgs(
            context = attemptContext,
            rejection = rejection,
            regeneration = regeneration,
            producerEvidence = evidenceResolution.evidence,
            producerGenerationScoped = stepPolicy(regeneration.producer).generationScoped,
          ),
        )
    }
  }

  internal data class RecordRejectionRegenerationEdge(
    val producer: String,
    val edge: FeatureTaskRuntimeBackwardEdge,
  )

  internal fun recordRejectionRegenerationEdge(
    transitions: FeatureTaskRuntimeTransitionDeclaration,
    consumer: String,
  ): PhaseAttemptContinuations.RecordRejectionRegenerationEdge? {
    val producer =
      FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_PRODUCER_BY_CONSUMER[consumer] ?: return null
    val edge =
      transitions.backwardEdges.firstOrNull {
        it.fromPhaseId == consumer &&
          it.destinationPhaseId == producer &&
          it.triggeringVerdict == FeatureTaskRuntimeVerdict.RECORD_REJECTED
      } ?: return null
    if (producer !in transitions.forwardPhaseIds) return null
    return PhaseAttemptContinuations.RecordRejectionRegenerationEdge(producer, edge)
  }

  internal sealed interface RecordRejectionEvidenceResolution {
    data class Ready(
      val evidence: ProducerOutputEvidence,
    ) : RecordRejectionEvidenceResolution

    data class Settled(
      val outcome: PhaseOutcome,
    ) : RecordRejectionEvidenceResolution
  }

  internal fun readProducerEvidenceForRecordRejection(
    request: FeatureTaskRuntimeRunFacts,
    state: FeatureTaskRuntimeRunState,
    recorder: PhaseRunRecords,
    observability: FeatureTaskRuntimeRunObservability,
    args: ProducerEvidenceRecordRejectionArgs,
  ): RecordRejectionEvidenceResolution {
    val run = args.context.run
    val state = args.context.state
    val iteration = args.context.iteration
    val observability = args.context.observability
    val producer = args.producer
    val consumer = args.consumer
    val producingIteration =
      (state.outputFor(producer)?.iteration ?: state.recordFor(producer)?.attemptCount ?: 1)
        .coerceAtLeast(1)
    val producerAgentId =
      state.recordFor(producer)?.resolvedAgentId
        ?: return missingProducerAgentResolution(
          MissingProducerAgentResolutionArgs(
            request,
            state,
            recorder,
            run,
            iteration,
            consumer,
            producer,
            observability,
          ),
        )
    return when (
      val producerRead =
        recorder.producerOutput(
          ProducerOutputQueryArgs(
            workflowId = request.workflowId,
            phaseId = producer,
            attempt = producingIteration,
            agentId = producerAgentId,
            generation = state.evidenceGeneration(args.producerGenerationScoped),
          ),
        )
    ) {
      is FeatureTaskRuntimeProducerOutputRead.Found ->
        RecordRejectionEvidenceResolution.Ready(producerRead.evidence)
      is FeatureTaskRuntimeProducerOutputRead.Absent,
      is FeatureTaskRuntimeProducerOutputRead.Unreadable,
      ->
        RecordRejectionEvidenceResolution.Settled(
          missingProducerEvidenceBlock(
            request,
            state,
            recorder,
            observability,
            MissingProducerEvidenceBlockArgs(
              run = run,
              iteration = iteration,
              consumer = consumer,
              producer = producer,
              producingIteration = producingIteration,
              producerRead = producerRead,
              observability = observability,
            ),
          ),
        )
    }
  }

  private fun missingProducerAgentResolution(
    args: MissingProducerAgentResolutionArgs,
  ): RecordRejectionEvidenceResolution =
    RecordRejectionEvidenceResolution.Settled(
      missingProducerAgentBlock(
        args.request,
        args.state,
        args.recorder,
        args.observability,
        MissingProducerAgentBlockArgs(
          args.run,
          args.iteration,
          args.consumer,
          args.producer,
          args.observability,
        ),
      ),
    )

  private fun missingProducerAgentBlock(
    request: FeatureTaskRuntimeRunFacts,
    state: FeatureTaskRuntimeRunState,
    recorder: PhaseRunRecords,
    observability: FeatureTaskRuntimeRunObservability,
    args: MissingProducerAgentBlockArgs,
  ): PhaseOutcome =
    FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
      request,
      state,
      recorder,
      observability,
      PhaseBlockRequest(
        run = args.run,
        attemptCount = args.iteration,
        reason =
          "Feature-task-runtime phase '${args.consumer}' rejected the durable record produced by " +
            "'${args.producer}', but the producing phase's resolved agent is unavailable, so exact raw " +
            "evidence cannot be scoped to a producer. The run blocks instead of fabricating a " +
            "rejected-output diagnostic.",
        observability = args.observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      ),
    )

  private data class MissingProducerEvidenceBlockArgs(
    val run: PhaseRun,
    val iteration: Int,
    val consumer: String,
    val producer: String,
    val producingIteration: Int,
    val producerRead: FeatureTaskRuntimeProducerOutputRead,
    val observability: FeatureTaskRuntimeRunObservability,
  )

  private fun missingProducerEvidenceBlock(
    request: FeatureTaskRuntimeRunFacts,
    state: FeatureTaskRuntimeRunState,
    recorder: PhaseRunRecords,
    observability: FeatureTaskRuntimeRunObservability,
    args: MissingProducerEvidenceBlockArgs,
  ): PhaseOutcome {
    val evidenceClause =
      if (args.producerRead is FeatureTaskRuntimeProducerOutputRead.Unreadable) {
        "retained evidence for attempt ${args.producingIteration} exists and the diagnostic store " +
          "refused it (${args.producerRead.failureClass.wireValue}). The run blocks instead of " +
          "fabricating a rejected-output diagnostic from normalized workflow persistence.state."
      } else {
        "no retained evidence exists for attempt ${args.producingIteration}. The run blocks instead " +
          "of fabricating a rejected-output diagnostic from normalized workflow persistence.state."
      }
    return FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
      request,
      state,
      recorder,
      observability,
      PhaseBlockRequest(
        run = args.run,
        attemptCount = args.iteration,
        reason =
          "Feature-task-runtime phase '${args.consumer}' rejected the durable record " +
            "produced by '${args.producer}', but $evidenceClause",
        observability = args.observability,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      ),
    )
  }

  internal fun quarantineRecordRejection(
    request: FeatureTaskRuntimeRunFacts,
    state: FeatureTaskRuntimeRunState,
    recorder: PhaseRunRecords,
    args: QuarantineRecordRejectionArgs,
  ): PhaseOutcome =
    quarantineRecordRejectionBody(
      request,
      state,
      recorder,
      args,
    )

  internal fun quarantineRecordRejectionBody(
    request: FeatureTaskRuntimeRunFacts,
    state: FeatureTaskRuntimeRunState,
    recorder: PhaseRunRecords,
    args: QuarantineRecordRejectionArgs,
  ): PhaseOutcome {
    val run = args.context.run
    val state = args.context.state
    val iteration = args.context.iteration
    val rejection = args.rejection
    val regeneration = args.regeneration
    val producerEvidence = args.producerEvidence
    val consumer = run.phaseId
    val producer = regeneration.producer
    val producingIteration =
      (state.outputFor(producer)?.iteration ?: state.recordFor(producer)?.attemptCount ?: 1)
        .coerceAtLeast(1)
    val diagnosticWrite =
      writeQuarantineRejectedOutput(
        state,
        recorder,
        WriteQuarantineRejectedOutputArgs(
          run,
          producingIteration,
          rejection,
          producer,
          producerEvidence,
          args.producerGenerationScoped,
        ),
      )
    appendQuarantineEntryForRejection(
      request,
      recorder,
      QuarantineEntryWriteArgs(
        consumer = consumer,
        producer = producer,
        producingIteration = producingIteration,
        rejection = rejection,
        regenerationAttempt = (state.edgeIterationCount(regeneration.edge.loopId) + 1).coerceAtLeast(1),
        iteration = iteration,
        diagnosticWrite = diagnosticWrite,
        producerEvidence = producerEvidence,
      ),
    )
    return PhaseOutcome.regenerateProducer(producer)
  }

  private fun writeQuarantineRejectedOutput(
    state: FeatureTaskRuntimeRunState,
    recorder: PhaseRunRecords,
    args: WriteQuarantineRejectedOutputArgs,
  ): FeatureTaskRuntimeRejectedOutputWrite {
    val run = args.run
    val producingIteration = args.producingIteration
    val rejection = args.rejection
    val producer = args.producer
    val producerEvidence = args.producerEvidence
    val rejectedPayload = producerEvidence.payload ?: byteArrayOf()
    return PhaseOutputGate.recordRejectedOutput(
      state,
      recorder,
      RecordRejectedOutputArgs(
        run = run,
        iteration = producingIteration,
        rule = "reconciliation-${rejection.rejectionClass}",
        reason =
          retryRejectionReason(
            payloadFreeRejectionReason(
              "reconciliation-${rejection.rejectionClass}",
              rejectionPath(rejection.rejectionDetail),
            ),
            rejection.rejectionDetail,
          ),
        captured =
          CapturedPhaseOutput(
            text = rejectedPayload.decodeToString(),
            bytes = rejectedPayload,
            truncated = producerEvidence.payload == null,
            byteSize = producerEvidence.byteSize,
            sha256 = producerEvidence.sha256,
          ),
        targeting =
          PhaseOutputGate.rejectedOutputTargeting(
            defaultRejectedOutputTargetingArgs(
              run,
              RejectedOutputTargetingOverrides(
                phaseId = producer,
                agentId = producerEvidence.agentId,
                model = producerEvidence.model,
                path = rejectionPath(rejection.rejectionDetail),
                repairTurn = producerEvidence.repairTurn,
                generationScoped = args.producerGenerationScoped,
              ),
            ),
          ),
      ),
    )
  }

  private data class QuarantineEntryWriteArgs(
    val consumer: String,
    val producer: String,
    val producingIteration: Int,
    val rejection: RecordRejection,
    val regenerationAttempt: Int,
    val iteration: Int,
    val diagnosticWrite: FeatureTaskRuntimeRejectedOutputWrite,
    val producerEvidence: ProducerOutputEvidence,
  )

  private fun appendQuarantineEntryForRejection(
    request: FeatureTaskRuntimeRunFacts,
    recorder: PhaseRunRecords,
    args: QuarantineEntryWriteArgs,
  ) {
    recorder.appendQuarantineEntry(
      request.workflowId,
      FeatureTaskRuntimeQuarantineEntry(
        producingPhaseId = args.producer,
        consumingPhaseId = args.consumer,
        producingIteration = args.producingIteration,
        rejectionClass = args.rejection.rejectionClass,
        rejectionDetail =
          payloadFreeRejectionReason(
            "reconciliation-${args.rejection.rejectionClass}",
            rejectionPath(args.rejection.rejectionDetail),
          ),
        regenerationAttempt = args.regenerationAttempt,
        quarantinedAtIteration = args.iteration.coerceAtLeast(1),
        diagnosticIdentity =
          (args.diagnosticWrite as? FeatureTaskRuntimeRejectedOutputWrite.Written)?.identity,
        rejectedRecordByteSize = args.producerEvidence.byteSize,
        rejectedRecordSha256 = args.producerEvidence.sha256,
        diagnosticDegraded = args.diagnosticWrite is FeatureTaskRuntimeRejectedOutputWrite.Degraded,
      ),
    )
  }
}
