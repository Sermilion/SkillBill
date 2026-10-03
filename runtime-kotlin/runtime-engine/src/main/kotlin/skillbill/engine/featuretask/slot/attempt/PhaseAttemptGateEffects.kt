package skillbill.engine.featuretask.slot.attempt

import skillbill.engine.featuretask.runloop.attempt.RunLoopSettlementCoupling
import skillbill.engine.featuretask.runloop.core.PhaseAttemptContext
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestArgs
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestAttachments
import skillbill.engine.featuretask.runloop.core.PhaseStateWriteArgs
import skillbill.engine.featuretask.runloop.core.RepositoryCheckpointResolutionArgs
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimePhaseStartReentry
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.qualitygate.gateAttemptCall
import skillbill.engine.featuretask.runloop.settlement.FeatureTaskRuntimeRunLoopValidationScope
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.runner.STATUS_RUNNING
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.ports.diagnostics.model.ProducerOutputEvidence
import skillbill.text.sha256HexUtf8
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition

internal fun PhaseQualityGateCycleContext.gateCheckpoint(run: PhaseRun): String? =
  gitOperations
    .repositoryFingerprint(run.request.repoRoot)
    .value
    .takeIf(String::isNotBlank)

internal fun PhaseQualityGateCycleContext.gateChangedPaths(run: PhaseRun): List<String> =
  FeatureTaskRuntimeRunLoopValidationScope
    .validationChangedPaths(
      RepositoryCheckpointResolutionArgs(
        gitOperations = gitOperations,
        qualityGateCycles = qualityGateCycles,
        recorder = recorder,
        goalContinuationRecorder = goalContinuationRecorder,
        coupledRunTransitions = coupledRunTransitions,
        session = session,
        run = run,
      ),
    ).orEmpty()

internal fun PhaseQualityGateCycleContext.persistGateRequiredRunning(
  run: PhaseRun,
  iteration: Int,
): PhaseOutcome? {
  val runningPhaseState =
    FeatureTaskRuntimeRunLoopPhaseBlocking.phaseStateRequest(
      request,
      gateSettlementCoupling().progress,
      goalContinuationRecorder,
      PhaseStateRequestArgs(
        write =
          PhaseStateWriteArgs(
            run = run,
            iteration = iteration,
            status = STATUS_RUNNING,
            finished = false,
            outputArtifact = null,
          ),
      ),
    )
  when (val write = coupledRunTransitions.acknowledgeRequiredPhaseStart(recorder, runningPhaseState)) {
    is RequiredPhaseWrite.Acknowledged -> Unit
    is RequiredPhaseWrite.Rejected -> return blockGateRequiredWriteRejection(run, write)
  }
  observability.started(
    run.phaseId,
    run.resolvedAgent.resolvedAgentId,
    iteration,
    run.modelDirective,
    FeatureTaskRuntimePhaseStartReentry.FIRST_VISIT,
  )
  return null
}

internal fun PhaseQualityGateCycleContext.gateAttemptContext(
  run: PhaseRun,
  iteration: Int,
  observability: FeatureTaskRuntimeRunObservability,
): PhaseAttemptContext {
  val coupling = gateSettlementCoupling()
  return PhaseAttemptContext(
    run,
    coupling.transitions,
    transitionDeclaration,
    coupling.progress,
    coupling.session,
    iteration,
    observability,
  )
}

internal fun PhaseQualityGateCycleContext.blockGateStep(
  run: PhaseRun,
  iteration: Int,
  reason: String,
  disposition: FeatureTaskRuntimeFailureDisposition,
  observability: FeatureTaskRuntimeRunObservability,
): PhaseOutcome {
  val coupling = gateSettlementCoupling()
  return FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
    coupling.progress,
    coupling.transitions,
    recorder,
    PhaseBlockRequest(
      run = run,
      attemptCount = iteration,
      reason = reason,
      observability = observability,
      failureDisposition = disposition,
    ),
  )
}

