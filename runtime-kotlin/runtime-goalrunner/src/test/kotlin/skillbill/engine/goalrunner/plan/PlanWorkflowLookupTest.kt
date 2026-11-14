package skillbill.engine.goalrunner.plan

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import skillbill.application.testHarnessClock
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskContinuationLookupService
import skillbill.engine.featuretask.lifecycle.continuation.claimForTest
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.lifecycle.execution.workflowAgentAssignmentStoreForTests
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibilityStateCodec
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.testing.featureTaskRuntimeWireArtifactValidatorForTest
import skillbill.engine.goalrunner.planning.outcome.proseRecordPayload
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.validation.FeatureTaskRuntimeProviderLimitPause
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanWorkflowLookupTest {
  @ParameterizedTest
  @ValueSource(strings = ["released", "selected", "owned", "unadmitted", "running-unit", "unclassified-pause"])
  fun `released provider-paused plans resume while owned or ambiguous plans remain protected`(scenario: String) {
    val home = Files.createTempDirectory("released-plan-recovery")
    try {
      val database = phaseRunDatabase(home, testHarnessClock)
      val execution = ExecutionPlanAdmissionFixture(SkeletonDefinition.PLAN, database = database)
      seed(database, execution, scenario)
      val before = database.read { assertNotNull(it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, WORKFLOW)) }
      val ownership = database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW) }
      val generation = database.read { it.workflowStates.getLatestFeatureTaskRuntimeWorkerGeneration(WORKFLOW) }
      val service =
        FeatureTaskContinuationLookupService(
          database,
          testWorkflowSnapshotValidator,
          execution.compatibility,
          NoopRuntimeDiagnostics,
        )
      val lookup =
        PlanWorkflowLookup(
          service,
          FeatureTaskRuntimeCrashReconciler(
            database,
            NoopFeatureTaskRuntimeWorkerSupervisor,
            NoopRuntimeDiagnostics,
            testHarnessClock,
            execution.compatibility,
            execution.recoveryResolver(),
          ),
          database,
          PlanBundleFeasibilityStateCodec(featureTaskRuntimeWireArtifactValidatorForTest()),
          NoopRuntimeDiagnostics,
          workflowAgentAssignmentStoreForTests(database),
          execution.recoveryResolver(),
        )
      val identity = execution.identity(WORKFLOW)

      fun recover() =
        if (scenario == "selected") {
          lookup.lookupSelected(identity.normalizedIssueKey, listOf(identity.repositoryIdentity), WORKFLOW)
        } else {
          lookup.lookupAfterCrashRecovery(identity.normalizedIssueKey, identity.repositoryIdentity)
        }

      val found = recover()
      val after = database.read { assertNotNull(it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, WORKFLOW)) }
      if (scenario == "released" || scenario == "selected") {
        val candidate = assertIs<FeatureTaskContinuationLookupResult.Resumable>(found).candidate
        assertEquals(WORKFLOW, candidate.workflowId)
        assertEquals(WorkflowStatus.PENDING, after.workflowStatus)
        assertEquals(before.copy(workflowStatus = WorkflowStatus.PENDING, updatedAt = after.updatedAt), after)
        assertIs<FeatureTaskContinuationLookupResult.Resumable>(recover())
        assertEquals(after, database.read { it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, WORKFLOW) })
        assertNull(ownership)
        assertNotNull(service.claimForTest(candidate, execution.inputs))
      } else {
        assertIs<FeatureTaskContinuationLookupResult.AlreadyRunning>(found)
        assertEquals(before, after)
      }
      assertEquals(
        generation,
        database.read { it.workflowStates.getLatestFeatureTaskRuntimeWorkerGeneration(WORKFLOW) },
      )
      assertEquals(ownership, database.read { it.workflowStates.getFeatureTaskRuntimeWorkerOwnership(WORKFLOW) })
      assertEquals(identity, database.read { it.workflowStates.getFeatureTaskExecutionIdentity(WORKFLOW) })
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  private fun seed(
    database: DatabaseSessionFactory,
    execution: ExecutionPlanAdmissionFixture,
    scenario: String,
  ) {
    database.transaction { unit ->
      val states = unit.workflowStates
      execution.seed(states, WORKFLOW)
      val snapshot = assertNotNull(states.get(WorkflowFamily.TASK_RUNTIME, WORKFLOW))
      val records = records(scenario)
      states.save(
        WorkflowFamily.TASK_RUNTIME,
        snapshot.copy(
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "plan",
          artifacts =
            DurableWorkflowArtifacts.fromMap(
              snapshot.artifacts +
                DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
                  records.mapValues { it.value.asWorkflowArtifactEntry() },
                ),
            ),
        ),
      )
      if (scenario != "unadmitted") {
        val row = assertNotNull(states.getFeatureTaskWorkflow(WORKFLOW))
        assertTrue(
          states.acquireFeatureTaskRuntimeWorker(
            ownership(),
            row.updatedAt,
          ),
        )
        if (scenario != "owned") assertTrue(states.releaseFeatureTaskRuntimeWorker(WORKFLOW, OWNER, 1))
      }
    }
  }

  private fun records(scenario: String): Map<String, FeatureTaskRuntimePhaseRecord> {
    val completed =
      FeatureTaskRuntimePhaseRecord(
        "plan",
        WorkflowStepStatus.COMPLETED,
        1,
        testHarnessClock.instant(),
        resolvedAgentId = "claude",
        outputArtifact = proseRecordPayload("plan", "Keep the accepted parent plan."),
      )
    val paused =
      FeatureTaskRuntimePhaseRecord(
        "plan_subtasks",
        WorkflowStepStatus.PAUSED,
        1,
        testHarnessClock.instant(),
        resolvedAgentId = "claude",
        blockedReason =
          if (scenario == "unclassified-pause") {
            "Stopped without provider evidence."
          } else {
            FeatureTaskRuntimeProviderLimitPause.MARKER
          },
      )
    return mapOf(
      "plan" to completed,
      "plan_subtasks:4" to
        completed.copy(
          phaseId = "plan_subtasks:4",
          outputArtifact = proseRecordPayload("plan_subtasks", "Keep the completed install-script plan."),
        ),
      "plan_subtasks:1" to paused,
      "plan_subtasks:2" to
        if (scenario == "running-unit") {
          paused.copy(
            status = WorkflowStepStatus.RUNNING,
          )
        } else {
          paused
        },
    )
  }

  private fun ownership() =
    FeatureTaskRuntimeWorkerOwnership(
      workflowId = WORKFLOW,
      generation = 1,
      ownerToken = OWNER,
      hostIdentity = "host",
      bootIdentity = "boot",
      pid = 1234,
      processBirthToken = "birth",
      leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
      heartbeatAt = testHarnessClock.instant().toString(),
      expiresAt = testHarnessClock.instant().plusSeconds(300).toString(),
      phaseId = "plan_subtasks",
      phaseAttempt = 1,
    )

  private companion object {
    const val WORKFLOW = "wftr-released-provider-plan"
    const val OWNER = "released-provider-plan-owner"
  }
}
