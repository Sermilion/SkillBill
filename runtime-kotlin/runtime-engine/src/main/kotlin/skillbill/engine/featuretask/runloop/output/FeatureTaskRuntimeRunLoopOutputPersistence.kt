package skillbill.engine.featuretask.runloop.output

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.runloop.attempt.settlementCoupling
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.PersistPhaseArgs
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestArgs
import skillbill.engine.featuretask.runloop.core.PhaseStateRequestAttachments
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.slot.attempt.PhaseOutputSettlementContext
import skillbill.engine.featuretask.slot.state.PhaseRunGoal
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite

object FeatureTaskRuntimeRunLoopOutputPersistence {
  internal fun schemaInvalidAttempt(
    operatorReason: String,
    fileManifest: FeatureTaskRuntimePhaseFileManifest,
    retryReason: String = operatorReason,
  ): AttemptResult =
    AttemptResult.schemaInvalid(
      operatorReason = operatorReason,
      fileManifest = fileManifest,
      retryReason = retryReason,
    )

  internal fun persistPhase(
    context: PhaseOutputSettlementContext,
    goalContinuationRecorder: PhaseRunGoal,
    args: PersistPhaseArgs,
  ): RequiredPhaseWrite {
    val coupling = context.settlementCoupling()
    val write = args.write
    val phaseState =
      FeatureTaskRuntimeRunLoopPhaseBlocking.phaseStateRequest(
        context.request,
        coupling.progress,
        goalContinuationRecorder,
        PhaseStateRequestArgs(
          write = write,
          extras =
            PhaseStateRequestAttachments(
              fileManifest = args.fileManifest,
              launched = args.launched,
              reviewRunId = args.reviewRunId,
            ),
        ),
      )
    return coupling.transitions.acknowledgeRequiredPhaseStart(context.recorder, phaseState)
  }
}
