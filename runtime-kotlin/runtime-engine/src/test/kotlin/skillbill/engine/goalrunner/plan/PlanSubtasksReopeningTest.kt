package skillbill.engine.goalrunner.plan

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.application.testHarnessClock
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskContinuationLookupService
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationRuntime
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationWriter
import skillbill.engine.featuretask.runner.RUNNER_TEST_PROJECT_STORE
import skillbill.engine.goalrunner.planning.outcome.proseRecordPayload
import skillbill.engine.goalrunner.planning.source.findImportedSubtaskPlanWorkflowIds
import skillbill.engine.goalrunner.planning.source.findLinkedSubtaskPlanWorkflowId
import skillbill.engine.goalrunner.planning.source.findReopenedSubtaskPlanWorkflowId
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.workflow.filesystem.FileSystemFeatureSpecPathResolver
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundleOrigin
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImport
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImportSource
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanSubtasksReopeningTest {
  @Test
  fun `reopened plan can be selected and claimed without changing older unfinished plans or accepted unit records`() {
    val home = Files.createTempDirectory("plan-subtasks-reopening")
    try {
      val database = phaseRunDatabase(home, testHarnessClock)
      val execution = ExecutionPlanAdmissionFixture(SkeletonDefinition.PLAN, database = database)
      val selectedId = "wftr-selected-plan"
      val olderId = "wftr-older-plan"
      val records = acceptedRecords()
      seedPlans(database, execution, selectedId, olderId, records)
      val olderBefore = database.read { it.workflowStates.getFeatureTaskWorkflow(olderId) }
      val reopening = reopening(database)
      val lookupService =
        FeatureTaskContinuationLookupService(
          database,
          testWorkflowSnapshotValidator,
          execution.compatibility,
          NoopRuntimeDiagnostics,
        )
      val lookup = lookup(database, execution, lookupService)

      assertTrue(reopening.reopenAtPlanSubtasks(selectedId))

      val identity = execution.identity(selectedId)
      assertEquals(
        selectedId,
        assertIs<FeatureTaskContinuationLookupResult.Resumable>(
          lookup.lookup(identity.normalizedIssueKey, identity.repositoryIdentity, null),
        ).candidate.workflowId,
      )
      val candidate =
        assertIs<FeatureTaskContinuationLookupResult.Resumable>(
          lookup.lookup(identity.normalizedIssueKey, identity.repositoryIdentity, selectedId),
        ).candidate
      assertEquals(selectedId, candidate.workflowId)
      assertEquals("plan_subtasks", candidate.currentStep)
      database.read { unit ->
        assertEquals(
          selectedId,
          unit.workflowStates.findReopenedSubtaskPlanWorkflowId(
            identity.normalizedIssueKey,
            identity.repositoryIdentity,
          ),
        )
        val reopened = requireNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedId))
        assertEquals(WorkflowStatus.PENDING, reopened.workflowStatus)
        assertNull(reopened.finishedAt)
        val retained = decodePhaseRecords(reopened.artifacts)
        assertEquals(WorkflowStepStatus.PENDING, retained["plan_subtasks"]?.status)
        records.filterKeys { it != "plan_subtasks" }.forEach { (phase, record) ->
          assertEquals(record, retained[phase])
        }
        assertEquals(olderBefore, unit.workflowStates.getFeatureTaskWorkflow(olderId))
        assertNull(unit.workflowStates.getFeatureTaskRuntimeWorkerOwnership(selectedId))
      }
      assertNotNull(lookupService.claim(candidate, execution.inputs))
      assertNull(lookupService.claim(candidate, execution.inputs))
      assertEquals(
        selectedId,
        database.read {
          it.workflowStates.findReopenedSubtaskPlanWorkflowId(identity.normalizedIssueKey, identity.repositoryIdentity)
        },
      )
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  @Test
  fun `spec drift replanning reopens a completed plan before it rewrites the plan bundle`() {
    val home = Files.createTempDirectory("plan-subtasks-rebase")
    try {
      val database = phaseRunDatabase(home, testHarnessClock)
      val execution = ExecutionPlanAdmissionFixture(SkeletonDefinition.PLAN, database = database)
      val selectedId = "wftr-drifted-plan"
      seedPlans(database, execution, selectedId, "wftr-older-plan", acceptedRecords())
      val recorder = FeatureTaskRuntimeDecomposeTerminalRecorder(database, testHarnessClock)
      val rebased =
        FeatureTaskRuntimePlanBundle(
          FeatureTaskRuntimePlanBundleOrigin.EXISTING,
          "drifted-spec",
          "manifest",
          emptyList(),
        )

      assertFailsWith<SkillBillRuntimeException> { recorder.recordPlanBundle(selectedId, rebased) }

      val identity = execution.identity(selectedId)
      assertTrue(
        reopening(database).reopenWithRebasedBundle(
          PlanSubtasksReplan(identity.normalizedIssueKey, selectedId, home, settledSubtaskIds = emptySet()),
        ),
      )
      assertTrue(recorder.recordPlanBundle(selectedId, rebased))
      database.read { unit ->
        val reopened = requireNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedId))
        assertEquals(WorkflowStatus.PENDING, reopened.workflowStatus)
      }
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  @Test
  fun `a reopened plan with worker ownership is excluded from recovery discovery`() {
    val home = Files.createTempDirectory("owned-plan-subtasks-reopening")
    try {
      val database = phaseRunDatabase(home, testHarnessClock)
      val execution = ExecutionPlanAdmissionFixture(SkeletonDefinition.PLAN, database = database)
      val selectedId = "wftr-owned-plan"
      seedPlans(database, execution, selectedId, "wftr-older-plan", acceptedRecords())
      assertTrue(reopening(database).reopenAtPlanSubtasks(selectedId))
      database.transaction { unit ->
        val before = requireNotNull(unit.workflowStates.getFeatureTaskWorkflow(selectedId))
        assertTrue(
          unit.workflowStates.acquireFeatureTaskRuntimeWorker(
            FeatureTaskRuntimeWorkerOwnership(
              workflowId = selectedId,
              generation = 1,
              ownerToken = "plan-owner-token-12345678",
              hostIdentity = "host",
              bootIdentity = "boot",
              pid = 1234,
              processBirthToken = "birth",
              leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
              heartbeatAt = testHarnessClock.instant().toString(),
              expiresAt = testHarnessClock.instant().plusSeconds(30).toString(),
              phaseId = "plan_subtasks",
              phaseAttempt = 1,
            ),
            before.updatedAt,
          ),
        )
      }
      val identity = execution.identity(selectedId)
      database.read { unit ->
        val before = unit.workflowStates.getFeatureTaskWorkflow(selectedId)
        assertNull(
          unit.workflowStates.findReopenedSubtaskPlanWorkflowId(
            identity.normalizedIssueKey,
            identity.repositoryIdentity,
          ),
        )
        assertEquals(before, unit.workflowStates.getFeatureTaskWorkflow(selectedId))
      }
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  @Test
  fun `an imported plan remains authoritative when an older plan is reopened more recently`() {
    val home = Files.createTempDirectory("imported-plan-resume")
    try {
      val database = phaseRunDatabase(home, testHarnessClock)
      val execution = ExecutionPlanAdmissionFixture(SkeletonDefinition.PLAN, database = database)
      val sourceId = "wftr-imported-source"
      val olderId = "wftr-obsolete-plan"
      val childId = "wftr-active-child"
      seedPlans(database, execution, sourceId, olderId, acceptedRecords())
      val executionIdentity = execution.identity(sourceId)
      val identity =
        GoalPlanningIdentity(
          "wftr-parent",
          executionIdentity.normalizedIssueKey,
          executionIdentity.repositoryIdentity,
        )
      seedImportedChild(database, execution, identity, sourceId, childId)
      database.transaction { unit ->
        val source = requireNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, sourceId))
        val older = requireNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, olderId))
        unit.workflowStates.saveAcceptedSnapshotMutation(
          WorkflowFamily.TASK_RUNTIME,
          source.copy(workflowStatus = WorkflowStatus.RUNNING, finishedAt = null),
        )
        val planning =
          listOf(
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS,
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE,
          ).associate { family -> family.entry(family.value(source.artifacts)) }
        unit.workflowStates.saveAcceptedSnapshotMutation(
          WorkflowFamily.TASK_RUNTIME,
          older.copy(artifacts = DurableWorkflowArtifacts.fromMap(older.artifacts + planning)),
        )
      }
      assertTrue(reopening(database).reopenAtPlanSubtasks(olderId))
      val subtask = DecompositionSubtask(3, "Current", "subtask-3.md", workflowId = childId)
      database.read { unit ->
        val before = unit.workflowStates.getFeatureTaskWorkflow(olderId)
        assertEquals(
          olderId,
          unit.workflowStates.findReopenedSubtaskPlanWorkflowId(
            identity.normalizedIssueKey,
            identity.repositoryIdentity,
          ),
        )
        assertEquals(sourceId, unit.workflowStates.findLinkedSubtaskPlanWorkflowId(identity, listOf(subtask)) { true })
        assertEquals(
          emptyList(),
          unit.workflowStates.findImportedSubtaskPlanWorkflowIds(
            identity.copy(parentGoalWorkflowId = "wftr-other-parent"),
            listOf(subtask),
          ),
        )
        assertEquals(before, unit.workflowStates.getFeatureTaskWorkflow(olderId))
      }
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  private fun seedImportedChild(
    database: DatabaseSessionFactory,
    execution: ExecutionPlanAdmissionFixture,
    identity: GoalPlanningIdentity,
    sourceId: String,
    childId: String,
  ) {
    val imported =
      FeatureTaskRuntimeGoalPlanningImport(
        parentGoalWorkflowId = identity.parentGoalWorkflowId,
        normalizedIssueKey = identity.normalizedIssueKey,
        repositoryIdentity = identity.repositoryIdentity,
        parentSpecHash = "parent", decompositionManifestHash = "manifest",
        planningContractId = "planning", planningContractVersion = "0.2",
        phaseOutputContractId = "phase-output", phaseOutputContractVersion = "0.7",
        subtaskId = 3, manifestOrder = 2, governedSubSpecPath = "subtask-3.md", subSpecHash = "spec",
        preplanPayloadSha256 = "preplan", planPayloadSha256 = "plan",
        source =
          FeatureTaskRuntimeGoalPlanningImportSource(
            FeatureTaskRuntimeGoalPlanningImportSource.PLAN_WORKFLOW,
            sourceId,
            "details",
          ),
      )
    database.transaction { unit ->
      execution.seed(unit.workflowStates, childId)
      val child = requireNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, childId))
      unit.workflowStates.save(
        WorkflowFamily.TASK_RUNTIME,
        child.copy(
          artifacts =
            DurableWorkflowArtifacts.fromMap(
              child.artifacts +
                DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.entry(
                  imported.asWorkflowArtifactEntry(),
                ),
            ),
        ),
      )
    }
  }

  private fun lookup(
    database: DatabaseSessionFactory,
    execution: ExecutionPlanAdmissionFixture,
    service: FeatureTaskContinuationLookupService,
  ): PlanWorkflowLookup =
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
    )

  private fun acceptedRecords(): Map<String, FeatureTaskRuntimePhaseRecord> =
    listOf("preplan", "plan", "plan_subtasks", "plan_subtasks:1", "plan_subtasks:3").associateWith { phase ->
      FeatureTaskRuntimePhaseRecord(
        phaseId = phase,
        status = WorkflowStepStatus.COMPLETED,
        attemptCount = 1,
        startedAt = testHarnessClock.instant(),
        resolvedAgentId = "runtime",
        outputArtifact = proseRecordPayload(phase.substringBefore(':'), "Retained planning for $phase."),
      )
    }

  private fun seedPlans(
    database: DatabaseSessionFactory,
    execution: ExecutionPlanAdmissionFixture,
    selectedId: String,
    olderId: String,
    records: Map<String, FeatureTaskRuntimePhaseRecord>,
  ) {
    database.transaction { unit ->
      execution.seed(unit.workflowStates, olderId)
      execution.seed(unit.workflowStates, selectedId)
      val selected = requireNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedId))
      unit.workflowStates.save(
        WorkflowFamily.TASK_RUNTIME,
        WorkflowEngine().updateRecord(
          WorkflowFamily.TASK_RUNTIME.definition,
          selected,
          WorkflowUpdateInput(
            workflowStatus = WorkflowStatus.COMPLETED,
            currentStepId = "plan_subtasks",
            stepUpdates = null,
            artifactsPatch =
              WorkflowArtifactPatch.from(
                mapOf(
                  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
                    records.mapValues { it.value.asWorkflowArtifactEntry() },
                  ),
                  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.entry(
                    FeatureTaskRuntimePlanBundle(
                      FeatureTaskRuntimePlanBundleOrigin.EXISTING,
                      "parent-spec-hash",
                      "manifest-hash",
                      emptyList(),
                    ).asWorkflowArtifactEntry(),
                  ),
                ),
              ),
            sessionId = selected.sessionId,
            terminalInstant = testHarnessClock.instant(),
          ),
        ),
      )
    }
  }

  private fun reopening(database: DatabaseSessionFactory): PlanSubtasksReopening =
    PlanSubtasksReopening(
      database,
      FeatureTaskRuntimeDecompositionPlanner(
        FeatureSpecPreparationRuntime(),
        FeatureSpecPreparationWriter(
          testDecompositionManifestValidator,
          TestDecompositionManifestStore,
          testDecompositionManifestWriter,
        ),
        FileSystemFeatureSpecPathResolver(RUNNER_TEST_PROJECT_STORE),
        RUNNER_TEST_PROJECT_STORE,
      ),
      FeatureTaskRuntimeDecomposeTerminalRecorder(database, testHarnessClock),
      testHarnessClock,
    )
}
