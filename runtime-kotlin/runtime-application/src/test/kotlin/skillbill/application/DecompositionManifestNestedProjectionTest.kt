package skillbill.application

import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.decompositionPlanningResult
import skillbill.application.decomposition.decompositionPlanningSubtask
import skillbill.application.decomposition.model.DecompositionManifestWriteRequest
import skillbill.application.decomposition.model.DecompositionPlanningSubtaskOptions
import skillbill.application.decomposition.parentSpecPath
import skillbill.contracts.decomposition.DecompositionPlanningResult
import skillbill.error.shellcontent.InvalidDecompositionManifestSchemaError
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class DecompositionManifestNestedProjectionTest {
  @Test
  fun `workflow update rejects decomposition when parent spec is already a decomposed subtask`() {
    val fixture = nestedDecompositionFixture()
    writeTopLevelDecomposition(fixture.repoRoot, fixture.topLevelParentSpecPath)

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        writeFromWorkflowUpdate(
          repoRoot = fixture.repoRoot,
          existingArtifactsJson = "{}",
          artifactsPatch =
            WorkflowArtifactPatch.from(
              mapOf(
                "plan" to nestedDecompositionPlan(fixture.nestedParentSpecPath, fixture.nestedDirectory).toPayload(),
              ),
            ),
        )
      }

    assertContains(error.reason, "already a decomposed subtask")
    assertContains(
      error.reason,
      ".feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec_subtask_1_install-policy.md",
    )
  }

  @Test
  fun `direct decomposed writer rejects decomposition when parent spec is already a decomposed subtask`() {
    val fixture = nestedDecompositionFixture()
    writeTopLevelDecomposition(fixture.repoRoot, fixture.topLevelParentSpecPath)

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        writeIfDecomposed(
          DecompositionManifestWriteRequest(
            repoRoot = fixture.repoRoot,
            parentSpecPath = fixture.nestedParentSpecPath,
            planningResult = nestedDecompositionPlan(fixture.nestedParentSpecPath, fixture.nestedDirectory),
            baseBranch = "main",
            featureBranch = "feature/SKILL-52.1-nested",
          ),
        )
      }

    assertContains(error.reason, "nested decomposition of subtask specs is not supported")
  }

  @Test
  fun `workflow update loud fails when manifest scan encounters malformed decomposition manifest`() {
    val fixture = nestedDecompositionFixture()
    val malformedManifest = fixture.topLevelParentSpecPath.parent.resolve("decomposition-manifest.yaml")
    Files.writeString(
      malformedManifest,
      """
      contract_version: "0.5"
      issue_key: SKILL-52.1
      subtasks: [
      """.trimIndent(),
    )

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        writeFromWorkflowUpdate(
          repoRoot = fixture.repoRoot,
          existingArtifactsJson = "{}",
          artifactsPatch =
            WorkflowArtifactPatch.from(
              mapOf(
                "plan" to nestedDecompositionPlan(fixture.nestedParentSpecPath, fixture.nestedDirectory).toPayload(),
              ),
            ),
        )
      }

    assertContains(error.reason, "failed to load decomposition manifest")
    assertContains(error.reason, malformedManifest.toString())
  }
}

private data class NestedDecompositionFixture(
  val repoRoot: Path,
  val topLevelParentSpecPath: Path,
  val nestedParentSpecPath: Path,
  val nestedDirectory: Path,
)

private fun nestedDecompositionFixture(): NestedDecompositionFixture {
  val repoRoot = Files.createTempDirectory("skillbill-nested-decomposition-manifest")
  val topLevelParentSpecPath = repoRoot.resolve(".feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec.md")
  val nestedParentSpecPath = topLevelParentSpecPath.parent.resolve("spec_subtask_1_install-policy.md")
  val nestedDirectory = nestedParentSpecPath.parent.resolve("install-policy-extraction")
  Files.createDirectories(nestedDirectory)
  Files.writeString(topLevelParentSpecPath, "# Parent spec\n")
  Files.writeString(nestedParentSpecPath, "# Subtask spec\n")
  return NestedDecompositionFixture(
    repoRoot = repoRoot,
    topLevelParentSpecPath = topLevelParentSpecPath,
    nestedParentSpecPath = nestedParentSpecPath,
    nestedDirectory = nestedDirectory,
  )
}

private fun writeTopLevelDecomposition(
  repoRoot: Path,
  parentSpecPath: Path,
) {
  val result =
    writeIfDecomposed(
      DecompositionManifestWriteRequest(
        repoRoot = repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = topLevelDecompositionPlan(parentSpecPath),
        baseBranch = "main",
        featureBranch = "feature/SKILL-52.1-top-level",
      ),
    )
  assertNotNull(result)
}

private fun topLevelDecompositionPlan(parentSpecPath: Path): DecompositionPlanningResult =
  decompositionPlanningResult(
    parentSpecPath = parentSpecPath.toString(),
    subtasks =
      listOf(
        decompositionPlanningSubtask(
          id = 1,
          name = "Install Policy",
          specPath = parentSpecPath.parent.resolve("spec_subtask_1_install-policy.md").toString(),
        ),
        decompositionPlanningSubtask(
          id = 2,
          name = "Runtime",
          specPath = parentSpecPath.parent.resolve("spec_subtask_2_runtime.md").toString(),
          options = DecompositionPlanningSubtaskOptions(dependsOn = listOf(1)),
        ),
      ),
  )

private fun nestedDecompositionPlan(
  parentSpecPath: Path,
  subtaskDirectory: Path,
): DecompositionPlanningResult =
  decompositionPlanningResult(
    parentSpecPath = parentSpecPath.toString(),
    subtasks =
      listOf(
        decompositionPlanningSubtask(
          id = 1,
          name = "Foundation",
          specPath = subtaskDirectory.resolve("spec_subtask_1_foundation.md").toString(),
        ),
        decompositionPlanningSubtask(
          id = 2,
          name = "Runtime",
          specPath = subtaskDirectory.resolve("spec_subtask_2_runtime.md").toString(),
          options = DecompositionPlanningSubtaskOptions(dependsOn = listOf(1)),
        ),
      ),
  )
