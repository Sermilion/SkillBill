package skillbill.engine.featuretask.runloop.phase

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.runloop.core.BlockAndPersistArgs
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.LEGACY_PLANNING_PROJECTION_LAUNCH_SEAM_REJECTION
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PreLaunchBlock
import skillbill.engine.featuretask.runloop.core.ShouldRetryPersistedBlockArgs
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.runner.missingUpstream
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptEnvironment
import skillbill.engine.featuretask.slot.state.PhaseBlockResume
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

object FeatureTaskRuntimeRunLoopPreLaunch {
  internal fun preLaunchBlock(
    context: PhaseAttemptEnvironment,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome? {
    context.strategyFor(run.phaseId).stepHooks(run.phaseId).reconcileBeforeLaunch(run, context)
    val persisted =
      state.persistedBlockedReason(run.phaseId)?.let { persistedReason ->
        val nextIteration = state.nextIteration(run.phaseId)
        val durable = state.recordFor(run.phaseId)
        if (
          shouldRelaunchPersistedBlock(
            session = context.session,
            state = state,
            run = run,
            durable = durable,
            persistedReason = persistedReason,
          )
        ) {
          return@let null
        }
        val reason =
          persistedReason.ifBlank {
            "Phase '${run.phaseId}' is durably blocked from a prior run; " +
              "the runtime re-blocks rather than relaunching."
          }
        PreLaunchBlock(nextIteration, reason, durable)
      }
    val missing =
      persisted ?: missingRequiredUpstream(run, state)?.let { missingIds ->
        PreLaunchBlock(
          1,
          "Phase '${run.phaseId}' requires upstream output(s) ${missingIds.joinToString()} that are not " +
            "present; the runtime blocks rather than launching the phase blind.",
        )
      }
    return missing?.let {
      persistPreLaunchBlock(
        context,
        run,
        state,
        observability,
        it,
      )
    }
  }

  private fun persistPreLaunchBlock(
    context: PhaseAttemptEnvironment,
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
    observability: FeatureTaskRuntimeRunObservability,
    preLaunch: PreLaunchBlock,
  ): PhaseOutcome {
    val durable = preLaunch.durableRecord
    return FeatureTaskRuntimeRunLoopPhaseBlocking.blockAndPersist(
      context.request,
      state,
      context.recorder,
      context.goalContinuationRecorder,
      BlockAndPersistArgs(
        run = run,
        attemptCount = preLaunch.attemptCount,
        reason = preLaunch.reason,
        observability = observability,
        loopId = durable?.loopId,
        edgeIteration = durable?.edgeIteration,
        failureDisposition =
          durable?.failureDisposition
            ?: FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
        payload =
          BlockAndPersistPayload(
            fileManifest =
              durable?.let {
                FeatureTaskRuntimePhaseFileManifest(it.fileManifestBefore, it.fileManifestAfter)
              },
            outputArtifact = durable?.outputArtifact,
            rejectedOutput = durable?.rejectedOutput,
          ),
      ),
    )
  }

  internal fun missingRequiredUpstream(
    run: PhaseRun,
    state: FeatureTaskRuntimeRunState,
  ): List<String>? =
    missingUpstream(
      run.declaration,
      state.outputs(run.declaration.consumedUpstreamPhaseIds),
    )?.takeIf(List<String>::isNotEmpty)

  fun isReenterableLaunchSeamRecordRejection(
    phaseId: String,
    reason: String,
  ): Boolean =
    reason.contains(LEGACY_PLANNING_PROJECTION_LAUNCH_SEAM_REJECTION) &&
      FeatureTaskRuntimePhaseWorkflowDefinition.REGENERATION_PRODUCER_BY_CONSUMER.containsKey(phaseId)

  fun isReenterableRecordRejection(
    state: FeatureTaskRuntimeRunState,
    phaseId: String,
    reason: String,
  ): Boolean =
    isReenterableLaunchSeamRecordRejection(phaseId, reason) ||
      state.legacyLaunchSeamRejectionConsumedBudget(phaseId, reason)

  internal fun shouldRelaunchPersistedBlock(
    session: FeatureTaskRuntimeRunLoopSession,
    state: FeatureTaskRuntimeRunState,
    run: PhaseRun,
    durable: FeatureTaskRuntimePhaseRecord?,
    persistedReason: String,
  ): Boolean {
    val phaseId = run.phaseId
    val resume = state.persistedBlockResume(phaseId, persistedReason)
    val reenterableRecordRejection = isReenterableRecordRejection(state, phaseId, persistedReason)
    val restartsBudget =
      listOf(
        resume == PhaseBlockResume.RELAUNCH_WITH_FRESH_BUDGET,
        reenterableRecordRejection,
        FeatureTaskRuntimeRunLoopPhaseBlocking.operatorReopenedPhase(session, phaseId),
      ).any { it }
    if (restartsBudget) {
      state.restartAttemptBudget(phaseId)
    }
    return shouldRetryPersistedBlock(
      session,
      ShouldRetryPersistedBlockArgs(
        phaseId = phaseId,
        durable = durable,
        resume = resume,
        reenterableRecordRejection = reenterableRecordRejection,
        relaunchOnInvalidOutput = run.policy.relaunchOnInvalidOutput,
      ),
    )
  }

  internal fun shouldRetryPersistedBlock(
    session: FeatureTaskRuntimeRunLoopSession,
    args: ShouldRetryPersistedBlockArgs,
  ): Boolean {
    val disposition = args.durable?.failureDisposition
    return when {
      FeatureTaskRuntimeRunLoopPhaseBlocking.operatorReopenedPhase(session, args.phaseId) -> true
      args.resume != PhaseBlockResume.DEFAULT -> true
      args.reenterableRecordRejection -> true
      disposition != null -> disposition.retryOnResume
      else -> args.relaunchOnInvalidOutput
    }
  }
}
