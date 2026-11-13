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
import skillbill.engine.goal.goalRepositoryIdentity
import skillbill.engine.goal.goalRepositoryIdentityCandidates
import skillbill.engine.goalrunner.intake.GoalIntakePreparation
import skillbill.error.core.catchingNonCancellation
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.ports.agentrun.model.AgentRunOutputSink
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.model.DecompositionManifestBundleProjection
import skillbill.ports.workflow.decomposition.model.DecompositionManifestFileIdentity
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.text.sha256HexUtf8
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSpecOrigin
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityMode
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
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
  private val projectStore: ProjectStorePort,
  private val repositories: RepositoryEnclosingRootPort,
  private val fileStore: DecompositionManifestStore,
  private val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner,
  private val statusPublisherFactory: StandalonePhaseStatusPublisherFactory,
  private val bundleServices: PlanBundleServices,
  private val planningOwnership: GoalPlanningCheckoutOwnership,
) {
  public fun issueKeyOf(intake: String): String = intakePreparation.issueKeyOf(intake)

  public fun prepareGoalCheckout(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
    selectedWorkflowId: String? = null,
  ): StandalonePlanResult.Refused? {
    val establishedGoal =
      selectedWorkflowId == null &&
        database.read { unit ->
          goalRepositoryIdentityCandidates(repoRoot, repositories).any { identity ->
            val parent =
              unit.workflowStates.findOccupyingDecomposedParentWorkflow(issueKey, repositoryIdentity = identity)
            parent != null &&
              (
                unit.goalRunnerControls.controlState(parent.workflowId).executionWorktreePath != null ||
                  parent.toSnapshot().decompositionRuntime()?.subtasks?.any { !it.workflowId.isNullOrBlank() } == true
              )
          }
        }
    if (establishedGoal) return null
    val launch = StandalonePlanLaunch(scope = IdeStatusLaunchScope.GOAL)
    val completedPlan =
      if (selectedWorkflowId == null) {
        eligibleCompletedPlan(issueKey, intake, repoRoot)
      } else {
        planLookup.selection(selectedWorkflowId)?.takeIf { it.terminal != null }
      }
    val prepared =
      prepareGoalPlanningOwnership(
        issueKey,
        intake,
        repoRoot,
        launch,
        PlanningSelection(completedPlan, selectedWorkflowId),
      )
    return when (prepared) {
      is GoalPlanningLaunch.Ready -> preserveGoalPlanBundle(issueKey, intake, repoRoot, prepared, completedPlan)
      is GoalPlanningLaunch.Refused -> StandalonePlanResult.Refused(prepared.reason, prepared.workflowId)
    }
  }

  private fun preserveGoalPlanBundle(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
    prepared: GoalPlanningLaunch.Ready,
    completedPlan: PlanWorkflowSelection?,
  ): StandalonePlanResult.Refused? {
    if (prepared.preserveLegacyBundle) {
      val sourceSpec =
        selectedPlanSpecPath(
          issueKey,
          intake,
          repoRoot,
          prepared.launch.workflowIdForCreation,
          completedPlan,
        )
      if (sourceSpec == null) {
        return StandalonePlanResult.Refused(
          "Could not locate the selected legacy plan bundle for workflow " +
            "'${prepared.launch.workflowIdForCreation}'. Preserve the source and owned checkout, then inspect " +
            "the selected plan spec before retrying.",
          prepared.launch.workflowIdForCreation,
        )
      }
      if (prepared.launch.executionRoot != null) {
        val sourceBundle = sourceSpec.parent
        val targetSpec = executionPath(sourceSpec, repoRoot, prepared.launch.executionRoot)
        if (sourceSpec != targetSpec) {
          preserveLegacyBundle(
            issueKey,
            repoRoot,
            prepared.launch.executionRoot,
            sourceBundle,
            targetSpec.parent,
          )?.let { reason ->
            return StandalonePlanResult.Refused(reason, prepared.launch.workflowIdForCreation)
          }
        }
        if (!markLegacyBundlePreserved(prepared.launch.workflowIdForCreation, prepared.launch.executionRoot)) {
          return StandalonePlanResult.Refused(
            "Could not record legacy plan evidence transfer for ${prepared.launch.executionRoot}. Preserve " +
              "both locations and inspect the ownership record before retrying.",
            prepared.launch.workflowIdForCreation,
          )
        }
      }
    }
    return null
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
            "Plan workflow '$planWorkflowId' for $issueKey cannot reopen `plan_subtasks` in $repoRoot. " +
              "Preserve the checkout and plan evidence, then reconcile the selected workflow, required step, " +
              "ownership and worker before retrying.",
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
    val prepared =
      prepareGoalPlanningOwnership(issueKey, suppliedIntake, repoRoot, launch, PlanningSelection(completedPlan))
    val effectiveLaunch =
      when (prepared) {
        is GoalPlanningLaunch.Ready -> prepared.launch
        is GoalPlanningLaunch.Refused -> launch
      }
    val executionRoot = effectiveLaunch.executionRoot ?: repoRoot
    val executionCompletedPlan =
      eligibleCompletedPlan(issueKey, suppliedIntake, executionRoot, sourceRoot = repoRoot) ?: completedPlan
    val publisher =
      statusPublisherFactory.forPlan(
        repoRoot = executionRoot,
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
          ResumeLocation(executionRoot, repoRoot),
          PlanRunSelection(
            publisher,
            executionCompletedPlan,
            preserveLegacyBundle = (prepared as? GoalPlanningLaunch.Ready)?.preserveLegacyBundle == true,
          ),
        ) { specPath ->
          val original = inputFor(specPath)
          val input =
            original.copy(
              repoRoot = effectiveLaunch.executionRoot ?: original.repoRoot,
              planIntakeRoot = effectiveLaunch.executionRoot ?: original.repoRoot,
              workflowIdForCreation = effectiveLaunch.workflowIdForCreation,
            )
          withPublisher(
            input.copy(
              inheritRecordedPlanningTimeout = launch.scope == IdeStatusLaunchScope.GOAL || input.timeout == null,
            ),
            publisher,
          )
        }
      }.onFailure(publisher::settleFailure).getOrThrow().also { result ->
        settleCompletedStandalone(result, effectiveLaunch, issueKey, repoRoot)
      }
    } finally {
      publisher.closeOutputCapture()
    }
  }

  private fun settleCompletedStandalone(
    result: StandalonePlanResult,
    launch: StandalonePlanLaunch,
    issueKey: String,
    sourceRoot: Path,
  ) {
    if (launch.scope != IdeStatusLaunchScope.STANDALONE || result !is StandalonePlanResult.Completed) return
    val executionRoot = launch.executionRoot ?: return
    planningOwnership.settleCompletedStandalone(issueKey, sourceRoot, executionRoot, result.workflowId)
  }

  private data class PlanningSelection(
    val completedPlan: PlanWorkflowSelection?,
    val boundWorkflowId: String? = null,
  )

  private fun prepareGoalPlanningOwnership(
    issueKey: String,
    suppliedIntake: String?,
    repoRoot: Path,
    launch: StandalonePlanLaunch,
    selection: PlanningSelection,
  ): GoalPlanningLaunch {
    val (completedPlan, boundWorkflowId) = selection
    val discoveredPlan = discoverGoalPlan(issueKey, repoRoot, boundWorkflowId)
    if (discoveredPlan is FeatureTaskContinuationLookupResult.AlreadyRunning) {
      val workflowId = discoveredPlan.candidate.workflowId
      val ownedPath =
        database.read { unit -> unit.goalPlanningOwnership.findByWorkflow(workflowId)?.ownedPath }
      val path = ownedPath?.toString() ?: "the unresolved owned checkout for repository $repoRoot"
      return GoalPlanningLaunch.Refused(
        "Plan workflow '$workflowId' for $issueKey still has a live worker using $path. " +
          "Stop that worker or let crash recovery reconcile it before retrying goal adoption.",
        workflowId,
      )
    }
    val selectedWorkflowId =
      boundWorkflowId ?: completedPlan?.workflowId
        ?: discoveredPlan?.forLaunchedSpec(issueKey, suppliedIntake, repoRoot)?.selectedWorkflowId()
    val hadOwnership =
      database.read { unit ->
        selectedWorkflowId?.let(unit.goalPlanningOwnership::findByWorkflow)
          ?: unit.goalPlanningOwnership.findByIssue(
            goalRepositoryIdentity(repoRoot, repositories),
            FeatureTaskExecutionIdentityPolicy.canonicalIssueKey(issueKey),
          )
      } != null
    val supersedableWorkflowId =
      if (completedPlan == null) {
        planningOwnership.supersedablePlan(
          issueKey,
          repoRoot,
          (discoveredPlan as? FeatureTaskContinuationLookupResult.Resumable)?.candidate?.workflowId,
          hasDistinctIntake(suppliedIntake, issueKey),
        ) { spec -> isLaunchedSpec(spec, issueKey, suppliedIntake, repoRoot) }
      } else {
        null
      }
    val hasNewIntake = hasDistinctIntake(suppliedIntake, issueKey)
    val existingManifest = bundleServices.reports.manifestExists(issueKey, repoRoot)
    val reusableGoal = hasReusableDecomposedParent(issueKey, repoRoot)
    val existingPlan = selectedWorkflowId != null || existingManifest && !hasNewIntake || reusableGoal
    val prepared =
      planningOwnership.prepare(
        issueKey,
        repoRoot,
        launch,
        GoalPlanningSelection(
          existingPlan,
          selectedWorkflowId,
          supersedableWorkflowId,
          preserveLegacyBundle = selectedWorkflowId != null && !hadOwnership,
        ),
      )
    return if (prepared is GoalPlanningLaunch.Ready) {
      prepared.copy(preserveLegacyBundle = prepared.preserveLegacyBundle || selectedWorkflowId != null && !hadOwnership)
    } else {
      prepared
    }
  }

  private fun discoverGoalPlan(
    issueKey: String,
    repoRoot: Path,
    boundWorkflowId: String?,
  ): FeatureTaskContinuationLookupResult? =
    boundWorkflowId?.let {
      planLookup.lookupSelected(issueKey, goalRepositoryIdentityCandidates(repoRoot, repositories), it)
    }
      ?: goalRepositoryIdentityCandidates(repoRoot, repositories).firstNotNullOfOrNull { identity ->
        planLookup.lookupAfterCrashRecovery(issueKey, identity)
          .takeIf { it != FeatureTaskContinuationLookupResult.NoMatch }
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
    val preserveLegacyBundle: Boolean = false,
  )

  private data class ResumeLocation(
    val repoRoot: Path,
    val sourceRoot: Path = repoRoot,
    val preserveLegacyBundle: Boolean = false,
  )

  private fun runInternal(
    issueKey: String,
    suppliedIntake: String?,
    location: ResumeLocation,
    selection: PlanRunSelection,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
  ): StandalonePlanResult {
    val (repoRoot, sourceRoot) = location
    val (statusPublisher, completedPlan) = selection
    val intake =
      suppliedIntake?.takeIf { supplied ->
        val normalized = supplied.trim()
        normalized.isNotEmpty() && !normalized.equals(issueKey, ignoreCase = true)
      }
    val found =
      (
        goalRepositoryIdentityCandidates(sourceRoot, repositories).firstNotNullOfOrNull { identity ->
          planLookup.lookupAfterCrashRecovery(issueKey, identity)
            .takeIf { it != FeatureTaskContinuationLookupResult.NoMatch }
        } ?: FeatureTaskContinuationLookupResult.NoMatch
      ).forLaunchedSpec(issueKey, intake, sourceRoot)
    val activeSelection =
      when (val legacy = prepareLegacySelection(issueKey, location, selection, found)) {
        is LegacySelection.Ready -> legacy.selection
        is LegacySelection.Refused -> return legacy.result
      }
    if (hasReusableDecomposedParent(issueKey, repoRoot)) {
      return open(issueKey, intake, location, inputFor, statusPublisher)
    }
    if (bundleServices.reports.manifestExists(issueKey, repoRoot)) {
      return when (found) {
        is FeatureTaskContinuationLookupResult.Resumable ->
          resume(
            found.candidate,
            intake,
            ResumeLocation(repoRoot, sourceRoot, activeSelection.preserveLegacyBundle),
            inputFor,
            statusPublisher,
          )
        else -> settleExistingManifest(issueKey, intake, location, activeSelection, inputFor)
      }
    }
    return when (found) {
      is FeatureTaskContinuationLookupResult.Resumable ->
        resume(
          found.candidate,
          intake,
          ResumeLocation(repoRoot, sourceRoot, activeSelection.preserveLegacyBundle),
          inputFor,
          statusPublisher,
        )
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
      -> open(issueKey, intake, location, inputFor, statusPublisher)
    }
  }

  private sealed interface LegacySelection {
    data class Ready(val selection: PlanRunSelection) : LegacySelection

    data class Refused(val result: StandalonePlanResult) : LegacySelection
  }

  private fun prepareLegacySelection(
    issueKey: String,
    location: ResumeLocation,
    selection: PlanRunSelection,
    found: FeatureTaskContinuationLookupResult,
  ): LegacySelection {
    val (repoRoot, sourceRoot) = location
    val (statusPublisher, completedPlan) = selection
    return if (selection.preserveLegacyBundle) {
      val candidate =
        when (found) {
          is FeatureTaskContinuationLookupResult.Resumable -> found.candidate
          is FeatureTaskContinuationLookupResult.AlreadyRunning -> found.candidate
          else -> null
        }
      val workflowId = completedPlan?.workflowId ?: candidate?.workflowId
      val sourceSpec =
        completedPlan?.terminal?.parentSpecPath?.let { decompositionPlanner.bundlePath(sourceRoot, it) }
          ?: candidate?.let { decompositionPlanner.bundlePath(sourceRoot, it.governedSpecPath) }
      if (workflowId == null || sourceSpec == null) {
        return LegacySelection.Refused(
          refused(
            statusPublisher,
            "Could not locate the selected legacy plan bundle for $issueKey. Preserve the source and owned " +
              "checkout, then inspect the selected plan evidence before retrying.",
            workflowId,
          ),
        )
      }
      val targetSpec = executionPath(sourceSpec, sourceRoot, repoRoot)
      if (sourceSpec != targetSpec) {
        preserveLegacyBundle(issueKey, sourceRoot, repoRoot, sourceSpec.parent, targetSpec.parent)?.let { reason ->
          return LegacySelection.Refused(refused(statusPublisher, reason, workflowId))
        }
      }
      if (!markLegacyBundlePreserved(workflowId, repoRoot)) {
        return LegacySelection.Refused(
          refused(
            statusPublisher,
            "Could not record legacy plan evidence transfer for $repoRoot. Preserve both locations and inspect " +
              "the ownership record before retrying.",
            workflowId,
          ),
        )
      }
      LegacySelection.Ready(selection.copy(preserveLegacyBundle = false))
    } else {
      LegacySelection.Ready(selection)
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
    location: ResumeLocation,
    selection: PlanRunSelection,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
  ): StandalonePlanResult {
    val (repoRoot, sourceRoot) = location
    val (statusPublisher, completedPlan) = selection
    val completed = completedPlan
    if (completed == null) {
      return open(
        issueKey,
        intake,
        location,
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
          PlanSubtasksReplan(
            issueKey,
            id,
            repoRoot,
            sourceRoot,
            settledIds,
            feasibilityAdoption = true,
            bundleRoot = repoRoot,
          ),
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
        "Run `sona $issueKey` to execute it.",
    )
  }

  private fun eligibleCompletedPlan(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
    sourceRoot: Path = repoRoot,
  ): PlanWorkflowSelection? =
    if (!bundleServices.reports.manifestExists(issueKey, repoRoot)) {
      null
    } else {
      planLookup.completedSelection(issueKey, goalRepositoryIdentityCandidates(repoRoot, repositories))
        ?.takeIf { selected ->
          selected.terminal == null || isLaunchedSpec(selected.terminal.parentSpecPath, issueKey, intake, sourceRoot)
        }
    }

  private fun open(
    issueKey: String,
    intake: String?,
    location: ResumeLocation,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
    statusPublisher: StandalonePhaseStatusEventSink?,
  ): StandalonePlanResult {
    val (repoRoot, sourceRoot) = location
    val resolution = intakePreparation.resolvePlanSpec(issueKey, intake, sourceRoot)
    val plannedPath = resolution.specPath
    val existing = fileStore.isRegularFile(plannedPath)
    if (existing && !isKeyResolvedSpec(issueKey, plannedPath, sourceRoot)) {
      return refused(
        statusPublisher,
        "The supplied spec $plannedPath is not the governed spec for $issueKey; settlement guards the spec " +
          "found under ${decompositionPlanner.specsRoot(sourceRoot)}/$issueKey-*/ " +
          "(`sona project path` prints the project store). Move it there and plan again.",
      )
    }
    val selectedContent = if (existing) fileStore.readText(plannedPath) else null
    val specPath = executionPath(plannedPath, sourceRoot, repoRoot)
    if (selectedContent != null && specPath != plannedPath) {
      planningOwnership.prepareCheckoutWrite(issueKey, sourceRoot, repoRoot) {
        fileStore.writeTextAtomically(specPath, selectedContent)
        null
      }?.let { return refused(statusPublisher, it) }
    }
    val input =
      inputFor(specPath.toString()).copy(
        definition = SkeletonDefinition.PLAN,
        planIntake = intake,
        planIntakeRoot = repoRoot,
        planSpecResolution = resolution.copy(specPath = specPath),
        protectedSpecSha256 =
          if (resolution.specOrigin == FeatureTaskRuntimePlanSpecOrigin.OPERATOR) {
            selectedContent?.let(::sha256HexUtf8)
          } else {
            null
          },
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
          selectedFeasibility.mode == PlanBundleFeasibilityMode.BLOCKED && selectedFeasibility.activeLaunch == null ||
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
            bundleRoot = repoRoot,
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
    val sourceSpecPath = decompositionPlanner.bundlePath(sourceRoot, candidate.governedSpecPath)
    val specPath = executionPath(sourceSpecPath, sourceRoot, repoRoot)
    resumeAdmissionProblem(candidate, location, sourceSpecPath, specPath, statusPublisher)?.let { return it }
    if (!adoptFeasibilityForResume(candidate, repoRoot, sourceRoot)) {
      return refused(
        statusPublisher,
        "Plan workflow '${candidate.workflowId}' could not adopt its retained plan_subtasks output " +
          "for a full v2 review.",
        candidate.workflowId,
      )
    }
    val seed = bundleServices.seedRecorder.read(candidate.workflowId)
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
        if (intakePreparation.keepsOperatorSpec(sourceSpecPath)) specHash(sourceSpecPath) else null
      }
    val input =
      inputFor(specPath.toString()).copy(
        explicitWorkflowId = candidate.workflowId,
        definition = SkeletonDefinition.PLAN,
        planIntake = intake,
        planIntakeRoot = repoRoot,
        protectedSpecSha256 = protectedSpecSha256,
      )
    return runEntry(input, repoRoot, statusPublisher)
  }

  private fun resumeAdmissionProblem(
    candidate: FeatureTaskContinuationCandidate,
    location: ResumeLocation,
    sourceSpecPath: Path,
    specPath: Path,
    statusPublisher: StandalonePhaseStatusEventSink?,
  ): StandalonePlanResult? {
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
    return resumeSpecProblem(candidate, location, sourceSpecPath, specPath, statusPublisher)
  }

  private fun resumeSpecProblem(
    candidate: FeatureTaskContinuationCandidate,
    location: ResumeLocation,
    sourceSpecPath: Path,
    specPath: Path,
    statusPublisher: StandalonePhaseStatusEventSink?,
  ): StandalonePlanResult? {
    val (repoRoot, sourceRoot) = location
    if (location.preserveLegacyBundle && specPath != sourceSpecPath) {
      preserveLegacyBundle(
        candidate.executionIdentity.normalizedIssueKey,
        sourceRoot,
        repoRoot,
        sourceSpecPath.parent,
        specPath.parent,
      )?.let { reason ->
        return refused(statusPublisher, reason, candidate.workflowId)
      }
    }
    if (location.preserveLegacyBundle && !markLegacyBundlePreserved(candidate.workflowId, repoRoot)) {
      return refused(
        statusPublisher,
        "Could not record legacy plan evidence transfer for $repoRoot. Preserve both locations and inspect " +
          "the ownership record before retrying.",
        candidate.workflowId,
      )
    }
    if (specPath != sourceSpecPath && !fileStore.isRegularFile(specPath)) {
      return refused(
        statusPublisher,
        "Owned planning spec $specPath is missing for workflow '${candidate.workflowId}'. Preserve the " +
          "caller spec $sourceSpecPath and restore or inspect the owned checkout before retrying.",
        candidate.workflowId,
      )
    }
    return null
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

  private fun executionPath(
    sourcePath: Path,
    sourceRoot: Path,
    executionRoot: Path,
  ): Path {
    val canonicalSourceRoot = repositories.canonicalPath(sourceRoot)
    val canonicalSourcePath = repositories.canonicalPath(sourcePath)
    val projectSpecsRoot = repositories.canonicalPath(projectStore.resolve(sourceRoot).specsRoot)
    if (canonicalSourcePath.startsWith(projectSpecsRoot) || !canonicalSourcePath.startsWith(canonicalSourceRoot)) {
      return sourcePath
    }
    val target = repositories.canonicalPath(executionRoot).resolve(canonicalSourceRoot.relativize(canonicalSourcePath))
    return target
  }

  private fun selectedPlanSpecPath(
    issueKey: String,
    intake: String?,
    sourceRoot: Path,
    workflowId: String?,
    completedPlan: PlanWorkflowSelection?,
  ): Path? {
    completedPlan?.terminal?.parentSpecPath?.let { return decompositionPlanner.bundlePath(sourceRoot, it) }
    val identities = goalRepositoryIdentityCandidates(sourceRoot, repositories)
    val found =
      workflowId?.let { planLookup.lookupSelected(issueKey, identities, it) }
        ?: identities.firstNotNullOfOrNull { identity -> planLookup.lookupAfterCrashRecovery(issueKey, identity) }
    val candidate =
      (found as? FeatureTaskContinuationLookupResult.Resumable)?.candidate
        ?.takeIf { isLaunchedSpec(it.governedSpecPath, issueKey, intake, sourceRoot) }
    return candidate?.let { decompositionPlanner.bundlePath(sourceRoot, it.governedSpecPath) }
  }

  private fun markLegacyBundlePreserved(
    workflowId: String?,
    executionRoot: Path,
  ): Boolean {
    if (workflowId == null) return false
    return database.transaction { unit ->
      val ownership = unit.goalPlanningOwnership.findByWorkflow(workflowId) ?: return@transaction false
      if (ownership.legacyBundlePending == false) return@transaction true
      unit.goalPlanningOwnership.markLegacyBundlePreserved(workflowId, executionRoot)
    }
  }

  private fun preserveLegacyBundle(
    issueKey: String,
    sourceRepository: Path,
    executionRoot: Path,
    sourceBundle: Path,
    targetBundle: Path,
  ): String? =
    planningOwnership.prepareCheckoutWrite(issueKey, sourceRepository, executionRoot) {
      preserveLegacyBundleFiles(sourceRepository, sourceBundle, targetBundle)
    }

  private fun preserveLegacyBundleFiles(
    sourceRepository: Path,
    sourceBundle: Path,
    targetBundle: Path,
  ): String? {
    val sourceRoot = repositories.canonicalPath(sourceBundle).normalize()
    val targetRoot = repositories.canonicalPath(targetBundle).normalize()
    val specsRoot = repositories.canonicalPath(decompositionPlanner.specsRoot(sourceRepository)).normalize()
    if (!sourceRoot.startsWith(specsRoot) || sourceRoot == specsRoot || Files.isSymbolicLink(sourceBundle)) {
      return "Legacy plan bundle $sourceBundle is outside the governed specs directory or linked. Preserve both " +
        "locations and inspect the selected spec path before retrying."
    }
    val paths =
      legacyTransferAttempt { fileStore.listTree(sourceRoot) }.getOrElse {
        return legacyBundleTransferFailure(sourceRoot, targetRoot, it.message)
      }
    val transfer = LegacyBundleTransfer(sourceRoot, targetRoot)
    collectLegacyBundleWrites(paths, transfer)?.let { return it }
    return writeLegacyBundle(transfer)
  }

  private fun writeLegacyBundle(transfer: LegacyBundleTransfer): String? {
    val sourceRoot = transfer.sourceRoot
    val targetRoot = transfer.targetRoot
    val writes = transfer.writes
    val protectedSources = transfer.protectedSources
    for ((_, parentWrites) in writes.groupBy { it.first.parent }) {
      legacyTransferAttempt {
        val identities = parentWrites.map { (path, _) -> DecompositionManifestFileIdentity(path, null) }
        fileStore.writeBundleAtomically(
          parentWrites,
          DecompositionManifestBundleProjection(identities, protectedSources),
        ) { true }
      }.exceptionOrNull()?.let { return legacyBundleTransferFailure(sourceRoot, targetRoot, it.message) }
    }
    return null
  }

  private data class LegacyBundleTransfer(
    val sourceRoot: Path,
    val targetRoot: Path,
    val writes: MutableList<Pair<Path, String>> = mutableListOf(),
    val protectedSources: MutableList<DecompositionManifestFileIdentity> = mutableListOf(),
  )

  private fun collectLegacyBundleWrites(
    paths: List<Path>,
    transfer: LegacyBundleTransfer,
  ): String? {
    val sourceRoot = transfer.sourceRoot
    val targetRoot = transfer.targetRoot
    val writes = transfer.writes
    val protectedSources = transfer.protectedSources
    for (path in paths) {
      val source = repositories.canonicalPath(path).normalize()
      if (!source.startsWith(sourceRoot) || Files.isSymbolicLink(path)) {
        return "Legacy plan bundle contains an unresolved or linked path $path. Preserve both $sourceRoot and " +
          "$targetRoot, then resolve that path before retrying."
      }
      if (!fileStore.isRegularFile(path)) continue
      val destination = targetRoot.resolve(sourceRoot.relativize(source)).normalize()
      legacyDestinationProblem(path, targetRoot, destination)?.let { return it }
      if (fileStore.isRegularFile(destination)) {
        if (Files.isSymbolicLink(destination) || fileStore.readText(path) != fileStore.readText(destination)) {
          return "Legacy plan evidence differs between $path and $destination. Preserve both files and reconcile " +
            "the conflict before retrying."
        }
      } else {
        val content = fileStore.readText(path)
        writes += destination to content
        protectedSources += DecompositionManifestFileIdentity(path, sha256HexUtf8(content))
      }
    }
    return null
  }

  private fun legacyDestinationProblem(
    path: Path,
    targetRoot: Path,
    destination: Path,
  ): String? {
    if (!destination.startsWith(targetRoot)) {
      return "Legacy plan bundle path $path escapes its selected bundle. Preserve both locations and inspect " +
        "it before retrying."
    }
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && !fileStore.isRegularFile(destination)) {
      return "Legacy plan evidence destination $destination is occupied by a non-file. Preserve both locations " +
        "and resolve the conflict before retrying."
    }
    return null
  }

  private fun legacyBundleTransferFailure(
    sourceRoot: Path,
    targetRoot: Path,
    detail: String?,
  ): String =
    "Could not transfer legacy plan evidence from $sourceRoot to $targetRoot${detail?.let { ": $it"}.orEmpty()}. " +
      "Preserve both locations and inspect the transfer before retrying."

  private fun specHash(specPath: Path): String = sha256HexUtf8(fileStore.readText(specPath))
}

private fun <T> legacyTransferAttempt(block: () -> T): Result<T> =
  try {
    Result.success(block())
  } catch (failure: IOException) {
    Result.failure(failure)
  } catch (failure: UncheckedIOException) {
    Result.failure(failure)
  } catch (failure: SecurityException) {
    Result.failure(failure)
  }

private val LIVE_PLAN_REPORT_PHASES =
  setOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS,
  )

private fun hasDistinctIntake(
  suppliedIntake: String?,
  issueKey: String,
): Boolean = suppliedIntake?.trim()?.takeIf { it.isNotEmpty() && !it.equals(issueKey, ignoreCase = true) } != null
