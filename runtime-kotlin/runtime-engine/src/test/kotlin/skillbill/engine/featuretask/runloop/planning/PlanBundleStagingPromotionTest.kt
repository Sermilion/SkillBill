package skillbill.engine.featuretask.runloop.planning

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.testDecompositionManifestValidator
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.phase.planning.PlanBundleTarget
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationRuntime
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationWriter
import skillbill.featurespec.model.FeatureSpecPreparationDecision
import skillbill.featurespec.model.FeatureSpecPreparationMode
import skillbill.infrastructure.workflow.filesystem.FileSystemFeatureSpecPathResolver
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.FixedProjectStorePort
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlanBundleStagingPromotionTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-staging-repo")
  private val store = FixedProjectStorePort(Files.createTempDirectory("skillbill-staging-store"))
  private val specsRoot = store.root.resolve("specs")
  private val diagnostics = RecordingStagingDiagnostics()
  private val writer =
    FeatureSpecPreparationWriter(
      testDecompositionManifestValidator,
      TestDecompositionManifestStore,
      DecompositionManifestWriter(store),
      store,
    )
  private val planner =
    FeatureTaskRuntimeDecompositionPlanner(
      preparationRuntime = FeatureSpecPreparationRuntime(),
      preparationWriter = writer,
      specPathResolver = FileSystemFeatureSpecPathResolver(store),
      projectStore = store,
      fileStore = TestDecompositionManifestStore,
    )

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
    store.root.toFile().deleteRecursively()
  }

  @Test
  fun `promotion copies the staged bundle into the store and removes only its own staging directory`() {
    val sentinel = writeFile(repoRoot.resolve("src/Main.kt"), "fun main() = Unit\n")
    val target = stagedTarget("wf-promote")
    val otherWorkflow = writeFile(planner.planStagingRoot(repoRoot).resolve("wf-other/keep.md"), "keep\n")
    stage(
      target,
      "spec.md" to "# agent spec\n",
      "decomposition-manifest.yaml" to "manifest\n",
      "spec_subtask_1_a.md" to "one\n",
    )

    PlanBundleStagingPromotion.promote(planner, diagnostics, request(target))

    assertEquals("# agent spec\n", Files.readString(target.storeDirectory.resolve("spec.md")))
    assertEquals("manifest\n", Files.readString(target.storeDirectory.resolve("decomposition-manifest.yaml")))
    assertEquals("one\n", Files.readString(target.storeDirectory.resolve("spec_subtask_1_a.md")))
    assertTrue(Files.notExists(target.stagingRoot))
    assertTrue(Files.isRegularFile(otherWorkflow))
    assertEquals("fun main() = Unit\n", Files.readString(sentinel))
    val warning = diagnostics.warnings.single()
    assertContains(warning, "seam=agent_spec_staging")
    assertContains(warning, "value_expected=${target.storeDirectory}")
    assertContains(warning, "value_used=${target.directory}")
    assertContains(warning, "cause=provider_without_additional_directories")
    assertContains(warning, "provider=cursor")
  }

  @Test
  fun `a protected operator spec is not overwritten by the staged copy`() {
    val target = stagedTarget("wf-protected")
    writeFile(target.storeDirectory.resolve("spec.md"), "# operator spec\n")
    stage(target, "spec.md" to "# agent rewrite\n", "spec_subtask_1_a.md" to "one\n")

    PlanBundleStagingPromotion.promote(planner, diagnostics, request(target, parentSpecProtected = true))

    assertEquals("# operator spec\n", Files.readString(target.storeDirectory.resolve("spec.md")))
    assertEquals("one\n", Files.readString(target.storeDirectory.resolve("spec_subtask_1_a.md")))
  }

  @Test
  fun `a staging root outside the checkout staging directory is never deleted`() {
    val foreignRoot = repoRoot.resolve("keep")
    val target =
      PlanBundleTarget.Staged(
        stagingRoot = foreignRoot,
        directory = foreignRoot.resolve("specs/SKILL-59-staged"),
        storeDirectory = specsRoot.resolve("SKILL-59-staged"),
      )
    stage(target, "spec.md" to "# agent spec\n")

    PlanBundleStagingPromotion.promote(planner, diagnostics, request(target))

    assertTrue(Files.isRegularFile(target.directory.resolve("spec.md")))
    assertTrue(Files.isDirectory(foreignRoot))
  }

  @Test
  fun `the staging directory itself is never deleted`() {
    val stagingParent = planner.planStagingRoot(repoRoot)
    val target =
      PlanBundleTarget.Staged(
        stagingRoot = stagingParent,
        directory = stagingParent.resolve("specs/SKILL-59-staged"),
        storeDirectory = specsRoot.resolve("SKILL-59-staged"),
      )
    stage(target, "spec.md" to "# agent spec\n")

    PlanBundleStagingPromotion.promote(planner, diagnostics, request(target))

    assertTrue(Files.isDirectory(stagingParent))
  }

  @Test
  fun `a promotion failure warns with the throwable leaves staging in place and does not throw`() {
    val target = stagedTarget("wf-failure")
    val outside = writeFile(repoRoot.resolve("outside.md"), "outside\n")
    stage(target, "spec.md" to "# agent spec\n")
    Files.createSymbolicLink(target.directory.resolve("escape.md"), outside)

    PlanBundleStagingPromotion.promote(planner, diagnostics, request(target))

    assertTrue(Files.isRegularFile(target.directory.resolve("spec.md")))
    assertTrue(Files.isDirectory(target.stagingRoot))
    val warning = diagnostics.warnings.single()
    assertContains(warning, "cause=staging_promotion_failed")
    assertContains(warning, "provider=cursor")
    assertNotNull(diagnostics.errors.single())
  }

  @Test
  fun `seeding copies existing store bundle files into staging without overwriting staged files`() {
    val target = stagedTarget("wf-seed")
    writeFile(target.storeDirectory.resolve("spec.md"), "# store spec\n")
    writeFile(target.storeDirectory.resolve("spec_subtask_1_a.md"), "store one\n")
    stage(target, "spec_subtask_1_a.md" to "staged one\n")

    PlanBundleStagingPromotion.seed(planner, target, diagnostics, "cursor")

    assertEquals("# store spec\n", Files.readString(target.directory.resolve("spec.md")))
    assertEquals("staged one\n", Files.readString(target.directory.resolve("spec_subtask_1_a.md")))
    assertTrue(diagnostics.warnings.isEmpty())
  }

  @Test
  fun `a provider that honors additional directories writes the store bundle in place`() {
    writeStoreParentSpec()

    val target = planTarget(ISSUE_KEY, "wf-store", honorsAdditionalDirectories = true)

    assertEquals(PlanBundleTarget.Store(specsRoot.resolve(BUNDLE_NAME)), target)
  }

  @Test
  fun `a provider without additional directories plans in a staging bundle under the checkout`() {
    writeStoreParentSpec()

    val target = planTarget(ISSUE_KEY, "wf-staged", honorsAdditionalDirectories = false)

    val staged = assertIs<PlanBundleTarget.Staged>(target)
    val stagingRoot = planner.planStagingRoot(repoRoot).resolve("wf-staged")
    assertEquals(stagingRoot, staged.stagingRoot)
    assertEquals(stagingRoot.resolve("specs/$BUNDLE_NAME"), staged.directory)
    assertEquals(specsRoot.resolve(BUNDLE_NAME), staged.storeDirectory)
  }

  @Test
  fun `a legacy bundle keeps planning in place whatever the provider capability`() {
    val legacySpec = writeFile(repoRoot.resolve(".feature-specs/SKILL-60-legacy/spec.md"), "# legacy\n")

    val target = planTarget("SKILL-60", "wf-legacy", honorsAdditionalDirectories = false)

    assertEquals(PlanBundleTarget.Legacy(legacySpec.parent), target)
  }

  private fun planTarget(
    issueKey: String,
    workflowId: String,
    honorsAdditionalDirectories: Boolean,
  ): PlanBundleTarget? =
    planner.planBundleTarget(
      repoRoot,
      issueKey,
      workflowId,
      governedSpecPath = null,
      providerHonorsAdditionalDirectories = honorsAdditionalDirectories,
    )

  private fun stagedTarget(workflowId: String): PlanBundleTarget.Staged {
    val stagingRoot = planner.planStagingRoot(repoRoot).resolve(workflowId)
    return PlanBundleTarget.Staged(
      stagingRoot = stagingRoot,
      directory = stagingRoot.resolve("specs/$BUNDLE_NAME"),
      storeDirectory = specsRoot.resolve(BUNDLE_NAME),
    )
  }

  private fun request(
    target: PlanBundleTarget.Staged,
    parentSpecProtected: Boolean = false,
  ) = PromotionRequest(target, repoRoot, "cursor", parentSpecProtected)

  private fun stage(
    target: PlanBundleTarget.Staged,
    vararg files: Pair<String, String>,
  ) {
    files.forEach { (name, content) -> writeFile(target.directory.resolve(name), content) }
  }

  private fun writeStoreParentSpec() {
    writer.writeParentSpecOnly(
      repoRoot,
      FeatureSpecPreparationDecision(
        ISSUE_KEY,
        "Stage the plan bundle.",
        listOf("The bundle is promoted."),
        emptyList(),
        emptyList(),
        FeatureSpecPreparationMode.DECOMPOSED,
      ),
      FEATURE_NAME,
      "bill-code-check",
    )
  }

  private fun writeFile(
    path: Path,
    content: String,
  ): Path {
    Files.createDirectories(path.parent)
    Files.writeString(path, content)
    return path
  }

  private companion object {
    const val ISSUE_KEY = "SKILL-59"
    const val FEATURE_NAME = "staged-bundle"
    const val BUNDLE_NAME = "$ISSUE_KEY-$FEATURE_NAME"
  }
}

private class RecordingStagingDiagnostics : RuntimeDiagnostics {
  val warnings = mutableListOf<String>()
  val errors = mutableListOf<Throwable>()

  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    warnings += message
    error?.let(errors::add)
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}
