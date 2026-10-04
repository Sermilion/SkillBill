package skillbill.engine.featuretask.runloop.phase

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.runloop.attempt.FeatureTaskRuntimeRunLoopHookViews.launchHookContext
import skillbill.engine.featuretask.runloop.attempt.settlementCoupling
import skillbill.engine.featuretask.runloop.core.BlockAndPersistArgs
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunSessionObservations
import skillbill.engine.featuretask.runloop.core.LEGACY_PLANNING_PROJECTION_LAUNCH_SEAM_REJECTION
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.PreLaunchBlock
import skillbill.engine.featuretask.runloop.core.ShouldRetryPersistedBlockArgs
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeRunObservability
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runner.missingUpstream
import skillbill.engine.featuretask.slot.attempt.PhaseRunLoopAttemptCollaborators
import skillbill.engine.featuretask.slot.state.PhaseBlockResume
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

object FeatureTaskRuntimeRunLoopPreLaunch {
  internal fun preLaunchBlock(
    context: PhaseRunLoopAttemptCollaborators,
    run: PhaseRun,
    state: FeatureTaskRuntimeProgressSnapshotAccess,
    observability: FeatureTaskRuntimeRunObservability,
  ): PhaseOutcome? {
    val hooks = context.strategyFor(run.phaseId).stepHooks(run.phaseId)
    hooks.reconcileBeforeLaunch(run, context.launchHookContext(run, hooks))
    val persisted =
      state.phase(run.phaseId).blockedReason?.let { persistedReason ->
        val nextIteration = state.phase(run.phaseId).nextIteration
        val durable = state.phase(run.phaseId).record
        if (
          shouldRelaunchPersistedBlock(
            context = context,
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
        observability,
        it,
      )
    }
  }

  private fun persistPreLaunchBlock(
    context: PhaseRunLoopAttemptCollaborators,
    run: PhaseRun,
    observability: FeatureTaskRuntimeRunObservability,
    preLaunch: PreLaunchBlock,
  ): PhaseOutcome {
    val durable = preLaunch.durableRecord
    val coupling = context.settlementCoupling()
    return FeatureTaskRuntimeRunLoopPhaseBlocking.blockAndPersist(
      coupling.progress,
      coupling.transitions,
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
    state: FeatureTaskRuntimeProgressSnapshotAccess,
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

  internal fun isReenterableRecordRejection(
    state: FeatureTaskRuntimeProgressSnapshotAccess,
    phaseId: String,
    reason: String,
  ): Boolean =
    isReenterableLaunchSeamRecordRejection(phaseId, reason) ||
      state.legacyLaunchSeamRejectionConsumedBudget(phaseId, reason)

  internal fun shouldRelaunchPersistedBlock(
    context: PhaseRunLoopAttemptCollaborators,
    run: PhaseRun,
    durable: FeatureTaskRuntimePhaseRecord?,
    persistedReason: String,
  ): Boolean {
    val state = context.progress
    val session = context.session
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
      context.coupledRunTransitions.restartAttemptBudgetForRelaunch(phaseId)
    }
    return shouldRetryPersistedBlock(
      session,
      ShouldRetryPersistedBlockArgs(
        phaseId = phaseId,
        durable = durable,
        resume = resume,
        reenterableRecordRejection = reenterableRecordRejection,
      ),
    )
  }

  internal fun shouldRetryPersistedBlock(
    session: FeatureTaskRuntimeRunSessionObservations,
    args: ShouldRetryPersistedBlockArgs,
  ): Boolean {
    val disposition = args.durable?.failureDisposition
    return when {
      FeatureTaskRuntimeRunLoopPhaseBlocking.operatorReopenedPhase(session, args.phaseId) -> true
      args.resume != PhaseBlockResume.DEFAULT -> true
      args.reenterableRecordRejection -> true
      disposition != null -> disposition.retryOnResume
      else -> false
    }
  }
}
