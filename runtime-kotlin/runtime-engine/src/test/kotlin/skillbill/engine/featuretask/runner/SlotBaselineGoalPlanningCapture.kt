package skillbill.engine.featuretask.runner

import skillbill.application.testDecompositionManifestValidator
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.goalplanning.GoalPlanningPreparationCheckpoint
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalPlanningSweepPortsParams
import skillbill.engine.goalrunner.execution.core.testGoalPlanningSweepPorts
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStoreDefaults
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.planning.GoalPlanningLogService
import skillbill.engine.goalrunner.planning.attempt.DurableGoalPlanningAttemptRecorder
import skillbill.engine.goalrunner.planning.hydration.GoalChildPlanningHydrator
import skillbill.engine.goalrunner.planning.model.GoalChildPlanningHydration
import skillbill.engine.goalrunner.planning.model.GoalPlanningLog
import skillbill.engine.goalrunner.planning.model.GoalPlanningLogRequest
import skillbill.engine.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.engine.goalrunner.planning.sweep.CountingManifestFileStore
import skillbill.engine.goalrunner.planning.sweep.FakeInvariantsSource
import skillbill.engine.goalrunner.planning.sweep.SweepPlanningLauncher
import skillbill.engine.goalrunner.planning.sweep.fakeContextDiscovery
import skillbill.engine.goalrunner.planning.sweep.validPhaseOutcome
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.infrastructure.workflow.decomposition.FileSystemDecompositionManifestFileStore
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.diagnostics.model.RejectedOutputDiagnostic
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.NoopGoalPlanningPreparationEnvelopeValidator
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionDependency
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertIs

internal object SlotBaselineGoalPlanningCapture {
  private const val ISSUE_KEY = "SKILL-380"
  private const val FEATURE_BRANCH = "feat/SKILL-380-phase-slot-strategies"
  private const val BUILD_CHILD = "slot baseline build child"
  private const val VALIDATE_CHILD = "slot baseline validate child"

  fun encodedFiles(): Map<String, String> =
    inSeededWorkspace { repoRoot, home ->
      capture(prepare(repoRoot, home)).entries.associate { (fileName, value) ->
        "${SlotBaselinePaths.GOAL_PLANNING}/$fileName" to SlotBaselineJson.encode(value)
      }
    }

  fun preparedRows(table: String): List<Map<String, Any?>> =
    inSeededWorkspace { repoRoot, home ->
      SlotBaselineSqlite.rows(prepare(repoRoot, home).database.resolveDbPath(), table)
    }

  fun hydratedChild(subtaskId: Int): HydratedGoalChild =
    inSeededWorkspace { repoRoot, home -> hydrate(prepare(repoRoot, home), subtaskId) }

  private fun <T> inSeededWorkspace(block: (Path, Path) -> T): T {
    val repoRoot = SlotBaselineFullRunCapture.seededRepoRoot()
    val home = SlotBaselineNormalizer.newTempHome()
    try {
      return block(repoRoot, home)
    } finally {
      repoRoot.toFile().deleteRecursively()
      home.toFile().deleteRecursively()
    }
  }

  private fun prepare(
    repoRoot: Path,
    home: Path,
  ): PreparedGoalPlanning {
    val clock = SlotBaselineFullRunCapture.sqliteClock
    val database = SlotBaselineFullRunCapture.sqliteDatabase(home)
    val manifest = slotBaselineManifest()
    val manifestFileStore = seededManifestStore(repoRoot, manifest)
    val launcher = SweepPlanningLauncher { phase, _, _ -> validPhaseOutcome(phase) }
    val outcomeStore = SlotBaselinePlanningOutcomeStore()
    val sweep =
      testGoalPlanningSweepPorts(
        GoalPlanningSweepPortsParams(
          checkpoint =
            GoalPlanningPreparationCheckpoint(
              database = database,
              envelopeValidator = NoopGoalPlanningPreparationEnvelopeValidator,
            ),
          subtaskLauncher = launcher,
          invariantsSource = FakeInvariantsSource(),
          manifestFileStore = manifestFileStore,
          contextDiscovery = fakeContextDiscovery,
          planningAttemptRecorder = DurableGoalPlanningAttemptRecorder(outcomeStore, clock),
        ),
      )
    val state =
      GoalRunnerManifestState(
        parentWorkflowId = SlotBaselineFullRunCapture.PARENT_WORKFLOW_ID,
        dbPath = database.resolveDbPath().toString(),
        manifest = manifest,
      )
    val request = GoalRunnerRunRequest(issueKey = ISSUE_KEY, repoRoot = repoRoot, invokedAgentId = "claude")
    val outcome = assertIs<GoalPlanningSweepOutcome.PreparedAll>(sweep.prepare(state, request))
    return PreparedGoalPlanning(repoRoot, database, state, launcher, outcomeStore, outcome)
  }

