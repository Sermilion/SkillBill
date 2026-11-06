package skillbill.engine.goalrunner.plan

import skillbill.application.TestDecompositionManifestStore
import skillbill.engine.work.EmptyManifestStore
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.projectstore.FixedProjectStorePort
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StandalonePlanReportBuilderTest {
  @Test
  fun `seeded subtask spec is omitted while existing parent and manifest files remain visible`() {
    val projectRoot = Files.createTempDirectory("standalone-plan-detail")
    val bundle = projectRoot.resolve("specs/SKILL-505-live-plan-run-detail")
    Files.createDirectories(bundle)
    val parent = Files.writeString(bundle.resolve("spec.md"), "parent")
    val manifest = Files.writeString(bundle.resolve("manifest.yaml"), "manifest")
    val seeded = Files.writeString(bundle.resolve("spec_subtask_1_progress.md"), "seed")
    val builder =
      StandalonePlanReportBuilder(
        EmptyManifestStore,
        TestDecompositionManifestStore,
        FixedProjectStorePort(projectRoot),
        NoopRuntimeDiagnostics,
      )

    val report = assertNotNull(builder.detailReport("SKILL-505", projectRoot, emptySet()))

    assertEquals(parent.toAbsolutePath().normalize().toString(), report.parentSpecPath)
    assertTrue(report.bundleFiles.contains(parent.toAbsolutePath().normalize().toString()))
    assertTrue(report.bundleFiles.contains(manifest.toAbsolutePath().normalize().toString()))
    assertFalse(report.bundleFiles.contains(seeded.toAbsolutePath().normalize().toString()))
  }
}
