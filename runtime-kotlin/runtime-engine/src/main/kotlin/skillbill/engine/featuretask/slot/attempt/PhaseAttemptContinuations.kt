package skillbill.engine.featuretask.slot.attempt

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeProducerOutputRead
import skillbill.engine.featuretask.model.phase.ProducerOutputQueryArgs
import skillbill.engine.featuretask.model.review.FeatureTaskRuntimeRejectedOutputWrite
import skillbill.engine.featuretask.phase.prompt.directives.PriorAttemptCorrection
import skillbill.engine.featuretask.runloop.attempt.settlementCoupling
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
import skillbill.engine.featuretask.runloop.output.payloadFreeRejectionReason
import skillbill.engine.featuretask.runloop.output.rejectionPath
import skillbill.engine.featuretask.runloop.output.retryRejectionReason
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeAttemptBudgets
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunTransitionOwner
import skillbill.engine.featuretask.runner.STATUS_RUNNING
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeQuarantineEntry
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

object PhaseAttemptContinuations {
  internal fun settleIncompleteWork(
    recorder: PhaseRunRecords,
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
        context.progress,
        context.loopTransitions,
        recorder,
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
        context.progress,
        context.loopTransitions,
        recorder,
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

  internal fun settleRetryableTerminal(
    recorder: PhaseRunRecords,
    context: FixLoopBranchContext,
    terminal: AttemptResult.RetryableTerminal,
  ): PhaseOutcome? {
    val run = context.run
    val loop = context.loop
    val reportPersisted =
      terminal.continuationOutput == null ||
        recordIncompleteAttempt(recorder, run, loop.iteration, context.attempt)
    val exhausted = loop.semanticIteration >= FeatureTaskRuntimeAttemptBudgets.MAX_PROCESS_FAILURE_ATTEMPTS
    if (!reportPersisted || exhausted) {
      return FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
        context.progress,
        context.loopTransitions,
        recorder,
        PhaseBlockRequest(
          run = run,
          attemptCount = loop.iteration,
          reason =
            if (reportPersisted) {
              "Phase '${run.phaseId}' exhausted its retryable-failure budget after " +
                "${loop.semanticIteration} attempts. ${terminal.operatorReason}"
            } else {
              "Phase '${run.phaseId}' could not durably save its retryable repair report; " +
                "continuing would lose work evidence."
            },
          observability = context.observability,
          payload =
            BlockAndPersistPayload(fileManifest = terminal.fileManifest, normalizedOutput = terminal.normalizedOutput),
          failureDisposition =
            if (reportPersisted) terminal.failureDisposition else FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        ),
      )
    }
    val failedIteration = loop.semanticIteration
    loop.iteration += 1
    loop.semanticIteration += 1
    loop.priorCorrection = PriorAttemptCorrection.retryableTerminal(terminal.operatorReason)
    context.observability.continuation(
      run.phaseId,
      context.agentId,
      loop.iteration,
      failedIteration,
      FeatureTaskRuntimeContinuationKind.PROCESS_RETRY,
    )
    return null
  }

