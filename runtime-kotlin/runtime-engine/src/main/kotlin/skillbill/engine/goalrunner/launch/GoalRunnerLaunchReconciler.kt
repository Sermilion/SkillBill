package skillbill.engine.goalrunner.launch

import me.tatarka.inject.annotations.Inject
import skillbill.application.idestatus.AgentActivityStampWriter
import skillbill.engine.agentoutput.stderrExcerpt
import skillbill.engine.goalrunner.execution.core.GoalRunnerProgressReader
import skillbill.engine.goalrunner.execution.core.SubtaskLaunchRequestArgs
import skillbill.engine.goalrunner.execution.core.workflowIdFor
import skillbill.engine.goalrunner.execution.support.GoalRunnerTickProgressReader
import skillbill.engine.goalrunner.execution.support.branchPlanFor
import skillbill.engine.goalrunner.execution.support.declaredProgressProbe
import skillbill.engine.goalrunner.execution.support.progressProbe
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerLaunchReconciliation
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerMissingResultPrefixRecovery
import skillbill.engine.goalrunner.model.GoalRunnerReconcileGate
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.model.malformedResultJsonDiagnostics
import skillbill.engine.goalrunner.model.missingPrefixRecoveryCandidate
import skillbill.engine.goalrunner.model.missingResultPrefixDiagnostics
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.review.effectiveAgentAddonSelection
import skillbill.engine.goalrunner.telemetry.GoalRunnerProgressEventEmitter
import skillbill.engine.worktreeedit.WorktreeEditJournalWriter
import skillbill.goalrunner.GoalRunnerOutcomeReconciler
import skillbill.goalrunner.GoalRunnerQualityGateSelectionResolver
import skillbill.goalrunner.model.GoalRunnerLaunchFacts
import skillbill.goalrunner.model.GoalRunnerLivenessSnapshot
import skillbill.goalrunner.model.GoalRunnerLivenessState
import skillbill.goalrunner.model.GoalRunnerReconciledOutcome
import skillbill.goalrunner.model.GoalRunnerStoredOutcome
import skillbill.ports.agentrun.model.AgentRunActivityStampSink
import skillbill.ports.agentrun.model.AgentRunLaunchDenied
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.agentrun.model.AgentRunSpawnAuthorization
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.model.AgentRunWorktreeEditObserver
import skillbill.ports.agentrun.model.SkillRunGoalContinuationContext
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock

