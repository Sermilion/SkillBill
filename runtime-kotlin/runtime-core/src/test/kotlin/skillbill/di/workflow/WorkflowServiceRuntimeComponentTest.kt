package skillbill.di.workflow

import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.di.core.OptionalCallbacks
import skillbill.di.core.RuntimeComponent
import skillbill.di.core.RuntimeContext
import skillbill.di.core.TransportContext
import skillbill.di.core.WorkflowOpsContext
import skillbill.di.core.create
import skillbill.model.EnvironmentContext
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import skillbill.application.workflow.model.WorkflowOpenResult.Ok as WorkflowOpenResultOk
import skillbill.application.workflow.model.WorkflowUpdateResult.Ok as WorkflowUpdateResultOk

class WorkflowServiceRuntimeComponentTest {
  @Test
  fun `runtime component workflow service writes decomposition projection through configured file store`() {
    val tempDir = Files.createTempDirectory("skillbill-component-decomposition")
    val parentSpec = tempDir.resolve(".feature-specs/SKILL-51-demo/spec.md")
    val subtaskSpec = parentSpec.parent.resolve("spec_subtask_1_foundation.md")
    Files.createDirectories(parentSpec.parent)
    Files.writeString(parentSpec, "# Parent")
    val service =
      RuntimeComponent::class.create(
        RuntimeContext(
          environment =
            EnvironmentContext(
              environment = emptyMap(),
              userHome = tempDir,
              repositoryRoot = tempDir,
            ),
          transport = TransportContext(),
          workflowOps = WorkflowOpsContext(),
          callbacks = OptionalCallbacks(),
        ),
      ).workflowService
    val opened = service.openTestFeatureTask(WorkflowFamilyKind.TASK_RUNTIME, sessionId = "ftr-001")
    val workflowId =
      (opened as WorkflowOpenResultOk).workflowId

    val updated =
      decompositionPlanPatch(parentSpec, subtaskSpec).let { artifactsPatch ->
        service.update(
          WorkflowFamilyKind.TASK_RUNTIME,
          WorkflowUpdateRequest(
            workflowId = workflowId,
            workflowStatus = WorkflowStatus.RUNNING.wireValue,
            currentStepId = "plan",
            stepUpdates =
              WorkflowStepUpdates.from(
                listOf(
                  mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1),
                ),
              ),
            artifactsPatch = artifactsPatch,
            planningResult = decompositionPlanningResultFromPatch(artifactsPatch),
          ),
        )
      }

    val manifest = parentSpec.parent.resolve("decomposition-manifest.yaml")
    assertTrue(updated is WorkflowUpdateResultOk)
    assertTrue(Files.isRegularFile(manifest), "RuntimeComponent must bind a writable decomposition manifest store.")
    assertTrue(Files.readString(manifest).contains("same_branch_commit_per_subtask"))
  }

  private fun decompositionPlanPatch(
    parentSpec: Path,
    subtaskSpec: Path,
  ): WorkflowArtifactPatch =
    WorkflowArtifactPatch.from(
      mapOf(
        "branch" to mapOf("branch" to "feat/SKILL-51-demo"),
        "plan" to
          linkedMapOf(
            "mode" to "decompose",
            "parent_spec_path" to parentSpec.toString(),
            "recommended_first_subtask_id" to 1,
            "subtasks" to
              listOf(
                mapOf(
                  "id" to 1,
                  "name" to "foundation",
                  "spec_path" to subtaskSpec.toString(),
                  "depends_on" to emptyList<Int>(),
                ),
              ),
          ),
      ),
    )!!
}
