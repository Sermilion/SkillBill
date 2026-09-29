package skillbill.engine.featuretask.slot.attempt

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.phase.prompt.directives.PriorAttemptCorrection
import skillbill.engine.featuretask.runloop.attempt.launchHookContext
import skillbill.engine.featuretask.runloop.attempt.settlementCoupling
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.BlockAndPersistInPhaseArgs
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.CapturedPhaseOutput
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopLaunch
import skillbill.engine.featuretask.runloop.core.LaunchMeasurementContextReady
import skillbill.engine.featuretask.runloop.core.LaunchPreparationRejected
import skillbill.engine.featuretask.runloop.core.PauseAndPersistInPhaseArgs
import skillbill.engine.featuretask.runloop.core.PersistPhaseArgs
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PhaseStateWriteArgs
import skillbill.engine.featuretask.runloop.core.PreparedLaunchReady
import skillbill.engine.featuretask.runloop.core.RecordRejection
import skillbill.engine.featuretask.runloop.core.SettleRecordRejectionArgs
import skillbill.engine.featuretask.runloop.core.phaseBlockArgs
import skillbill.engine.featuretask.runloop.core.withDisposition
import skillbill.engine.featuretask.runloop.output.FeatureTaskRuntimeRunLoopOutputPersistence
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking.blockAndPersistInPhase
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking.pauseAndPersistInPhase
import skillbill.engine.featuretask.runloop.state.featureTaskRuntimeChildOutput
import skillbill.engine.featuretask.runner.LaunchResult
import skillbill.engine.featuretask.runner.STATUS_RUNNING
import skillbill.engine.featuretask.slot.PhaseLaunchFailureKind
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.featuretask.slot.state.RequiredPhaseWriteRejected
import skillbill.engine.featuretask.slot.stepFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import java.util.concurrent.CancellationException

object PhaseAttemptOnce {
  internal fun persistRequiredStart(
    context: PhaseAttemptLaunchCollaborationScope,
    run: PhaseRun,
    iteration: Int,
  ) {
    FeatureTaskRuntimeRunLoopOutputPersistence.persistPhase(
      context,
      context.goalContinuationRecorder,
      PersistPhaseArgs(
        write =
          PhaseStateWriteArgs(
            run,
            iteration,
            STATUS_RUNNING,
            false,
            context.progress.outputFor(run.phaseId)?.payload,
          ),
        launched = FeatureTaskRuntimeRunLoopLaunch.launchedModelDirective(run),
      ),
    )
  }

  internal fun attemptOnce(
    context: PhaseAttemptLaunchCollaborationScope,
    args: RecordRejectionAttemptArgs,
  ): AttemptResult {
    args.call.acceptedExecution.requireAcceptedAttempt(args.context.run, args.call)
    return with(context) {
      val run = args.context.run
      val iteration = args.context.iteration
      val priorCorrection = args.priorCorrection
      try {
        persistRequiredStart(this, run, iteration)
        val launch = PhaseAttemptOnce.launchAndCapture(this, run, iteration, priorCorrection, args.call)
        PhaseAttemptOnce.settleRecordRejectionLaunchOutcome(this, args, launch)
      } catch (rejection: RequiredPhaseWriteRejected) {
        AttemptResult.settled(blockRequiredWriteRejection(this, run, rejection))
      }
    }
  }

