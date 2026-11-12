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
  public val executionRoot: Path? = null,
  public val workflowIdForCreation: String? = null,
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
  private val bundleServices: PlanBundleServices,
  private val planningOwnership: GoalPlanningCheckoutOwnership,
) {
  public fun issueKeyOf(intake: String): String = intakePreparation.issueKeyOf(intake)

  public fun prepareGoalCheckout(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
  ): StandalonePlanResult.Refused? {
    val launch = StandalonePlanLaunch(scope = IdeStatusLaunchScope.GOAL)
    val completedPlan = eligibleCompletedPlan(issueKey, intake, repoRoot)
    return when (val prepared = prepareGoalPlanningOwnership(issueKey, intake, repoRoot, launch, completedPlan)) {
      is GoalPlanningLaunch.Ready -> null
      is GoalPlanningLaunch.Refused -> StandalonePlanResult.Refused(prepared.reason, prepared.workflowId)
    }
  }

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
        if (!bundleServices.reopening.reopenWithRebasedBundle(replan)) {
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
          ResumeLocation(repoRoot, replan.sourceRoot),
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
        publisher.publishPlanReport { bundleServices.reports.build(issueKey, repoRoot) }
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
    val completedPlan = eligibleCompletedPlan(issueKey, suppliedIntake, repoRoot)
    val prepared = prepareGoalPlanningOwnership(issueKey, suppliedIntake, repoRoot, launch, completedPlan)
    val effectiveLaunch =
      when (prepared) {
        is GoalPlanningLaunch.Ready -> prepared.launch
        is GoalPlanningLaunch.Refused -> launch
      }
    val publisher =
      statusPublisherFactory.forPlan(
        repoRoot = repoRoot,
        issueKey = issueKey,
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        launch = launch.forPhase(suppliedIntake ?: issueKey),
      )
    return try {
      if (prepared is GoalPlanningLaunch.Refused) {
        return refused(publisher, prepared.reason, prepared.workflowId)
      }
      catchingNonCancellation {
        runInternal(
          issueKey,
          suppliedIntake,
          repoRoot,
          PlanRunSelection(publisher, completedPlan),
        ) { specPath ->
          val original = inputFor(specPath)
          val input =
            original.copy(
              repoRoot = effectiveLaunch.executionRoot ?: original.repoRoot,
              planIntakeRoot = repoRoot,
              workflowIdForCreation = effectiveLaunch.workflowIdForCreation,
            )
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

  private fun prepareGoalPlanningOwnership(
    issueKey: String,
    suppliedIntake: String?,
    repoRoot: Path,
    launch: StandalonePlanLaunch,
    completedPlan: PlanWorkflowSelection?,
  ): GoalPlanningLaunch {
    if (launch.scope != IdeStatusLaunchScope.GOAL) {
      return planningOwnership.prepare(
        issueKey,
        repoRoot,
        launch,
        GoalPlanningSelection(existingPlan = true, selectedWorkflowId = null, supersedableWorkflowId = null),
      )
    }
    val discoveredPlan =
      goalRepositoryIdentityCandidates(repoRoot, repositories).firstNotNullOfOrNull { identity ->
        planLookup.lookupAfterCrashRecovery(issueKey, identity)
          .takeIf { it != FeatureTaskContinuationLookupResult.NoMatch }
      }
    val selectedWorkflowId =
      completedPlan?.workflowId
        ?: discoveredPlan?.forLaunchedSpec(issueKey, suppliedIntake, repoRoot)?.selectedWorkflowId()
    val supersedableWorkflowId =
      if (completedPlan == null) {
        (discoveredPlan as? FeatureTaskContinuationLookupResult.Resumable)?.candidate?.workflowId
      } else {
        null
      }
    val hasNewIntake =
      suppliedIntake?.trim()?.takeIf { it.isNotEmpty() && !it.equals(issueKey, ignoreCase = true) } != null
    val existingManifest = bundleServices.reports.manifestExists(issueKey, repoRoot)
    val reusableGoal = hasReusableDecomposedParent(issueKey, repoRoot)
    val existingPlan = selectedWorkflowId != null || existingManifest && !hasNewIntake || reusableGoal
    return planningOwnership.prepare(
      issueKey,
      repoRoot,
      launch,
      GoalPlanningSelection(existingPlan, selectedWorkflowId, supersedableWorkflowId),
    )
  }

  private fun FeatureTaskContinuationLookupResult.selectedWorkflowId(): String? =
    when (this) {
      is FeatureTaskContinuationLookupResult.Resumable -> candidate.workflowId
      is FeatureTaskContinuationLookupResult.AlreadyRunning -> candidate.workflowId
      is FeatureTaskContinuationLookupResult.NeedsIdentityRepair -> workflowId
      else -> null
    }

  private data class PlanRunSelection(
    val publisher: StandalonePhaseStatusEventSink,
    val completedPlan: PlanWorkflowSelection?,
  )

  private data class ResumeLocation(
    val repoRoot: Path,
    val sourceRoot: Path = repoRoot,
  )

  private fun runInternal(
    issueKey: String,
    suppliedIntake: String?,
    repoRoot: Path,
    selection: PlanRunSelection,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
  ): StandalonePlanResult {
    val (statusPublisher, completedPlan) = selection
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
    if (bundleServices.reports.manifestExists(issueKey, repoRoot)) {
      return if (completedPlan == null && found is FeatureTaskContinuationLookupResult.Resumable) {
        resume(found.candidate, intake, ResumeLocation(repoRoot), inputFor, statusPublisher)
      } else {
        settleExistingManifest(issueKey, intake, repoRoot, selection, inputFor)
      }
    }
    return when (found) {
      is FeatureTaskContinuationLookupResult.Resumable ->
        resume(found.candidate, intake, ResumeLocation(repoRoot), inputFor, statusPublisher)
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
    selection: PlanRunSelection,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
  ): StandalonePlanResult {
    val (statusPublisher, completedPlan) = selection
    val completed = completedPlan
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
          PlanSubtasksReplan(issueKey, id, repoRoot, repoRoot, settledIds, feasibilityAdoption = true),
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
      statusPublisher.settlePlan("completed", result.workflowId, bundleServices.reports.build(issueKey, repoRoot))
      return result
    }
    return refused(
      statusPublisher,
      "A decomposition manifest already exists for $issueKey; the runtime never overwrites a plan. " +
        "Run `skill-bill $issueKey` to execute it.",
    )
  }

  private fun eligibleCompletedPlan(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
  ): PlanWorkflowSelection? =
    if (!bundleServices.reports.manifestExists(issueKey, repoRoot)) {
      null
    } else {
      planLookup.completedSelection(issueKey, goalRepositoryIdentityCandidates(repoRoot, repositories))
        ?.takeIf { selected ->
          selected.terminal == null || isLaunchedSpec(selected.terminal.parentSpecPath, issueKey, intake, repoRoot)
        }
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
          "(`skill-bill project path` prints the project store). Move it there and plan again.",
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
    sourceRoot: Path = repoRoot,
  ): Boolean {
    val selected = planLookup.selection(candidate.workflowId)
    val selectedPhase = selected?.phaseRecord
    val selectedFeasibility = selected?.feasibilityState
    if (selectedPhase != null) {
      val current =
        PlanBundleFeasibility.capture(
          decompositionPlanner,
          sourceRoot,
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
        bundleServices.reopening.reopenWithRebasedBundle(
          PlanSubtasksReplan(
            candidate.executionIdentity.normalizedIssueKey,
            candidate.workflowId,
            repoRoot,
            sourceRoot = sourceRoot,
            settledSubtaskIds = current.settledSubtaskIds,
            feasibilityAdoption = true,
          ),
        )
    }
    return true
  }

  private fun resume(
    candidate: FeatureTaskContinuationCandidate,
    intake: String?,
    location: ResumeLocation,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
    statusPublisher: StandalonePhaseStatusEventSink?,
  ): StandalonePlanResult {
    val (repoRoot, sourceRoot) = location
    if (database.read { it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, candidate.workflowId) }
        ?.hasPlanSubtasksStep() == false
    ) {
      val key = candidate.executionIdentity.normalizedIssueKey
      return refused(
        statusPublisher,
        "Plan workflow '${candidate.workflowId}' for $key predates the `plan_subtasks` step and cannot resume. " +
          "Run `skill-bill goal purge $key`, then `skill-bill phase plan $key`.",
        candidate.workflowId,
      )
    }
    val specPath = decompositionPlanner.bundlePath(sourceRoot, candidate.governedSpecPath)
    if (!adoptFeasibilityForResume(candidate, repoRoot, sourceRoot)) {
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
        statusSink?.settlePlan("completed", result.workflowId, bundleServices.reports.build(report.issueKey, repoRoot))
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
