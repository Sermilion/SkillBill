package skillbill.infrastructure.workflow.featuretask

import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.taskruntime.model.IMPLEMENTATION_CHECKLIST_STORE_ROOT
import skillbill.ports.taskruntime.model.ImplementationChecklistPrepareResult
import skillbill.ports.taskruntime.model.ImplementationChecklistSeed
import skillbill.ports.taskruntime.model.ImplementationChecklistTask
import skillbill.ports.taskruntime.model.implementationChecklistRelativePath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileSystemFeatureTaskImplementationChecklistStoreTest {
  @Test
  fun `prepare seeds a private checklist without treating ticks as completion`() {
    val root = Files.createTempDirectory("skillbill-checklist")
    val store = FileSystemFeatureTaskImplementationChecklistStore(RecordingDiagnostics())
    val seed =
      ImplementationChecklistSeed(
        workflowId = "wf-1",
        planDigest = "digest-1",
        tasks = listOf(ImplementationChecklistTask("t1", "Ship the adapter")),
      )

    val ready = assertIs<ImplementationChecklistPrepareResult.Ready>(store.prepare(root, seed))
    val path = root.resolve(ready.address.relativePath)
    assertTrue(Files.isRegularFile(path))
    val body = Files.readString(path)
    assertTrue(body.contains("# wf-1"))
    assertTrue(body.contains("Plan digest: digest-1"))
    assertTrue(body.contains("- [ ] t1 Ship the adapter"))
    assertEquals(
      implementationChecklistRelativePath("wf-1"),
      ready.address.relativePath,
    )
  }

  @Test
  fun `an unwritable checklist degrades tracking and leaves the seed unwritten`() {
    val root = Files.createTempDirectory("skillbill-checklist-ro")
    val store = FileSystemFeatureTaskImplementationChecklistStore(RecordingDiagnostics())
    val address = implementationChecklistRelativePath("wf-2")
    val trackingRoot = root.resolve(IMPLEMENTATION_CHECKLIST_STORE_ROOT)
    Files.createDirectories(trackingRoot.parent)
    Files.writeString(trackingRoot, "not-a-directory")
    val seed =
      ImplementationChecklistSeed(
        workflowId = "wf-2",
        planDigest = "digest-2",
        tasks = listOf(ImplementationChecklistTask("t1", "Remain open")),
      )

    val degraded = assertIs<ImplementationChecklistPrepareResult.Degraded>(store.prepare(root, seed))
    assertEquals(address, degraded.address.relativePath)
    assertFalse(Files.isRegularFile(root.resolve(address)))
  }

  @Test
  fun `prepare keeps a tick only for the same plan digest and task title`() {
    val root = Files.createTempDirectory("skillbill-checklist-ticks")
    val store = FileSystemFeatureTaskImplementationChecklistStore(RecordingDiagnostics())
    val seed =
      ImplementationChecklistSeed(
        workflowId = "wf-3",
        planDigest = "digest-3",
        tasks = listOf(ImplementationChecklistTask("t1", "Ship the adapter")),
      )
    val ready = assertIs<ImplementationChecklistPrepareResult.Ready>(store.prepare(root, seed))
    val path = root.resolve(ready.address.relativePath)
    Files.writeString(path, Files.readString(path).replace("- [ ] t1 ", "- [x] t1 "))

    assertIs<ImplementationChecklistPrepareResult.Ready>(store.prepare(root, seed))
    assertTrue(Files.readString(path).contains("- [x] t1 Ship the adapter"))

    val renamed =
      seed.copy(tasks = listOf(ImplementationChecklistTask("t1", "Ship the other adapter")))
    assertIs<ImplementationChecklistPrepareResult.Ready>(store.prepare(root, renamed))
    assertTrue(Files.readString(path).contains("- [ ] t1 Ship the other adapter"))

    val nextPlan = seed.copy(planDigest = "digest-4")
    assertIs<ImplementationChecklistPrepareResult.Ready>(store.prepare(root, nextPlan))
    val body = Files.readString(path)
    assertTrue(body.contains("Plan digest: digest-4"))
    assertTrue(body.contains("- [ ] t1 Ship the adapter"))
    assertFalse(body.contains("- [x]"))
  }
}

private class RecordingDiagnostics : RuntimeDiagnostics {
  override fun warning(
    message: String,
    error: Throwable?,
  ) = Unit

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}
