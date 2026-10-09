package skillbill.engine.goalrunner.plan

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.decomposition.findCompletedPlanWorkflowId
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskContinuationLookupService
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationCandidate
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunInput
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.phaserun.StandalonePhaseStatusEventSink
import skillbill.engine.featuretask.phaserun.StandalonePhaseStatusPublisherFactory
import skillbill.engine.featuretask.runner.FeatureTaskRuntimeRunEntry
import skillbill.engine.goalrunner.intake.GoalIntakePreparation
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.text.sha256HexUtf8
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodePlanSeedFromArtifact
import skillbill.workflow.taskruntime.artifact.decomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSeed
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

@Inject
class StandalonePlanRun(
  private val intakePreparation: GoalIntakePreparation,
  private val manifestStore: GoalRunnerManifestStore,
  private val lookupService: FeatureTaskContinuationLookupService,
  private val entry: FeatureTaskRuntimeRunEntry,
  private val crashReconciler: FeatureTaskRuntimeCrashReconciler,
  private val database: DatabaseSessionFactory,
  private val repositories: RepositoryEnclosingRootPort,
  private val fileStore: DecompositionManifestStore,
  private val decompositionPlanner: FeatureTaskRuntimeDecompositionPlanner,
  private val preparation: StandalonePlanPreparation,
  private val statusPublisherFactory: StandalonePhaseStatusPublisherFactory,
) {
  fun issueKeyOf(intake: String): String = intakePreparation.issueKeyOf(intake)

  fun incompletePlanWorkflowId(
    issueKey: String,
    repoRoot: Path,
  ): String? =
    if (manifestStore.readByIssueKey(issueKey, repoRoot) != null) {
      null
    } else {
      when (val found = lookupService.lookupPlan(issueKey, repositories.repositoryIdentity(repoRoot))) {
        is FeatureTaskContinuationLookupResult.Resumable -> found.candidate.workflowId
        is FeatureTaskContinuationLookupResult.AlreadyRunning -> found.candidate.workflowId
        else -> null
      }
    }

  fun run(
    issueKey: String,
    suppliedIntake: String?,
    repoRoot: Path,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
  ): StandalonePlanResult {
    val publisher =
      statusPublisherFactory.forPlan(
        repoRoot = repoRoot,
        issueKey = issueKey,
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      )
    return runCatching {
      runInternal(issueKey, suppliedIntake, repoRoot, publisher) { specPath ->
        val input = inputFor(specPath)
        input.copy(eventSink = statusPublisherFactory.compose(input.eventSink::emit, publisher))
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
    if (manifestStore.readByIssueKey(issueKey, repoRoot) != null) {
      val completed =
        database.read { unitOfWork ->
          unitOfWork.workflowStates.findCompletedPlanWorkflowId(issueKey, repositories.repositoryIdentity(repoRoot))
            ?.let { id ->
              unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, id)
                ?.let { id to it.artifacts.decomposeTerminal() }
            }
        }
      if (completed != null && (suppliedIntake == null || suppliedIntake.trim().equals(issueKey, ignoreCase = true))) {
        val (id, terminal) = completed
        if (terminal != null) {
          preparation.capture(id, issueKey, repoRoot)
          val result =
            StandalonePlanResult.Completed(
              id,
              terminal.parentSpecPath,
              terminal.decompositionManifestPath,
              terminal.subtaskSpecPaths,
            )
          statusPublisher.settlePlan("completed", result.workflowId)
          return result
        }
      }
      return refused(
        statusPublisher,
        "A decomposition manifest already exists for $issueKey; the runtime never overwrites a plan. " +
          "Run `skill-bill $issueKey` to execute it.",
      )
    }
    val intake = suppliedIntake?.trim()?.takeIf { it.isNotEmpty() && !it.equals(issueKey, ignoreCase = true) }
    val identity = repositories.repositoryIdentity(repoRoot)
    return when (val found = lookupPlanAfterCrashRecovery(issueKey, identity)) {
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

  private fun lookupPlanAfterCrashRecovery(
    issueKey: String,
    repositoryIdentity: String,
  ): FeatureTaskContinuationLookupResult {
    val found = lookupService.lookupPlan(issueKey, repositoryIdentity)
    if (found !is FeatureTaskContinuationLookupResult.AlreadyRunning) return found
    crashReconciler.reconcile(found.candidate.workflowId)
    return lookupService.lookupPlan(issueKey, repositoryIdentity)
  }

  private fun open(
    issueKey: String,
    intake: String?,
    repoRoot: Path,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
    statusPublisher: StandalonePhaseStatusEventSink?,
  ): StandalonePlanResult {
    val spec = intakePreparation.seedPlanSpec(issueKey, intake, repoRoot)
    val operatorAuthored = spec.specOrigin == FeatureTaskRuntimePlanSpecOrigin.OPERATOR
    if (operatorAuthored && !isKeyResolvedSpec(issueKey, spec.specPath, repoRoot)) {
      return refused(
        statusPublisher,
        "The supplied spec ${spec.specPath} is not the governed spec for $issueKey; settlement guards the spec " +
          "found under .feature-specs/$issueKey-*/. Move it there and plan again.",
      )
    }
    val seed = FeatureTaskRuntimePlanSeed(intake?.let(::sha256HexUtf8), spec.specOrigin, specHash(spec.specPath))
    val input =
      inputFor(spec.specPath.toString()).copy(
        definition = SkeletonDefinition.PLAN,
        protectedSpecSha256 = seed.specSha256.takeIf { operatorAuthored },
        openArtifacts =
          requireNotNull(
            WorkflowArtifactPatch.from(
              mapOf(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_SEED.entry(seed.asWorkflowArtifactEntry())),
            ),
          ),
      )
    return runEntry(input, repoRoot, statusPublisher)
  }

  private fun resume(
    candidate: FeatureTaskContinuationCandidate,
    intakeSha256: String?,
    repoRoot: Path,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
    statusPublisher: StandalonePhaseStatusEventSink?,
  ): StandalonePlanResult {
    val specPath = repoRoot.resolve(candidate.governedSpecPath)
    val seed = recordedSeed(candidate.workflowId)
    if (intakeSha256 != null && intakeSha256 != seed?.intakeSha256) {
      return refused(
        statusPublisher,
        "Plan workflow '${candidate.workflowId}' was opened with a different intake; resume it without an intake.",
        candidate.workflowId,
      )
    }
    val operatorAuthored = seed == null || seed.specOrigin == FeatureTaskRuntimePlanSpecOrigin.OPERATOR
    val input =
      inputFor(specPath.toString()).copy(
        explicitWorkflowId = candidate.workflowId,
        definition = SkeletonDefinition.PLAN,
        protectedSpecSha256 = (seed?.specSha256 ?: specHash(specPath)).takeIf { operatorAuthored },
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
        is FeatureTaskRuntimeRunReport.Decomposed -> {
          preparation.capture(report.workflowId, report.issueKey, repoRoot)
          StandalonePlanResult.Completed(
            report.workflowId,
            report.parentSpecPath,
            report.decompositionManifestPath,
            report.subtaskSpecPaths,
          )
        }
        is FeatureTaskRuntimeRunReport.Blocked -> StandalonePlanResult.Blocked(report.workflowId, report.blockedReason)
        is FeatureTaskRuntimeRunReport.Paused -> StandalonePlanResult.Blocked(report.workflowId, report.pauseReason)
        is FeatureTaskRuntimeRunReport.Completed ->
          StandalonePlanResult.Blocked(report.workflowId, "The plan finished without authoring a spec bundle.")
      }
    when (result) {
      is StandalonePlanResult.Completed -> statusSink?.settlePlan("completed", result.workflowId)
      is StandalonePlanResult.Blocked -> statusSink?.settleBlocked(result.reason, result.workflowId)
      is StandalonePlanResult.Refused -> statusSink?.settleBlocked(result.reason, result.workflowId)
    }
    return result
  }

  private fun recordedSeed(workflowId: String): FeatureTaskRuntimePlanSeed? =
    database.read { unitOfWork ->
      unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, workflowId)
        ?.artifacts
        ?.toMap()
        ?.let(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_SEED::value)
        ?.let(::decodePlanSeedFromArtifact)
    }

  private fun isKeyResolvedSpec(
    issueKey: String,
    specPath: Path,
    repoRoot: Path,
  ): Boolean =
    decompositionPlanner.existingParentSpec(repoRoot, issueKey)
      ?.let { resolved -> repoRoot.resolve(resolved).normalize() == repoRoot.resolve(specPath).normalize() } == true

  private fun specHash(specPath: Path): String = sha256HexUtf8(fileStore.readText(specPath))
}