  internal fun blockRequiredWriteRejection(
    host: PhaseAttemptRunHost,
    run: PhaseRun,
    rejection: RequiredPhaseWriteRejected,
  ): PhaseOutcome {
    val reason = rejection.message.orEmpty()
    val scope = PhaseAttemptLaunchCollaborationScope(host)
    val coupling = scope.settlementCoupling()
    return try {
      FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
        host.request,
        coupling.progress,
        coupling.transitions,
        scope.recorder,
        scope.observability,
        PhaseBlockRequest(
          run = run,
          attemptCount = rejection.attempt,
          reason = reason,
          observability = scope.observability,
          failureDisposition = FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
          payload = BlockAndPersistPayload(childNeverLaunched = true),
        ),
      )
    } catch (secondary: CancellationException) {
      rejection.addSuppressed(secondary)
      throw secondary
    } catch (secondary: Throwable) {
      rejection.addSuppressed(secondary)
      RuntimeDiagnosticsBestEffortWarning.record(
        scope.diagnostics,
        "Required phase write rejection for '${run.phaseId}' could not be persisted; " +
          "the original ${rejection.writeKind.wireValue} rejection remains primary.",
        secondary,
      )
      PhaseOutcome.blocked(reason)
    }
  }

  internal fun blockRequiredWriteRejection(
    context: PhaseAttemptLaunchCollaborationScope,
    run: PhaseRun,
    rejection: RequiredPhaseWriteRejected,
  ): PhaseOutcome = blockRequiredWriteRejection(context.attemptRunHost(), run, rejection)

  internal fun launchAndCapture(
    context: PhaseAttemptLaunchCollaborationScope,
    run: PhaseRun,
    iteration: Int,
    priorCorrection: PriorAttemptCorrection?,
    call: PhaseStepCall,
  ): LaunchResult {
    call.acceptedExecution.requireAcceptedAttempt(run, call)
    var rejected: LaunchResult? = null
    val preparingState =
      object : PhaseLaunchState by call.acceptedExecution.launchState {
        override fun prepareLaunch(input: PhaseStepInput): PhaseStepInput? =
          when (
            val preparation =
              PhaseLaunchPreparation.prepareLaunchForCapture(
                context,
                run,
                iteration,
                priorCorrection,
                call.description.prompt,
                call.acceptedExecution,
              )
          ) {
            is PreparedLaunchReady -> {
              val launchBlock =
                context
                  .stepHooks(
                    run,
                  ).beforeAgentLaunch(run, context.launchHookContext(run), call.acceptedExecution)
              if (launchBlock != null) {
                rejected = LaunchResult.infraFailure(launchBlock, childNeverLaunched = true)
                null
              } else {
                input.copy(
                  directive = preparation.value.prompt,
                  facts = input.facts.copy(briefingText = preparation.value.briefing.briefingText),
                )
              }
            }
            is LaunchPreparationRejected -> {
              rejected = preparation.result
              null
            }
            is LaunchMeasurementContextReady -> error("Unexpected launch preparation result.")
          }
      }
    val output =
      context.attemptRunHost().runPreparedStep(
        run,
        call,
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
    context: PhaseAttemptLaunchCollaborationScope,
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
      FeatureTaskRuntimeRunLoopLaunch.capturePhaseContentIdentities(
        request,
        coupledRunTransitions,
        phaseGates,
        run.phaseId,
      )
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
    context: PhaseAttemptLaunchCollaborationScope,
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
          stepHooks = context.stepHooks(run),
        ),
      )
    }
  }

  private fun settleProviderLimit(
    context: PhaseAttemptLaunchCollaborationScope,
    args: RecordRejectionAttemptArgs,
    launch: LaunchResult,
    reason: String,
  ): AttemptResult =
    AttemptResult.settled(
      with(context) {
        with(FeatureTaskRuntimeRunLoopPhaseBlocking) {
          pauseAndPersistInPhase(
            PauseAndPersistInPhaseArgs(
              args.context.run,
              args.context.iteration,
              reason,
              context.observability,
              launch.fileManifest,
            ),
          )
        }
      },
    )

  private fun settleInfrastructureFailure(
    context: PhaseAttemptLaunchCollaborationScope,
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
      val blockArgs =
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
        ).withDisposition(launch.failureDisposition)
      return AttemptResult.settled(
        with(context) {
          blockAndPersistInPhase(
            BlockAndPersistInPhaseArgs(
              run = blockArgs.run,
              attemptCount = blockArgs.attemptCount,
              reason = blockArgs.reason,
              observability = blockArgs.observability,
              failureDisposition = blockArgs.failureDisposition,
              payload = blockArgs.payload,
            ),
          )
        },
      )
    }
  }

  private fun settleRecordRejection(
    context: PhaseAttemptLaunchCollaborationScope,
    args: RecordRejectionAttemptArgs,
    rejection: RecordRejection,
  ): AttemptResult =
    AttemptResult.settled(
      with(PhaseAttemptContinuations) {
        context.settleRecordRejection(
          SettleRecordRejectionArgs(
            args.context.run,
            context.settlementCoupling().progress,
            args.context.iteration,
            context.observability,
            rejection,
          ),
        )
      },
    )
}
