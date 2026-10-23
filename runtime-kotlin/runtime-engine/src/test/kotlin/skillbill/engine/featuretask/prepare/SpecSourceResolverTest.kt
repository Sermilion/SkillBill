package skillbill.engine.featuretask.prepare

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.encodeValidatedDecompositionManifestYaml
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.specSource
import skillbill.application.decomposition.writeDecompositionManifestText
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testProjectStore
import skillbill.engine.goalrunner.manifest
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.model.SpecSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpecSourceResolverTest {
  private val resolver =
    SpecSourceResolver(TestDecompositionManifestStore, testDecompositionManifestValidator, testProjectStore)

  @Test
  fun `reads spec_source from the decomposition manifest for decomposed runs`() {
    val repoRoot = Files.createTempDirectory("spec-source-manifest")
    val parentSpec = seedSpec(repoRoot, "spec.md", "# Parent\n")
    writeManifest(repoRoot, SpecSource.LINEAR)

    assertEquals(
      SpecSource.LINEAR,
      resolver.resolve(repoRoot, repoRoot.relativize(parentSpec).toString(), isGoalContinuation = false),
    )
  }

  @Test
  fun `manifest-absent legacy runtime reads its persisted spec_source stamp`() {
    val repoRoot = Files.createTempDirectory("spec-source-single")
    seedSpec(repoRoot, "spec.md", "---\nspec_source: linear\n---\n# Spec\n")

    assertEquals(
      SpecSource.LINEAR,
      resolver.resolve(repoRoot, ".feature-specs/SKILL-71/spec.md", isGoalContinuation = false),
    )
  }

  @Test
  fun `defaults to local when the spec is absent`() {
    val repoRoot = Files.createTempDirectory("spec-source-absent")

    assertEquals(
      SpecSource.LOCAL,
      resolver.resolve(repoRoot, ".feature-specs/SKILL-71/spec.md", isGoalContinuation = false),
    )
  }

  @Test
  fun `defaults to local for a bare spec without a spec_source line`() {
    val repoRoot = Files.createTempDirectory("spec-source-local")
    seedSpec(repoRoot, "spec.md", "---\nstatus: Pending\n---\n# Spec\n")

    assertEquals(
      SpecSource.LOCAL,
      resolver.resolve(repoRoot, ".feature-specs/SKILL-71/spec.md", isGoalContinuation = false),
    )
  }

  @Test
  fun `manifest-absent legacy runtime loud-fails an invalid persisted source stamp`() {
    val repoRoot = Files.createTempDirectory("spec-source-bad")
    seedSpec(repoRoot, "spec.md", "spec_source: github\n")

    assertFailsWith<IllegalArgumentException> {
      resolver.resolve(repoRoot, ".feature-specs/SKILL-71/spec.md", isGoalContinuation = false)
    }
  }

  @Test
  fun `goal continuation reads the sibling manifest spec_source even when the subtask spec is unstamped`() {
    val repoRoot = Files.createTempDirectory("spec-source-goalcont")
    seedSpec(repoRoot, "spec.md", "# Parent\n")
    seedSpec(repoRoot, "spec_subtask_1.md", "# Subtask\n")
    writeManifest(repoRoot, SpecSource.LINEAR)

    assertEquals(
      SpecSource.LINEAR,
      resolver.resolve(repoRoot, ".feature-specs/SKILL-71/spec_subtask_1.md", isGoalContinuation = true),
    )
  }

  private fun seedSpec(
    repoRoot: Path,
    name: String,
    content: String,
  ): Path {
    val specDir = repoRoot.resolve(".feature-specs/SKILL-71")
    Files.createDirectories(specDir)
    val path = specDir.resolve(name)
    Files.writeString(path, content)
    return path
  }

  private fun writeManifest(
    repoRoot: Path,
    specSource: SpecSource,
  ) {
    val manifest =
      DecompositionManifest(
        issueKey = "SKILL-71",
        featureName = "local-config",
        parentSpecPath = ".feature-specs/SKILL-71/spec.md",
        specSource = specSource,
        baseBranch = "main",
        featureBranch = "feat/SKILL-71",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
        subtasks =
          listOf(
            DecompositionSubtask(id = 1, name = "subtask", specPath = ".feature-specs/SKILL-71/spec_subtask_1.md"),
          ),
      )
    val yaml =
      encodeDecompositionManifestYaml(
        manifest,
        testDecompositionManifestValidator,
        TestDecompositionManifestStore,
      )
    writeDecompositionManifestText(
      repoRoot.resolve(".feature-specs/SKILL-71/decomposition-manifest.yaml"),
      yaml,
      TestDecompositionManifestStore,
    )
  }
}

private fun encodeDecompositionManifestYaml(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
  fileStore: DecompositionManifestStore,
  sourceLabel: String = "<in-memory>",
): String =
  skillbill.application.decomposition.encodeValidatedDecompositionManifestYaml(
    manifest,
    validator,
    fileStore,
    sourceLabel,
  ).yamlText