@Inject
class GoalRunnerLaunchReconciler(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val progressReader: GoalRunnerProgressReader,
  private val activityStampWriter: AgentActivityStampWriter,
  private val worktreeEditJournalWriter: WorktreeEditJournalWriter,
  private val clock: Clock,
  private val diagnostics: RuntimeDiagnostics,
) {
  private data class SubtaskLaunchDependencies(
    val tickReader: GoalRunnerTickProgressReader,
    val progressEmitter: GoalRunnerProgressEventEmitter,
    val goalContinuation: SkillRunGoalContinuationContext?,
    val activityStampSink: AgentRunActivityStampSink,
    val worktreeEditObserver: AgentRunWorktreeEditObserver,
  )

  internal fun subtaskLaunchRequest(args: SubtaskLaunchRequestArgs): GoalRunnerSubtaskLaunchRequest {
    val issueKey = args.issueKey
    val subtaskId = args.subtaskId
    val request = args.request
    val assignedWorkflowId = args.assignedWorkflowId
    val reviewBaseline = args.reviewBaseline
    val spawnAuthorization = args.spawnAuthorization
    val tickReader =
      GoalRunnerTickProgressReader(
        manifestStore = manifestStore,
        progressReader = progressReader,
        issueKey = issueKey,
        subtaskId = subtaskId,
        request = request,
        clock = clock,
      )
    val progressEmitter =
      GoalRunnerProgressEventEmitter(
        outcomeStore = outcomeStore,
        resolveWorkflowId = { tickReader.progressState()?.subtask?.workflowId?.takeIf(String::isNotBlank) },
        issueKey = issueKey,
        clock = clock,
        diagnostics = diagnostics,
      )
    val goalContinuation = goalContinuationContext(issueKey, subtaskId, request, assignedWorkflowId, reviewBaseline)
    val activityStampSink =
      activityStampWriter.lazySink(
        resolveWorkflowId = { tickReader.progressState()?.subtask?.workflowId },
        parentWorkflowId = goalContinuation?.parentWorkflowId,
      )
    val worktreeEditObserver =
      worktreeEditJournalWriter.observer(
        repoRoot = request.repoRoot,
        resolveWorkflowId = { tickReader.progressState()?.subtask?.workflowId },
        resolvePhaseId = { tickReader.progressState()?.childProgress?.currentStepId },
      )
    return buildLaunchRequest(
      args,
      SubtaskLaunchDependencies(tickReader, progressEmitter, goalContinuation, activityStampSink, worktreeEditObserver),
      spawnAuthorization,
    )
  }

  private fun buildLaunchRequest(
    args: SubtaskLaunchRequestArgs,
    dependencies: SubtaskLaunchDependencies,
    spawnAuthorization: AgentRunSpawnAuthorization?,
  ): GoalRunnerSubtaskLaunchRequest {
    val request = args.request
    return GoalRunnerSubtaskLaunchRequest(
      invokedAgentId = request.invokedAgentId,
      configuredAgentOverrideId = request.configuredAgentOverrideId,
      skillRunRequest =
        SkillRunRequest(
          issueKey = args.issueKey,
          repoRoot = request.repoRoot,
          subtaskId = args.subtaskId,
          timeout = request.timeout,
          progressIdleTimeout = request.progressIdleTimeout,
          progressProbe = progressProbe(dependencies.tickReader, args.subtaskId),
          declaredProgressProbe = declaredProgressProbe(dependencies.tickReader),
          progressEmitter = dependencies.progressEmitter,
          outputSink = request.outputSink,
          readOnlyPhase =
            dependencies.goalContinuation?.lastResumableStep ==
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
          goalContinuation = dependencies.goalContinuation,
          spawnAuthorization = spawnAuthorization,
          activityStampSink = dependencies.activityStampSink,
          worktreeEditObserver = dependencies.worktreeEditObserver,
        ),
    )
  }

  private fun goalContinuationContext(
    issueKey: String,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
    assignedWorkflowId: String?,
    reviewBaseline: GoalSubtaskReviewBaseline?,
  ): SkillRunGoalContinuationContext? {
    val state = manifestStore.loadByIssueKey(issueKey, request.repoRoot) ?: return null
    val branch =
      state.manifest.branchPlanFor(subtaskId).branch.takeIf(String::isNotBlank)
        ?: state.manifest.featureBranch?.takeIf(String::isNotBlank)
    val subtask = state.manifest.subtasks.firstOrNull { it.id == subtaskId }
    val specPath = subtask?.specPath?.takeIf(String::isNotBlank)
    return if (branch != null && subtask != null && specPath != null) {
      val childWorkflowId =
        if (assignedWorkflowId == null) {
          state.manifest.workflowIdFor(subtaskId)
        } else {
          null
        }
      SkillRunGoalContinuationContext(
        parentIssueKey = issueKey,
        subtaskId = subtaskId,
        goalBranch = branch,
        suppressPr = true,
        specPath = specPath,
        parentWorkflowId = state.parentWorkflowId,
        lastResumableStep = subtask.lastResumableStep?.takeIf(String::isNotBlank),
        childWorkflowId = childWorkflowId,
        assignedWorkflowId = assignedWorkflowId,
        codeReviewMode = request.codeReviewMode ?: CodeReviewExecutionMode.DEFAULT,
        validationDepth = ValidationDepth.FULL,
        qualityGateSelection = GoalRunnerQualityGateSelectionResolver.resolve(state.manifest, subtaskId),
        agentAddonSelection = manifestStore.effectiveAgentAddonSelection(state.parentWorkflowId, request),
        reviewBaseline =
          state.manifest.workflowIdFor(subtaskId)
            ?.let { workflowId -> outcomeStore.goalSubtaskReviewState(workflowId) }
            ?.let { reviewState ->
              GoalSubtaskReviewBaseline(reviewState.reviewBaseSha, reviewState.baselineUntrackedPaths)
            }
            ?: reviewBaseline,
      )
    } else {
      null
    }
  }

  internal fun reconcileLaunchOutcome(
    attemptedState: GoalRunnerManifestState,
    launchOutcome: AgentRunLaunchOutcome,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerLaunchReconciliation {
    val refreshed =
      manifestStore.loadByIssueKey(request.issueKey, request.repoRoot)
        ?: attemptedState
    val launchFacts = launchOutcome.toGoalRunnerLaunchFacts()
    val reconciled =
      GoalRunnerOutcomeReconciler.reconcile(
        subtaskId = subtaskId,
        launchFacts = launchFacts,
        storedOutcome = storedOutcome(refreshed, subtaskId, request),
      )
    return launchReconciliation(refreshed, reconciled, launchOutcome, subtaskId, request)
  }

  private fun launchReconciliation(
    refreshed: GoalRunnerManifestState,
    reconciled: GoalRunnerReconciledOutcome,
    launchOutcome: AgentRunLaunchOutcome,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerLaunchReconciliation {
    val recovery = missingResultPrefixRecovery(refreshed, reconciled, launchOutcome, subtaskId, request)
    val recoveredReconciled =
      recovery?.storedOutcome?.let { recoveredOutcome ->
        GoalRunnerOutcomeReconciler.reconcile(
          subtaskId = subtaskId,
          launchFacts = launchOutcome.toGoalRunnerLaunchFacts(),
          storedOutcome = recoveredOutcome,
        )
      } ?: reconciled
    return GoalRunnerLaunchReconciliation(
      refreshed = refreshed,
      reconciled = recoveredReconciled,
      launchOutcome = launchOutcome,
      diagnostics = recovery?.diagnostics ?: malformedResultJsonDiagnostics(reconciled, launchOutcome),
    )
  }

  private fun missingResultPrefixRecovery(
    refreshed: GoalRunnerManifestState,
    reconciled: GoalRunnerReconciledOutcome,
    launchOutcome: AgentRunLaunchOutcome,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerMissingResultPrefixRecovery? =
    missingPrefixRecoveryCandidate(
      reconciled,
      launchOutcome,
    )?.let { candidate ->
      val workflowId =
        refreshed.manifest.workflowIdFor(subtaskId)
          ?: candidate.workflowId
      val storedOutcome =
        workflowId?.let { resolvedWorkflowId ->
          outcomeStore.recoverMissingResultPrefixOutput(
            workflowId = resolvedWorkflowId,
            issueKey = request.issueKey,
            subtaskId = subtaskId,
            output = candidate.output,
          )
        }
      GoalRunnerMissingResultPrefixRecovery(
        storedOutcome = storedOutcome,
        diagnostics = missingResultPrefixDiagnostics(storedOutcome?.lastResumableStep ?: candidate.lastResumableStep),
      )
    }

  private fun storedOutcome(
    state: GoalRunnerManifestState,
    subtaskId: Int,
    request: GoalRunnerRunRequest,
  ): GoalRunnerStoredOutcome? {
    val manifestWorkflowId =
      state.manifest.subtasks.firstOrNull { it.id == subtaskId }?.workflowId
        ?.takeIf(String::isNotBlank)
    if (manifestWorkflowId != null) {
      return outcomeStore.recoverAndPersistTerminalOutcome(
        workflowId = manifestWorkflowId,
        issueKey = state.manifest.issueKey,
        subtaskId = subtaskId,
        repoRoot = request.repoRoot,
      )
    }
    return outcomeStore.reconcileAuthoritativeOutcomes(
      issueKey = state.manifest.issueKey,
      gate = GoalRunnerReconcileGate(requireStalenessEvidence = false),
      repoRoot = request.repoRoot,
    )[subtaskId]
  }
}

internal fun AgentRunLaunchOutcome.toGoalRunnerLaunchFacts(): GoalRunnerLaunchFacts =
  when (this) {
    is AgentRunLaunchFacts ->
      GoalRunnerLaunchFacts(
        timedOut = termination == AgentRunTermination.TimedOut,
        interrupted = termination == AgentRunTermination.Interrupted,
        spawnFailed = termination == AgentRunTermination.SpawnFailed,
        exitStatus = (termination as? AgentRunTermination.Exited)?.code,
        stderrExcerpt = stderrExcerpt(stderr, GoalRunnerLaunchFacts.STDERR_EXCERPT_MAX_CHARS),
        liveness =
          liveness?.let { snapshot ->
            GoalRunnerLivenessSnapshot(
              phase = snapshot.phase,
              reason = snapshot.reason,
              processState = snapshot.processState,
              workflowId = snapshot.workflowId,
              workflowStep = snapshot.workflowStep,
              lastDurableProgressAt = snapshot.lastDurableProgressAt,
              lastDurableProgressLabel = snapshot.lastDurableProgressLabel,
              lastWorkflowSnapshotAt = snapshot.lastWorkflowSnapshotAt,
              lastFileActivityAt = snapshot.lastFileActivityAt,
              lastFileActivityLabel = snapshot.lastFileActivityLabel,
              lastOutputAt = snapshot.lastOutputAt,
              livenessState = snapshot.livenessState,
              aliveAtKill =
                snapshot.livenessState == GoalRunnerLivenessState.WORKING ||
                  snapshot.livenessState == GoalRunnerLivenessState.PROGRESSING,
            )
          },
      )
    is UnsupportedAgentRunLaunch -> GoalRunnerLaunchFacts(spawnFailed = true)
    is AgentRunLaunchDenied -> error("A denied launch is paused before launch reconciliation and has no launch facts.")
  }
