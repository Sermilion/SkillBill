package skillbill.engine.featuretask.runloop.attempt

import skillbill.engine.featuretask.runloop.checkpoint.RuntimeCommitUpstreamHeadRecovery
import skillbill.engine.featuretask.runloop.core.AttemptResult
import skillbill.engine.featuretask.runloop.core.BlockAndPersistPayload
import skillbill.engine.featuretask.runloop.core.PhaseBlockRequest
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.ValidatedOutputCapture
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeContinuationKind
import skillbill.engine.featuretask.runloop.observability.continuation
import skillbill.engine.featuretask.runloop.phase.FeatureTaskRuntimeRunLoopPhaseBlocking
import skillbill.engine.featuretask.runloop.planning.PlanDecompositionStop
import skillbill.engine.featuretask.runloop.state.repositoryObservations
import skillbill.engine.featuretask.slot.PhaseLoopContext
import skillbill.engine.featuretask.slot.PhaseStepHookContextKind
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchRuntimeContext
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptTraversalHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptTraversalRuntimeContext
import skillbill.engine.featuretask.slot.attempt.PhaseAuditOutputContext
import skillbill.engine.featuretask.slot.attempt.PhaseCheckpointRemediationContext
import skillbill.engine.featuretask.slot.attempt.PhaseCommitLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseFindingEvidenceContext
import skillbill.engine.featuretask.slot.attempt.PhaseOutputSettlementContext
import skillbill.engine.featuretask.slot.attempt.PhasePlanningLaunchContext
import skillbill.engine.featuretask.slot.attempt.PhasePlanningOutputContext
import skillbill.engine.featuretask.slot.attempt.PhasePlanningTraversalContext
import skillbill.engine.featuretask.slot.attempt.PhasePullRequestLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput

internal object FeatureTaskRuntimeRunLoopHookViews {
  internal fun PhaseAttemptLaunchRuntimeContext.launchHookContext(
    run: PhaseRun,
    hooks: PhaseStepHooks,
  ): PhaseAttemptLaunchHookContext {
    check(request === run.request)
    return when (hooks.contextKind) {
      PhaseStepHookContextKind.COMMIT -> CommitLaunchView(this, run)
      PhaseStepHookContextKind.FINDING_VERIFICATION -> FindingLaunchView(this)
      PhaseStepHookContextKind.PULL_REQUEST -> PullRequestLaunchView(this)
      PhaseStepHookContextKind.PLANNING -> PlanningLaunchView(this)
      else -> LaunchView(this)
    }
  }

  private class PlanningLaunchView(
    private val context: PhaseAttemptLaunchRuntimeContext,
  ) : LaunchView(context),
    PhasePlanningLaunchContext {
    override fun existingBundleReason(): String? =
      context.decompositionPlanner
        .existingParentSpec(request.repoRoot, request.issueKey)
        ?.let { PlanDecompositionStop.existingBundleReason(request.issueKey, it) }
  }

  private open class LaunchView(
    private val context: PhaseAttemptLaunchRuntimeContext,
  ) : PhaseAttemptLaunchHookContext {
    override val request get() = context.request
    override val progress get() = context.progress
    override val session get() = context.session
    override val diagnostics get() = context.diagnostics

    override fun resolvedBranch() = context.recorder.loadResolvedBranch(request.workflowId)
  }

  private class PullRequestLaunchView(
    private val context: PhaseAttemptLaunchRuntimeContext,
  ) : LaunchView(context),
    PhasePullRequestLaunchHookContext {
    override fun pushResolvedBranchIfAhead(): String? = resolvedBranch()?.branch?.let(context::pushLocalBranchIfAhead)
  }

  private class CommitLaunchView(
    private val context: PhaseAttemptLaunchRuntimeContext,
    private val acceptedRun: PhaseRun,
  ) : LaunchView(context),
    PhaseCommitLaunchHookContext {
    override fun recoverCommitUpstream(
      run: PhaseRun,
      upstreamReceipt: (String, Int) -> FeatureTaskRuntimePhaseOutput?,
    ) {
      check(run === acceptedRun)
      RuntimeCommitUpstreamHeadRecovery.reconcileBeforeLaunch(run, context, upstreamReceipt)
    }
  }

  private class FindingLaunchView(
    private val context: PhaseAttemptLaunchRuntimeContext,
  ) : LaunchView(context),
    PhaseFindingEvidenceContext {
    override val findingVerificationBoundaryMemory get() = context.findingVerificationBoundaryMemory
    override val specIntentProjectionResolver get() = context.specIntentProjectionResolver
  }

  internal fun PhaseOutputSettlementContext.stepOutputContext(
    run: PhaseRun,
    hooks: PhaseStepHooks,
  ): PhaseStepOutputContext {
    check(request === run.request)
    return when (hooks.contextKind) {
      PhaseStepHookContextKind.AUDIT ->
        AuditOutputView(this as PhaseCheckpointRemediationContext, this, run)
      PhaseStepHookContextKind.FINDING_VERIFICATION -> FindingOutputView(this)
      PhaseStepHookContextKind.PLANNING -> PlanningOutputView(this)
      else -> OutputView(this)
    }
  }