  private fun hydrate(
    prepared: PreparedGoalPlanning,
    subtaskId: Int,
  ): HydratedGoalChild {
    val request = requireNotNull(prepared.outcome.hydrationFor(subtaskId))
    val setup =
      GoalRunnerChildWorkflowSetup(
        subtaskId = subtaskId,
        workflowId = "wfl-slot-baseline-child-$subtaskId",
        goalBranch = FEATURE_BRANCH,
        normalizedIssueKey = request.identity.normalizedIssueKey,
        repositoryIdentity = request.identity.repositoryIdentity,
        governedSpecPath = request.descriptor.governedSubSpecPath,
        reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        reviewPolicy = GoalRunnerReviewPolicy(CodeReviewExecutionMode.INLINE),
        planningHydration = request,
      )
    val hydrator = GoalChildPlanningHydrator(SlotBaselineFullRunCapture.sqliteClock)
    return prepared.database.read { unitOfWork ->
      val preparations = unitOfWork.goalPlanningPreparations
      HydratedGoalChild(
        hydration = hydrator.hydrate(unitOfWork, setup, request),
        preplanPayload = requireNotNull(preparations.findSharedPreplan(request.identity)).preplanPayload,
        planPayload =
          requireNotNull(
            preparations.findSubtaskPlan(request.identity, subtaskId, request.descriptor.governedSubSpecPath),
          ).planPayload,
      )
    }
  }

  private fun capture(prepared: PreparedGoalPlanning): Map<String, Any?> {
    val planningLog =
      GoalPlanningLogService(
        manifestStore = FixedGoalPlanningManifestStore(prepared.state),
        outcomeStore = prepared.outcomeStore,
        database = prepared.database,
        diagnosticMetadataValidator = AcceptingRejectedOutputDiagnosticMetadataValidator,
        clock = SlotBaselineFullRunCapture.sqliteClock,
      ).log(GoalPlanningLogRequest(issueKey = ISSUE_KEY, repoRoot = prepared.repoRoot))
    val databasePath = prepared.database.resolveDbPath()
    return mapOf(
      SlotBaselinePaths.PREPLAN_PROMPT to launchedPrompts(prepared.launcher, "preplan").values.single(),
      SlotBaselinePaths.PLAN_PROMPTS to launchedPrompts(prepared.launcher, "plan"),
      SlotBaselinePaths.SHARED_PREPLAN_CHECKPOINT to SlotBaselineSqlite.rows(databasePath, "goal_shared_preplans"),
      SlotBaselinePaths.PLAN_RECORDS to SlotBaselineSqlite.rows(databasePath, "goal_subtask_plans"),
      SlotBaselinePaths.PLANNING_ATTEMPT_LOG to
        prepared.outcomeStore.recordedEvents.map(GoalProgressEvent::toPersistenceWire),
      SlotBaselinePaths.PLANNING_LOG to planningLogPayload(planningLog),
    )
  }

  private fun launchedPrompts(
    launcher: SweepPlanningLauncher,
    phase: String,
  ): Map<String, String> =
    launcher.requests
      .filter { launcher.phaseOf(it) == phase }
      .associate { request ->
        (request.skillRunRequest.subtaskId ?: 0).toString() to request.skillRunRequest.promptOverride.orEmpty()
      }

  private fun slotBaselineManifest(): DecompositionManifest =
    DecompositionManifest(
      issueKey = ISSUE_KEY,
      featureName = "phase-slot-strategies",
      parentSpecPath = SlotBaselineFullRunCapture.SPEC_REFERENCE,
      baseBranch = "main",
      featureBranch = FEATURE_BRANCH,
      currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
      subtasks =
        listOf(
          DecompositionSubtask(
            id = 1,
            name = BUILD_CHILD,
            specPath = ".feature-specs/SKILL-380-phase-slot-strategies/spec_subtask_1.md",
            dependencies = emptyList(),
          ),
          DecompositionSubtask(
            id = 2,
            name = VALIDATE_CHILD,
            specPath = ".feature-specs/SKILL-380-phase-slot-strategies/spec_subtask_2.md",
            dependencies = listOf(DecompositionDependency(subtaskId = 1)),
          ),
        ),
    )