internal class RuntimeOwnedGateSettlement(
  private val context: PhaseQualityGateCycleContext,
  private val label: String,
  private val acceptance: (PhaseRun, NormalizedFeatureTaskRuntimePhaseOutput) -> Unit = { _, _ -> },
  private val afterCompleted: (PhaseRun) -> Unit = {},
) {
  internal fun settle(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome {
    val accepted =
      accept(run, outputText).getOrElse { error ->
        return context.blockGateStep(
          run,
          iteration,
          "Runtime-owned $label settlement did not validate: ${error.message.orEmpty()}",
          FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
          observability,
        )
      }
    val payload = outputText.toByteArray(Charsets.UTF_8)
    context.recorder.retainProducerOutput(
      ProducerOutputEvidence(
        workflowId = context.request.workflowId,
        phaseId = run.phaseId,
        attempt = iteration,
        agentId = run.resolvedAgent.resolvedAgentId,
        model = "runtime",
        recordedAt = context.clock.instant(),
        byteSize = payload.size.toLong(),
        sha256 = sha256HexUtf8(outputText),
        payload = payload,
        generation = (if (run.policy.generationScoped) context.progress.reviewEvidenceGeneration else 0),
      ),
    )
    if (!persistCompleted(run, iteration, outputText, accepted)) {
      return context.blockGateStep(
        run,
        iteration,
        "Runtime-owned $label settlement could not be persisted.",
        FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        observability,
      )
    }
    observability.completed(run.phaseId, run.resolvedAgent.resolvedAgentId, iteration)
    afterCompleted(run)
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(
        run.phaseId,
        iteration,
        accepted.canonicalJson,
        accepted,
      ),
    )
  }

  private fun accept(
    run: PhaseRun,
    outputText: String,
  ): Result<NormalizedFeatureTaskRuntimePhaseOutput> =
    runCatching {
      val accepted = NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(outputText, run.phaseId)
      acceptance(run, accepted)
      accepted
    }

  private fun persistCompleted(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    accepted: NormalizedFeatureTaskRuntimePhaseOutput,
  ): Boolean {
    val coupling = context.gateSettlementCoupling()
    val phaseState =
      FeatureTaskRuntimeRunLoopPhaseBlocking.phaseStateRequest(
        context.request,
        coupling.progress,
        context.goalContinuationRecorder,
        PhaseStateRequestArgs(
          write =
            PhaseStateWriteArgs(
              run = run,
              iteration = iteration,
              status = STATUS_COMPLETED,
              finished = true,
              outputArtifact = outputText,
            ),
          extras =
            PhaseStateRequestAttachments(
              normalizedOutput = accepted,
            ),
        ),
      )
    val inMemoryOutput =
      FeatureTaskRuntimePhaseOutput(
        run.phaseId,
        iteration,
        accepted.canonicalJson,
        accepted,
      )
    return context.coupledRunTransitions.persistAuthoritativePhaseCompletion(
      recorder = context.recorder,
      phaseState = phaseState,
      inMemoryOutput = inMemoryOutput,
    )
  }
}

internal fun PhaseQualityGateCycleContext.runGateAttemptOnce(
  call: PhaseStepCall,
  acceptedRun: PhaseRun,
  run: PhaseRun,
  iteration: Int,
): PhaseOutcome? =
  PhaseAttemptOnce
    .attemptOnce(
      this as? PhaseAttemptLaunchCollaborationScope
        ?: error("Quality-gate context is not bound to a run-loop attempt."),
      recordRejectionAttemptArgs(
        gateAttemptContext(run, iteration, observability),
        gateAttemptCall(call, acceptedRun, run),
      ),
    ).settledOutcome

internal fun PhaseQualityGateCycleContext.blockGateRequiredWriteRejection(
  run: PhaseRun,
  rejection: RequiredPhaseWrite.Rejected,
): PhaseOutcome =
  PhaseAttemptOnce.blockRequiredWriteRejection(
    this as? PhaseAttemptLaunchCollaborationScope
      ?: error("Quality-gate context is not bound to a run-loop attempt."),
    run,
    rejection,
  )

internal fun PhaseQualityGateCycleContext.runAcceptedAttemptLoop(
  run: PhaseRun,
  call: PhaseStepCall,
): PhaseOutcome =
  (
    this as? PhaseAttemptLaunchCollaborationScope
      ?: error("Quality-gate context is not bound to a run-loop attempt.")
  ).runAcceptedAttemptLoop(run, call)

private fun PhaseQualityGateCycleContext.gateSettlementCoupling(): RunLoopSettlementCoupling =
  RunLoopSettlementCoupling(progress, session, session, coupledRunTransitions)