  private class PlanningOutputView(
    private val context: PhaseOutputSettlementContext,
  ) : OutputView(context),
    PhasePlanningOutputContext {
    override fun settleAuthoredBundle(capture: ValidatedOutputCapture): AttemptResult? {
      val reason = PlanDecompositionStop.authoredBundleRejection(context, capture) ?: return null
      val coupling = context.settlementCoupling()
      return AttemptResult.settled(
        FeatureTaskRuntimeRunLoopPhaseBlocking.blockInPhase(
          coupling.progress,
          coupling.transitions,
          context.recorder,
          PhaseBlockRequest(
            run = capture.run,
            attemptCount = capture.iteration,
            reason = reason,
            observability = context.observability,
            payload = BlockAndPersistPayload(fileManifest = capture.fileManifest),
          ),
        ),
      )
    }

    override fun withAuthoredParentSpecPath(attested: NormalizedFeatureTaskRuntimePhaseOutput) =
      PlanDecompositionStop.withAuthoredParentSpecPath(context, attested)
  }

  private open class OutputView(
    private val context: PhaseOutputSettlementContext,
  ) : PhaseStepOutputContext {
    override val request get() = context.request
    override val progress get() = context.progress
    override val diagnostics get() = context.diagnostics
    override val specSource get() = context.specSource

    override fun resolvedBranch() = context.recorder.loadResolvedBranch(request.workflowId)
  }

  private class AuditOutputView(
    private val remediation: PhaseCheckpointRemediationContext,
    context: PhaseOutputSettlementContext,
    private val acceptedRun: PhaseRun,
  ) : OutputView(context),
    PhaseAuditOutputContext {
    override val operatorReopened: Boolean
      get() = FeatureTaskRuntimeRunLoopPhaseBlocking.operatorReopenedPhase(remediation.session, acceptedRun.phaseId)

    override val nonShrinkingRounds: Int
      get() =
        remediation.recorder.loadPhaseLedger(request.workflowId).orEmpty().count { entry ->
          entry.phaseId == acceptedRun.phaseId &&
            FeatureTaskRuntimeContinuationKind.fromLedgerDetail(entry.blockedReason) ==
            FeatureTaskRuntimeContinuationKind.AUDIT_NON_SHRINKING_ROUND
        }

    override val missingBaselineRounds: Int
      get() =
        remediation.recorder.loadPhaseLedger(request.workflowId).orEmpty().count { entry ->
          entry.phaseId == acceptedRun.phaseId &&
            FeatureTaskRuntimeContinuationKind.fromLedgerDetail(entry.blockedReason) ==
            FeatureTaskRuntimeContinuationKind.AUDIT_MISSING_BASELINE
        }

    override fun recordMissingBaselineRound(capture: ValidatedOutputCapture) {
      check(capture.run === acceptedRun)
      remediation.observability.continuation(
        acceptedRun.phaseId,
        acceptedRun.resolvedAgent.resolvedAgentId,
        capture.iteration,
        missingBaselineRounds + 1,
        FeatureTaskRuntimeContinuationKind.AUDIT_MISSING_BASELINE,
      )
    }

    override fun recordNonShrinkingRound(capture: ValidatedOutputCapture) {
      check(capture.run === acceptedRun)
      remediation.observability.continuation(
        acceptedRun.phaseId,
        acceptedRun.resolvedAgent.resolvedAgentId,
        capture.iteration,
        nonShrinkingRounds + 1,
        FeatureTaskRuntimeContinuationKind.AUDIT_NON_SHRINKING_ROUND,
      )
    }

    override fun settleAuditRound(
      capture: ValidatedOutputCapture,
      attested: NormalizedFeatureTaskRuntimePhaseOutput,
      outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
      progressRejection: String?,
    ) = FeatureTaskRuntimeRunLoopAuditSettlement.settleCompletedRound(
      remediation,
      capture.also { check(it.run === acceptedRun) },
      attested,
      outputMap,
      progressRejection,
    )
  }

  private class FindingOutputView(
    private val context: PhaseOutputSettlementContext,
  ) : OutputView(context),
    PhaseFindingEvidenceContext {
    override val findingVerificationBoundaryMemory get() = context.findingVerificationBoundaryMemory
    override val specIntentProjectionResolver get() = context.specIntentProjectionResolver
  }

  internal fun PhaseAttemptTraversalRuntimeContext.traversalHookContext(
    output: FeatureTaskRuntimePhaseOutput,
    hooks: PhaseStepHooks,
  ): PhaseAttemptTraversalHookContext =
    if (hooks.contextKind == PhaseStepHookContextKind.PLANNING) {
      PlanningTraversalView(this, output)
    } else {
      TraversalView(this)
    }

  private open class TraversalView(
    private val context: PhaseAttemptTraversalRuntimeContext,
  ) : PhaseAttemptTraversalHookContext {
    override val request get() = context.request
  }

  private class PlanningTraversalView(
    private val context: PhaseAttemptTraversalRuntimeContext,
    private val acceptedOutput: FeatureTaskRuntimePhaseOutput,
  ) : TraversalView(context),
    PhasePlanningTraversalContext {
    override fun settlePlanningStop(output: FeatureTaskRuntimePhaseOutput): String? {
      check(output === acceptedOutput && context.progress.phase(output.phaseId).completed)
      return PlanDecompositionStop.apply(context, output)
    }
  }

  internal fun PhaseCheckpointRemediationContext.phaseLoopContext(): PhaseLoopContext =
    PhaseLoopContext(request, gitOperations.repositoryObservations())
}
