package skillbill.engine.featuretask.phase.planning

import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.realPlanningProjectionValidator
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.application.testHarnessClock
import skillbill.application.testProjectStore
import skillbill.application.workflow.ProjectCheckout
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.application.workflow.service.WorkflowService
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWrite
import skillbill.engine.featuretask.model.phase.PlanBundleFeasibilityStateWriteResult
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.runner.NoopWorkflowSnapshotValidator
import skillbill.engine.featuretask.slot.plan.PlanBundleFeasibilityBlockedRetry
import skillbill.engine.featuretask.testing.featureTaskRuntimeWireArtifactValidatorForTest
import skillbill.infrastructure.contracts.workflow.WorkflowStateSchemaValidator
import skillbill.model.RepositoryRoot
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestStore
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.goalworktree.InPlaceGoalWorktreeResources
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.model.planning.PLAN_BUNDLE_FEASIBILITY_OPERATOR_RETRY_REASON
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityIdentity
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunch
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchKind
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchOutcome
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityMode
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityRepositoryBaseline
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilitySubtaskIdentity
import skillbill.workflow.taskruntime.model.planning.blockPlanBundleFeasibilityLaunch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanBundleFeasibilityBlockedRetryTest {
  @Test
  fun `operator retry of a safety-blocked feasibility review opens a fresh review cycle`() {
    withFixture { fixture ->
      fixture.blockAdmittedReview()

      val retried =
        fixture.service.retryBlockedFeatureTaskRuntimePhase(
          WORKFLOW_ID,
          PHASE_ID,
          "Operator edits in the shared checkout caused the authority violation.",
          phaseArtifacts = PlanBundleFeasibilityBlockedRetry(fixture.codec),
        )

      assertIs<WorkflowUpdateResult.Ok>(retried)
      val reopened = assertNotNull(fixture.feasibility.loadPlanBundleFeasibility(WORKFLOW_ID))
      assertEquals(PlanBundleFeasibilityMode.REVIEW, reopened.mode)
      assertNull(reopened.activeLaunch)
      assertEquals(0, reopened.reviewRoundsStarted)
      assertTrue(reopened.currentCycleId.orEmpty().startsWith("$WORKFLOW_ID:plan_subtasks:retry-"))
      assertEquals(
        PlanBundleFeasibilityLaunchOutcome.BLOCKED,
        reopened.cycleHistory.single().settledLaunches.single().outcome,
      )
      assertEquals(PLAN_BUNDLE_FEASIBILITY_OPERATOR_RETRY_REASON, reopened.reopenings.single().reason)
      val record = assertNotNull(fixture.phases.loadPhaseRecords(WORKFLOW_ID)?.get(PHASE_ID))
      assertEquals(WorkflowStepStatus.PENDING, record.status)
      fixture.phases.recordRequiredPhaseStart(
        FeatureTaskRuntimePhaseStateRequest(WORKFLOW_ID, PHASE_ID, "running", 2, "codex", false),
      )
      val nextLaunch = fixture.reviewLaunch(reopened, attempt = 2)
      val admission =
        fixture.feasibility.writePlanBundleFeasibility(
          PlanBundleFeasibilityStateWrite(
            WORKFLOW_ID,
            PHASE_ID,
            2,
            reopened.revision,
            nextLaunch.transitionId,
            reopened.copy(revision = reopened.revision + 1, reviewRoundsStarted = 1, activeLaunch = nextLaunch),
          ),
        )
      assertIs<PlanBundleFeasibilityStateWriteResult.Applied>(admission, admission.toString())
    }
  }

  @Test
  fun `retry without the feasibility hook leaves the cycle blocked`() {
    withFixture { fixture ->
      fixture.blockAdmittedReview()

      val retried = fixture.service.retryBlockedFeatureTaskRuntimePhase(WORKFLOW_ID, PHASE_ID, "Retry without hook.")

      assertIs<WorkflowUpdateResult.Ok>(retried)
      assertEquals(
        PlanBundleFeasibilityMode.BLOCKED,
        fixture.feasibility.loadPlanBundleFeasibility(WORKFLOW_ID)?.mode,
      )
    }
  }

  private fun withFixture(block: (Fixture) -> Unit) {
    val home = Files.createTempDirectory("feasibility-blocked-retry")
    try {
      block(Fixture(home, phaseRunDatabase(home, testHarnessClock)))
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  private class Fixture(
    home: Path,
    database: DatabaseSessionFactory,
  ) {
    private val validator = featureTaskRuntimeWireArtifactValidatorForTest()
    val codec = PlanBundleFeasibilityStateCodec(validator)
    val phases =
      featureTaskRuntimePhaseRecorder(
        database,
        NoopWorkflowSnapshotValidator,
        validator,
        validator,
        testHarnessClock,
        NoopRuntimeDiagnostics,
      )
    val feasibility =
      FeatureTaskRuntimeDecomposeTerminalRecorder(database, testHarnessClock, codec, NoopRuntimeDiagnostics)
    val service =
      WorkflowService(
        database = database,
        gitOperations = NoopWorkflowGitOperations,
        decompositionManifestStore = UnavailableDecompositionManifestStore,
        workflowSnapshotValidator = WorkflowStateSchemaValidator(),
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestWriter = testDecompositionManifestWriter,
        repositoryRoot = RepositoryRoot(home),
        goalObservabilityEventValidator = realPlanningProjectionValidator,
        runtimeDiagnostics = NoopRuntimeDiagnostics,
        clock = testHarnessClock,
        projectCheckout =
          ProjectCheckout(
            TestRepositoryEnclosingRoot,
            database,
            testProjectStore,
            InPlaceGoalWorktreeResources,
          ),
      )

    fun blockAdmittedReview() {
      phases.openTestWorkflow(WORKFLOW_ID, "session")
      phases.recordRequiredPhaseStart(
        FeatureTaskRuntimePhaseStateRequest(WORKFLOW_ID, PHASE_ID, "running", 1, "codex", false),
      )
      val initial = initialState()
      assertIs<PlanBundleFeasibilityStateWriteResult.Applied>(
        feasibility.writePlanBundleFeasibility(
          PlanBundleFeasibilityStateWrite(WORKFLOW_ID, PHASE_ID, 1, null, null, initial),
        ),
      )
      val launch = reviewLaunch(initial)
      val launched = initial.copy(revision = 1, reviewRoundsStarted = 1, activeLaunch = launch)
      assertIs<PlanBundleFeasibilityStateWriteResult.Applied>(
        feasibility.writePlanBundleFeasibility(write(0, launch.transitionId, launched)),
      )
      val reason = "Feasibility changed repository paths outside its admitted authority: src/Operator.kt."
      val blocked = assertNotNull(blockPlanBundleFeasibilityLaunch(launched, launch.transitionId, reason))
      assertIs<PlanBundleFeasibilityStateWriteResult.Applied>(
        feasibility.writePlanBundleFeasibility(write(1, launch.transitionId, blocked)),
      )
      phases.recordPhaseState(
        FeatureTaskRuntimePhaseStateRequest(
          WORKFLOW_ID,
          PHASE_ID,
          "blocked",
          1,
          "codex",
          finished = true,
          blockedReason = "Bundle feasibility is blocked.",
        ),
      )
    }

    private fun write(
      revision: Int,
      transitionId: String,
      state: FeatureTaskRuntimePlanBundleFeasibilityState,
    ) = PlanBundleFeasibilityStateWrite(WORKFLOW_ID, PHASE_ID, 1, revision, transitionId, state)

    fun reviewLaunch(
      state: FeatureTaskRuntimePlanBundleFeasibilityState,
      attempt: Int = 1,
    ): PlanBundleFeasibilityLaunch {
      val cycleId = checkNotNull(state.currentCycleId)
      val sequence = state.reviewRoundsStarted + state.repairLaunchesStarted + 1
      return PlanBundleFeasibilityLaunch(
        "$WORKFLOW_ID:$cycleId:$sequence",
        PHASE_ID,
        attempt,
        PlanBundleFeasibilityLaunchKind.REVIEW,
        state.identity,
        null,
        null,
        cycleId = cycleId,
        sequence = sequence,
        repositoryBaseline = PlanBundleFeasibilityRepositoryBaseline("head", emptyList(), emptyMap()),
      )
    }

    private fun initialState() =
      FeatureTaskRuntimePlanBundleFeasibilityState(
        revision = 0,
        mode = PlanBundleFeasibilityMode.REVIEW,
        identity =
          PlanBundleFeasibilityIdentity(
            parentSha256 = "a".repeat(64),
            manifestSha256 = "c".repeat(64),
            immutableManifestSha256 = "d".repeat(64),
            subtasks =
              listOf(
                PlanBundleFeasibilitySubtaskIdentity(1, "spec.md", "e".repeat(64), listOf("1.1"), emptyList()),
              ),
          ),
        acceptedLines = emptyList(),
        conflicts = emptyList(),
        changedSubtaskIds = emptyList(),
        requiredRefs = listOf("1.1"),
        carriedRefs = emptyList(),
        reviewRoundsStarted = 0,
        repairLaunchesStarted = 0,
        activeLaunch = null,
        settledLaunches = emptyList(),
        lastReviewValue = null,
        approval = null,
        legacyOrigin = false,
        currentCycleId = "cycle-1",
      )
  }

  private companion object {
    const val WORKFLOW_ID = "wf-blocked-feasibility-retry"
    const val PHASE_ID = "plan_subtasks"
  }
}
