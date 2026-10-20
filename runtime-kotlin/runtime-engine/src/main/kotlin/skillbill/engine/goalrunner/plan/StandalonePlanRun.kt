package skillbill.engine.goalrunner.plan

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.decomposition.findCompletedPlanWorkflowId
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationCandidate
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunInput
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.phaserun.StandalonePhaseLaunch
import skillbill.engine.featuretask.phaserun.StandalonePhaseStatusEventSink
import skillbill.engine.featuretask.phaserun.StandalonePhaseStatusPublisherFactory
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeRunEntry
import skillbill.engine.goalrunner.intake.GoalIntakePreparation
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.model.StandalonePlanReport
import skillbill.ports.idestatus.model.StandalonePlanSubtask
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.text.sha256HexUtf8
import skillbill.workflow.taskruntime.artifact.decomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSpecOrigin
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

/** How a standalone plan run ended. */
sealed interface StandalonePlanResult {
  /** The plan authored a verified spec bundle and settled. */
  data class Completed(
    val workflowId: String,
    val parentSpecPath: String,
    val decompositionManifestPath: String,
    val subtaskSpecPaths: List<String>,
  ) : StandalonePlanResult

  /** The plan opened or resumed but could not settle; the workflow stays resumable. */
  data class Blocked(
    val workflowId: String,
    val reason: String,
  ) : StandalonePlanResult

  /** The plan was not opened or resumed; [workflowId] names the plan workflow in the way, when one exists. */
  data class Refused(
    val reason: String,
    val workflowId: String? = null,
  ) : StandalonePlanResult
}

