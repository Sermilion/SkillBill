package skillbill.engine.featuretask.lifecycle.core

import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.testHarnessClock
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.goalplanning.GoalPlanningMigrationAdmission
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.execution.core.GoalRunnerRunPreparation
import skillbill.engine.goalrunner.execution.core.testSpecDriftRecovery
import skillbill.engine.goalrunner.manifest.TestNoopGoalPlanningManifestStore
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.WorkflowGoalRunnerBlockWrites
import skillbill.engine.goalrunner.persist.planningMigrationForTest
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.phaseRecordsFromWorkflowArtifacts
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockedWorkerCrashRecoveryTest {
  private lateinit var execution: ExecutionPlanAdmissionFixture

  @Test
  fun `timed out blocked child releases dead expired worker before operator resume without losing evidence`() =
    withDatabase { database ->
      seedBlockedChild(database, WORKFLOW_ID)
      seedBlockedChild(database, OTHER_WORKFLOW_ID)
      val before = database.read { requireNotNull(it.workflowStates.getFeatureTaskWorkflow(WORKFLOW_ID)) }
      val otherLease = database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(OTHER_WORKFLOW_ID) }
      val reconciler = reconciler(database, FeatureTaskRuntimeProcessInspection.NotRunning)

      val preparation =
        GoalRunnerRunPreparation(
          TestNoopGoalPlanningManifestStore,
          TestRepositoryEnclosingRoot,
          execution.recoveryResolver(),
          reconciler,
          testSpecDriftRecovery(TestNoopGoalPlanningManifestStore, RecordingOutcomeStore()),
          GoalPlanningMigrationAdmission(database, planningMigrationForTest(), NoopRuntimeDiagnostics),
        )
      val manifest =
        DecompositionManifest(
          issueKey = "SKILL-384",
          featureName = "blocked-worker-recovery",
          parentSpecPath = ".feature-specs/SKILL-384/spec.md",
          baseBranch = "base",
          featureBranch = "feat/SKILL-384",
          currentSubtaskIntent = CurrentSubtaskIntent(1, "blocked"),
          subtasks =
            listOf(DecompositionSubtask(1, "repair", ".feature-specs/SKILL-384/spec.md", workflowId = WORKFLOW_ID)),
        )
      val admission =
        preparation.existingChildExecutionPlanAdmission(
          GoalRunnerManifestState("wftr-parent", "", manifest),
          GoalRunnerRunRequest("SKILL-384", Path.of("/tmp/admission-repository"), "codex"),
        )
      assertEquals(WORKFLOW_ID, assertNotNull(admission).workflowId)
      assertEquals(0, reconciler.reconcile(WORKFLOW_ID).reconciledCount)
      database.read { unit ->
        val after = requireNotNull(unit.workflowStates.getFeatureTaskWorkflow(WORKFLOW_ID))
        assertEquals(WorkflowStatus.BLOCKED.wireValue, after.workflowStatus)
        assertEquals(before.currentStepId, after.currentStepId)
        assertEquals(before.stepsJson, after.stepsJson)
        assertEquals(before.artifactsJson, after.artifactsJson)
        assertEquals(execution.identity(WORKFLOW_ID), unit.workflowStates.getFeatureTaskExecutionIdentity(WORKFLOW_ID))
        assertNull(unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID))
        assertEquals(otherLease, unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(OTHER_WORKFLOW_ID))
      }

      assertEquals(1, reconciler.reconcile().reconciledCount)
      assertEquals(
        WorkflowStatus.BLOCKED.wireValue,
        database.read { it.workflowStates.getFeatureTaskWorkflow(OTHER_WORKFLOW_ID)?.workflowStatus },
      )
      val writes = WorkflowGoalRunnerBlockWrites(WorkflowEngine(), testHarnessClock)
      assertTrue(
        database.transaction { unit ->
          writes.reopenBlockedPhaseForOperatorResume(
            unit,
            PHASE_ID,
            "operator resume after wall-clock timeout",
            execution.identity(WORKFLOW_ID),
            ValidatedFeatureTaskRuntimeExecutionPlan.read(execution.encoded, execution.validator),
          )
        },
      )
      database.read { unit ->
        val resumed = requireNotNull(unit.workflowStates.getFeatureTaskWorkflow(WORKFLOW_ID)).toSnapshot()
        assertEquals(WorkflowStatus.RUNNING, resumed.workflowStatus)
        assertEquals(
          WorkflowStepStatus.PENDING,
          phaseRecordsFromWorkflowArtifacts(resumed.artifacts).getValue(PHASE_ID).status,
        )
      }
    }

  @Test
  fun `blocked child recovery preserves live unexpired reserved or unconfirmed workers`() =
    withDatabase { database ->
      listOf(
        FeatureTaskRuntimeProcessInspection.ExactLive,
        FeatureTaskRuntimeProcessInspection.OwnershipMismatch("pid reuse"),
        FeatureTaskRuntimeProcessInspection.Unsupported("process probe unavailable"),
        FeatureTaskRuntimeProcessInspection.NotRunning,
      ).forEachIndexed { index, inspection ->
        val workflowId = "$WORKFLOW_ID-$index"
        seedBlockedChild(database, workflowId, expired = index != 3)
        val before = database.read { requireNotNull(it.workflowStates.getFeatureTaskWorkflow(workflowId)) }
        val lease = database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId) }

        assertEquals(0, reconciler(database, inspection).reconcile(workflowId).reconciledCount)
        database.read { unit ->
          assertEquals(before, unit.workflowStates.getFeatureTaskWorkflow(workflowId))
          assertEquals(lease, unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(workflowId))
        }
      }
      seedBlockedChild(database, WORKFLOW_ID, leaseState = FeatureTaskRuntimeWorkerLeaseState.TAKEOVER_RESERVED)
      val reserved = database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID) }
      assertEquals(
        0,
        reconciler(database, FeatureTaskRuntimeProcessInspection.NotRunning).reconcile(WORKFLOW_ID).reconciledCount,
      )
      assertEquals(reserved, database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW_ID) })
    }

  private fun seedBlockedChild(
    database: DatabaseSessionFactory,
    workflowId: String,
    expired: Boolean = true,
    leaseState: FeatureTaskRuntimeWorkerLeaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
  ) {
    database.transaction { unit ->
      execution.seed(unit.workflowStates, workflowId)
      val row = requireNotNull(unit.workflowStates.getFeatureTaskWorkflow(workflowId))
      val retainedPhase =
        FeatureTaskRuntimePhaseRecord(
          phaseId = PHASE_ID,
          status = WorkflowStepStatus.RUNNING,
          attemptCount = 2,
          startedAt = "2000-01-01T00:00:00Z",
          resolvedAgentId = "codex",
          outputArtifact = "retained repair evidence",
        )
      val artifacts =
        row.toSnapshot().artifacts +
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
            mapOf(PHASE_ID to retainedPhase.asWorkflowArtifactEntry()),
          )
      unit.workflowStates.saveFeatureTaskWorkflow(
        row.copy(
          workflowStatus = WorkflowStatus.BLOCKED.wireValue,
          currentStepId = PHASE_ID,
          artifactsJson = JsonCodec.mapToJsonString(artifacts),
        ),
        FeatureTaskWorkflowMode.RUNTIME,
      )
      val updated = requireNotNull(unit.workflowStates.getFeatureTaskWorkflow(workflowId))
      assertTrue(
        unit.workflowStates.acquireFeatureTaskRuntimeWorker(
          FeatureTaskRuntimeWorkerOwnership(
            workflowId = workflowId,
            generation = 1,
            ownerToken = "$workflowId-owner",
            hostIdentity = "host",
            bootIdentity = "boot",
            pid = 4242,
            processBirthToken = "birth-4242",
            leaseState = leaseState,
            heartbeatAt = "2000-01-01T00:00:00Z",
            expiresAt = if (expired) "2000-01-01T00:00:30Z" else "2999-01-01T00:00:00Z",
            phaseId = PHASE_ID,
            phaseAttempt = 2,
          ),
          updated.updatedAt,
        ),
      )
      unit.workflowStates.saveFeatureTaskWorkflow(updated, FeatureTaskWorkflowMode.RUNTIME)
    }
  }

  private fun reconciler(
    database: DatabaseSessionFactory,
    inspection: FeatureTaskRuntimeProcessInspection,
  ): FeatureTaskRuntimeCrashReconciler =
    FeatureTaskRuntimeCrashReconciler(
      database,
      object : FeatureTaskRuntimeWorkerSupervisor by NoopFeatureTaskRuntimeWorkerSupervisor {
        override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = inspection
      },
      NoopRuntimeDiagnostics,
      testHarnessClock,
      execution.compatibility,
      execution.recoveryResolver(),
    )

  private fun withDatabase(block: (DatabaseSessionFactory) -> Unit) {
    val root = Files.createTempDirectory("blocked-worker-recovery")
    try {
      val database = sqliteSessionFactoryForTests(root, root.resolve("runtime.db").toString(), emptyMap())
      execution = ExecutionPlanAdmissionFixture(SkeletonDefinition.GOAL_CHILD, database = database)
      block(database)
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private companion object {
    const val WORKFLOW_ID = "wftr-blocked-timeout"
    const val OTHER_WORKFLOW_ID = "wftr-blocked-other"
    const val PHASE_ID = "audit_implement_fix"
  }
}
