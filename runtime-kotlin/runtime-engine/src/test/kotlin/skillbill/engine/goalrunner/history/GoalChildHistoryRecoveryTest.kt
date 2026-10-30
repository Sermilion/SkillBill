package skillbill.engine.goalrunner.history

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.testHarnessClock
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.testing.featureTaskRuntimeWireArtifactValidatorForTest
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerManifestStore
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerOutcomeStore
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalChildHistoryAcceptanceRestoreResult
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.persist.taskRuntimeWorkflowRecord
import skillbill.error.core.SkillBillRuntimeException
import skillbill.goalrunner.model.history.GoalChildHistoryDraft
import skillbill.goalrunner.model.history.GoalChildHistoryDraftDisposition
import skillbill.goalrunner.model.history.GoalChildHistoryDraftSaveResult
import skillbill.infrastructure.contracts.workflow.featuretask.FeatureTaskRuntimeExecutionPlanSchemaValidator
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.infrastructure.sqlite.workflow.artifact.SqliteWorkflowArtifactWireEncoder
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.resetManifest
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalChildHistoryRecoveryTest {
  @Test
  fun `hard reset preserves draft evidence across reopen and restores only the discarded acceptance`() {
    val fixture = RecoveryFixture(SkeletonDefinition.GOAL_CHILD_REVISION_5)
    fixture.saveDraft()
    val reset = fixture.reset()
    assertNull(fixture.database.read { it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, "child") })
    val reopened = fixture.reopen()
    val outcomes = testWorkflowGoalRunnerOutcomeStore(reopened)
    val accepted = outcomes.goalChildHistoryDrafts("parent").single()
    assertIs<GoalChildHistoryDraftSaveResult.Saved>(outcomes.saveGoalChildHistoryDraft(accepted))
    assertTrue(outcomes.hasRequiredGoalChildHistoryDraft("parent", 1, "child"))
    assertFalse(outcomes.hasRequiredGoalChildHistoryDraft("parent", 2, "child"))
    val store = fixture.store(reopened)
    assertIs<GoalChildHistoryAcceptanceRestoreResult.Refused>(
      store.restoreOutOfBandAcceptance(reset, fixture.acceptance.copy(commitSha = "other")),
    )
    val restored =
      assertIs<GoalChildHistoryAcceptanceRestoreResult.Restored>(
        store.restoreOutOfBandAcceptance(reset, fixture.acceptance),
      )
    assertEquals("complete", restored.state.manifest.subtasks.single().status)
    assertNull(restored.state.manifest.subtasks.single().workflowId)
    assertEquals("child", outcomes.restoredGoalChildWorkflowId("parent", 1))
    assertIs<GoalChildHistoryAcceptanceRestoreResult.Restored>(
      store.restoreOutOfBandAcceptance(
        restored.state,
        fixture.acceptance,
      ),
    )
    val resetAgain = store.saveHardReset(restored.state.copy(manifest = restored.state.manifest.resetManifest(true)))
    assertIs<GoalChildHistoryAcceptanceRestoreResult.Restored>(
      store.restoreOutOfBandAcceptance(
        resetAgain,
        fixture.acceptance,
      ),
    )
  }

  @Test
  fun `restoration replay refuses a completed projection whose accepted draft disappeared`() {
    val fixture = RecoveryFixture(SkeletonDefinition.GOAL_CHILD_REVISION_5)
    fixture.saveDraft()
    val restored =
      assertIs<GoalChildHistoryAcceptanceRestoreResult.Restored>(
        fixture.store().restoreOutOfBandAcceptance(fixture.reset(), fixture.acceptance),
      )
    fixture.database.transaction { unit ->
      val parent = unit.workflowStates.getFeatureTaskWorkflow("parent")!!
      unit.workflowStates.saveFeatureTaskWorkflow(
        parent.copy(
          artifactsJson =
            JsonCodec.mapToJsonString(
              JsonCodec.anyToStringAnyMap(
                JsonCodec.parseValue(parent.artifactsJson),
              ).orEmpty() - DurableWorkflowArtifactFamily.GOAL_CHILD_HISTORY_DRAFTS.label(),
            ),
        ),
        FeatureTaskWorkflowMode.RUNTIME,
      )
    }
    assertIs<GoalChildHistoryAcceptanceRestoreResult.Refused>(
      fixture.store().restoreOutOfBandAcceptance(restored.state, fixture.acceptance),
    )
  }

  @Test
  fun `completion boundary rejects missing history and a stale sibling child before changing the manifest`() {
    val fixture = RecoveryFixture(SkeletonDefinition.GOAL_CHILD_REVISION_5, "in_progress")
    val completed =
      fixture.state.copy(
        manifest =
          fixture.state.manifest.copy(
            subtasks = fixture.state.manifest.subtasks.map { it.copy(status = "complete") },
          ),
      )
    assertFailsWith<SkillBillRuntimeException> {
      fixture.store().saveCompletedSubtaskAtBoundary(completed, 1)
    }
    assertEquals(
      "in_progress",
      fixture.database.read {
        it.workflowStates.get(
          WorkflowFamily.TASK_RUNTIME,
          "parent",
        )!!.decompositionRuntime()!!.subtasks.single().status
      },
    )
    fixture.saveDraft()
    val stale =
      completed.copy(
        manifest =
          fixture.state.manifest.copy(
            subtasks = fixture.state.manifest.subtasks.map { it.copy(workflowId = "sibling") },
          ),
      )
    assertFailsWith<SkillBillRuntimeException> { fixture.store().saveCompletedSubtaskAtBoundary(stale, 1) }
    assertEquals(
      "complete",
      fixture.store().saveCompletedSubtaskAtBoundary(
        completed,
        1,
      ).state.manifest.subtasks.single().status,
    )
  }

  @Test
  fun `restoration rolls back acceptance evidence and manifest together on transaction failure`() {
    val fixture = RecoveryFixture(SkeletonDefinition.GOAL_CHILD_REVISION_5)
    fixture.saveDraft()
    val reset = fixture.reset()
    val failing =
      object : DatabaseSessionFactory by fixture.database {
        override fun <T> transaction(block: (UnitOfWork) -> T): T =
          fixture.database.transaction { unit ->
            block(unit)
            error("injected transaction failure")
          }
      }
    assertFailsWith<IllegalStateException> {
      fixture.store(failing).restoreOutOfBandAcceptance(
        reset,
        fixture.acceptance,
      )
    }
    assertTrue(fixture.store().outOfBandAcceptances("parent").isEmpty())
    assertEquals(
      "pending",
      fixture.database.read {
        it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, "parent")!!.let { snapshot ->
          snapshot.decompositionRuntime()!!
        }.subtasks.single().status
      },
    )
    assertNull(testWorkflowGoalRunnerOutcomeStore(fixture.database).restoredGoalChildWorkflowId("parent", 1))
    assertIs<GoalChildHistoryAcceptanceRestoreResult.Restored>(
      fixture.store().restoreOutOfBandAcceptance(
        reset,
        fixture.acceptance,
      ),
    )
  }

  @Test
  fun `legacy skips survive deletion without being converted to drafts and retired evidence cannot restore`() {
    for (definition in listOf(
      SkeletonDefinition.GOAL_CHILD_REVISION_2,
      SkeletonDefinition.GOAL_CHILD_REVISION_3,
      SkeletonDefinition.GOAL_CHILD,
    )) {
      val fixture = RecoveryFixture(definition)
      fixture.saveLegacySkip()
      val reset = fixture.reset()
      val reopened = fixture.reopen()
      val outcomes = testWorkflowGoalRunnerOutcomeStore(reopened)
      assertTrue(outcomes.hasRequiredGoalChildHistoryDraft("parent", 1, "child"))
      assertTrue(outcomes.goalChildHistoryDrafts("parent").isEmpty())
      reopened.transaction { fixture.evidence.retire(it, "parent", 1) }
      assertIs<GoalChildHistoryAcceptanceRestoreResult.Refused>(
        fixture.store(reopened).restoreOutOfBandAcceptance(
          reset,
          fixture.acceptance,
        ),
      )
      assertTrue(fixture.store(reopened).outOfBandAcceptances("parent").isEmpty())
    }
  }

  @Test
  fun `legacy skipped step survives child deletion even without a phase output record`() {
    val fixture = RecoveryFixture(SkeletonDefinition.GOAL_CHILD_REVISION_2)
    fixture.database.transaction { unit ->
      val child = unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, "child")!!
      val updated =
        WorkflowEngine().updateRecord(
          WorkflowFamily.TASK_RUNTIME.definition,
          child,
          WorkflowUpdateInput(
            workflowStatus = child.workflowStatus,
            artifactsPatch = null,
            currentStepId = child.currentStepId,
            stepUpdates =
              WorkflowStepUpdates.from(
                listOf(mapOf("step_id" to "write_history", "status" to "skipped", "attempt_count" to 1)),
              ),
            sessionId = child.sessionId,
          ),
        )
      unit.workflowStates.save(WorkflowFamily.TASK_RUNTIME, updated)
    }
    val reset = fixture.reset()
    assertIs<GoalChildHistoryAcceptanceRestoreResult.Restored>(
      fixture.store(fixture.reopen()).restoreOutOfBandAcceptance(reset, fixture.acceptance),
    )
    assertTrue(testWorkflowGoalRunnerOutcomeStore(fixture.reopen()).goalChildHistoryDrafts("parent").isEmpty())
  }

  @Test
  fun `missing draft and old reset without evidence cannot manufacture completion`() {
    val fixture = RecoveryFixture(SkeletonDefinition.GOAL_CHILD_REVISION_5)
    val reset = fixture.reset()
    assertIs<GoalChildHistoryAcceptanceRestoreResult.Refused>(
      fixture.store().restoreOutOfBandAcceptance(
        reset,
        fixture.acceptance,
      ),
    )
    assertFalse(
      testWorkflowGoalRunnerOutcomeStore(fixture.database).hasRequiredGoalChildHistoryDraft(
        "parent",
        1,
        "child",
      ),
    )
    fixture.database.transaction { unit ->
      val parent = unit.workflowStates.getFeatureTaskWorkflow("parent")!!
      unit.workflowStates.saveFeatureTaskWorkflow(
        parent.copy(
          artifactsJson =
            JsonCodec.mapToJsonString(
              JsonCodec.anyToStringAnyMap(
                JsonCodec.parseValue(parent.artifactsJson),
              ).orEmpty() - DurableWorkflowArtifactFamily.GOAL_CHILD_HISTORY_EVIDENCE.label(),
            ),
        ),
        FeatureTaskWorkflowMode.RUNTIME,
      )
    }
    assertIs<GoalChildHistoryAcceptanceRestoreResult.Refused>(
      fixture.store().restoreOutOfBandAcceptance(
        reset,
        fixture.acceptance,
      ),
    )
  }

  @Test
  fun `malformed preserved evidence fails loudly without accepting the commit`() {
    val fixture = RecoveryFixture(SkeletonDefinition.GOAL_CHILD_REVISION_5)
    fixture.saveDraft()
    val reset = fixture.reset()
    fixture.database.transaction { unit ->
      val parent = unit.workflowStates.getFeatureTaskWorkflow("parent")!!
      unit.workflowStates.saveFeatureTaskWorkflow(
        parent.copy(
          artifactsJson =
            JsonCodec.mapToJsonString(
              JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(parent.artifactsJson)).orEmpty() +
                DurableWorkflowArtifactFamily.GOAL_CHILD_HISTORY_EVIDENCE.entry(
                  listOf(mapOf("contract_version" to "wrong")),
                ),
            ),
        ),
        FeatureTaskWorkflowMode.RUNTIME,
      )
    }
    assertFailsWith<SkillBillRuntimeException> {
      fixture.store().restoreOutOfBandAcceptance(
        reset,
        fixture.acceptance,
      )
    }
    assertTrue(fixture.store().outOfBandAcceptances("parent").isEmpty())
  }
}