  internal fun recordIncompleteAttempt(
    recorder: PhaseRunRecords,
    run: PhaseRun,
    iteration: Int,
    attempt: AttemptResult,
  ): Boolean {
    val terminal = attempt.retryableTerminal
    val normalized = attempt.incompleteWorkOutput ?: terminal?.continuationOutput ?: return false
    return recorder.recordIncompleteImplementationAttempt(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = run.request.workflowId,
        phaseId = run.phaseId,
        status = STATUS_RUNNING,
        attemptCount = iteration.coerceAtLeast(1),
        resolvedAgentId = run.resolvedAgent.resolvedAgentId,
        finished = false,
        normalizedOutput = normalized,
        failureDisposition = terminal?.failureDisposition,
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
      args.context.state,
      args.context.loopTransitions,
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
        .mapNotNull { phaseId -> state.phase(phaseId).output }
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
    state: FeatureTaskRuntimeProgressSnapshotAccess,
    output: FeatureTaskRuntimePhaseOutput,
    generationScoped: Boolean,
  ): ProducerOutputEvidence? {
    val agentId = state.phase(output.phaseId).record?.resolvedAgentId ?: return null
    return when (
      val read =
        recorder.producerOutput(
          ProducerOutputQueryArgs(
            workflowId = request.workflowId,
            phaseId = output.phaseId,
            attempt = output.iteration.coerceAtLeast(1),
            agentId = agentId,
            generation = (if (generationScoped) state.reviewEvidenceGeneration else 0),
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
        "Inspect status and diagnostics with a compatible runtime or use a separately reviewed semantic mapping. " +
        "Detail: $detail"
    }

  internal fun PhaseAttemptLaunchCollaborationScope.settleRecordRejection(
    args: SettleRecordRejectionArgs,
  ): PhaseOutcome {
    val run = args.run
    val state = args.state
    val iteration = args.iteration
    val observability = args.observability
    val rejection = args.rejection
    val transitionDeclaration = transitionDeclaration
    val coupling = settlementCoupling()
    val attemptContext =
      PhaseAttemptContext(
        run,
        coupling.transitions,
        transitionDeclaration,
        state,
        coupling.session,
        iteration,
        observability,
      )
    val regeneration =
      PhaseAttemptContinuations.recordRejectionRegenerationEdge(transitionDeclaration, run.phaseId)
    if (regeneration == null) {
      return PhaseAttemptContinuations.blockUnattributableRecordRejection(
        request,
        recorder,
        UnattributableRecordRejectionArgs(
          context = attemptContext,
          rejection = rejection,
          producer =
            transitionDeclaration.backwardEdges
              .firstOrNull {
                it.fromPhaseId == run.phaseId && it.triggeringVerdict == FeatureTaskRuntimeVerdict.RECORD_REJECTED
              }?.destinationPhaseId,
        ),
        generationScoped = { acceptedStepPolicy(it).generationScoped },
      )
    }
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
            producerGenerationScoped = acceptedStepPolicy(regeneration.producer).generationScoped,
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
            producerGenerationScoped = acceptedStepPolicy(regeneration.producer).generationScoped,
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
    val edge =
      transitions.backwardEdges.singleOrNull {
        it.fromPhaseId == consumer &&
          it.triggeringVerdict == FeatureTaskRuntimeVerdict.RECORD_REJECTED &&
          it.destinationPhaseId in FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_LOOP_ID_BY_PRODUCER &&
          FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_LOOP_ID_BY_PRODUCER[it.destinationPhaseId] == it.loopId
      } ?: return null
    val producer = edge.destinationPhaseId
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
    state: FeatureTaskRuntimeProgressSnapshotAccess,
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
      (state.phase(producer).output?.iteration ?: state.phase(producer).record?.attemptCount ?: 1)
        .coerceAtLeast(1)
    val producerAgentId =
      state.phase(producer).record?.resolvedAgentId
        ?: return missingProducerAgentResolution(
          MissingProducerAgentResolutionArgs(
            request,
            args.context.loopTransitions,
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
            generation = (if (args.producerGenerationScoped) state.reviewEvidenceGeneration else 0),
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
            args.context.state,
            args.context.loopTransitions,
            recorder,
            MissingProducerEvidenceBlockArgs(
              run = run,
              iteration = iteration,
              consumer = consumer,
              producer = producer,
              producingIteration = producingIteration,
              producerRead = producerRead,
              observability = observability,
              progress = args.context.state,
              loopTransitions = args.context.loopTransitions,
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
        args.state,
        args.loopTransitions,
        args.recorder,
        MissingProducerAgentBlockArgs(
          args.run,
          args.iteration,
          args.consumer,
          args.producer,
          args.observability,
          args.state,
          args.loopTransitions,
        ),
      ),
    )

  private fun missingProducerAgentBlock(
    progress: FeatureTaskRuntimeProgressSnapshotAccess,
    loopTransitions: FeatureTaskRuntimeRunTransitionOwner,
    recorder: PhaseRunRecords,
    args: MissingProducerAgentBlockArgs,
  ): PhaseOutcome =
    FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
      progress,
      loopTransitions,
      recorder,
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
    val progress: FeatureTaskRuntimeProgressSnapshotAccess,
    val loopTransitions: FeatureTaskRuntimeRunTransitionOwner,
  )

  private fun missingProducerEvidenceBlock(
    progress: FeatureTaskRuntimeProgressSnapshotAccess,
    loopTransitions: FeatureTaskRuntimeRunTransitionOwner,
    recorder: PhaseRunRecords,
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
      progress,
      loopTransitions,
      recorder,
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
    state: FeatureTaskRuntimeProgressSnapshotAccess,
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
    state: FeatureTaskRuntimeProgressSnapshotAccess,
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
      (state.phase(producer).output?.iteration ?: state.phase(producer).record?.attemptCount ?: 1)
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
        regenerationAttempt = (state.loop(regeneration.edge.loopId).iteration + 1).coerceAtLeast(1),
        iteration = iteration,
        diagnosticWrite = diagnosticWrite,
        producerEvidence = producerEvidence,
      ),
    )
    return PhaseOutcome.regenerateProducer(producer)
  }

  private fun writeQuarantineRejectedOutput(
    state: FeatureTaskRuntimeProgressSnapshotAccess,
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
