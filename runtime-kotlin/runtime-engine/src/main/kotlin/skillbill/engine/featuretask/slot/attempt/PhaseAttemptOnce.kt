package skillbill.engine.featuretask.slot.attempt

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.phase.prompt.directives.PriorAttemptCorrection
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.CapturedPhaseOutput
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopLaunch
import skillbill.engine.featuretask.runloop.core.LaunchMeasurementContextReady
import skillbill.engine.featuretask.runloop.core.LaunchPreparationRejected
import skillbill.engine.featuretask.runloop.core.PauseAndPersistInPhaseArgs
import skillbill.engine.featuretask.runloop.core.PersistPhaseArgs
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PhaseStateWriteArgs
import skillbill.engine.featuretask.runloop.core.PreparedLaunchReady
import skillbill.engine.featuretask.runloop.core.RecordRejection
import skillbill.engine.featuretask.runloop.core.SettleRecordRejectionArgs
import skillbill.engine.featuretask.runloop.core.phaseBlockArgs
import skillbill.engine.featuretask.runloop.core.withDisposition
import skillbill.engine.featuretask.runloop.output.FeatureTaskRuntimeRunLoopOutputPersistence
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.state.featureTaskRuntimeChildOutput
import skillbill.engine.featuretask.runner.LaunchResult
import skillbill.engine.featuretask.runner.STATUS_RUNNING
import skillbill.engine.featuretask.slot.PhaseLaunchFailureKind
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.stepFacts
import skillbill.ports.agentrun.model.AgentRunTermination

object PhaseAttemptOnce {
  internal fun attemptOnce(
    context: PhaseAttemptEnvironment,
    args: RecordRejectionAttemptArgs,
  ): AttemptResult {
    with(context) {
      val run = args.context.run
      val iteration = args.context.iteration
      val priorCorrection = args.priorCorrection
      FeatureTaskRuntimeRunLoopOutputPersistence.persistPhase(
        request,
        state,
        recorder,
        goalContinuationRecorder,
        PersistPhaseArgs(
          write =
            PhaseStateWriteArgs(
              run = run,
              iteration = iteration,
              status = STATUS_RUNNING,
              finished = false,
              outputArtifact = state.outputFor(run.phaseId)?.payload,
            ),
          launched = FeatureTaskRuntimeRunLoopLaunch.launchedModelDirective(run),
        ),
      )
      val launch = PhaseAttemptOnce.launchAndCapture(context, run, iteration, priorCorrection, args.call)
      return PhaseAttemptOnce.settleRecordRejectionLaunchOutcome(context, args, launch)
    }
  }

  internal fun launchAndCapture(
    context: PhaseAttemptEnvironment,
    run: PhaseRun,
    iteration: Int,
    priorCorrection: PriorAttemptCorrection?,
    call: PhaseStepCall,
  ): LaunchResult {
    var rejected: LaunchResult? = null
    val preparingState =
      object : PhaseLaunchState by call.state {
        override fun prepareLaunch(input: PhaseStepInput): PhaseStepInput? =
          when (
            val preparation =
              PhaseLaunchPreparation.prepareLaunchForCapture(
                context,
                run,
                iteration,
                priorCorrection,
                call.description.prompt,
              )
          ) {
            is PreparedLaunchReady ->
              input.copy(
                directive = preparation.value.prompt,
                facts = input.facts.copy(briefingText = preparation.value.briefing.briefingText),
              )
            is LaunchPreparationRejected -> {
              rejected = preparation.result
              null
            }
            is LaunchMeasurementContextReady -> error("Unexpected launch preparation result.")
          }
      }
    val output =
      call.runner.run(
        PhaseStepInput(
          stepName = run.phaseId,
          directive = "",
          priorValues = emptyMap(),
          operatorInstructions = run.request.phaseInstructions?.forStep(run.phaseId),
          facts = run.stepFacts(run.request.issueKey, iteration),
          policy = run.policy,
        ),
        preparingState,
      )
    return rejected ?: PhaseAttemptOnce.reconcileLaunch(context, run, output)
  }

