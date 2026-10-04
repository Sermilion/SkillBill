package skillbill.application

import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.decompositionPlanningResult
import skillbill.application.decomposition.decompositionPlanningSubtask
import skillbill.application.decomposition.model.DecompositionManifestRuntimeUpdate
import skillbill.application.decomposition.model.DecompositionManifestWriteRequest
import skillbill.application.decomposition.parentSpecPath
import skillbill.contracts.JsonCodec
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class DecompositionManifestPayloadProjectionTest {
  @Test
  fun `decomposition runtime omits review audit and validation result payloads`() {
    val repoRoot = Files.createTempDirectory("skillbill-runtime-omits-result-payloads")
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
                  decompositionPlanningSubtask(id = 1, name = "Foundation", specPath = subtaskSpec.toString()),
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
            mapOf(
              "review_result" to mapOf("finding_count" to 0),
              "audit_report" to mapOf("pass" to true),
              "validation_result" to mapOf("passed" to true),
            ),
          ),
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
    val subtaskWire = firstSubtaskWire(result.manifest)
    assertFalse("review_result" in subtaskWire)
    assertFalse("audit_result" in subtaskWire)
    assertFalse("validation_result" in subtaskWire)
  }

  private fun firstSubtaskWire(manifest: DecompositionManifest): Map<*, *> =
    (
      testDecompositionManifestValidator.encodeManifestWireMap(manifest).getValue("subtasks") as List<*>
    ).first() as Map<*, *>

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