  private fun seededManifestStore(
    repoRoot: Path,
    manifest: DecompositionManifest,
  ): CountingManifestFileStore =
    CountingManifestFileStore().apply {
      replaceDecompositionManifest(
        FileSystemDecompositionManifestFileStore().encodeManifestYaml(
          testDecompositionManifestValidator.encodeManifestWireMap(manifest),
        ),
      )
      replaceSpec("spec.md", Files.readString(repoRoot.resolve(SlotBaselineFullRunCapture.SPEC_REFERENCE)))
      replaceSpec("spec_subtask_1.md", subtaskSpecBody(BUILD_CHILD))
      replaceSpec("spec_subtask_2.md", subtaskSpecBody(VALIDATE_CHILD))
    }

  private fun subtaskSpecBody(name: String): String =
    """
    # $name

    ## Acceptance Criteria

    1. The sweep produces a schema-valid plan for this sub-spec.

    ## Implementation Details

    Planned implementation details for $name.
    """.trimIndent()

  private fun planningLogPayload(log: GoalPlanningLog): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.ISSUE_KEY to log.issueKey,
      "parent_workflow_id" to log.parentWorkflowId,
      "total_attempts" to log.totalAttempts,
      "succeeded_attempts" to log.succeededAttempts,
      "failed_attempts" to log.failedAttempts,
      "first_attempt_failures" to log.firstAttemptFailures,
      "phases_observed" to log.phasesObserved,
      "total_planning_ms" to log.totalPlanningMs,
      "attempts" to
        log.attempts.map { attempt ->
          linkedMapOf(
            SharedPayloadKeys.PHASE_ID to attempt.phaseId,
            SharedPayloadKeys.SUBTASK_ID to attempt.subtaskId,
            "attempt" to attempt.attempt,
            "started_at" to attempt.startedAt?.toString(),
            "finished_at" to attempt.finishedAt?.toString(),
            "duration_ms" to attempt.durationMs,
            "timestamps_inconsistent" to attempt.timestampsInconsistent,
            "outcome" to attempt.outcome,
            "rule" to attempt.rule,
            "reason" to attempt.reason,
            "agent_id" to attempt.agentId,
            "rejected_output_identity" to attempt.rejectedOutputIdentity,
            "rejected_output_bytes" to attempt.rejectedOutputBytes,
          )
        },
    )
}

private class PreparedGoalPlanning(
  val repoRoot: Path,
  val database: DatabaseSessionFactory,
  val state: GoalRunnerManifestState,
  val launcher: SweepPlanningLauncher,
  val outcomeStore: SlotBaselinePlanningOutcomeStore,
  val outcome: GoalPlanningSweepOutcome.PreparedAll,
)

internal class HydratedGoalChild(
  val hydration: GoalChildPlanningHydration,
  val preplanPayload: String,
  val planPayload: String,
)

private class SlotBaselinePlanningOutcomeStore(
  private val backing: RecordingOutcomeStore = RecordingOutcomeStore(),
) : GoalRunnerWorkflowOutcomeStore by backing {
  val recordedEvents: List<GoalProgressEvent>
    get() = backing.progressEvents

  override fun progressEvents(workflowId: String): List<GoalProgressEvent> =
    backing.progressEvents.filter { event -> event.workflowId == workflowId }
}

private class FixedGoalPlanningManifestStore(
  private val state: GoalRunnerManifestState,
) : GoalRunnerManifestStoreDefaults() {
  override fun loadByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ): GoalRunnerManifestState? = state.takeIf { it.manifest.issueKey == issueKey }

  override fun save(state: GoalRunnerManifestState): GoalRunnerManifestState = state

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
  ): Boolean = true

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
  ): Boolean = true

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
  ): Boolean = true
}

private object AcceptingRejectedOutputDiagnosticMetadataValidator : RejectedOutputDiagnosticMetadataValidator {
  override fun validate(metadata: RejectedOutputDiagnostic) = Unit
}
