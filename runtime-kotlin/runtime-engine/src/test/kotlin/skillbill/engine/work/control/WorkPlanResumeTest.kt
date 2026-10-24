package skillbill.engine.work.control

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.work.EmptyManifestStore
import skillbill.engine.work.EmptyOutcomeStore
import skillbill.engine.work.control.model.WorkControlRejection
import skillbill.engine.work.control.model.WorkControlResult
import skillbill.engine.work.control.model.WorkRunTarget
import skillbill.engine.work.fixtureCheckedOutBranch
import skillbill.engine.work.ideStatusClock
import skillbill.engine.work.ideStatusObservedAt
import skillbill.engine.work.ideStatusService
import skillbill.engine.work.runtimeRecord
import skillbill.engine.work.testGoalRepositoryIdentity
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.idestatus.model.IdeStatusWorkflowRegistration
import skillbill.ports.idestatus.model.WorkDetailRequest
import skillbill.ports.idestatus.model.WorkLiveSection
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.work.WorkRunLauncher
import skillbill.ports.work.model.WorkRunLaunchKind
import skillbill.ports.work.model.WorkRunLaunchRequest
import skillbill.ports.work.model.WorkRunLaunchResult
import skillbill.ports.work.model.WorkRunStopRequest
import skillbill.ports.work.model.WorkRunStopResult
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkPlanResumeTest {
  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `a recovered plan resumes planning only while an owned plan refuses duplicate launch`(owned: Boolean) {
    establishTemporarySchemaReadiness("resume-interrupted-plan").use { fixture ->
      val root = fixture.tempDir.toRealPath()
      val database = sqliteSessionFactoryForTests(root, fixture.dbPath.toString(), emptyMap(), ideStatusClock)
      val identity = testGoalRepositoryIdentity(root)
      val workflowId = "wftr-interrupted-plan"
      seedPlan(database, identity, workflowId, owned)
      val launches = mutableListOf<WorkRunLaunchRequest>()
      val launcher =
        object : WorkRunLauncher {
          override fun start(request: WorkRunLaunchRequest): WorkRunLaunchResult {
            launches += request
            return WorkRunLaunchResult.Started(root.resolve("plan.log"))
          }

          override fun stop(request: WorkRunStopRequest) = WorkRunStopResult.NotRunning
        }
      val supervisor =
        object : FeatureTaskRuntimeWorkerSupervisor by NoopFeatureTaskRuntimeWorkerSupervisor {
          override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership): FeatureTaskRuntimeProcessInspection {
            return FeatureTaskRuntimeProcessInspection.ExactLive
          }
        }
      val status = ideStatusService(database)
      val goalStatus =
        testGoalRunnerStatusService(
          EmptyManifestStore,
          EmptyOutcomeStore,
          database = database,
          clock = ideStatusClock,
        )
      val actions =
        WorkRunActions(
          database,
          status,
          goalStatus,
          CheckedOutBranchSource(::fixtureCheckedOutBranch),
          supervisor,
          launcher,
          ideStatusClock,
        )
      val item = assertNotNull(status.detail(WorkDetailRequest(workflowId)).item)
      val options = database.read { actions.options(item, it) }
      assertEquals(!owned, options.canLaunch)
      assertEquals("Resume", options.launchLabel)
      if (!owned) {
        assertEquals(IdeStatusLifecycleState.PAUSED, item.snapshot.lifecycleState)
        assertEquals(WorkLiveSection.NEEDS_YOU, item.liveSection)
      }
      val target =
        WorkRunTarget(
          workflowId,
          identity,
          Path.of(item.repositoryRoot),
          item.snapshot.execution?.executionId,
          item.snapshot.execution?.statusRevision,
          item.snapshot.updatedAt,
          assertNotNull(item.databaseFileObservation),
        )

      val result = actions.launch(target, "claude")

      if (owned) {
        assertEquals(WorkControlResult.Rejected(WorkControlRejection.INELIGIBLE_TARGET), result)
        assertTrue(launches.isEmpty())
      } else {
        assertEquals(WorkControlResult.Accepted, result)
        assertEquals(
          WorkRunLaunchKind.RuntimeCommand(listOf("phase", "plan", "SKILL-148", "--agent", "claude")),
          launches.single().kind,
        )
      }
    }
  }

  private fun seedPlan(
    database: DatabaseSessionFactory,
    identity: String,
    workflowId: String,
    owned: Boolean,
  ) {
    val execution = ExecutionPlanAdmissionFixture(SkeletonDefinition.PLAN, identity, database = database)
    database.transaction { unit ->
      execution.seed(unit.workflowStates, workflowId, "SKILL-148")
      val paused = runtimeRecord(workflowId, ideStatusObservedAt.toString(), "plan_subtasks")
      unit.workflowStates.saveFeatureTaskWorkflow(
        assertNotNull(unit.workflowStates.getFeatureTaskWorkflow(workflowId)).copy(
          workflowStatus = "pending",
          currentStepId = paused.currentStepId,
          stepsJson = paused.stepsJson,
          updatedAt = paused.updatedAt,
        ),
        FeatureTaskWorkflowMode.RUNTIME,
      )
      unit.standalonePhaseStatuses.registerWorkflow(
        IdeStatusWorkflowRegistration(
          identity,
          "main",
          "SKILL-148",
          workflowId,
          "invocation",
          "execution",
          "active",
          ideStatusObservedAt.minusSeconds(60),
        ),
      )
      if (owned) {
        assertTrue(
          unit.workflowStates.acquireFeatureTaskRuntimeWorker(
            FeatureTaskRuntimeWorkerOwnership(
              workflowId, 1, "plan-owner-token-12345678", "host", "boot", 1234, "birth",
              FeatureTaskRuntimeWorkerLeaseState.ACTIVE, ideStatusObservedAt.toString(),
              ideStatusObservedAt.plusSeconds(30).toString(), "plan_subtasks", 1,
            ),
            unit.workflowStates.getFeatureTaskWorkflow(workflowId)?.updatedAt,
          ),
        )
      }
    }
  }
}
