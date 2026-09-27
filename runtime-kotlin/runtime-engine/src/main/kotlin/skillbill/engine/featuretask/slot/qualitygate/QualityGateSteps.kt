package skillbill.engine.featuretask.slot.qualitygate

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimeCurrentPhaseExecutionContext
import skillbill.engine.featuretask.phase.core.attemptPhaseExecution
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestArgs
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestAttachments
import skillbill.engine.featuretask.runloop.core.PhaseStateWriteArgs
import skillbill.engine.featuretask.runloop.core.phaseBlockArgs
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.settlement.FeatureTaskRuntimeRunLoopValidationScope
import skillbill.engine.featuretask.runner.STATUS_COMPLETED
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptEnvironment
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.validation.model.ValidationGateProgressStore
import skillbill.engine.work.model.IdeStatusCurrentPhaseExecution
import skillbill.engine.work.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.workflow.taskruntime.model.core.PhaseStepPolicy
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.AcceptedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.requireAcceptedOutput
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress

internal val QUALITY_GATE_STEP_POLICY: PhaseStepPolicy =
  PhaseStepPolicy(
    mutating = false,
    relaunchOnInvalidOutput = true,
    singleAgentSession = false,
    readOnlyIdle = false,
    fileMutating = true,
    generationScoped = false,
  )

internal fun gateCurrentExecution(
  stepId: String,
  context: FeatureTaskRuntimeCurrentPhaseExecutionContext,
): IdeStatusCurrentPhaseExecution? =
  context.gateRunCount?.takeIf { it >= 1 }?.let { count ->
    IdeStatusCurrentPhaseExecution(
      phaseId = stepId,
      kind = IdeStatusCurrentPhaseExecutionKind.GATE_RUN,
      count = count,
    )
  } ?: attemptPhaseExecution(stepId, context)

internal fun PhaseAttemptEnvironment.gateCheckpoint(run: PhaseRun): String? =
  phaseGates.gitOperations.repositoryFingerprint(run.request.repoRoot).value.takeIf(String::isNotBlank)

internal fun PhaseAttemptEnvironment.gateChangedPaths(run: PhaseRun): List<String> =
  FeatureTaskRuntimeRunLoopValidationScope.validationChangedPaths(
    phaseGates,
    recorder,
    goalContinuationRecorder,
    session,
    run,
  ).orEmpty()

internal fun PhaseRunRecords.buildGateProgressStore(): ValidationGateProgressStore =
  object : ValidationGateProgressStore {
    override fun persist(
      workflowId: String,
      progress: FeatureTaskRuntimeValidationGateProgress,
    ) = persistBuildGateProgress(workflowId, progress)

    override fun load(workflowId: String): FeatureTaskRuntimeValidationGateProgress? = loadBuildGateProgress(workflowId)
  }

internal class RuntimeOwnedGateSettlement(
  private val context: PhaseAttemptEnvironment,
  private val label: String,
  private val acceptance: (PhaseRun, AcceptedFeatureTaskRuntimePhaseOutput) -> Unit = { _, _ -> },
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
        return FeatureTaskRuntimeRunLoopPhaseBlocking.blockAndPersistInPhase(
          context.request,
          context.state,
          context.recorder,
          context.goalContinuationRecorder,
          phaseBlockArgs(
            run,
            iteration,
            "Runtime-owned $label settlement did not validate: ${error.message.orEmpty()}",
            observability,
          ),
        )
      }
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
    val normalizedOutput = accepted.normalizedOutput
    return PhaseOutcome.completed(
      FeatureTaskRuntimePhaseOutput(
        run.phaseId,
        iteration,
        normalizedOutput.canonicalJson,
        normalizedOutput,
        accepted.repairEvidence,
      ),
    )
  }

  private fun accept(
    run: PhaseRun,
    outputText: String,
  ): Result<AcceptedFeatureTaskRuntimePhaseOutput> =
    runCatching {
      val accepted =
        context.outputValidator.validatePhaseOutput(outputText, sourceLabel = run.phaseId)
          .requireAcceptedOutput(run.phaseId)
      acceptance(run, accepted)
      accepted
    }

  private fun persistCompleted(
    run: PhaseRun,
    iteration: Int,
    outputText: String,
    accepted: AcceptedFeatureTaskRuntimePhaseOutput,
  ): Boolean =
    context.recorder.recordCompletedPhase(
      FeatureTaskRuntimeRunLoopPhaseBlocking.phaseStateRequest(
        context.request,
        context.state,
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
              normalizedOutput = accepted.normalizedOutput,
              repairEvidence = accepted.repairEvidence,
            ),
        ),
      ),
    )
}

internal fun PhaseAttemptEnvironment.blockGateStep(
  run: PhaseRun,
  iteration: Int,
  reason: String,
  disposition: FeatureTaskRuntimeFailureDisposition,
  observability: FeatureTaskRuntimeRunObservability,
): PhaseOutcome =
  FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
    request,
    state,
    recorder,
    observability,
    PhaseBlockRequest(
      run = run,
      attemptCount = iteration,
      reason = reason,
      observability = observability,
      failureDisposition = disposition,
    ),
  )
