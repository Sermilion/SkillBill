package skillbill.engine.goalrunner.intake

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestDecompositionManifestStore
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.model.FeatureTaskWorkflowMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacySpecImportTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-legacy-import-repo")
  private val store = FixedProjectStorePort(Files.createTempDirectory("skillbill-legacy-import-store"))
  private val workflowStates = InMemoryWorkflowStates()
  private val specsRoot: Path = store.resolve(repoRoot).specsRoot
  private val legacyRoot: Path = repoRoot.resolve(".feature-specs")

  @Test
  fun `active and archived bundles are copied into the store and the legacy files are left alone`() {
    writeBundle("SKILL-7-active", manifest = true)
    writeBundle("done/SKILL-3-archived")

    val result = importer().import(repoRoot)

    assertEquals(listOf("SKILL-7-active", "done/SKILL-3-archived"), result.imported)
    assertEquals("# SKILL-7-active\n", Files.readString(specsRoot.resolve("SKILL-7-active/spec.md")))
    assertEquals("# done/SKILL-3-archived\n", Files.readString(specsRoot.resolve("done/SKILL-3-archived/spec.md")))
    assertEquals("# SKILL-7-active\n", Files.readString(legacyRoot.resolve("SKILL-7-active/spec.md")))
  }

  @Test
  fun `copied manifests point at the store bundle instead of the legacy directory`() {
    writeBundle("SKILL-7-active", manifest = true)

    importer().import(repoRoot)

    val manifest = Files.readString(specsRoot.resolve("SKILL-7-active/decomposition-manifest.yaml"))
    assertEquals(
      "parent_spec_path: \"specs/SKILL-7-active/spec.md\"\nsubtasks:\n" +
        "- spec_path: specs/SKILL-7-active/spec_subtask_1.md\n",
      manifest,
    )
  }

  @Test
  fun `a bundle already in the store is not overwritten`() {
    writeBundle("SKILL-7-active")
    Files.createDirectories(specsRoot.resolve("SKILL-7-active"))
    Files.writeString(specsRoot.resolve("SKILL-7-active/spec.md"), "store copy\n")

    val result = importer().import(repoRoot)

    assertEquals(listOf("SKILL-7-active"), result.alreadyInStore)
    assertEquals("store copy\n", Files.readString(specsRoot.resolve("SKILL-7-active/spec.md")))
  }

  @Test
  fun `an active bundle is not copied when the store already has a bundle for its issue under another name`() {
    writeBundle("SKILL-7-legacy-name")
    Files.createDirectories(specsRoot.resolve("SKILL-7-store-name"))
    Files.writeString(specsRoot.resolve("SKILL-7-store-name/spec.md"), "store copy\n")

    val result = importer().import(repoRoot)

    assertEquals(listOf("SKILL-7-legacy-name"), result.alreadyInStore)
    assertTrue(Files.notExists(specsRoot.resolve("SKILL-7-legacy-name")))
  }

  @Test
  fun `an active bundle whose issue has a blocked workflow stays in place for the in-progress goal`() {
    writeBundle("SKILL-7-active")
    writeBundle("SKILL-8-unlaunched")
    recordWorkflow("SKILL-7", "blocked")
    recordWorkflow("SKILL-8", "completed")

    val result = importer().import(repoRoot)

    assertEquals(listOf("SKILL-7-active"), result.inFlight)
    assertEquals(listOf("SKILL-8-unlaunched"), result.imported)
    assertTrue(Files.notExists(specsRoot.resolve("SKILL-7-active")))
  }

  @Test
  fun `an explicit source directory is imported instead of the checkout's legacy directory`() {
    val elsewhere = Files.createTempDirectory("skillbill-legacy-import-source")
    Files.createDirectories(elsewhere.resolve("SKILL-9-elsewhere"))
    Files.writeString(elsewhere.resolve("SKILL-9-elsewhere/spec.md"), "# elsewhere\n")

    val result = importer().import(repoRoot, elsewhere)

    assertEquals(listOf("SKILL-9-elsewhere"), result.imported)
    assertEquals("# elsewhere\n", Files.readString(specsRoot.resolve("SKILL-9-elsewhere/spec.md")))
  }

  private fun importer(): LegacySpecImport =
    LegacySpecImport(
      FakeDatabaseSessionFactory(workflowStates),
      store,
      TestDecompositionManifestStore,
      SilentDiagnostics,
    )

  private fun writeBundle(
    name: String,
    manifest: Boolean = false,
  ) {
    val bundle = Files.createDirectories(legacyRoot.resolve(name))
    Files.writeString(bundle.resolve("spec.md"), "# $name\n")
    if (manifest) {
      Files.writeString(
        bundle.resolve("decomposition-manifest.yaml"),
        "parent_spec_path: \".feature-specs/$name/spec.md\"\nsubtasks:\n" +
          "- spec_path: .feature-specs/$name/spec_subtask_1.md\n",
      )
    }
  }

  private fun recordWorkflow(
    issueKey: String,
    status: String,
  ) {
    workflowStates.saveFeatureTaskWorkflow(
      WorkflowStateRecord(
        workflowId = "wftr-$issueKey",
        sessionId = "session-$issueKey",
        workflowName = "bill-feature-task",
        contractVersion = "1.0",
        workflowStatus = status,
        currentStepId = "plan",
        stepsJson = "[]",
        artifactsJson = "{}",
        startedAt = null,
        updatedAt = null,
        finishedAt = null,
        mode = FeatureTaskWorkflowMode.RUNTIME,
        issueKey = issueKey,
      ),
      FeatureTaskWorkflowMode.RUNTIME,
    )
  }
}

private object SilentDiagnostics : RuntimeDiagnostics {
  override fun warning(
    message: String,
    error: Throwable?,
  ) = Unit

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}
