package skillbill.application

import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.decompositionPlanningSubtask
import skillbill.application.decomposition.executionModel
import skillbill.application.decomposition.loadDecompositionManifest
import skillbill.application.decomposition.model.DecompositionManifestRuntimeUpdate
import skillbill.application.decomposition.model.DecompositionManifestWriteRequest
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.parseStackBranches
import skillbill.contracts.JsonCodec
import skillbill.contracts.decomposition.DecompositionPlanningResult
import skillbill.error.shellcontent.InvalidDecompositionManifestSchemaError
import skillbill.model.toPath
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DecompositionManifestWriterTest {
  private val engine: WorkflowEngine = WorkflowEngine()

  @Test
  fun `decomposition planning result writes validated same branch manifest beside parent spec`() {
    val repoRoot = Files.createTempDirectory("skillbill-decomposition-manifest")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")

    val result =
      writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpecPath,
          planningResult = decompositionPlanningPlan(Path.of(".feature-specs/SKILL-51-decomposition/spec.md")),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
      )

    assertNotNull(result)
    assertTrue(Files.isRegularFile(result.manifestPath.toPath()))
    assertEquals(parentSpecPath.parent.resolve("decomposition-manifest.yaml"), result.manifestPath.toPath())

    val loaded = loadDecompositionManifest(result.manifestPath.toPath())
    assertEquals("same_branch_commit_per_subtask", loaded.executionModel.wireValue)
    assertEquals("feature/SKILL-51-decomposition", loaded.featureBranch)
    assertEquals(emptyList(), loaded.stackBranches)
    assertEquals(1, loaded.currentSubtaskIntent.subtaskId)
    assertEquals("start", loaded.currentSubtaskIntent.action)
    assertEquals(null, loaded.subtasks.first().workflowId)
  }

  @Test
  fun `workflow update writes stacked branch manifest without same branch feature branch`() {
    val repoRoot = Files.createTempDirectory("skillbill-stacked-decomposition")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")

    val result =
      writeFromWorkflowUpdate(
        repoRoot = repoRoot,
        existingArtifactsJson = "{}",
        artifactsPatch = WorkflowArtifactPatch.from(mapOf("plan" to stackedDecompositionPlanningPlan().toPayload())),
      )

    assertNotNull(result)
    assertEquals("stacked_branches", result.manifest.executionModel.wireValue)
    assertEquals(null, result.manifest.featureBranch)
    assertEquals(
      listOf("feature/SKILL-51-01-foundation", "feature/SKILL-51-02-runtime"),
      result.manifest.stackBranches.map { it.branch },
    )
  }

  @Test
  fun `workflow update records runtime state without mutating the subtask spec`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-state")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val secondSubtaskSpec = parentSpecPath.parent.resolve("spec_subtask_2_runtime.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    Files.writeString(
      secondSubtaskSpec,
      """
      ---
      status: Pending
      ---

      # Runtime
      """.trimIndent(),
    )
    writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = decompositionPlanningPlan(parentSpecPath),
        baseBranch = "main",
        featureBranch = "feature/SKILL-51-decomposition",
      ),
    )

    val result =
      writeFromWorkflowUpdate(
        repoRoot = repoRoot,
        existingArtifactsJson = runtimeArtifactsJson(secondSubtaskSpec),
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf("review_result" to mapOf("finding_count" to 0)),
          ),
        runtimeUpdate =
          DecompositionManifestRuntimeUpdate(
            workflowId = "wfl-subtask-2",
            workflowStatus = WorkflowStatus.RUNNING.wireValue,
            currentStepId = "audit",
            stepUpdates =
              WorkflowStepUpdates.from(
                listOf(
                  mapOf("step_id" to "review", "status" to "completed", "attempt_count" to 1),
                ),
              ),
          ),
      )

    assertNotNull(result)
    val runtimeSubtask = result.manifest.subtasks.single { it.id == 2 }
    assertEquals("in_progress", runtimeSubtask.status)
    assertEquals("feature/SKILL-51-decomposition", runtimeSubtask.branch)
    assertEquals("wfl-subtask-2", runtimeSubtask.workflowId)
    assertEquals("audit", runtimeSubtask.lastResumableStep)
    assertContains(Files.readString(secondSubtaskSpec), "status: Pending")
  }

  @Test
  fun `runtime update projects completed subtask state only to the manifest`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-completed-projection")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val subtaskSpec = parentSpecPath.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    Files.writeString(
      subtaskSpec,
      """
      ---
      status: In Progress
      ---

      # Foundation
      """.trimIndent(),
    )
    val initial =
      writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpecPath,
          planningResult = decompositionPlanningPlan(parentSpecPath),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
      )
    assertNotNull(initial)
    val completed =
      initial.manifest.copy(
        subtasks =
          initial.manifest.subtasks.map { subtask ->
            if (subtask.id == 1) {
              subtask.copy(status = "complete", commitSha = "commit-subtask-1", workflowId = "wfl-subtask-1")
            } else {
              subtask
            }
          },
      )

    val result =
      writeFromWorkflowUpdate(
        repoRoot = repoRoot,
        existingArtifactsJson = durableRuntimeArtifactsJson(completed, subtaskSpec),
        artifactsPatch = null,
        runtimeUpdate =
          DecompositionManifestRuntimeUpdate(
            workflowId = "wfl-subtask-1",
            workflowStatus = WorkflowStatus.COMPLETED.wireValue,
            currentStepId = "complete",
            stepUpdates =
              WorkflowStepUpdates.from(
                listOf(
                  mapOf("step_id" to "complete", "status" to "completed", "attempt_count" to 1),
                ),
              ),
          ),
      )

    assertNotNull(result)
    val subtask = result.manifest.subtasks.single { it.id == 1 }
    assertEquals("complete", subtask.status)
    assertEquals(null, subtask.commitSha)
    assertContains(Files.readString(subtaskSpec), "status: In Progress")
  }

  @Test
  fun `pending runtime projection leaves spec content unchanged`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-reset-projection")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val subtaskSpec = parentSpecPath.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "---\nstatus: Blocked\n---\n\n# Parent spec\n")
    Files.writeString(subtaskSpec, "---\nstatus: Blocked\n---\n\n# Foundation\n")
    val initial =
      writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpecPath,
          planningResult = decompositionPlanningPlan(parentSpecPath),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
      )
    assertNotNull(initial)
    val reset =
      initial.manifest.copy(
        status = "pending",
        subtasks = initial.manifest.subtasks.map { subtask -> subtask.copy(status = "pending") },
      )

    val result =
      writeProjectionFromWorkflowState(
        repoRoot,
        JsonCodec.mapToJsonString(
          mapOf(
            DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label() to
              testDecompositionManifestValidator.encodeManifestWireMap(reset),
          ),
        ),
      )

    assertNotNull(result)
    assertContains(Files.readString(parentSpecPath), "status: Blocked")
    assertContains(Files.readString(subtaskSpec), "status: Blocked")
  }

  @Test
  fun `runtime update with explicit unmatched spec path does not fall back to current subtask`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-unmatched-spec")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = decompositionPlanningPlan(parentSpecPath),
        baseBranch = "main",
        featureBranch = "feature/SKILL-51-decomposition",
      ),
    )

    val result =
      writeFromWorkflowUpdate(
        repoRoot = repoRoot,
        existingArtifactsJson = runtimeArtifactsJson(parentSpecPath.parent.resolve("missing-subtask.md")),
        artifactsPatch = WorkflowArtifactPatch.from(mapOf("review_result" to mapOf("finding_count" to 0))),
        runtimeUpdate =
          DecompositionManifestRuntimeUpdate(
            workflowId = "wfl-wrong-subtask",
            workflowStatus = WorkflowStatus.RUNNING.wireValue,
            currentStepId = "audit",
            stepUpdates =
              WorkflowStepUpdates.from(
                listOf(
                  mapOf("step_id" to "review", "status" to "completed", "attempt_count" to 1),
                ),
              ),
          ),
      )

    assertNotNull(result)
    assertEquals(listOf("pending", "pending"), result.manifest.subtasks.map { it.status })
    assertEquals(null, result.manifest.subtasks.first().workflowId)
  }

  @Test
  fun `skipping an intermediate tracked step keeps subtask in progress when workflow advances`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-intermediate-skip")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val subtaskSpec = parentSpecPath.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = decompositionPlanningPlan(parentSpecPath),
        baseBranch = "main",
        featureBranch = "feature/SKILL-51-decomposition",
      ),
    )

    val result =
      writeFromWorkflowUpdate(
        repoRoot = repoRoot,
        existingArtifactsJson = runtimeArtifactsJson(subtaskSpec),
        artifactsPatch = null,
        runtimeUpdate =
          DecompositionManifestRuntimeUpdate(
            workflowId = "wfl-subtask-1",
            workflowStatus = WorkflowStatus.RUNNING.wireValue,
            currentStepId = "audit",
            stepUpdates =
              WorkflowStepUpdates.from(
                listOf(
                  mapOf("step_id" to "review", "status" to "skipped", "attempt_count" to 1),
                ),
              ),
          ),
      )

    assertNotNull(result)
    assertEquals("in_progress", result.manifest.subtasks.first().status)
    assertEquals("resume", result.manifest.currentSubtaskIntent.action)
  }

  @Test
  fun `runtime projection is regenerated from durable decomposition runtime when manifest file is missing`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-regenerate")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val subtaskSpec = parentSpecPath.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    val initial =
      writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpecPath,
          planningResult = decompositionPlanningPlan(parentSpecPath),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
      )
    assertNotNull(initial)
    Files.delete(initial.manifestPath.toPath())

    val result =
      writeFromWorkflowUpdate(
        repoRoot = repoRoot,
        existingArtifactsJson = durableRuntimeArtifactsJson(initial.manifest, subtaskSpec),
        artifactsPatch = WorkflowArtifactPatch.from(mapOf("validation_result" to mapOf("passed" to true))),
        runtimeUpdate =
          DecompositionManifestRuntimeUpdate(
            workflowId = "wfl-subtask-1",
            workflowStatus = WorkflowStatus.RUNNING.wireValue,
            currentStepId = "validate",
            stepUpdates =
              WorkflowStepUpdates.from(
                listOf(
                  mapOf("step_id" to "validate", "status" to "completed", "attempt_count" to 1),
                ),
              ),
          ),
      )

    assertNotNull(result)
    assertTrue(Files.isRegularFile(initial.manifestPath.toPath()))
    assertEquals("in_progress", result.manifest.subtasks.first().status)
    assertEquals("wfl-subtask-1", result.manifest.subtasks.first().workflowId)
  }

  @Test
  fun `runtime updates prefer durable decomposition runtime over stale filesystem projection`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-durable-first")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val subtaskSpec = parentSpecPath.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    val initial =
      writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpecPath,
          planningResult = decompositionPlanningPlan(parentSpecPath),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
      )
    assertNotNull(initial)
    val durable =
      initial.manifest.copy(
        subtasks =
          initial.manifest.subtasks.map { subtask ->
            if (subtask.id == 1) {
              subtask.copy(status = "in_progress", commitSha = "abc123", workflowId = "wfl-subtask-1")
            } else {
              subtask
            }
          },
      )

    val result =
      writeFromWorkflowUpdate(
        repoRoot = repoRoot,
        existingArtifactsJson = durableRuntimeArtifactsJson(durable, subtaskSpec),
        artifactsPatch = WorkflowArtifactPatch.from(mapOf("review_result" to mapOf("finding_count" to 0))),
        runtimeUpdate =
          DecompositionManifestRuntimeUpdate(
            workflowId = "wfl-subtask-1",
            workflowStatus = WorkflowStatus.RUNNING.wireValue,
            currentStepId = "audit",
            stepUpdates =
              WorkflowStepUpdates.from(
                listOf(
                  mapOf("step_id" to "review", "status" to "completed", "attempt_count" to 1),
                ),
              ),
          ),
      )

    assertNotNull(result)
    val subtask = result.manifest.subtasks.first()
    assertEquals(null, subtask.commitSha)
    assertEquals("wfl-subtask-1", subtask.workflowId)
  }

  @Test
  fun `execution model can change before any subtask starts`() {
    val repoRoot = Files.createTempDirectory("skillbill-model-change-before-start")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = decompositionPlanningPlan(parentSpecPath),
        baseBranch = "main",
        featureBranch = "feature/SKILL-51-decomposition",
      ),
    )

    val changed =
      writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpecPath,
          planningResult = stackedDecompositionPlanningPlan(parentSpecPath),
          baseBranch = "main",
          featureBranch = null,
          executionModel = DecompositionExecutionModel.STACKED_BRANCHES,
          stackBranches = parseStackBranches(stackedDecompositionPlanningPlan(parentSpecPath)),
        ),
      )

    assertNotNull(changed)
    assertEquals("stacked_branches", changed.manifest.executionModel.wireValue)
  }

  @Test
  fun `execution model change after subtask starts fails with manual migration message`() {
    val repoRoot = Files.createTempDirectory("skillbill-model-change-after-start")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = decompositionPlanningPlan(parentSpecPath),
        baseBranch = "main",
        featureBranch = "feature/SKILL-51-decomposition",
      ),
    )
    writeFromWorkflowUpdate(
      repoRoot = repoRoot,
      existingArtifactsJson = runtimeArtifactsJson(parentSpecPath.parent.resolve("spec_subtask_1_foundation.md")),
      artifactsPatch = null,
      runtimeUpdate =
        DecompositionManifestRuntimeUpdate(
          workflowId = "wfl-subtask-1",
          workflowStatus = WorkflowStatus.RUNNING.wireValue,
          currentStepId = "implement",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
              ),
            ),
        ),
    )

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        writeIfDecomposed(
          DecompositionManifestWriteRequest(
            repoRoot = repoRoot,
            parentSpecPath = parentSpecPath,
            planningResult = stackedDecompositionPlanningPlan(parentSpecPath),
            baseBranch = "main",
            featureBranch = null,
            executionModel = DecompositionExecutionModel.STACKED_BRANCHES,
            stackBranches = parseStackBranches(stackedDecompositionPlanningPlan(parentSpecPath)),
          ),
        )
      }

    assertContains(error.reason, "execution_model cannot change after decomposition execution has begun")
    assertContains(error.reason, "manually migrate")
  }

  @Test
  fun `single spec implement plan does not require or write decomposition manifest`() {
    val repoRoot = Files.createTempDirectory("skillbill-single-spec")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-single/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")

    val result =
      writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpecPath,
          planningResult =
            DecompositionPlanningResult(
              mode = "implement",
              subtasks = listOf(nonDecomposeSubtaskPlaceholder()),
            ),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-single",
        ),
      )

    assertEquals(null, result)
    assertFalse(Files.exists(parentSpecPath.parent.resolve("decomposition-manifest.yaml")))

    val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
    val opened = engine.openRecord(definition, "ftr-compat-001", "session-compat", "plan")
    val updated =
      engine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "implement",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1),
                mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
              ),
            ),
          artifactsPatch = WorkflowArtifactPatch.from(mapOf("plan" to mapOf("mode" to "implement", "task_count" to 1))),
          sessionId = "session-compat",
        ),
      )

    val snapshot = engine.snapshotView(definition, updated)
    assertEquals(mapOf("mode" to "implement", "task_count" to 1), snapshot.artifacts["plan"])
  }

  @Test
  fun `decomposition planning rejects non exact integer subtask ids before schema validation`() {
    val repoRoot = Files.createTempDirectory("skillbill-decomposition-exact-int")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")

    val fractional =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        writeIfDecomposed(
          DecompositionManifestWriteRequest(
            repoRoot = repoRoot,
            parentSpecPath = parentSpecPath,
            planningResult = decompositionPlanWithFirstSubtaskId(parentSpecPath, 1.5),
            baseBranch = "main",
            featureBranch = "feature/SKILL-51-decomposition",
          ),
        )
      }
    val oversized =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        writeIfDecomposed(
          DecompositionManifestWriteRequest(
            repoRoot = repoRoot,
            parentSpecPath = parentSpecPath,
            planningResult = decompositionPlanWithFirstSubtaskId(parentSpecPath, 4_294_967_297L),
            baseBranch = "main",
            featureBranch = "feature/SKILL-51-decomposition",
          ),
        )
      }

    assertContains(fractional.reason, "id must be an integer")
    assertContains(oversized.reason, "id must be an integer")
  }

  @Test
  fun `decomposition planning rejects non string base branch without coercion`() {
    val repoRoot = Files.createTempDirectory("skillbill-decomposition-base-branch-shape")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    val plan = decompositionPlanningPlan(parentSpecPath).toPayload().toMutableMap().apply { put("base_branch", 17) }

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        writeFromWorkflowUpdate(
          repoRoot = repoRoot,
          existingArtifactsJson = "{}",
          artifactsPatch = WorkflowArtifactPatch.from(mapOf("plan" to plan)),
        )
      }

    assertContains(error.reason, "base_branch")
    assertContains(error.reason, "nonblank string")
  }

  private fun decompositionPlanWithFirstSubtaskId(
    parentSpecPath: Path,
    subtaskId: Any,
  ): DecompositionPlanningResult {
    val plan = decompositionPlanningPlan(parentSpecPath).toPayload().toMutableMap()
    val subtasks =
      (plan.getValue("subtasks") as List<*>).mapIndexed { index, raw ->
        val item =
          (raw as Map<*, *>).entries.associateTo(LinkedHashMap<String, Any?>()) { (key, value) ->
            key.toString() to value
          }
        if (index == 0) {
          item["id"] = subtaskId
        }
        item
      }
    plan["subtasks"] = subtasks
    return DecompositionPlanningResult.fromWireMap(plan, parentSpecPath.toString())
  }

  private fun nonDecomposeSubtaskPlaceholder() =
    decompositionPlanningSubtask(id = 1, name = "placeholder", specPath = "placeholder.md")

  private fun runtimeArtifactsJson(subtaskSpec: Path): String =
    """{"assessment":{"spec_path":"${subtaskSpec.toString().replace("\\", "\\\\")}"},""" +
      """"branch":{"branch":"feature/SKILL-51-decomposition"}}"""

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
        "branch" to mapOf("branch" to "feature/SKILL-51-decomposition"),
      ),
    )
}
