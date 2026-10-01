package skillbill.di.workflow

import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.model.DecompositionManifestWorkflowProjectionInput
import skillbill.application.decomposition.model.DecompositionManifestWriteRequest
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decompositionPlanningPlan
import skillbill.error.shellcontent.InvalidDecompositionManifestSchemaError
import skillbill.infrastructure.contracts.workflow.decomposition.DecompositionManifestSchemaValidator
import skillbill.infrastructure.workflow.decomposition.FileSystemDecompositionManifestFileStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.runtime.invalidManifest
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class DecompositionManifestWriterValidationTest {
  private val validator: DecompositionManifestValidator = DecompositionManifestSchemaValidator()
  private val fileStore = FileSystemDecompositionManifestFileStore()
  private val writer = DecompositionManifestWriter()

  @Test
  fun `workflow update rejects schema invalid durable decomposition runtime`() {
    val repoRoot = Files.createTempDirectory("skillbill-invalid-runtime-update")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    val initial =
      writer.writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpecPath,
          planningResult = decompositionPlanningPlan(parentSpecPath),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
        validator,
        fileStore,
      )
    assertNotNull(initial)

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        writer.writeFromWorkflowUpdate(
          DecompositionManifestWorkflowProjectionInput(
            repoRoot = repoRoot,
            existingArtifacts = invalidDurableRuntimeArtifacts(initial.manifest),
            validator = validator,
            fileStore = fileStore,
          ),
        )
      }

    assertEquals("decomposition_runtime", error.sourceLabel)
    assertContains(error.reason, "contract_version")
    assertContains(error.reason, "offending value: invalid-contract")
  }

  @Test
  fun `workflow projection rejects schema invalid durable decomposition runtime`() {
    val repoRoot = Files.createTempDirectory("skillbill-invalid-runtime-projection")
    val parentSpecPath = repoRoot.resolve(".feature-specs/SKILL-51-decomposition/spec.md")
    Files.createDirectories(parentSpecPath.parent)
    Files.writeString(parentSpecPath, "# Parent spec\n")
    val initial =
      writer.writeIfDecomposed(
        DecompositionManifestWriteRequest(
          repoRoot = repoRoot,
          parentSpecPath = parentSpecPath,
          planningResult = decompositionPlanningPlan(parentSpecPath),
          baseBranch = "main",
          featureBranch = "feature/SKILL-51-decomposition",
        ),
        validator,
        fileStore,
      )
    assertNotNull(initial)

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        writer.writeProjectionFromWorkflowState(
          repoRoot = repoRoot,
          artifacts = invalidDurableRuntimeArtifacts(initial.manifest),
          validator = validator,
          fileStore = fileStore,
        )
      }

    assertEquals("decomposition_runtime", error.sourceLabel)
    assertContains(error.reason, "contract_version")
    assertContains(error.reason, "offending value: invalid-contract")
  }

  private fun invalidDurableRuntimeArtifacts(manifest: DecompositionManifest): DurableWorkflowArtifacts {
    val invalidManifest =
      LinkedHashMap(validator.encodeManifestWireMap(manifest)).apply {
        put("contract_version", "invalid-contract")
      }
    return DurableWorkflowArtifacts.fromMap(
      mapOf("decomposition_runtime" to invalidManifest),
    )
  }
}
