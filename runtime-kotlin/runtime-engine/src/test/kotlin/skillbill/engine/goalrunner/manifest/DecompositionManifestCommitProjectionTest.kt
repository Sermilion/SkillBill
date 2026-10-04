package skillbill.engine.goalrunner.manifest

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestDecompositionManifestStore
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.clearDecompositionManifestProjectionFailure
import skillbill.application.decomposition.decompositionPlanningResult
import skillbill.application.decomposition.decompositionPlanningSubtask
import skillbill.application.decomposition.loadDecompositionManifest
import skillbill.application.decomposition.model.DecompositionManifestProjectionFailurePersistence
import skillbill.application.decomposition.model.DecompositionManifestRuntimeUpdate
import skillbill.application.decomposition.model.DecompositionManifestWriteRequest
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.persistDecompositionManifestProjectionFailure
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.application.workflow.service.WorkflowService
import skillbill.application.writeFromWorkflowUpdate
import skillbill.application.writeIfDecomposed
import skillbill.application.writeProjectionFromWorkflowState
import skillbill.contracts.JsonCodec
import skillbill.contracts.decomposition.DecompositionPlanningResult
import skillbill.engine.featuretask.slot.testExecutionPlan
import skillbill.model.RepositoryRoot
import skillbill.model.toPath
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.model.toSnapshot
import skillbill.workflow.NoopGoalPlanningPreparationEnvelopeValidator
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.decomposition.runtime.model.DecompositionManifestProjectionOutcome
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.WorkflowStatus
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DecompositionManifestCommitProjectionTest {
  @Test
  fun `pre-commit projection writes completed manifest before runtime commit sha is known`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-pre-commit-projection")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val subtaskSpec = parentSpecPath.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    Files.writeString(subtaskSpec, "---\nstatus: In Progress\n---\n\n# Foundation\n")
    val initial =
      writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpecPath,
          planningResult =
            decompositionPlanningResult(
              parentSpecPath = parentSpecPath.toString(),
              subtasks =
                listOf(
                  decompositionPlanningSubtask(id = 1, name = "foundation", specPath = subtaskSpec.toString()),
                ),
            ),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
      )
    assertNotNull(initial)

    val preCommit =
      writeFromWorkflowUpdate(
        repoRoot = repoRoot,
        existingArtifactsJson = durableRuntimeArtifactsJson(initial.manifest, subtaskSpec),
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf("commit_push_result" to mapOf("pre_commit_projection" to true)),
          ),
        runtimeUpdate =
          DecompositionManifestRuntimeUpdate(
            workflowId = "wfl-subtask-1",
            workflowStatus = WorkflowStatus.RUNNING.wireValue,
            currentStepId = "commit_push",
            stepUpdates =
              WorkflowStepUpdates.from(
                listOf(
                  mapOf("step_id" to "commit_push", "status" to "running", "attempt_count" to 1),
                ),
              ),
          ),
      )

    assertNotNull(preCommit)
    val projectedBeforeCommit = preCommit.manifest.subtasks.single { it.id == 1 }
    assertEquals("complete", projectedBeforeCommit.status)
    assertEquals(null, projectedBeforeCommit.commitSha)
    assertContains(Files.readString(subtaskSpec), "status: In Progress")
    val manifestTextBeforeSha = Files.readString(preCommit.manifestPath.toPath())
    assertFinalCommitProjectionUnchanged(
      repoRoot,
      subtaskSpec,
      preCommit.manifest,
      manifestTextBeforeSha,
    )
  }

  private fun assertFinalCommitProjectionUnchanged(
    repoRoot: Path,
    subtaskSpec: Path,
    manifest: DecompositionManifest,
    manifestTextBeforeSha: String,
  ) {
    val final =
      writeFromWorkflowUpdate(
        repoRoot = repoRoot,
        existingArtifactsJson = durableRuntimeArtifactsJson(manifest, subtaskSpec),
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf("commit_push_result" to mapOf("commit_sha" to "commit-subtask-1")),
          ),
        runtimeUpdate =
          DecompositionManifestRuntimeUpdate(
            workflowId = "wfl-subtask-1",
            workflowStatus = WorkflowStatus.RUNNING.wireValue,
            currentStepId = "commit_push",
            stepUpdates =
              WorkflowStepUpdates.from(
                listOf(
                  mapOf("step_id" to "commit_push", "status" to "completed", "attempt_count" to 1),
                ),
              ),
          ),
      )

    assertNotNull(final)
    assertEquals(manifestTextBeforeSha, Files.readString(final.manifestPath.toPath()))
    val projectedAfterSha = final.manifest.subtasks.single { it.id == 1 }
    assertEquals("complete", projectedAfterSha.status)
    assertEquals(null, projectedAfterSha.commitSha)
  }

  @Test
  fun `runtime update does not project commit push sha into decomposition manifest`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-commit-sha-projection")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val subtaskSpec = parentSpecPath.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    val initial =
      writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpecPath,
          planningResult =
            decompositionPlanningResult(
              parentSpecPath = parentSpecPath.toString(),
              subtasks =
                listOf(
                  decompositionPlanningSubtask(id = 1, name = "foundation", specPath = subtaskSpec.toString()),
                ),
            ),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
      )
    assertNotNull(initial)

    val result =
      writeFromWorkflowUpdate(
        repoRoot = repoRoot,
        existingArtifactsJson = durableRuntimeArtifactsJson(initial.manifest, subtaskSpec),
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf("commit_push_result" to mapOf("commit_sha" to "commit-subtask-1")),
          ),
        runtimeUpdate =
          DecompositionManifestRuntimeUpdate(
            workflowId = "wfl-subtask-1",
            workflowStatus = WorkflowStatus.COMPLETED.wireValue,
            currentStepId = "finish",
            stepUpdates =
              WorkflowStepUpdates.from(
                listOf(
                  mapOf("step_id" to "finish", "status" to "completed", "attempt_count" to 1),
                ),
              ),
          ),
      )

    assertNotNull(result)
    val subtask = result.manifest.subtasks.single { it.id == 1 }
    assertEquals("complete", subtask.status)
    assertEquals(null, subtask.commitSha)
  }

  @Test
  fun `workflow-state projection keeps runtime commit sha out of git-tracked manifest`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-commit-sha-file-projection")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val subtaskSpec = parentSpecPath.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    val initial =
      writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpecPath,
          planningResult =
            decompositionPlanningResult(
              parentSpecPath = parentSpecPath.toString(),
              subtasks =
                listOf(
                  decompositionPlanningSubtask(id = 1, name = "foundation", specPath = subtaskSpec.toString()),
                ),
            ),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
      )
    assertNotNull(initial)
    val runtimeManifest =
      initial.manifest.copy(
        subtasks =
          initial.manifest.subtasks.map { subtask ->
            subtask.copy(status = "complete", commitSha = "commit-subtask-1", workflowId = "wfl-subtask-1")
          },
      )

    val result =
      writeProjectionFromWorkflowState(
        repoRoot = repoRoot,
        artifactsJson = durableRuntimeArtifactsJson(runtimeManifest, subtaskSpec),
      )

    assertNotNull(result)
    val projected = result.manifest.subtasks.single { it.id == 1 }
    assertEquals("complete", projected.status)
    assertEquals(null, projected.commitSha)
    val loaded =
      loadDecompositionManifest(
        result.manifestPath.toPath(),
        TestDecompositionManifestStore,
        testDecompositionManifestValidator,
      )
    assertEquals(null, loaded.subtasks.single { it.id == 1 }.commitSha)
  }

  @Test
  fun `projection failure after durable runtime update leaves existing manifest bytes intact`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-projection-failure-intact")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val subtaskSpec = parentSpecPath.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    val initial =
      writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpecPath,
          planningResult =
            decompositionPlanningResult(
              parentSpecPath = parentSpecPath.toString(),
              subtasks =
                listOf(
                  decompositionPlanningSubtask(id = 1, name = "foundation", specPath = subtaskSpec.toString()),
                ),
            ),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
      )
    assertNotNull(initial)
    val manifestPath = initial.manifestPath.toPath()
    val manifestBefore = Files.readString(manifestPath)
    val failingStore =
      object : DecompositionManifestStore by TestDecompositionManifestStore {
        override fun writeTextAtomically(
          target: Path,
          content: String,
        ) {
          if (target == manifestPath) throw IOException("simulated projection write failure")
          TestDecompositionManifestStore.writeTextAtomically(target, content)
        }
      }
    val outcome =
      testDecompositionManifestWriter.writeProjectionFromWorkflowState(
        repoRoot = repoRoot,
        artifacts = durableRuntimeArtifacts(initial.manifest, subtaskSpec),
        validator = testDecompositionManifestValidator,
        fileStore = failingStore,
      )
    assertEquals(manifestBefore, Files.readString(manifestPath))
    assertIs<DecompositionManifestProjectionOutcome.Failed>(outcome)
    val recovered =
      testDecompositionManifestWriter.writeProjectionFromWorkflowState(
        repoRoot = repoRoot,
        artifacts = durableRuntimeArtifacts(initial.manifest, subtaskSpec),
        validator = testDecompositionManifestValidator,
        fileStore = TestDecompositionManifestStore,
      )
    assertIs<DecompositionManifestProjectionOutcome.Written>(recovered)
    loadDecompositionManifest(
      recovered.result.manifestPath.toPath(),
      TestDecompositionManifestStore,
      testDecompositionManifestValidator,
    )
  }

  @Test
  fun `continueWorkflow by issue key records projection failure on the parent workflow row`() {
    val setup = createProjectionRetrySetup()
    val plan =
      decompositionPlanningResult(
        parentSpecPath = setup.parentSpecPath.toString(),
        subtasks =
          listOf(
            decompositionPlanningSubtask(id = 1, name = "foundation", specPath = setup.subtaskSpec.toString()),
          ),
      )
    persistProjectionRetryPlan(setup.service, setup.opened.workflowId, plan)
    setup.failure.enabled = true
    setup.service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51")
    val parent = requireNotNull(setup.workflows.getFeatureTaskWorkflowAsMode(setup.opened.workflowId, RUNTIME))
    assertContains(
      parent.artifactsJson,
      DurableWorkflowArtifactFamily.DECOMPOSITION_MANIFEST_PROJECTION_FAILURE.label(),
    )
  }

  @Test
  fun `continueWorkflow by child workflow id records projection failure on the parent workflow row`() {
    val setup = createProjectionRetrySetup()
    val plan =
      decompositionPlanningResult(
        parentSpecPath = setup.parentSpecPath.toString(),
        subtasks =
          listOf(
            decompositionPlanningSubtask(id = 1, name = "foundation", specPath = setup.subtaskSpec.toString()),
          ),
      )
    persistProjectionRetryPlan(setup.service, setup.opened.workflowId, plan)
    setup.service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51")
    val parentBefore = requireNotNull(setup.workflows.getFeatureTaskWorkflowAsMode(setup.opened.workflowId, RUNTIME))
    val childWorkflowId =
      parentBefore.toSnapshot().decompositionRuntime()
        ?.subtasks
        ?.single()
        ?.workflowId
        ?: error("expected started subtask workflow id")
    setup.failure.enabled = true
    setup.service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, childWorkflowId)
    val parent = requireNotNull(setup.workflows.getFeatureTaskWorkflowAsMode(setup.opened.workflowId, RUNTIME))
    assertContains(
      parent.artifactsJson,
      DurableWorkflowArtifactFamily.DECOMPOSITION_MANIFEST_PROJECTION_FAILURE.label(),
    )
  }

  @Test
  fun `missing projection owner returns a typed settlement failure`() {
    val workflows = InMemoryWorkflowStates()
    val database = FakeDatabaseSessionFactory(workflows)
    val outcome =
      database.transaction { unitOfWork ->
        persistDecompositionManifestProjectionFailure(
          engine = WorkflowEngine(),
          unitOfWork = unitOfWork,
          workflowId = "missing-owner",
          outcome =
            DecompositionManifestProjectionOutcome.Failed(
              operation = "test-projection",
              targetPath = "decomposition-manifest.yaml",
            ),
        )
      }

    assertEquals(DecompositionManifestProjectionFailurePersistence.OWNER_ABSENT, outcome)
  }

  @Test
  fun `missing projection owner is also visible when clearing after a written projection`() {
    val workflows = InMemoryWorkflowStates()
    val database = FakeDatabaseSessionFactory(workflows)
    val outcome =
      database.transaction { unitOfWork ->
        clearDecompositionManifestProjectionFailure(
          engine = WorkflowEngine(),
          unitOfWork = unitOfWork,
          workflowId = "missing-owner",
        )
      }

    assertEquals(DecompositionManifestProjectionFailurePersistence.OWNER_ABSENT, outcome)
  }

  @Test
  fun `continueWorkflow preserves unknown workflow lookup`() {
    val setup = createProjectionRetrySetup()

    val result =
      setup.service.continueWorkflow(
        WorkflowFamilyKind.TASK_RUNTIME,
        "missing-workflow",
      )

    assertIs<WorkflowContinueResult.UnknownWorkflow>(result)
  }

  @Test
  fun `projection retry preserves committed workflow state and child count`() {
    val setup = createProjectionRetrySetup()
    val repoRoot = setup.repoRoot
    val parentSpecPath = setup.parentSpecPath
    val subtaskSpec = setup.subtaskSpec
    val manifestPath = setup.manifestPath
    val workflows = setup.workflows
    val service = setup.service
    val opened = setup.opened
    val plan =
      decompositionPlanningResult(
        parentSpecPath = parentSpecPath.toString(),
        subtasks =
          listOf(
            decompositionPlanningSubtask(id = 1, name = "foundation", specPath = subtaskSpec.toString()),
          ),
      )
    persistProjectionRetryPlan(service, opened.workflowId, plan)
    val manifestBeforeFailure = Files.readString(manifestPath)
    val childrenBeforeFailure = workflows.countGoalChildIdentities("SKILL-51")
    setup.failure.enabled = true

    val committedResult =
      service.update(
        WorkflowFamilyKind.TASK_RUNTIME,
        WorkflowUpdateRequest(
          workflowId = opened.workflowId,
          workflowStatus = WorkflowStatus.RUNNING.wireValue,
          currentStepId = "implement",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1)),
            ),
          artifactsPatch =
            WorkflowArtifactPatch.from(
              mapOf("projection_probe" to mapOf("value" to "committed")),
            ),
        ),
      )
    assertIs<WorkflowUpdateResult.Ok>(committedResult)
    val afterFailure = requireNotNull(workflows.getFeatureTaskWorkflowAsMode(opened.workflowId, RUNTIME))
    assertContains(afterFailure.artifactsJson, "projection_probe")
    assertContains(
      afterFailure.artifactsJson,
      DurableWorkflowArtifactFamily.DECOMPOSITION_MANIFEST_PROJECTION_FAILURE.label(),
    )
    assertEquals("implement", afterFailure.currentStepId)
    assertEquals(manifestBeforeFailure, Files.readString(manifestPath))
    assertEquals(childrenBeforeFailure, workflows.countGoalChildIdentities("SKILL-51"))

    setup.failure.enabled = false
    val retryOutcome = service.retryDecompositionManifestProjection(opened.workflowId)
    val written = assertIs<DecompositionManifestProjectionOutcome.Written>(retryOutcome)
    assertEquals(
      written.result.manifest,
      loadDecompositionManifest(manifestPath, TestDecompositionManifestStore, testDecompositionManifestValidator),
    )
    val afterRetry = requireNotNull(workflows.getFeatureTaskWorkflowAsMode(opened.workflowId, RUNTIME))
    assertContains(afterRetry.artifactsJson, "projection_probe")
    assertTrue(
      DurableWorkflowArtifactFamily.DECOMPOSITION_MANIFEST_PROJECTION_FAILURE.label() !in afterRetry.artifactsJson,
    )
    assertEquals(afterFailure.currentStepId, afterRetry.currentStepId)
    assertEquals(afterFailure.workflowStatus, afterRetry.workflowStatus)
    assertEquals(afterFailure.stepsJson, afterRetry.stepsJson)
    assertEquals(childrenBeforeFailure, workflows.countGoalChildIdentities("SKILL-51"))
  }

  @Test
  fun `projection retry clears a parent failure recorded by child continuation`() {
    val setup = createProjectionRetrySetup()
    val plan =
      decompositionPlanningResult(
        parentSpecPath = setup.parentSpecPath.toString(),
        subtasks =
          listOf(
            decompositionPlanningSubtask(id = 1, name = "foundation", specPath = setup.subtaskSpec.toString()),
          ),
      )
    persistProjectionRetryPlan(setup.service, setup.opened.workflowId, plan)
    setup.service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "SKILL-51")
    val parentBeforeFailure =
      requireNotNull(
        setup.workflows.getFeatureTaskWorkflowAsMode(setup.opened.workflowId, RUNTIME),
      )
    val childWorkflowId =
      parentBeforeFailure.toSnapshot()
        .decompositionRuntime()
        ?.subtasks
        ?.single()
        ?.workflowId
        ?: error("expected started subtask workflow id")
    val childBeforeFailure = requireNotNull(setup.workflows.getFeatureTaskWorkflowAsMode(childWorkflowId, RUNTIME))
    val childrenBeforeFailure = setup.workflows.countGoalChildIdentities("SKILL-51")

    setup.failure.enabled = true
    setup.service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, childWorkflowId)
    val parentAfterFailure =
      requireNotNull(
        setup.workflows.getFeatureTaskWorkflowAsMode(setup.opened.workflowId, RUNTIME),
      )
    assertContains(
      parentAfterFailure.artifactsJson,
      DurableWorkflowArtifactFamily.DECOMPOSITION_MANIFEST_PROJECTION_FAILURE.label(),
    )

    setup.failure.enabled = false
    val retryOutcome = setup.service.retryDecompositionManifestProjection(setup.opened.workflowId)
    assertIs<DecompositionManifestProjectionOutcome.Written>(retryOutcome)
    val parentAfterRetry =
      requireNotNull(
        setup.workflows.getFeatureTaskWorkflowAsMode(setup.opened.workflowId, RUNTIME),
      )
    val childAfterRetry = requireNotNull(setup.workflows.getFeatureTaskWorkflowAsMode(childWorkflowId, RUNTIME))
    assertTrue(
      DurableWorkflowArtifactFamily.DECOMPOSITION_MANIFEST_PROJECTION_FAILURE.label() !in
        parentAfterRetry.artifactsJson,
    )
    assertEquals(childBeforeFailure.currentStepId, childAfterRetry.currentStepId)
    assertEquals(childBeforeFailure.workflowStatus, childAfterRetry.workflowStatus)
    assertEquals(childrenBeforeFailure, setup.workflows.countGoalChildIdentities("SKILL-51"))
  }

  private fun createProjectionRetrySetup(): ProjectionRetrySetup {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-projection-retry")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val subtaskSpec = parentSpecPath.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    Files.writeString(subtaskSpec, "# Foundation\n")
    val failure = ProjectionRetryFailure()
    val manifestPath = parentSpecPath.parent.resolve("decomposition-manifest.yaml")
    val fileStore =
      object : DecompositionManifestStore by TestDecompositionManifestStore {
        override fun writeTextAtomically(
          target: Path,
          content: String,
        ) {
          if (failure.enabled && target == manifestPath) throw IOException("simulated projection failure")
          TestDecompositionManifestStore.writeTextAtomically(target, content)
        }
      }
    val workflows = InMemoryWorkflowStates()
    val repositoryIdentity = "repo-root-realpath-v1:${repoRoot.toRealPath()}"
    workflows.saveFeatureTaskExecutionIdentity(
      FeatureTaskExecutionIdentity(
        workflowId = "child-existing",
        normalizedIssueKey = "SKILL-51",
        repositoryIdentity = repositoryIdentity,
        governedSpecPath = ".feature-specs/SKILL-51-decomposition/spec_subtask_1_foundation.md",
        mode = RUNTIME,
        routeScope = FeatureTaskRouteScope.GOAL_CHILD,
      ),
    )
    val service =
      WorkflowService(
        database = FakeDatabaseSessionFactory(workflows),
        gitOperations = NoopWorkflowGitOperations,
        decompositionManifestStore = fileStore,
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestWriter = DecompositionManifestWriter(),
        repositoryRoot = RepositoryRoot(repoRoot),
        goalObservabilityEventValidator = NoopGoalPlanningPreparationEnvelopeValidator,
        runtimeDiagnostics = NoopRuntimeDiagnostics,
        clock = Clock.systemUTC(),
      )
    val opened =
      assertIs<WorkflowOpenResult.Ok>(
        service.open(
          WorkflowServiceOpenArgs(
            kind = WorkflowFamilyKind.TASK_RUNTIME,
            executionPlan = testExecutionPlan(),
            issueKey = "SKILL-51",
            repositoryIdentity = repositoryIdentity,
            governedSpecPath = ".feature-specs/SKILL-51-decomposition/spec.md",
          ),
        ),
      )
    return ProjectionRetrySetup(
      repoRoot,
      parentSpecPath,
      subtaskSpec,
      manifestPath,
      workflows,
      service,
      opened,
      failure,
    )
  }

  private fun persistProjectionRetryPlan(
    service: WorkflowService,
    workflowId: String,
    plan: DecompositionPlanningResult,
  ) {
    service.update(
      WorkflowFamilyKind.TASK_RUNTIME,
      WorkflowUpdateRequest(
        workflowId = workflowId,
        workflowStatus = WorkflowStatus.RUNNING.wireValue,
        currentStepId = "plan",
        stepUpdates =
          WorkflowStepUpdates.from(
            listOf(mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1)),
          ),
        artifactsPatch = WorkflowArtifactPatch.from(mapOf("plan" to plan.toPayload())),
        planningResult = plan,
      ),
    )
  }

  private fun durableRuntimeArtifactsJson(
    manifest: DecompositionManifest,
    subtaskSpec: Path,
  ): String =
    JsonCodec.mapToJsonString(
      mapOf(
        DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label() to
          testDecompositionManifestValidator.encodeManifestWireMap(
            manifest,
            DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label(),
          ),
        "assessment" to mapOf("spec_path" to subtaskSpec.toString()),
        "goal_continuation" to
          mapOf(
            "issue_key" to "SKILL-51",
            "subtask_id" to 1,
            "suppress_pr" to true,
            "goal_branch" to manifest.featureBranch,
            "code_review_mode" to "inline",
          ),
      ),
    )

  private fun durableRuntimeArtifacts(
    manifest: DecompositionManifest,
    subtaskSpec: Path,
  ): DurableWorkflowArtifacts =
    DurableWorkflowArtifacts.fromMap(
      requireNotNull(
        JsonCodec.anyToStringAnyMap(
          JsonCodec.parseValue(durableRuntimeArtifactsJson(manifest, subtaskSpec)),
        ),
      ),
    )
}

private class ProjectionRetryFailure(var enabled: Boolean = false)

private data class ProjectionRetrySetup(
  val repoRoot: Path,
  val parentSpecPath: Path,
  val subtaskSpec: Path,
  val manifestPath: Path,
  val workflows: InMemoryWorkflowStates,
  val service: WorkflowService,
  val opened: WorkflowOpenResult.Ok,
  val failure: ProjectionRetryFailure,
)