  private fun reconcileLaunch(
    context: PhaseAttemptEnvironment,
    run: PhaseRun,
    output: PhaseStepOutput,
  ): LaunchResult {
    val kind = output.launchFailure?.kind
    val reason = output.launchFailure?.reason.orEmpty()
    when (kind) {
      PhaseLaunchFailureKind.BEFORE_CAPTURE_FAILED ->
        return LaunchResult.infraFailure(reason, childNeverLaunched = true)
      PhaseLaunchFailureKind.AFTER_CAPTURE_FAILED ->
        return LaunchResult.infraFailure(reason, childNeverLaunched = false)
      else -> Unit
    }
    val fileManifest =
      requireNotNull(output.fileManifest).let { FeatureTaskRuntimePhaseFileManifest(it.before, it.after) }
    with(context) {
      FeatureTaskRuntimeRunLoopLaunch.capturePhaseContentIdentities(request, session, phaseGates, run.phaseId)
    }
    return when (kind) {
      PhaseLaunchFailureKind.UNSUPPORTED_AGENT ->
        LaunchResult.infraFailure(reason, fileManifest, childNeverLaunched = true)
      PhaseLaunchFailureKind.PROVIDER_LIMIT -> LaunchResult.providerLimited(reason, fileManifest)
      PhaseLaunchFailureKind.INFRASTRUCTURE ->
        LaunchResult.infraFailure(
          reason,
          fileManifest,
          childNeverLaunched = output.termination == AgentRunTermination.SpawnFailed || !output.processStarted,
          childOutput = featureTaskRuntimeChildOutput(output.stdout.text, output.stderr, output.termination),
        )
      else ->
        LaunchResult.captured(
          CapturedPhaseOutput(
            text = output.stdout.text,
            bytes = output.stdout.bytes,
            truncated = output.stdout.truncated,
            byteSize = output.stdout.byteSize,
            sha256 = output.stdout.sha256,
          ),
          fileManifest = fileManifest,
          settledEnvelope = output.settledEnvelope,
        )
    }
  }

  internal fun settleRecordRejectionLaunchOutcome(
    context: PhaseAttemptEnvironment,
    args: RecordRejectionAttemptArgs,
    launch: LaunchResult,
  ): AttemptResult {
    with(context) {
      val run = args.context.run
      val iteration = args.context.iteration
      launch.providerLimitReason?.let { reason ->
        return PhaseAttemptOnce.settleProviderLimit(context, args, launch, reason)
      }
      launch.infraFailureReason?.let { reason ->
        return PhaseAttemptOnce.settleInfrastructureFailure(context, args, launch, reason)
      }
      launch.recordRejection?.let { rejection ->
        return PhaseAttemptOnce.settleRecordRejection(context, args, rejection)
      }
      val fileManifest = requireNotNull(launch.fileManifest)
      return PhaseOutputGate.gateOutput(
        GateOutput(
          run = run,
          iteration = iteration,
          captured = requireNotNull(launch.capturedPhaseOutput),
          fileManifest = fileManifest,
          settledEnvelope = launch.capturedSettledEnvelope,
          call = args.call,
          outputGateFailuresBefore = args.context.outputGateFailuresBefore,
          settlementContext = context,
        ),
      )
    }
  }

  private fun settleProviderLimit(
    context: PhaseAttemptEnvironment,
    args: RecordRejectionAttemptArgs,
    launch: LaunchResult,
    reason: String,
  ): AttemptResult =
    AttemptResult.settled(
      with(FeatureTaskRuntimeRunLoopPhaseBlocking) {
        context.pauseAndPersistInPhase(
          PauseAndPersistInPhaseArgs(
            args.context.run,
            args.context.iteration,
            reason,
            context.observability,
            launch.fileManifest,
          ),
        )
      },
    )

  private fun settleInfrastructureFailure(
    context: PhaseAttemptEnvironment,
    args: RecordRejectionAttemptArgs,
    launch: LaunchResult,
    reason: String,
  ): AttemptResult {
    with(context) {
      val run = args.context.run
      PhaseOutputGate.persistChildProcessFailureOutput(
        context,
        run,
        args.context.iteration,
        reason,
        launch.infraFailureChildOutput,
      )
      return AttemptResult.settled(
        FeatureTaskRuntimeRunLoopPhaseBlocking.blockAndPersistInPhase(
          request,
          state,
          recorder,
          goalContinuationRecorder,
          phaseBlockArgs(
            run,
            args.context.iteration,
            reason,
            observability,
            payload =
              BlockAndPersistPayload(
                childNeverLaunched = launch.childNeverLaunched,
                fileManifest = launch.fileManifest,
              ),
          ).withDisposition(launch.failureDisposition),
        ),
      )
    }
  }

  private fun settleRecordRejection(
    context: PhaseAttemptEnvironment,
    args: RecordRejectionAttemptArgs,
    rejection: RecordRejection,
  ): AttemptResult =
    AttemptResult.settled(
      with(PhaseAttemptContinuations) {
        context.settleRecordRejection(
          SettleRecordRejectionArgs(
            args.context.run,
            context.state,
            args.context.iteration,
            context.observability,
            rejection,
          ),
        )
      },
    )
}
