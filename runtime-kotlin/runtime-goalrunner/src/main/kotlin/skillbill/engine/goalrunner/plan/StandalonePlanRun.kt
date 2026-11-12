package skillbill.engine.goalrunner.plan

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.decomposition.findDecomposedParentWorkflow
import skillbill.application.workflow.decomposition.findOccupyingDecomposedParentWorkflow
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationCandidate
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEvent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunEventSink
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunInput
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibility
import skillbill.engine.featuretask.phaserun.StandalonePhaseLaunch
import skillbill.engine.featuretask.phaserun.StandalonePhaseStatusEventSink
import skillbill.engine.featuretask.phaserun.StandalonePhaseStatusPublisherFactory
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeRunEntry
import skillbill.engine.featuretask.slot.intake.FeatureTaskRuntimePlanSeedRecorder
import skillbill.engine.goal.goalRepositoryIdentityCandidates
import skillbill.engine.goalrunner.intake.GoalIntakePreparation
import skillbill.error.core.catchingNonCancellation
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.text.sha256HexUtf8
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSpecOrigin
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityMode
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

/** How a standalone plan run ended. */
public sealed interface StandalonePlanResult {
  /** The plan authored a verified spec bundle and settled. */
  public data class Completed(
    public val workflowId: String,
    public val parentSpecPath: String,
    public val decompositionManifestPath: String,
    public val subtaskSpecPaths: List<String>,
  ) : StandalonePlanResult

  /** The plan opened or resumed but could not settle; the workflow stays resumable. */
  public data class Blocked(
    public val workflowId: String,
    public val reason: String,
  ) : StandalonePlanResult

  /** The plan was not opened or resumed; [workflowId] names the plan workflow in the way, when one exists. */
  public data class Refused(
    public val reason: String,
    public val workflowId: String? = null,
  ) : StandalonePlanResult
}

public data class StandalonePlanLaunch(
  public val agentId: String? = null,
  public val scope: IdeStatusLaunchScope = IdeStatusLaunchScope.STANDALONE,
) {
  public fun forPhase(target: String): StandalonePhaseLaunch =
    StandalonePhaseLaunch(
      agentId = agentId,
      arguments =
        agentId?.let { agent ->
          when (scope) {
            IdeStatusLaunchScope.STANDALONE -> listOf("phase", "plan", target, "--agent", agent)
            IdeStatusLaunchScope.GOAL -> listOf("goal", target, "--agent", agent)
          }
        },
      scope = scope,
    )
}

