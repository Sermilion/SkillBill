package skillbill.engine.decomposition

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.decomposition.model.SpecBundleScope
import skillbill.application.decomposition.resolveDecompositionManifest
import skillbill.application.testDecompositionManifestValidator
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.model.SpecSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StorePreferredManifestDiscoveryTest {
  private val checkout: Path = Files.createTempDirectory("store-preferred-manifest-checkout")
  private val store = FixedProjectStorePort(Files.createTempDirectory("store-preferred-manifest-store"))
  private val scope = SpecBundleScope(checkout, store.resolve(checkout))

  @Test
  fun `an imported store manifest wins over the legacy copy of the same bundle`() {
    writeManifest(checkout.resolve(".feature-specs/SKILL-901-goal"), "legacy")
    writeManifest(scope.store.specsRoot.resolve("SKILL-901-goal"), "store")

    val resolved = resolveDecompositionManifest(scope, "SKILL-901", bothRoots(), testDecompositionManifestValidator)

    assertEquals("store", resolved?.featureName)
  }

  @Test
  fun `two active legacy manifests for one issue still fail as duplicates`() {
    writeManifest(checkout.resolve(".feature-specs/SKILL-901-first"), "first")
    writeManifest(checkout.resolve(".feature-specs/SKILL-901-second"), "second")

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        resolveDecompositionManifest(scope, "SKILL-901", bothRoots(), testDecompositionManifestValidator)
      }

    assertEquals(WorkflowFailureCode.DECOMPOSITION_MANIFEST_DUPLICATE_ACTIVE, error.code)
  }

  private fun bothRoots(): DecompositionManifestStore =
    object : DecompositionManifestStore by TestDecompositionManifestStore {
      override fun findDecompositionManifestFiles(repoRoot: Path): List<Path> =
        listOf(repoRoot.resolve(".feature-specs"), scope.store.specsRoot).flatMap { root ->
          if (Files.isDirectory(root)) {
            Files.walk(root).use { paths -> paths.filter { it.fileName.toString() == MANIFEST }.toList() }
          } else {
            emptyList()
          }
        }
    }

  private fun writeManifest(
    bundle: Path,
    featureName: String,
  ) {
    Files.createDirectories(bundle)
    Files.writeString(
      bundle.resolve(MANIFEST),
      encodeDecompositionManifestYaml(
        manifest(featureName),
        testDecompositionManifestValidator,
        TestDecompositionManifestStore,
      ),
    )
  }

  private fun manifest(featureName: String): DecompositionManifest =
    DecompositionManifest(
      issueKey = "SKILL-901",
      featureName = featureName,
      parentSpecPath = "specs/SKILL-901-goal/spec.md",
      specSource = SpecSource.LOCAL,
      status = "in_progress",
      baseBranch = "main",
      featureBranch = "feat/SKILL-901-goal",
      currentSubtaskIntent = CurrentSubtaskIntent(1, "start"),
      subtasks =
        listOf(
          DecompositionSubtask(
            id = 1,
            name = "first",
            specPath = "specs/SKILL-901-goal/spec_subtask_1.md",
            linearIssueId = "SKILL-901",
          ),
        ),
    )

  private companion object {
    const val MANIFEST = "decomposition-manifest.yaml"
  }
}