private class RecoveryFixture(definition: SkeletonDefinition, initialStatus: String = "complete") {
  private val root = Files.createTempDirectory("history-recovery")
  val database = reopen()
  val acceptance =
    GoalRunnerOutOfBandAcceptance(
      1,
      "a".repeat(40),
      "Previously accepted child",
      "2026-10-26T10:00:00Z",
    )
  val evidence =
    GoalChildHistoryEvidenceStore(
      testWorkflowSnapshotValidator,
      featureTaskRuntimeWireArtifactValidatorForTest(),
      FeatureTaskRuntimeExecutionPlanSchemaValidator(),
      SqliteWorkflowArtifactWireEncoder(),
    )
  val state: GoalRunnerManifestState

  init {
    database.transaction { unit ->
      unit.workflowStates.saveFeatureTaskWorkflow(
        taskRuntimeWorkflowRecord("parent"),
        FeatureTaskWorkflowMode.RUNTIME,
      )
      val execution = ExecutionPlanAdmissionFixture(definition)
      val child = taskRuntimeWorkflowRecord("child", "parent")
      unit.workflowStates.saveFeatureTaskWorkflow(
        child.copy(
          issueKey = "SKILL-446",
          artifactsJson =
            JsonCodec.mapToJsonString(
              JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(child.artifactsJson)).orEmpty() +
                DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.entry(execution.descriptor()),
            ),
        ),
        FeatureTaskWorkflowMode.RUNTIME,
      )
      unit.workflowStates.saveFeatureTaskExecutionIdentity(execution.identity("child", "SKILL-446"))
    }
    state =
      store().save(
        GoalRunnerManifestState(
          parentWorkflowId = "parent",
          dbPath = database.resolveDbPath().toString(),
          manifest =
            manifest(1).let { original ->
              original.copy(
                issueKey = "SKILL-446",
                subtasks =
                  original.subtasks.map {
                    it.copy(
                      status = initialStatus,
                      workflowId = "child",
                      commitSha = acceptance.commitSha,
                    )
                  },
              )
            },
          repoRoot = root,
        ),
      )
    database.transaction { it.goalRunnerControls.persistOutOfBandAcceptance("parent", acceptance) }
  }

  fun reopen() =
    sqliteDatabaseSessionFactory(
      userHome = root,
      dbPathOverride = root.resolve("runtime.db").toString(),
      environment = emptyMap(),
    )

  fun store(database: DatabaseSessionFactory = this.database) =
    testWorkflowGoalRunnerManifestStore(
      database,
      TestDecompositionManifestStore,
      testHarnessClock,
    )

  fun saveDraft() {
    testWorkflowGoalRunnerOutcomeStore(database).saveGoalChildHistoryDraft(
      GoalChildHistoryDraft(
        parentWorkflowId = "parent",
        subtaskId = 1,
        childWorkflowId = "child",
        disposition = GoalChildHistoryDraftDisposition.SKIP,
        skipReason = "No durable history change.",
      ),
    )
  }

  fun saveLegacySkip() {
    database.transaction { unit ->
      val record = unit.workflowStates.getFeatureTaskWorkflow("child")!!
      val history =
        FeatureTaskRuntimePhaseRecord(
          phaseId = "write_history",
          status = WorkflowStepStatus.SKIPPED,
          attemptCount = 1,
          startedAt = "2026-10-26T10:00:00Z",
          resolvedAgentId = "codex",
        )
      unit.workflowStates.saveFeatureTaskWorkflow(
        record.copy(
          artifactsJson =
            JsonCodec.mapToJsonString(
              JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(record.artifactsJson)).orEmpty() +
                DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
                  mapOf("write_history" to history.asWorkflowArtifactEntry()),
                ),
            ),
        ),
        FeatureTaskWorkflowMode.RUNTIME,
      )
    }
  }

  fun reset() = store().saveHardReset(state.copy(manifest = state.manifest.resetManifest(true)))
}
