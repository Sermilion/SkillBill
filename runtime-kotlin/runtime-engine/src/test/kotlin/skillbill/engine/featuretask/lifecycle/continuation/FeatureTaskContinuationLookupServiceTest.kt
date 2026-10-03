package skillbill.engine.featuretask.lifecycle.continuation

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.application.testRepositoryRoot
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.persist.openFeatureTask
import skillbill.application.workflow.service.WorkflowService
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.model.continuation.FeatureTaskContinuationLookupResult
import skillbill.engine.featuretask.slot.testExecutionPlan
import skillbill.engine.goalrunner.manifest
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.error.shellcontent.LegacyProseWorkflowError
import skillbill.goalrunner.model.GoalContinuation
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestStore
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.toRecord
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode.PROSE
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val DECOMPOSITION_RUNTIME_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label()

class FeatureTaskContinuationLookupServiceTest {
  @Test
  fun `lookup returns no match without mutating workflow state`() {
    val fixture = fixture()

    assertIs<FeatureTaskContinuationLookupResult.NoMatch>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
    )
    assertEquals(emptyList(), fixture.states.listFeatureTaskWorkflows(RUNTIME))
  }

  @Test
  fun `lookup isolates equal issue keys by canonical repository identity`() {
    val fixture = fixture()
    val opened = fixture.open(REPOSITORY_B)

    assertIs<FeatureTaskContinuationLookupResult.NoMatch>(
      fixture.lookup.lookup("skill-120", REPOSITORY_A),
    )
    val running =
      assertIs<FeatureTaskContinuationLookupResult.AlreadyRunning>(
        fixture.lookup.lookup("SKILL-120", REPOSITORY_B),
      )
    assertEquals(opened.workflowId, running.candidate.workflowId)
  }

  @Test
  fun `lookup reports every ambiguous eligible workflow without selecting newest`() {
    val fixture = fixture()
    val terminal = fixture.open(REPOSITORY_A)
    fixture.service.abandonFeatureTaskRuntime(terminal.workflowId, "Terminal lookup fixture.")
    val first = fixture.open(REPOSITORY_A)
    val second = fixture.open(REPOSITORY_A)

    val ambiguous =
      assertIs<FeatureTaskContinuationLookupResult.Ambiguous>(
        fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
      )

    assertEquals(
      listOf(terminal.workflowId, first.workflowId, second.workflowId),
      ambiguous.candidates.map { it.workflowId },
    )
  }

  @Test
  fun `lookup keeps terminal-only history terminal`() {
    val fixture = fixture()
    val opened = fixture.open(REPOSITORY_A)
    fixture.service.abandonFeatureTaskRuntime(opened.workflowId, "Terminal lookup fixture.")

    val terminal =
      assertIs<FeatureTaskContinuationLookupResult.TerminalOnly>(
        fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
      )

    assertEquals(listOf(opened.workflowId), terminal.candidates.map { it.workflowId })
  }

  @Test
  fun `only one caller can claim a resumable workflow`() {
    val fixture = fixture()
    val opened = fixture.open(REPOSITORY_A)
    fixture.service.update(
      WorkflowFamilyKind.TASK_RUNTIME,
      WorkflowUpdateRequest(
        workflowId = opened.workflowId,
        workflowStatus = WorkflowStatus.BLOCKED.wireValue,
        currentStepId = "implement",
        sessionId = "",
      ),
    )
    val row = requireNotNull(fixture.states.getFeatureTaskWorkflow(opened.workflowId))
    val artifacts = requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(row.artifactsJson)))
    fixture.states.saveFeatureTaskWorkflow(
      row.copy(
        artifactsJson =
          JsonCodec.mapToJsonString(
            artifacts +
              mapOf(
                DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.entry(
                  ExecutionPlanAdmissionFixture().descriptor(),
                ),
              ),
          ),
      ),
      RUNTIME,
    )
    val candidate =
      assertIs<FeatureTaskContinuationLookupResult.Resumable>(
        fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
      ).candidate

    assertNotNull(fixture.lookup.claim(candidate, ExecutionPlanAdmissionFixture().inputs))
    assertNull(fixture.lookup.claim(candidate, ExecutionPlanAdmissionFixture().inputs))
    val running =
      assertIs<FeatureTaskContinuationLookupResult.AlreadyRunning>(
        fixture.lookup.lookup("SKILL-120", REPOSITORY_A, candidate.workflowId),
      )
    assertEquals(candidate.workflowId, running.candidate.workflowId)
    assertEquals("ownership_unavailable", running.candidate.liveness?.classification)
  }

  @Test
  fun `feature-task creation rejects malformed identity before persistence`() {
    val fixture = fixture()

    assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
      fixture.service.openFeatureTask(
        WorkflowServiceOpenFeatureTaskArgs(
          executionPlan = testExecutionPlan(),
          kind = WorkflowFamilyKind.TASK_RUNTIME,
          issueKey = "SKILL-120",
          repositoryIdentity = "not-a-repository",
          governedSpecPath = ".feature-specs/SKILL-120-continuation/spec.md",
        ),
      )
    }

    assertEquals(emptyList(), fixture.states.listFeatureTaskWorkflows(RUNTIME))
  }

  @Test
  fun `identity-less matching feature-task returns needs-identity-repair instead of crashing lookup`() {
    val fixture = fixture()
    assertIs<WorkflowOpenResult.Ok>(
      fixture.service.open(WorkflowServiceOpenArgs(kind = WorkflowFamilyKind.TASK_RUNTIME, issueKey = "SKILL-120")),
    )

    val repair =
      assertIs<FeatureTaskContinuationLookupResult.NeedsIdentityRepair>(
        fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
      )
    assertTrue(repair.workflowId.isNotBlank())
    assertTrue(repair.summary.contains("repair-identity"), repair.summary)
  }

  @Test
  fun `malformed identity from another repository cannot poison scoped lookup`() {
    val fixture = fixture()
    val opened = fixture.open(REPOSITORY_B)
    val identity = requireNotNull(fixture.states.executionIdentity(opened.workflowId))
    fixture.states.overwriteExecutionIdentity(identity.copy(contractVersion = "corrupt"))

    assertIs<FeatureTaskContinuationLookupResult.NoMatch>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
    )
  }

  @Test
  fun `conflicting identity in the selected repository loud-fails`() {
    val fixture = fixture()
    val opened = fixture.open(REPOSITORY_A)
    val identity = requireNotNull(fixture.states.executionIdentity(opened.workflowId))
    fixture.states.overwriteExecutionIdentity(identity.copy(normalizedIssueKey = "SKILL-999"))

    assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A)
    }
  }

  @Test
  fun `legacy prose-mode candidate loud-fails on continuation with the runtime re-run error`() {
    val fixture = fixture()
    val opened = fixture.open(REPOSITORY_A)
    val identity = requireNotNull(fixture.states.executionIdentity(opened.workflowId))
    fixture.states.overwriteExecutionIdentity(identity.copy(mode = PROSE))

    val row = requireNotNull(fixture.states.getFeatureTaskWorkflow(opened.workflowId))
    fixture.states.saveFeatureTaskWorkflow(row.copy(mode = PROSE), RUNTIME)

    assertFailsWith<LegacyProseWorkflowError> {
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A)
    }
  }

  @Test
  fun `goal-child identity stays outside standalone continuation lookup`() {
    val fixture = fixture()
    val opened =
      assertIs<WorkflowOpenResult.Ok>(
        fixture.service.openFeatureTask(
          WorkflowServiceOpenFeatureTaskArgs(
            executionPlan = testExecutionPlan(),
            kind = WorkflowFamilyKind.TASK_RUNTIME,
            issueKey = "SKILL-120",
            repositoryIdentity = REPOSITORY_A,
            governedSpecPath = ".feature-specs/SKILL-120-goal/spec_subtask_1.md",
            routeScope = FeatureTaskRouteScope.GOAL_CHILD,
          ),
        ),
      )

    assertIs<FeatureTaskContinuationLookupResult.NoMatch>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
    )
    fixture.service.update(
      WorkflowFamilyKind.TASK_RUNTIME,
      WorkflowUpdateRequest(
        workflowId = opened.workflowId,
        workflowStatus = WorkflowStatus.BLOCKED.wireValue,
        currentStepId = "preplan",
        sessionId = "",
      ),
    )

    val resumable =
      assertIs<FeatureTaskContinuationLookupResult.Resumable>(
        fixture.lookup.lookupGoalChild("SKILL-120", REPOSITORY_A, opened.workflowId),
      )
    assertEquals(opened.workflowId, resumable.candidate.workflowId)
  }

  @Test
  fun `lookup surfaces a prepared goal that owns durable state instead of reporting no match`() {
    val fixture = fixture()
    fixture.saveGoalParent(workflowStatus = WorkflowStatus.PAUSED.wireValue, manifestStatus = "in_progress")

    val goal =
      assertIs<FeatureTaskContinuationLookupResult.GoalContinuation>(
        fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
      )

    assertEquals("wfl-goal-parent", goal.candidate.parentWorkflowId)
    assertEquals("paused", goal.candidate.status)
    assertEquals(1, goal.candidate.currentSubtaskId)
    assertEquals(1, goal.candidate.pendingCount)
  }

  @Test
  fun `lookup surfaces a legacy prose-mode goal parent as goal continuation without no-match`() {
    val fixture = fixture()
    fixture.saveProseGoalParent(
      workflowStatus = WorkflowStatus.PAUSED.wireValue,
      manifestStatus = "in_progress",
      completeCount = 2,
      pendingCount = 1,
      blockedCount = 0,
    )

    val goal =
      assertIs<FeatureTaskContinuationLookupResult.GoalContinuation>(
        fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
      )

    assertEquals("wfl-prose-goal-parent", goal.candidate.parentWorkflowId)
    assertEquals("paused", goal.candidate.status)
    assertEquals(3, goal.candidate.currentSubtaskId)
    assertEquals(2, goal.candidate.completeCount)
    assertEquals(1, goal.candidate.pendingCount)
    assertEquals(0, goal.candidate.blockedCount)
    val stored = requireNotNull(fixture.states.getFeatureTaskWorkflow("wfl-prose-goal-parent"))
    assertEquals(PROSE, stored.mode)
  }

  @Test
  fun `lookup reports a running goal so a second run is never started against the same state`() {
    val fixture = fixture()
    fixture.saveGoalParent(workflowStatus = WorkflowStatus.RUNNING.wireValue, manifestStatus = "in_progress")

    val goal =
      assertIs<FeatureTaskContinuationLookupResult.GoalContinuation>(
        fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
      )

    assertEquals("running", goal.candidate.status)
  }

  @Test
  fun `lookup keeps a completed goal out of continuation`() {
    val fixture = fixture()
    fixture.saveGoalParent(workflowStatus = WorkflowStatus.PAUSED.wireValue, manifestStatus = "complete")

    assertIs<FeatureTaskContinuationLookupResult.NoMatch>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
    )
  }

  @Test
  fun `lookup isolates a goal bound to another repository by its children`() {
    val fixture = fixture()
    fixture.saveGoalParent(workflowStatus = WorkflowStatus.PAUSED.wireValue, manifestStatus = "in_progress")
    assertIs<WorkflowOpenResult.Ok>(
      fixture.service.openFeatureTask(
        WorkflowServiceOpenFeatureTaskArgs(
          executionPlan = testExecutionPlan(),
          kind = WorkflowFamilyKind.TASK_RUNTIME,
          issueKey = "SKILL-120",
          repositoryIdentity = REPOSITORY_B,
          governedSpecPath = ".feature-specs/SKILL-120-goal/spec_subtask_1.md",
          routeScope = FeatureTaskRouteScope.GOAL_CHILD,
        ),
      ),
    )

    assertIs<FeatureTaskContinuationLookupResult.NoMatch>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_A),
    )
    assertIs<FeatureTaskContinuationLookupResult.GoalContinuation>(
      fixture.lookup.lookup("SKILL-120", REPOSITORY_B),
    )
  }

  private fun fixture(): Fixture {
    val states = InMemoryWorkflowStates()
    val database = FakeDatabaseSessionFactory(states)
    val service =
      WorkflowService(
        database = database,
        gitOperations = NoopWorkflowGitOperations,
        decompositionManifestStore = UnavailableDecompositionManifestStore,
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestWriter = testDecompositionManifestWriter,
        repositoryRoot = testRepositoryRoot,
        goalObservabilityEventValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
        runtimeDiagnostics = NoopRuntimeDiagnostics,
        clock = Clock.systemUTC(),
      )
    return Fixture(
      states = states,
      service = service,
      lookup =
        FeatureTaskContinuationLookupService(
          database,
          testWorkflowSnapshotValidator,
          ExecutionPlanAdmissionFixture().compatibility,
          NoopRuntimeDiagnostics,
        ),
    )
  }

  private data class Fixture(
    val states: InMemoryWorkflowStates,
    val service: WorkflowService,
    val lookup: FeatureTaskContinuationLookupService,
  ) {
    fun saveGoalParent(
      workflowStatus: String,
      manifestStatus: String,
    ) {
      val manifest =
        DecompositionManifest(
          issueKey = "SKILL-120",
          featureName = "goal-continuation",
          parentSpecPath = ".feature-specs/SKILL-120-goal/spec.md",
          status = manifestStatus,
          baseBranch = "main",
          featureBranch = "feat/SKILL-120-goal",
          currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "start"),
          subtasks =
            listOf(
              DecompositionSubtask(
                id = 1,
                name = "first",
                specPath = ".feature-specs/SKILL-120-goal/spec_subtask_1.md",
                status = if (manifestStatus == "complete") "complete" else "pending",
              ),
            ),
        )
      val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
      val engine = WorkflowEngine()
      val opened = engine.openRecord(definition, "wfl-goal-parent", "ftr-goal", "preplan")
      states.saveFeatureTaskWorkflow(
        engine.updateRecord(
          definition,
          opened,
          WorkflowUpdateInput(
            terminalInstant = Instant.EPOCH,
            workflowStatus =
              WorkflowStatus.fromWire(workflowStatus)
                ?: error("Unknown workflow status '$workflowStatus'."),
            currentStepId = "plan",
            stepUpdates = null,
            artifactsPatch =
              WorkflowArtifactPatch.from(
                mapOf(
                  "plan" to mapOf("mode" to "decompose"),
                  DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
                    testDecompositionManifestValidator.encodeManifestWireMap(manifest),
                ),
              ),
            sessionId = "ftr-goal",
          ),
        ).toRecord().copy(issueKey = "SKILL-120"),
        RUNTIME,
      )
    }

    fun saveProseGoalParent(
      workflowStatus: String,
      manifestStatus: String,
      completeCount: Int,
      pendingCount: Int,
      blockedCount: Int,
    ) {
      val subtasks = proseGoalSubtasks(completeCount, pendingCount, blockedCount)
      val currentId = completeCount + blockedCount + pendingCount
      val manifest =
        DecompositionManifest(
          issueKey = "SKILL-120",
          featureName = "prose-goal-continuation",
          parentSpecPath = ".feature-specs/SKILL-120-goal/spec.md",
          status = manifestStatus,
          baseBranch = "main",
          featureBranch = "feat/SKILL-120-goal",
          currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = currentId, action = "start"),
          subtasks = subtasks,
        )
      val artifacts =
        mapOf(
          "plan" to mapOf("mode" to "decompose"),
          DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
            testDecompositionManifestValidator.encodeManifestWireMap(manifest),
        )
      states.saveFeatureTaskWorkflow(
        WorkflowStateRecord(
          workflowId = "wfl-prose-goal-parent",
          sessionId = "fis-prose-goal",
          workflowName = "bill-feature-task",
          contractVersion = "0.1",
          workflowStatus = workflowStatus,
          currentStepId = "assess",
          stepsJson =
            """[{"step_id":"assess","status":"completed"},{"step_id":"create_branch","status":"pending"}]""",
          artifactsJson = JsonCodec.mapToJsonString(artifacts),
          startedAt = null,
          updatedAt = null,
          finishedAt = null,
          mode = PROSE,
          implementationSkill = "bill-feature-task-" + "prose",
          issueKey = "SKILL-120",
        ),
        PROSE,
      )
    }

    private fun proseGoalSubtasks(
      completeCount: Int,
      pendingCount: Int,
      blockedCount: Int,
    ): List<DecompositionSubtask> =
      buildList {
        repeat(completeCount) { index ->
          add(
            DecompositionSubtask(
              id = index + 1,
              name = "complete-$index",
              specPath = ".feature-specs/SKILL-120-goal/spec_subtask_${index + 1}.md",
              status = "complete",
            ),
          )
        }
        repeat(blockedCount) { index ->
          val id = completeCount + index + 1
          add(
            DecompositionSubtask(
              id = id,
              name = "blocked-$index",
              specPath = ".feature-specs/SKILL-120-goal/spec_subtask_$id.md",
              status = "blocked",
            ),
          )
        }
        repeat(pendingCount) { index ->
          val id = completeCount + blockedCount + index + 1
          add(
            DecompositionSubtask(
              id = id,
              name = "pending-$index",
              specPath = ".feature-specs/SKILL-120-goal/spec_subtask_$id.md",
              status = "pending",
            ),
          )
        }
      }

    fun open(repositoryIdentity: String): WorkflowOpenResult.Ok =
      assertIs(
        service.openFeatureTask(
          WorkflowServiceOpenFeatureTaskArgs(
            executionPlan = testExecutionPlan(),
            kind = WorkflowFamilyKind.TASK_RUNTIME,
            issueKey = "SKILL-120",
            repositoryIdentity = repositoryIdentity,
            governedSpecPath = ".feature-specs/SKILL-120-continuation/spec.md",
          ),
        ),
      )
  }

  private companion object {
    const val REPOSITORY_A = "repo-root-realpath-v1:/tmp/skill-bill-a"
    const val REPOSITORY_B = "repo-root-realpath-v1:/tmp/skill-bill-b"
  }
}
