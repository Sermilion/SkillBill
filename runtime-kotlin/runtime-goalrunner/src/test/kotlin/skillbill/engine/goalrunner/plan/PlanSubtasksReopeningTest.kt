package skillbill.engine.goalrunner.plan

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import skillbill.application.TestDecompositionManifestStore
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.application.testHarnessClock
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.application.workflow.decomposition.findCompletedPlanWorkflowId
import skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskContinuationLookupService
import skillbill.engine.featuretask.lifecycle.continuation.claimForTest
import skillbill.engine.featuretask.lifecycle.core.FeatureTaskRuntimeCrashReconciler
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibility
import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibilityStateCodec
import skillbill.engine.featuretask.phase.planning.PlanBundleSnapshots
import skillbill.engine.featuretask.phase.planning.authoredBundleReasonForTest
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationRuntime
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationWriter
import skillbill.engine.featuretask.runner.RUNNER_TEST_PROJECT_STORE
import skillbill.engine.featuretask.testing.featureTaskRuntimeWireArtifactValidatorForTest
import skillbill.engine.goalrunner.planning.outcome.proseRecordPayload
import skillbill.engine.goalrunner.planning.source.findImportedSubtaskPlanWorkflowIds
import skillbill.engine.goalrunner.planning.source.findLinkedSubtaskPlanWorkflowId
import skillbill.engine.goalrunner.planning.source.findReopenedSubtaskPlanWorkflowId
import skillbill.error.core.SkillBillRuntimeException
import skillbill.featurespec.model.FeatureSpecPreparationMode
import skillbill.featurespec.model.FeatureSpecWriteResult
import skillbill.infrastructure.workflow.filesystem.FileSystemFeatureSpecPathResolver
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.text.sha256HexUtf8
import skillbill.workflow.decomposition.encodeDecompositionManifestWireMap
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodePlanBundleFromArtifact
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundle
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanBundleOrigin
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImport
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImportSource
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.planning.FeatureTaskRuntimePlanBundleFeasibilityState
import skillbill.workflow.taskruntime.model.planning.PLAN_BUNDLE_FEASIBILITY_FULL_V2_REVIEW_ADOPTION_REASON
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityIdentity
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunch
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchKind
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityLaunchOutcome
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityMode
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityReopening
import skillbill.workflow.taskruntime.model.planning.PlanBundleFeasibilityRepositoryBaseline
import skillbill.workflow.taskruntime.model.planning.blockPlanBundleFeasibilityLaunch
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
      assertNotNull(lookupService.claimForTest(candidate, execution.inputs))
      assertNull(lookupService.claimForTest(candidate, execution.inputs))
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
      val recorder =
        FeatureTaskRuntimeDecomposeTerminalRecorder(
          database,
          testHarnessClock,
          PlanBundleFeasibilityStateCodec(featureTaskRuntimeWireArtifactValidatorForTest()),
          NoopRuntimeDiagnostics,
        )
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
  fun `reopening drops the decompose terminal so the replanned bundle completes the plan again`() {
    val home = Files.createTempDirectory("plan-subtasks-terminal")
    try {
      val database = phaseRunDatabase(home, testHarnessClock)
      val execution = ExecutionPlanAdmissionFixture(SkeletonDefinition.PLAN, database = database)
      val selectedId = "wftr-terminal-plan"
      seedPlans(database, execution, selectedId, "wftr-older-plan", acceptedRecords())
      val recorder =
        FeatureTaskRuntimeDecomposeTerminalRecorder(
          database,
          testHarnessClock,
          PlanBundleFeasibilityStateCodec(featureTaskRuntimeWireArtifactValidatorForTest()),
          NoopRuntimeDiagnostics,
        )
      val terminal =
        FeatureTaskRuntimeDecomposeTerminal(
          reason = authoredBundleReasonForTest(),
          parentSpecPath = "spec.md",
          decompositionManifestPath = "decomposition-manifest.yaml",
          subtaskSpecPaths = listOf("spec_subtask_1.md"),
        )
      database.transaction { unit ->
        val completed = requireNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedId))
        unit.workflowStates.saveAcceptedSnapshotMutation(
          WorkflowFamily.TASK_RUNTIME,
          completed.copy(
            artifacts =
              DurableWorkflowArtifacts.fromMap(
                completed.artifacts +
                  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL.entry(
                    terminal.asWorkflowArtifactEntry(),
                  ),
              ),
          ),
        )
      }

      assertTrue(reopening(database).reopenAtPlanSubtasks(selectedId))

      database.read { unit ->
        val reopened = requireNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedId))
        assertNull(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DECOMPOSE_TERMINAL.value(reopened.artifacts))
      }
      assertNull(recorder.loadDecomposeTerminal(selectedId))
      assertTrue(recorder.recordDecomposeTerminal(selectedId, terminal, "plan_subtasks"))
      val identity = execution.identity(selectedId)
      database.read { unit ->
        val completed = requireNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedId))
        assertEquals(WorkflowStatus.COMPLETED, completed.workflowStatus)
        assertEquals(
          selectedId,
          unit.workflowStates.findCompletedPlanWorkflowId(identity.normalizedIssueKey, identity.repositoryIdentity),
        )
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

  @Test
  fun `a primary bound goal links an exact checkout plan only through its fallback identities`() {
    val home = Files.createTempDirectory("exact-keyed-plan-link")
    try {
      val database = phaseRunDatabase(home, testHarnessClock)
      val execution = ExecutionPlanAdmissionFixture(SkeletonDefinition.PLAN, database = database)
      val planId = "wftr-exact-keyed-plan"
      seedPlans(database, execution, planId, "wftr-older-plan", acceptedRecords())
      val exact = execution.identity(planId)
      val fallback = listOf(exact.repositoryIdentity)
      val primaryBound =
        GoalPlanningIdentity("wftr-parent", exact.normalizedIssueKey, "repo-root-realpath-v1:/primary")

      database.read { unit ->
        val states = unit.workflowStates
        assertNull(states.findLinkedSubtaskPlanWorkflowId(primaryBound, emptyList()) { true })
        assertEquals(planId, states.findLinkedSubtaskPlanWorkflowId(primaryBound, emptyList(), fallback) { true })
        assertNull(states.findLinkedSubtaskPlanWorkflowId(primaryBound, emptyList(), fallback) { false })
      }

      assertTrue(reopening(database).reopenAtPlanSubtasks(planId))

      database.read { unit ->
        val states = unit.workflowStates
        assertNull(states.findLinkedSubtaskPlanWorkflowId(primaryBound, emptyList()) { true })
        assertEquals(planId, states.findLinkedSubtaskPlanWorkflowId(primaryBound, emptyList(), fallback) { true })
      }
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  @Test
  fun `legacy feasibility adoption repairs an ownerless running pending phase and retains accepted plans`() {
    val home = Files.createTempDirectory("legacy-plan-adoption")
    try {
      val database = phaseRunDatabase(home, testHarnessClock)
      val execution = ExecutionPlanAdmissionFixture(SkeletonDefinition.PLAN, database = database)
      val selectedId = "wftr-adopted-plan"
      val records = acceptedRecords()
      seedPlans(database, execution, selectedId, "wftr-older-plan", records)
      val (bundle, manifestPath) = seedLegacyBundle(home, database, selectedId)
      val replan = PlanSubtasksReplan("SKILL-384", selectedId, home, setOf(1), feasibilityAdoption = true)
      val reopening = reopening(database)
      assertTrue(reopening.reopenWithRebasedBundle(replan))
      val adopted = database.read { requireNotNull(it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedId)) }
      val adoptedBundle =
        requireNotNull(DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.value(adopted.artifacts))
      val lookupService =
        FeatureTaskContinuationLookupService(
          database,
          testWorkflowSnapshotValidator,
          execution.compatibility,
          NoopRuntimeDiagnostics,
        )
      val lookup = lookup(database, execution, lookupService)
      val identity = execution.identity(selectedId)
      val candidate =
        assertIs<FeatureTaskContinuationLookupResult.Resumable>(
          lookup.lookupSelected(identity.normalizedIssueKey, listOf(identity.repositoryIdentity), selectedId),
        ).candidate
      assertNotNull(lookupService.claimForTest(candidate, execution.inputs))

      assertTrue(reopening.reopenWithRebasedBundle(replan))
      val recovered = database.read { requireNotNull(it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedId)) }
      assertEquals(WorkflowStatus.PENDING, recovered.workflowStatus)
      assertEquals(adopted.artifacts, recovered.artifacts)
      assertEquals(bundle.subtasks, assertNotNull(decodePlanBundleFromArtifact(adoptedBundle)).subtasks)
      assertEquals(
        sha256HexUtf8(Files.readString(manifestPath)),
        assertNotNull(decodePlanBundleFromArtifact(adoptedBundle)).manifestSha256,
      )
      val retained = decodePhaseRecords(recovered.artifacts)
      records.filterKeys { it != "plan_subtasks" }.forEach { (phase, record) -> assertEquals(record, retained[phase]) }
      assertTrue(reopening.reopenWithRebasedBundle(replan))
      assertEquals(recovered, database.read { it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedId) })
      database.transaction { unit ->
        unit.workflowStates.saveAcceptedSnapshotMutation(
          WorkflowFamily.TASK_RUNTIME,
          recovered.copy(workflowStatus = WorkflowStatus.RUNNING),
        )
        val before = requireNotNull(unit.workflowStates.getFeatureTaskWorkflow(selectedId))
        assertTrue(
          unit.workflowStates.acquireFeatureTaskRuntimeWorker(
            FeatureTaskRuntimeWorkerOwnership(
              selectedId, 1, "plan-owner-token-12345678", "host", "boot", 1234, "birth",
              FeatureTaskRuntimeWorkerLeaseState.ACTIVE, testHarnessClock.instant().toString(),
              testHarnessClock.instant().plusSeconds(30).toString(), "plan_subtasks", 1,
            ),
            before.updatedAt,
          ),
        )
      }
      val owned = database.read { it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedId) }
      assertFalse(reopening.reopenWithRebasedBundle(replan))
      assertEquals(owned, database.read { it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedId) })
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `goal resume reopens safety-blocked feasibility even after an interrupted pending adoption`(
    interruptedAdoption: Boolean,
  ) {
    val home = Files.createTempDirectory("blocked-feasibility-adoption")
    try {
      val database = phaseRunDatabase(home, testHarnessClock)
      val execution = ExecutionPlanAdmissionFixture(SkeletonDefinition.PLAN, database = database)
      val selectedId = "wftr-blocked-plan"
      seedPlans(database, execution, selectedId, "wftr-older-plan", acceptedRecords())
      seedLegacyBundle(home, database, selectedId)
      val codec = PlanBundleFeasibilityStateCodec(featureTaskRuntimeWireArtifactValidatorForTest())
      val captured = PlanBundleFeasibility.capture(planner(), home, "SKILL-384")
      seedSafetyBlockedFeasibility(database, selectedId, codec, captured.identity)
      if (interruptedAdoption) {
        database.transaction { unit ->
          val snapshot = requireNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedId))
          val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY
          val state = codec.decode(family.value(snapshot.artifacts), "test")
          val records = decodePhaseRecords(snapshot.artifacts)
          val pending = requireNotNull(records["plan_subtasks"]).copy(status = WorkflowStepStatus.PENDING)
          val retained =
            state.copy(
              reopenings =
                listOf(
                  PlanBundleFeasibilityReopening(
                    pending.attemptCount,
                    state.revision,
                    0,
                    PLAN_BUNDLE_FEASIBILITY_FULL_V2_REVIEW_ADOPTION_REASON,
                    pending,
                  ),
                ),
            )
          unit.workflowStates.saveAcceptedSnapshotMutation(
            WorkflowFamily.TASK_RUNTIME,
            snapshot.copy(
              artifacts =
                DurableWorkflowArtifacts.fromMap(
                  snapshot.artifacts + family.entry(codec.encode(retained)) +
                    DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
                      (records + ("plan_subtasks" to pending)).mapValues { it.value.asWorkflowArtifactEntry() },
                    ),
                ),
            ),
          )
        }
      }

      val reopening = reopening(database)
      val replan = PlanSubtasksReplan("SKILL-384", selectedId, home, setOf(1), feasibilityAdoption = true)
      assertTrue(reopening.reopenWithRebasedBundle(replan))

      val reopened = database.read { requireNotNull(it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedId)) }
      val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY
      val state = codec.decode(family.value(reopened.artifacts), "test")
      assertEquals(PlanBundleFeasibilityMode.REVIEW, state.mode)
      assertEquals(0, state.reviewRoundsStarted)
      assertNull(state.activeLaunch)
      assertTrue(state.currentCycleId.orEmpty().startsWith("$selectedId:plan_subtasks:retry-"))
      assertEquals(
        PlanBundleFeasibilityLaunchOutcome.BLOCKED,
        state.cycleHistory.single().settledLaunches.single().outcome,
      )
      assertEquals(WorkflowStepStatus.PENDING, decodePhaseRecords(reopened.artifacts)["plan_subtasks"]?.status)
      assertTrue(reopening.reopenWithRebasedBundle(replan))
      assertEquals(reopened, database.read { it.workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedId) })
    } finally {
      home.toFile().deleteRecursively()
    }
  }

  private fun seedSafetyBlockedFeasibility(
    database: DatabaseSessionFactory,
    selectedId: String,
    codec: PlanBundleFeasibilityStateCodec,
    identity: PlanBundleFeasibilityIdentity,
  ) {
    val cycleId = "$selectedId:plan_subtasks:cycle-1"
    val launch =
      PlanBundleFeasibilityLaunch(
        "$selectedId:$cycleId:1",
        "plan_subtasks",
        1,
        PlanBundleFeasibilityLaunchKind.REVIEW,
        identity,
        null,
        null,
        cycleId = cycleId,
        sequence = 1,
        repositoryBaseline = PlanBundleFeasibilityRepositoryBaseline("head", emptyList(), emptyMap()),
      )
    val launched =
      FeatureTaskRuntimePlanBundleFeasibilityState(
        revision = 1,
        mode = PlanBundleFeasibilityMode.REVIEW,
        identity = identity,
        acceptedLines = emptyList(),
        conflicts = emptyList(),
        changedSubtaskIds = emptyList(),
        requiredRefs = identity.subtasks.flatMap { it.criterionRefs },
        carriedRefs = emptyList(),
        reviewRoundsStarted = 1,
        repairLaunchesStarted = 0,
        activeLaunch = launch,
        settledLaunches = emptyList(),
        lastReviewValue = null,
        approval = null,
        legacyOrigin = false,
        currentCycleId = cycleId,
      )
    val blocked =
      assertNotNull(
        blockPlanBundleFeasibilityLaunch(
          launched,
          launch.transitionId,
          "Feasibility changed repository paths outside its admitted authority: src/Operator.kt.",
        ),
      )
    database.transaction { unit ->
      val snapshot = requireNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedId))
      val records =
        decodePhaseRecords(snapshot.artifacts).mapValues { (phase, record) ->
          if (phase == "plan_subtasks") {
            record.copy(status = WorkflowStepStatus.BLOCKED, blockedReason = "Bundle feasibility is blocked.")
          } else {
            record
          }
        }
      unit.workflowStates.saveAcceptedSnapshotMutation(
        WorkflowFamily.TASK_RUNTIME,
        snapshot.copy(
          workflowStatus = WorkflowStatus.BLOCKED,
          artifacts =
            DurableWorkflowArtifacts.fromMap(
              snapshot.artifacts +
                DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
                  records.mapValues { it.value.asWorkflowArtifactEntry() },
                ) +
                DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE_FEASIBILITY.entry(codec.encode(blocked)),
            ),
        ),
      )
    }
  }

  private fun seedLegacyBundle(
    home: Path,
    database: DatabaseSessionFactory,
    selectedId: String,
  ): Pair<FeatureTaskRuntimePlanBundle, Path> {
    val directory = Files.createDirectories(home.resolve(".feature-specs/SKILL-384-recovery"))
    val parent = Files.writeString(directory.resolve("spec.md"), "# Recovery\n")
    val child =
      Files.writeString(
        directory.resolve("subtask.md"),
        "## Acceptance Criteria\n\n1. Retain completed work.\n\n" +
          "## Implementation Details\n\nKeep the existing plan.\n",
      )
    val manifest =
      DecompositionManifest(
        issueKey = "SKILL-384",
        featureName = "recovery",
        parentSpecPath = home.relativize(parent).toString(),
        baseBranch = "main",
        featureBranch = "feat/recovery",
        currentSubtaskIntent = CurrentSubtaskIntent(1, "start"),
        subtasks = listOf(DecompositionSubtask(1, "Retained", home.relativize(child).toString())),
      )
    val manifestPath = directory.resolve("decomposition-manifest.yaml")
    writeManifest(manifestPath, manifest)
    val snapshots = PlanBundleSnapshots(planner())
    val bundle =
      snapshots.capture(
        home,
        FeatureSpecWriteResult(
          FeatureSpecPreparationMode.DECOMPOSED,
          home.relativize(parent).toString(),
          "",
          home.relativize(manifestPath).toString(),
          listOf(home.relativize(child).toString()),
        ),
        FeatureTaskRuntimePlanBundleOrigin.EXISTING,
      )
    database.transaction { unit ->
      val snapshot = requireNotNull(unit.workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedId))
      unit.workflowStates.saveAcceptedSnapshotMutation(
        WorkflowFamily.TASK_RUNTIME,
        snapshot.copy(
          artifacts =
            DurableWorkflowArtifacts.fromMap(
              snapshot.artifacts +
                DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.entry(
                  bundle.asWorkflowArtifactEntry(),
                ),
            ),
        ),
      )
    }
    val progressed =
      manifest.copy(
        status = "in_progress",
        subtasks =
          manifest.subtasks.map {
            it.copy(status = "complete")
          },
      )
    writeManifest(manifestPath, progressed)
    return bundle to manifestPath
  }

  private fun writeManifest(
    path: Path,
    manifest: DecompositionManifest,
  ) {
    TestDecompositionManifestStore.writeTextAtomically(
      path,
      TestDecompositionManifestStore.encodeManifestYaml(encodeDecompositionManifestWireMap(manifest)),
    )
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
                DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.entry(imported),
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
      database,
      PlanBundleFeasibilityStateCodec(featureTaskRuntimeWireArtifactValidatorForTest()),
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

  private fun planner(): FeatureTaskRuntimeDecompositionPlanner =
    FeatureTaskRuntimeDecompositionPlanner(
      FeatureSpecPreparationRuntime(),
      FeatureSpecPreparationWriter(
        testDecompositionManifestValidator,
        TestDecompositionManifestStore,
        testDecompositionManifestWriter,
        RUNNER_TEST_PROJECT_STORE,
      ),
      FileSystemFeatureSpecPathResolver(RUNNER_TEST_PROJECT_STORE),
      RUNNER_TEST_PROJECT_STORE,
      TestDecompositionManifestStore,
    )

  private fun reopening(database: DatabaseSessionFactory): PlanSubtasksReopening =
    PlanSubtasksReopening(
      database,
      planner(),
      FeatureTaskRuntimeDecomposeTerminalRecorder(
        database,
        testHarnessClock,
        PlanBundleFeasibilityStateCodec(featureTaskRuntimeWireArtifactValidatorForTest()),
        NoopRuntimeDiagnostics,
      ),
      testHarnessClock,
      PlanBundleFeasibilityStateCodec(featureTaskRuntimeWireArtifactValidatorForTest()),
      NoopRuntimeDiagnostics,
    )
}