@Inject
public class StandalonePlanRun(
  private val intakePreparation: GoalIntakePreparation,
  private val planLookup: PlanWorkflowLookup,
  private val entry: FeatureTaskRuntimeRunEntry,
  private val database: DatabaseSessionFactory,
  private val repositories: RepositoryEnclosingRootPort,
  private val fileStore: DecompositionManifestStore,
  private val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner,
  private val statusPublisherFactory: StandalonePhaseStatusPublisherFactory,
  private val seedRecorder: FeatureTaskRuntimePlanSeedRecorder,
  private val reopening: PlanSubtasksReopening,
  private val planReports: StandalonePlanReportBuilder,
) {
  public fun issueKeyOf(intake: String): String = intakePreparation.issueKeyOf(intake)

  internal fun replanSubtasks(
    replan: PlanSubtasksReplan,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
  ): StandalonePlanResult {
    val issueKey = replan.issueKey
    val planWorkflowId = replan.planWorkflowId
    val repoRoot = replan.repoRoot
    val publisher =
      statusPublisherFactory.forPlan(
        repoRoot = repoRoot,
        issueKey = issueKey,
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
        launch = StandalonePlanLaunch(replan.agentId, IdeStatusLaunchScope.GOAL).forPhase(issueKey),
      )
    return try {
      catchingNonCancellation {
        if (!reopening.reopenWithRebasedBundle(replan)) {
          return@catchingNonCancellation refused(
            publisher,
            "Plan workflow '$planWorkflowId' for $issueKey has no `plan_subtasks` step to re-plan.",
            planWorkflowId,
          )
        }
        val found =
          planLookup.lookupSelected(
            issueKey,
            goalRepositoryIdentityCandidates(repoRoot, repositories),
            planWorkflowId,
          )
        if (found !is FeatureTaskContinuationLookupResult.Resumable || found.candidate.workflowId != planWorkflowId) {
          return@catchingNonCancellation refused(
            publisher,
            "Plan workflow '$planWorkflowId' for $issueKey could not be resumed after reopening `plan_subtasks`.",
            planWorkflowId,
          )
        }
        resume(
          found.candidate,
          null,
          repoRoot,
          { specPath -> withPublisher(inputFor(specPath).copy(inheritRecordedPlanningTimeout = true), publisher) },
          publisher,
        )
      }.onFailure(publisher::settleFailure).getOrThrow()
    } finally {
      publisher.closeOutputCapture()
    }
  }

  private fun withLivePlanReport(
    sink: FeatureTaskRuntimeRunEventSink,
    publisher: StandalonePhaseStatusEventSink,
    issueKey: String,
    repoRoot: Path,
  ): FeatureTaskRuntimeRunEventSink =
    FeatureTaskRuntimeRunEventSink { event ->
      sink.emit(event)
      if (event is FeatureTaskRuntimeRunEvent.PhaseCompleted && event.phaseId in LIVE_PLAN_REPORT_PHASES) {
        publisher.publishPlanReport { planReports.build(issueKey, repoRoot) }
      }
    }

  private fun withPublisher(
    input: FeatureTaskRuntimeRunInput,
    publisher: StandalonePhaseStatusEventSink,
  ): FeatureTaskRuntimeRunInput =
    input.copy(eventSink = statusPublisherFactory.compose(input.eventSink::emit, publisher))

  public fun run(
    issueKey: String,
    suppliedIntake: String?,
    repoRoot: Path,
    launch: StandalonePlanLaunch = StandalonePlanLaunch(),
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
  ): StandalonePlanResult {
    val publisher =
      statusPublisherFactory.forPlan(
        repoRoot = repoRoot,
        issueKey = issueKey,
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        launch = launch.forPhase(suppliedIntake ?: issueKey),
      )
    return try {
      catchingNonCancellation {
        runInternal(
          issueKey,
          suppliedIntake,
          repoRoot,
          publisher,
        ) { specPath ->
          val input = inputFor(specPath)
          withPublisher(
            input.copy(
              inheritRecordedPlanningTimeout = launch.scope == IdeStatusLaunchScope.GOAL || input.timeout == null,
            ),
            publisher,
          )
        }
      }.onFailure(publisher::settleFailure).getOrThrow()
    } finally {
      publisher.closeOutputCapture()
    }
  }

  private fun runInternal(
    issueKey: String,
    suppliedIntake: String?,
    repoRoot: Path,
    statusPublisher: StandalonePhaseStatusEventSink,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
  ): StandalonePlanResult {
    val intake =
      suppliedIntake?.takeIf { supplied ->
        val normalized = supplied.trim()
        normalized.isNotEmpty() && !normalized.equals(issueKey, ignoreCase = true)
      }
    val found =
      (
        goalRepositoryIdentityCandidates(repoRoot, repositories).firstNotNullOfOrNull { identity ->
          planLookup.lookupAfterCrashRecovery(issueKey, identity)
            .takeIf { it != FeatureTaskContinuationLookupResult.NoMatch }
        } ?: FeatureTaskContinuationLookupResult.NoMatch
      ).forLaunchedSpec(issueKey, intake, repoRoot)
    if (hasReusableDecomposedParent(issueKey, repoRoot)) {
      return open(issueKey, intake, repoRoot, inputFor, statusPublisher)
    }
    if (planReports.manifestExists(issueKey, repoRoot)) {
      if (found is FeatureTaskContinuationLookupResult.Resumable) {
        return reopenBlockedFeasibility(issueKey, found.candidate.workflowId, repoRoot, inputFor)
          ?: resume(found.candidate, intake, repoRoot, inputFor, statusPublisher)
      }
      return settleExistingManifest(
        issueKey,
        intake,
        repoRoot,
        statusPublisher,
        inputFor,
      )
    }
    return when (found) {
      is FeatureTaskContinuationLookupResult.Resumable ->
        resume(found.candidate, intake, repoRoot, inputFor, statusPublisher)
      is FeatureTaskContinuationLookupResult.AlreadyRunning ->
        refused(
          statusPublisher,
          "Plan workflow '${found.candidate.workflowId}' for $issueKey is already running.",
          found.candidate.workflowId,
        )
      is FeatureTaskContinuationLookupResult.NeedsIdentityRepair ->
        refused(statusPublisher, found.summary, found.workflowId)
      is FeatureTaskContinuationLookupResult.TerminalOnly,
      is FeatureTaskContinuationLookupResult.GoalContinuation,
      FeatureTaskContinuationLookupResult.NoMatch,
      -> open(issueKey, intake, repoRoot, inputFor, statusPublisher)
    }
  }

  private fun hasReusableDecomposedParent(
    issueKey: String,
    repoRoot: Path,
  ): Boolean =
    database.read { unitOfWork ->
      goalRepositoryIdentityCandidates(repoRoot, repositories).any { identity ->
        unitOfWork.workflowStates.findDecomposedParentWorkflow(issueKey, repositoryIdentity = identity) != null &&
          unitOfWork.workflowStates.findOccupyingDecomposedParentWorkflow(issueKey, repositoryIdentity = identity) ==
          null
      }
    }

  private fun settleExistingManifest(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
    statusPublisher: StandalonePhaseStatusEventSink,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
  ): StandalonePlanResult {
    val completed =
      planLookup.completedSelection(issueKey, goalRepositoryIdentityCandidates(repoRoot, repositories))
        ?.takeIf { selected ->
          selected.terminal == null || isLaunchedSpec(selected.terminal.parentSpecPath, issueKey, intake, repoRoot)
        }
    if (completed == null) {
      return open(
        issueKey,
        intake,
        repoRoot,
        inputFor,
        statusPublisher,
      )
    }
    val id = completed.workflowId
    val terminal = completed.terminal
    if (terminal != null && intake == null) {
      val feasibility = PlanBundleFeasibility.capture(decompositionPlanner, repoRoot, issueKey)
      if (completed.feasibilityState == null ||
        !PlanBundleFeasibility.approved(
          completed.feasibilityState,
          feasibility,
          completed.phaseRecord,
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
        )
      ) {
        val settledIds = feasibility.settledSubtaskIds
        return replanSubtasks(
          PlanSubtasksReplan(issueKey, id, repoRoot, settledIds, feasibilityAdoption = true),
          inputFor,
        )
      }
      val result =
        StandalonePlanResult.Completed(
          id,
          terminal.parentSpecPath,
          terminal.decompositionManifestPath,
          terminal.subtaskSpecPaths,
        )
      statusPublisher.settlePlan("completed", result.workflowId, planReports.build(issueKey, repoRoot))
      return result
    }
    return refused(
      statusPublisher,
      "A decomposition manifest already exists for $issueKey; the runtime never overwrites a plan. " +
        "Run `sona $issueKey` to execute it.",
    )
  }

  private fun open(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
    statusPublisher: StandalonePhaseStatusEventSink?,
  ): StandalonePlanResult {
    val resolution = intakePreparation.resolvePlanSpec(issueKey, intake, repoRoot)
    val plannedPath = resolution.specPath
    val existing = fileStore.isRegularFile(plannedPath)
    if (existing && !isKeyResolvedSpec(issueKey, plannedPath, repoRoot)) {
      return refused(
        statusPublisher,
        "The supplied spec $plannedPath is not the governed spec for $issueKey; settlement guards the spec " +
          "found under ${decompositionPlanner.specsRoot(repoRoot)}/$issueKey-*/ " +
          "(`sona project path` prints the project store). Move it there and plan again.",
      )
    }
    val specPath =
      if (existing || !plannedPath.startsWith(repoRoot)) {
        plannedPath
      } else {
        repositories.canonicalPath(repoRoot).resolve(repoRoot.relativize(plannedPath))
      }
    val input =
      inputFor(specPath.toString()).copy(
        definition = SkeletonDefinition.PLAN,
        planIntake = intake,
        protectedSpecSha256 =
          if (resolution.specOrigin == FeatureTaskRuntimePlanSpecOrigin.OPERATOR) specHash(plannedPath) else null,
      )
    return runEntry(input, repoRoot, statusPublisher)
  }

  private fun adoptFeasibilityForResume(
    candidate: FeatureTaskContinuationCandidate,
    repoRoot: Path,
  ): Boolean {
    val selected = planLookup.selection(candidate.workflowId)
    val selectedPhase = selected?.phaseRecord
    val selectedFeasibility = selected?.feasibilityState
    if (selectedPhase != null) {
      val current =
        PlanBundleFeasibility.capture(
          decompositionPlanner,
          repoRoot,
          candidate.executionIdentity.normalizedIssueKey,
        )
      val completedApproval =
        selectedFeasibility?.takeIf { it.mode == PlanBundleFeasibilityMode.APPROVED }?.let {
          PlanBundleFeasibility.approved(
            it,
            current,
            selectedPhase,
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
          )
        } == true
      val needsFullReview =
        selectedFeasibility == null ||
          selectedFeasibility.mode == PlanBundleFeasibilityMode.APPROVED && !completedApproval ||
          selectedPhase.status == WorkflowStepStatus.COMPLETED &&
          selectedFeasibility.mode != PlanBundleFeasibilityMode.BLOCKED && !completedApproval
      return !needsFullReview ||
        reopening.reopenWithRebasedBundle(
          PlanSubtasksReplan(
            candidate.executionIdentity.normalizedIssueKey,
            candidate.workflowId,
            repoRoot,
            current.settledSubtaskIds,
            feasibilityAdoption = true,
          ),
        )
    }
    return true
  }

  private fun reopenBlockedFeasibility(
    issueKey: String,
    workflowId: String,
    repoRoot: Path,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
  ): StandalonePlanResult? {
    if (!reopening.hasReopenableBlockedFeasibility(workflowId)) return null
    val settledIds = PlanBundleFeasibility.capture(decompositionPlanner, repoRoot, issueKey).settledSubtaskIds
    return replanSubtasks(
      PlanSubtasksReplan(issueKey, workflowId, repoRoot, settledIds, feasibilityAdoption = true),
      inputFor,
    )
  }

  private fun resume(
    candidate: FeatureTaskContinuationCandidate,
    intake: String?,
    repoRoot: Path,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
    statusPublisher: StandalonePhaseStatusEventSink?,
  ): StandalonePlanResult {
    if (database.read { it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, candidate.workflowId) }
        ?.hasPlanSubtasksStep() == false
    ) {
      val key = candidate.executionIdentity.normalizedIssueKey
      return refused(
        statusPublisher,
        "Plan workflow '${candidate.workflowId}' for $key predates the `plan_subtasks` step and cannot resume. " +
          "Run `sona goal purge $key`, then `sona phase plan $key`.",
        candidate.workflowId,
      )
    }
    val specPath = decompositionPlanner.bundlePath(repositories.canonicalPath(repoRoot), candidate.governedSpecPath)
    if (!adoptFeasibilityForResume(candidate, repoRoot)) {
      return refused(
        statusPublisher,
        "Plan workflow '${candidate.workflowId}' could not adopt its retained plan_subtasks output " +
          "for a full v2 review.",
        candidate.workflowId,
      )
    }
    val seed = seedRecorder.read(candidate.workflowId)
    if (seed != null && intake != null && sha256HexUtf8(intake) != seed.intakeSha256) {
      return refused(
        statusPublisher,
        "Plan workflow '${candidate.workflowId}' was opened with a different intake; resume it without an intake.",
        candidate.workflowId,
      )
    }
    val protectedSpecSha256 =
      if (seed != null) {
        seed.specSha256.takeIf { seed.specOrigin == FeatureTaskRuntimePlanSpecOrigin.OPERATOR }
      } else {
        if (intakePreparation.keepsOperatorSpec(specPath)) specHash(specPath) else null
      }
    val input =
      inputFor(specPath.toString()).copy(
        explicitWorkflowId = candidate.workflowId,
        definition = SkeletonDefinition.PLAN,
        planIntake = intake,
        protectedSpecSha256 = protectedSpecSha256,
      )
    return runEntry(input, repoRoot, statusPublisher)
  }

  private fun runEntry(
    input: FeatureTaskRuntimeRunInput,
    repoRoot: Path,
    statusPublisher: StandalonePhaseStatusEventSink?,
  ): StandalonePlanResult {
    val outputSink = statusPublisher?.planOutputSink ?: AgentRunOutputSink.NONE
    val captureStreamOutput = outputSink !== AgentRunOutputSink.NONE
    val observed =
      statusPublisher?.let { publisher ->
        input.copy(eventSink = withLivePlanReport(input.eventSink, publisher, input.issueKey, repoRoot))
      } ?: input
    val report =
      catchingNonCancellation {
        entry.run(
          observed,
          { error("Could not run plan workflow: ${it.error}") },
          outputSink,
          captureStreamOutput,
          if (captureStreamOutput) statusPublisher?.let { it::flushOutputCapture } else null,
        )
      }.onFailure { error ->
        statusPublisher?.settleFailure(error, input.explicitWorkflowId)
      }.getOrThrow()
    return settle(statusPublisher, report, repoRoot)
  }

  private fun refused(
    statusPublisher: StandalonePhaseStatusEventSink?,
    reason: String,
    workflowId: String? = null,
  ): StandalonePlanResult.Refused {
    statusPublisher?.settleBlocked(reason, workflowId)
    return StandalonePlanResult.Refused(reason, workflowId)
  }

  private fun settle(
    statusSink: StandalonePhaseStatusEventSink?,
    report: FeatureTaskRuntimeRunReport,
    repoRoot: Path,
  ): StandalonePlanResult {
    val result =
      when (report) {
        is FeatureTaskRuntimeRunReport.Decomposed ->
          StandalonePlanResult.Completed(
            report.workflowId,
            report.parentSpecPath,
            report.decompositionManifestPath,
            report.subtaskSpecPaths,
          )
        is FeatureTaskRuntimeRunReport.Blocked -> StandalonePlanResult.Blocked(report.workflowId, report.blockedReason)
        is FeatureTaskRuntimeRunReport.Paused -> StandalonePlanResult.Blocked(report.workflowId, report.pauseReason)
        is FeatureTaskRuntimeRunReport.Completed ->
          StandalonePlanResult.Blocked(report.workflowId, "The plan finished without authoring a spec bundle.")
      }
    when (result) {
      is StandalonePlanResult.Completed ->
        statusSink?.settlePlan("completed", result.workflowId, planReports.build(report.issueKey, repoRoot))
      is StandalonePlanResult.Blocked -> statusSink?.settleBlocked(result.reason, result.workflowId)
      is StandalonePlanResult.Refused -> statusSink?.settleBlocked(result.reason, result.workflowId)
    }
    return result
  }

  private fun FeatureTaskContinuationLookupResult.forLaunchedSpec(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
  ): FeatureTaskContinuationLookupResult {
    val candidate =
      when (this) {
        is FeatureTaskContinuationLookupResult.Resumable -> candidate
        is FeatureTaskContinuationLookupResult.AlreadyRunning -> candidate
        else -> return this
      }
    return if (isLaunchedSpec(candidate.governedSpecPath, issueKey, intake, repoRoot)) {
      this
    } else {
      FeatureTaskContinuationLookupResult.NoMatch
    }
  }

  private fun isLaunchedSpec(
    governedSpecPath: String,
    issueKey: String,
    intake: String?,
    repoRoot: Path,
  ): Boolean {
    val root = repoRoot.toAbsolutePath().normalize()
    val launched = intakePreparation.resolvePlanSpec(issueKey, intake, repoRoot).specPath
    return specLocation(root, launched) == specLocation(root, Path.of(governedSpecPath))
  }

  private fun isKeyResolvedSpec(
    issueKey: String,
    specPath: Path,
    repoRoot: Path,
  ): Boolean =
    decompositionPlanner.existingParentSpec(repoRoot, issueKey)
      ?.let { resolved -> specLocation(repoRoot, resolved) == specLocation(repoRoot, specPath) } == true

  private fun specLocation(
    repoRoot: Path,
    specPath: Path,
  ): Path = decompositionPlanner.bundlePath(repoRoot, specPath.toString())

  private fun specHash(specPath: Path): String = sha256HexUtf8(fileStore.readText(specPath))
}

private val LIVE_PLAN_REPORT_PHASES =
  setOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
  )
