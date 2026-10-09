package skillbill.engine.goalrunner.plan

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskContinuationLookupService
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationCandidate
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunInput
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
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
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSeed
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSpecOrigin
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
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
    if (manifestStore.readByIssueKey(issueKey, repoRoot) != null) {
      return StandalonePlanResult.Refused(
        "A decomposition manifest already exists for $issueKey; the runtime never overwrites a plan. " +
          "Run `skill-bill $issueKey` to execute it.",
      )
    }
    val intake = suppliedIntake?.trim()?.takeIf { it.isNotEmpty() && !it.equals(issueKey, ignoreCase = true) }
    val intakeSha256 = intake?.let(::sha256HexUtf8)
    val identity = repositories.repositoryIdentity(repoRoot)
    return when (val found = lookupPlanAfterCrashRecovery(issueKey, identity)) {
      is FeatureTaskContinuationLookupResult.Resumable -> resume(found.candidate, intakeSha256, repoRoot, inputFor)
      is FeatureTaskContinuationLookupResult.AlreadyRunning ->
        StandalonePlanResult.Refused(
          "Plan workflow '${found.candidate.workflowId}' for $issueKey is already running.",
          found.candidate.workflowId,
        )
      is FeatureTaskContinuationLookupResult.Ambiguous ->
        StandalonePlanResult.Refused(
          "Several plan workflows exist for $issueKey: ${found.candidates.joinToString { it.workflowId }}.",
        )
      is FeatureTaskContinuationLookupResult.NeedsIdentityRepair ->
        StandalonePlanResult.Refused(found.summary, found.workflowId)
      is FeatureTaskContinuationLookupResult.TerminalOnly,
      is FeatureTaskContinuationLookupResult.GoalContinuation,
      FeatureTaskContinuationLookupResult.NoMatch,
      -> open(issueKey, intake, intakeSha256, repoRoot, inputFor)
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
    intakeSha256: String?,
    repoRoot: Path,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
  ): StandalonePlanResult {
    val spec = intakePreparation.seedPlanSpec(issueKey, intake, repoRoot)
    val operatorAuthored = spec.specOrigin == FeatureTaskRuntimePlanSpecOrigin.OPERATOR
    if (operatorAuthored && !isKeyResolvedSpec(issueKey, spec.specPath, repoRoot)) {
      return StandalonePlanResult.Refused(
        "The supplied spec ${spec.specPath} is not the governed spec for $issueKey; settlement guards the spec " +
          "found under .feature-specs/$issueKey-*/. Move it there and plan again.",
      )
    }
    val seed = FeatureTaskRuntimePlanSeed(intakeSha256, spec.specOrigin, specHash(spec.specPath))
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
    return settle(entry.run(input) { error("Could not open a plan workflow: ${it.error}") })
  }

  private fun resume(
    candidate: FeatureTaskContinuationCandidate,
    intakeSha256: String?,
    repoRoot: Path,
    inputFor: (specPath: String) -> FeatureTaskRuntimeRunInput,
  ): StandalonePlanResult {
    val specPath = repoRoot.resolve(candidate.governedSpecPath)
    val seed = recordedSeed(candidate.workflowId)
    if (intakeSha256 != null && intakeSha256 != seed?.intakeSha256) {
      return StandalonePlanResult.Refused(
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
    return settle(entry.run(input) { error("Could not resume plan workflow '${candidate.workflowId}': ${it.error}") })
  }

  private fun settle(report: FeatureTaskRuntimeRunReport): StandalonePlanResult =
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
