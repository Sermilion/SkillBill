package skillbill.application

import skillbill.application.decomposition.DecompositionManifestProjectionOperations
import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.decompositionPlanningResult
import skillbill.application.decomposition.decompositionPlanningSubtask
import skillbill.application.decomposition.model.DecompositionManifestWriteRequest
import skillbill.application.decomposition.parentSpecPath
import skillbill.contracts.JsonCodec
import skillbill.model.toPath
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.runtime.model.DecompositionManifestProjectionOutcome
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class DecompositionManifestProjectionOutcomeTest {
  @Test
  fun `workflow-state projection returns absent when runtime artifact is missing`() {
    val repoRoot = Files.createTempDirectory("skillbill-projection-absent")
    val outcome =
      testDecompositionManifestWriter.writeProjectionFromWorkflowState(
        repoRoot = repoRoot,
        artifacts = DurableWorkflowArtifacts.EMPTY,
        validator = testDecompositionManifestValidator,
        fileStore = TestDecompositionManifestStore,
      )
    assertEquals(DecompositionManifestProjectionOutcome.Absent, outcome)
  }

  @Test
  fun `completed workflow projection follows archived specs without recreating active bundle`() {
    val repoRoot = Files.createTempDirectory("skillbill-projection-written")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
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
                  decompositionPlanningSubtask(id = 1, name = "foundation", specPath = parentSpecPath.toString()),
                ),
            ),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
      )
    assertNotNull(initial)
    val archivedDirectory = repoRoot.resolve(".feature-specs/done/SKILL-51-decomposition")
    Files.createDirectories(archivedDirectory.parent)
    Files.move(parentSpecPath.parent, archivedDirectory)
    val completeManifest =
      initial.manifest.copy(
        subtasks = initial.manifest.subtasks.map { it.copy(status = "complete") },
      )
    val outcome =
      testDecompositionManifestWriter.writeProjectionFromWorkflowState(
        repoRoot = repoRoot,
        artifacts = durableRuntimeArtifacts(completeManifest),
        validator = testDecompositionManifestValidator,
        fileStore = TestDecompositionManifestStore,
      )
    val written = assertIs<DecompositionManifestProjectionOutcome.Written>(outcome)
    assertEquals(archivedDirectory.resolve("decomposition-manifest.yaml"), written.result.manifestPath.toPath())
    assertEquals(".feature-specs/done/SKILL-51-decomposition/spec.md", written.result.manifest.parentSpecPath)
    assertEquals(written.result.manifest.parentSpecPath, written.result.manifest.subtasks.single().specPath)
    assertEquals("complete", written.result.manifest.subtasks.single().status)
    assertFalse(Files.exists(parentSpecPath.parent))
  }

  @Test
  fun `workflow-state projection returns failed with operation and path when filesystem write fails`() {
    val repoRoot = Files.createTempDirectory("skillbill-projection-failed")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
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
                  decompositionPlanningSubtask(id = 1, name = "foundation", specPath = parentSpecPath.toString()),
                ),
            ),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
      )
    assertNotNull(initial)
    val manifestPath = initial.manifestPath.toPath()
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
        artifacts = durableRuntimeArtifacts(initial.manifest),
        validator = testDecompositionManifestValidator,
        fileStore = failingStore,
      )
    val failed = assertIs<DecompositionManifestProjectionOutcome.Failed>(outcome)
    assertEquals(DecompositionManifestProjectionOperations.WRITE_PROJECTION_FROM_WORKFLOW_STATE, failed.operation)
    assertEquals(manifestPath.toString(), failed.targetPath)
  }

  private fun durableRuntimeArtifactsJson(manifest: DecompositionManifest): String =
    JsonCodec.mapToJsonString(
      mapOf(
        DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label() to
          testDecompositionManifestValidator.encodeManifestWireMap(
            manifest,
            DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label(),
          ),
      ),
    )

  private fun durableRuntimeArtifacts(manifest: DecompositionManifest): DurableWorkflowArtifacts =
    DurableWorkflowArtifacts.fromMap(
      requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(durableRuntimeArtifactsJson(manifest)))),
    )
}
