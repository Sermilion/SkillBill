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
  fun `suggestions include archived decimal and reserved keys without borrowing other project prefixes`() {
    val root = Files.createTempDirectory("new-task-suggestions")
    val specs = root.resolve("project-specs")
    bundle(specs, "NEWS-4-existing", "", "")
    bundle(root.resolve(".feature-specs/done"), "NEWS-8.2-old", "", "")
    bundle(specs, "LOCAL-100-generated", "", "")
    val allocator =
      NewTaskIssueKeyAllocator(projectStore(root, specs), TestDecompositionManifestStore, NoopRuntimeDiagnostics)
    assertEquals(listOf("NEWS-10"), allocator.suggestions(root, setOf("NEWS-9", "OTHER-999")))
    assertEquals(listOf("NEWS-9"), allocator.suggestions(root, emptySet()))
  }

  @Test
  fun `allocates above each bundle identity in each inventory root`() {
    for (root in listOf("project-specs", ".feature-specs", ".feature-specs/done")) {
      for (identity in listOf("directory", "heading", "manifest")) {
        val repoRoot = Files.createTempDirectory("new-task-key-inventory")
        val storeRoot = repoRoot.resolve("project-specs")
        bundle(
          repoRoot.resolve(root),
          if (identity == "directory") "NEWS-42-task" else "unidentified-task",
          if (identity == "heading") "# NEWS-42 Parent" else "",
          if (identity == "manifest") "issue_key: NEWS-42\n" else "",
        )
        val allocator =
          NewTaskIssueKeyAllocator(
            projectStore(repoRoot, storeRoot),
            TestDecompositionManifestStore,
            NoopRuntimeDiagnostics,
          )

        assertEquals(
          NewTaskKeyAllocation.Allocated("NEWS-43"),
          allocator.allocate(repoRoot, "NEWS", emptySet()),
          "identity=$identity root=$root",
        )
      }
    }
  }

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

  @Test
  fun `a single prefix is never selected without the operator`() {
    val repoRoot = Files.createTempDirectory("new-task-single-prefix")
    val storeRoot = repoRoot.resolve("project-specs")
    bundle(storeRoot, "NEWS-2-task", "", "")
    val allocator =
      NewTaskIssueKeyAllocator(
        projectStore(repoRoot, storeRoot),
        TestDecompositionManifestStore,
        NoopRuntimeDiagnostics,
      )

    assertEquals(NewTaskKeyAllocation.NeedsPrefix(listOf("NEWS")), allocator.allocate(repoRoot, null, emptySet()))
  }
}

internal fun bundle(
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

internal fun projectStore(
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
        repoRoot.resolve("phases"),
      )

    override fun resolveCurrent(workingDirectory: Path) = resolve(workingDirectory)

    override fun link(
      workingDirectory: Path,
      projectId: String,
    ): ProjectStoreLocation = error("unused")
  }
