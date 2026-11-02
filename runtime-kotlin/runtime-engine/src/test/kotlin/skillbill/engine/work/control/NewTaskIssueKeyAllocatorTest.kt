package skillbill.engine.work.control

import skillbill.application.TestDecompositionManifestStore
import skillbill.engine.work.control.model.NewTaskKeyAllocation
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.model.ProjectStoreLocation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class NewTaskIssueKeyAllocatorTest {
  @Test
  fun `allocates above all local bundle identities and occupied decimal keys`() {
    val repoRoot = Files.createTempDirectory("new-task-key-allocation")
    val storeRoot = repoRoot.resolve("project-specs")
    bundle(storeRoot, "NEWS-143-store", "# NEWS-144 Parent", "")
    bundle(repoRoot.resolve(".feature-specs"), "NEWS-141-legacy", "", "issue_key: NEWS-145\n")
    bundle(repoRoot.resolve(".feature-specs/done"), "NEWS-144-decimal", "", "issue_key: NEWS-144.1\n")
    bundle(repoRoot.resolve(".feature-specs"), "LOCAL-23-local", "", "")
    Files.writeString(repoRoot.resolve(".feature-specs/NEWS-141-legacy/requirements.md"), "NEWS-999 is mentioned here")
    val allocator =
      NewTaskIssueKeyAllocator(
        projectStore(repoRoot, storeRoot),
        TestDecompositionManifestStore,
        NoopRuntimeDiagnostics,
      )

    assertEquals(
      NewTaskKeyAllocation.Allocated("NEWS-147"),
      allocator.allocate(repoRoot, "news", setOf("NEWS-146.5", "not-a-key")),
    )
  }

  @Test
  fun `reports sorted prefixes when the inventory is ambiguous`() {
    val repoRoot = Files.createTempDirectory("new-task-key-prefixes")
    val storeRoot = repoRoot.resolve("project-specs")
    bundle(storeRoot, "NEWS-2-task", "", "")
    bundle(repoRoot.resolve(".feature-specs"), "APP-1-task", "", "")
    val allocator =
      NewTaskIssueKeyAllocator(
        projectStore(repoRoot, storeRoot),
        TestDecompositionManifestStore,
        NoopRuntimeDiagnostics,
      )

    assertEquals(
      NewTaskKeyAllocation.NeedsPrefix(listOf("APP", "NEWS")),
      allocator.allocate(repoRoot, null, emptySet()),
    )
  }

  @Test
  fun `local keys do not select the project prefix`() {
    val repoRoot = Files.createTempDirectory("new-task-local-prefix")
    val storeRoot = repoRoot.resolve("project-specs")
    bundle(storeRoot, "LOCAL-23-local", "", "")
    val allocator =
      NewTaskIssueKeyAllocator(
        projectStore(repoRoot, storeRoot),
        TestDecompositionManifestStore,
        NoopRuntimeDiagnostics,
      )

    assertEquals(NewTaskKeyAllocation.NeedsPrefix(emptyList()), allocator.allocate(repoRoot, null, emptySet()))
  }

  private fun bundle(
    root: Path,
    name: String,
    spec: String,
    manifest: String,
  ) {
    val directory = root.resolve(name)
    Files.createDirectories(directory)
    Files.writeString(directory.resolve("spec.md"), spec)
    Files.writeString(directory.resolve("decomposition-manifest.yaml"), manifest)
  }

  private fun projectStore(
    repoRoot: Path,
    specsRoot: Path,
  ): ProjectStorePort =
    object : ProjectStorePort {
      override fun resolve(workingDirectory: Path) =
        ProjectStoreLocation(
          "test-project",
          repoRoot,
          specsRoot,
          repoRoot.resolve("config.yaml"),
          repoRoot.resolve("evidence"),
          repoRoot.resolve("tracking"),
          repoRoot.resolve("acceptance"),
        )

      override fun link(
        workingDirectory: Path,
        projectId: String,
      ): ProjectStoreLocation = error("unused")
    }
}