data class StandalonePlanLaunch(
  val agentId: String? = null,
  val scope: IdeStatusLaunchScope = IdeStatusLaunchScope.STANDALONE,
) {
  fun forPhase(target: String): StandalonePhaseLaunch =
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
class StandalonePlanRun(
  private val intakePreparation: GoalIntakePreparation,
  private val manifestStore: GoalRunnerManifestStore,
  private val planLookup: PlanWorkflowLookup,
  private val entry: FeatureTaskRuntimeRunEntry,
  private val database: DatabaseSessionFactory,
  private val repositories: RepositoryEnclosingRootPort,
  private val fileStore: DecompositionManifestStore,
  private val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner,
  private val statusPublisherFactory: StandalonePhaseStatusPublisherFactory,
  private val seedRecorder: FeatureTaskRuntimePlanSeedRecorder,
  private val reopening: PlanSubtasksReopening,
) {
  fun issueKeyOf(intake: String): String = intakePreparation.issueKeyOf(intake)

  fun replanSubtasks(
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
    return runCatching {
      if (!reopening.reopenWithRebasedBundle(replan)) {
        return@runCatching refused(
          publisher,
          "Plan workflow '$planWorkflowId' for $issueKey has no `plan_subtasks` step to re-plan.",
          planWorkflowId,
        )
      }
      val identity = repositories.repositoryIdentity(repoRoot)
      val found = planLookup.lookup(issueKey, identity, planWorkflowId)
      if (found !is FeatureTaskContinuationLookupResult.Resumable || found.candidate.workflowId != planWorkflowId) {
        return@runCatching refused(
          publisher,
          "Plan workflow '$planWorkflowId' for $issueKey could not be resumed after reopening `plan_subtasks`.",
          planWorkflowId,
        )
      }
      resume(found.candidate, null, repoRoot, { specPath -> withPublisher(inputFor(specPath), publisher) }, publisher)
    }.onFailure(publisher::settleFailure).getOrThrow()
  }

  private fun withPublisher(
    input: FeatureTaskRuntimeRunInput,
    publisher: StandalonePhaseStatusEventSink,
  ): FeatureTaskRuntimeRunInput =
    input.copy(eventSink = statusPublisherFactory.compose(input.eventSink::emit, publisher))

  fun run(
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
    return runCatching {
      runInternal(issueKey, suppliedIntake, repoRoot, publisher) { specPath ->
        withPublisher(inputFor(specPath), publisher)
      }
    }.onFailure(publisher::settleFailure).getOrThrow()
  }

  private fun runInternal(
    issueKey: String,
    suppliedIntake: String?,
    repoRoot: Path,
    statusPublisher: StandalonePhaseStatusEventSink,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
  ): StandalonePlanResult {
    val intake = suppliedIntake?.trim()?.takeIf { it.isNotEmpty() && !it.equals(issueKey, ignoreCase = true) }
    val identity = repositories.repositoryIdentity(repoRoot)
    val found = planLookup.lookupAfterCrashRecovery(issueKey, identity)
    if (manifestStore.readByIssueKey(issueKey, repoRoot) != null) {
      if (found is FeatureTaskContinuationLookupResult.Resumable) {
        return resume(found.candidate, intake, repoRoot, inputFor, statusPublisher)
      }
      return settleExistingManifest(issueKey, intake, repoRoot, statusPublisher, inputFor)
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
      is FeatureTaskContinuationLookupResult.Ambiguous ->
        refused(
          statusPublisher,
          "Several plan workflows exist for $issueKey: ${found.candidates.joinToString { it.workflowId }}.",
        )
      is FeatureTaskContinuationLookupResult.NeedsIdentityRepair ->
        refused(statusPublisher, found.summary, found.workflowId)
      is FeatureTaskContinuationLookupResult.TerminalOnly,
      is FeatureTaskContinuationLookupResult.GoalContinuation,
      FeatureTaskContinuationLookupResult.NoMatch,
      -> open(issueKey, intake, repoRoot, inputFor, statusPublisher)
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
      database.read { unitOfWork ->
        unitOfWork.workflowStates.findCompletedPlanWorkflowId(issueKey, repositories.repositoryIdentity(repoRoot))
          ?.let { id ->
            unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, id)
              ?.takeIf { it.hasPlanSubtasksStep() }
              ?.let { id to it.artifacts.decomposeTerminal() }
          }
      }
    if (completed == null) return open(issueKey, intake, repoRoot, inputFor, statusPublisher)
    val (id, terminal) = completed
    if (terminal != null && intake == null) {
      val result =
        StandalonePlanResult.Completed(
          id,
          terminal.parentSpecPath,
          terminal.decompositionManifestPath,
          terminal.subtaskSpecPaths,
        )
      statusPublisher.settlePlan("completed", result.workflowId, planReport(issueKey, repoRoot))
      return result
    }
    return refused(
      statusPublisher,
      "A decomposition manifest already exists for $issueKey; the runtime never overwrites a plan. " +
        "Run `skill-bill $issueKey` to execute it.",
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
          "found under .feature-specs/$issueKey-*/. Move it there and plan again.",
      )
    }
    val specPath =
      if (existing) plannedPath else repositories.canonicalPath(repoRoot).resolve(repoRoot.relativize(plannedPath))
    val input =
      inputFor(specPath.toString()).copy(
        definition = SkeletonDefinition.PLAN,
        planIntake = intake,
        protectedSpecSha256 =
          if (resolution.specOrigin == FeatureTaskRuntimePlanSpecOrigin.OPERATOR) specHash(plannedPath) else null,
      )
    return runEntry(input, repoRoot, statusPublisher)
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
          "Run `skill-bill goal purge $key`, then `skill-bill phase plan $key`.",
        candidate.workflowId,
      )
    }
    val specPath = repositories.canonicalPath(repoRoot).resolve(candidate.governedSpecPath)
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
    val report =
      runCatching {
        entry.run(input) { error("Could not run plan workflow: ${it.error}") }
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
        statusSink?.settlePlan("completed", result.workflowId, planReport(report.issueKey, repoRoot))
      is StandalonePlanResult.Blocked -> statusSink?.settleBlocked(result.reason, result.workflowId)
      is StandalonePlanResult.Refused -> statusSink?.settleBlocked(result.reason, result.workflowId)
    }
    return result
  }

  private fun isKeyResolvedSpec(
    issueKey: String,
    specPath: Path,
    repoRoot: Path,
  ): Boolean =
    decompositionPlanner.existingParentSpec(repoRoot, issueKey)
      ?.let { resolved -> repoRoot.resolve(resolved).normalize() == repoRoot.resolve(specPath).normalize() } == true

  private fun planReport(
    issueKey: String,
    repoRoot: Path,
  ): StandalonePlanReport? =
    manifestStore.readByIssueKeyIfPresent(issueKey, repoRoot)?.manifest?.let { manifest ->
      StandalonePlanReport(
        manifest.featureName,
        manifest.parentSpecPath,
        manifest.subtasks.map { subtask ->
          StandalonePlanSubtask(subtask.id, subtask.name, subtask.specPath, subtask.dependencies.map { it.subtaskId })
        },
      )
    }

  private fun specHash(specPath: Path): String = sha256HexUtf8(fileStore.readText(specPath))
}
