package skillbill.infrastructure.workflow.featuretask

import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceDeriver
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDerivation
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolution
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceFileEntry
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceHunkEntry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureTaskRuntimeSharedEvidenceResolveOutcomeTest {
  private val repoRoot: Path = createTempDirectory("shared-evidence-outcome")
  private val store = FileSystemFeatureTaskRuntimeSharedEvidenceStore()

  @Test
  fun `absent artifact derives exactly once and returns the derivation`() {
    val deriver = CountingDeriver()

    val resolution = store.resolved(request("fp-absent"), deriver)

    assertEquals(1, deriver.invocations)
    assertEquals("fp-absent", resolution.artifact.fingerprint)
    assertEquals("src/A.kt", resolution.artifact.files.single().path)
    assertEquals(".skill-bill/run-evidence/wf-1/fp-absent", resolution.storePath)
  }

  @Test
  fun `fingerprint hit serves the stored artifact with zero repository traversal`() {
    store.resolved(request("fp-hit"), CountingDeriver())

    val artifact = store.resolved(request("fp-hit"), ThrowingDeriver).artifact

    assertEquals("fp-hit", artifact.fingerprint)
  }

  @Test
  fun `a different fingerprint re-derives rather than serving the stored artifact`() {
    store.resolved(request("fp-old"), CountingDeriver(baseRef = "old-base"))
    val deriver = CountingDeriver(baseRef = "new-base")

    val artifact = store.resolved(request("fp-new"), deriver).artifact

    assertEquals(1, deriver.invocations)
    assertEquals("fp-new", artifact.fingerprint)
    assertEquals("new-base", artifact.baseRef)
  }

  @Test
  fun `a corrupt payload re-derives without failing the run`() {
    store.resolved(request("fp-corrupt"), CountingDeriver())
    val payload =
      artifactDir(request("fp-corrupt"))
        .resolve(FileSystemFeatureTaskRuntimeSharedEvidenceStore.PAYLOAD_FILE_NAME)
    Files.writeString(payload, "")
    val deriver = CountingDeriver()

    val artifact = store.resolved(request("fp-corrupt"), deriver).artifact

    assertEquals(1, deriver.invocations)
    assertEquals("fp-corrupt", artifact.fingerprint)
  }

  @Test
  fun `an unparseable envelope re-derives without failing the run`() {
    store.resolved(request("fp-unparseable"), CountingDeriver())
    val envelope =
      artifactDir(request("fp-unparseable"))
        .resolve(FileSystemFeatureTaskRuntimeSharedEvidenceStore.ENVELOPE_FILE_NAME)
    Files.writeString(envelope, "{\"fingerprint\": ")
    val deriver = CountingDeriver()

    val artifact = store.resolved(request("fp-unparseable"), deriver).artifact

    assertEquals(1, deriver.invocations)
    assertTrue(Files.isRegularFile(envelope))
  }

  private fun request(fingerprint: String) =
    FeatureTaskRuntimeSharedEvidenceRequest(
      repoRoot = repoRoot,
      workflowId = "wf-1",
      checkpoint = FeatureTaskRuntimeRepositoryCheckpoint(fingerprint),
    )
}

internal class CountingDeriver(private val baseRef: String? = "main") : FeatureTaskRuntimeSharedEvidenceDeriver {
  var invocations: Int = 0
    private set

  override fun derive(checkpoint: FeatureTaskRuntimeRepositoryCheckpoint): FeatureTaskRuntimeSharedEvidenceDerivation {
    invocations++
    return FeatureTaskRuntimeSharedEvidenceDerivation(
      baseRef = baseRef,
      headRef = "head",
      files = listOf(FeatureTaskRuntimeSharedEvidenceFileEntry("src/A.kt", "modified")),
      hunks = listOf(FeatureTaskRuntimeSharedEvidenceHunkEntry("src/A.kt", "@@ -1 +1 @@")),
      diffPayload = "diff for ${checkpoint.fingerprint}",
    )
  }
}

internal fun FileSystemFeatureTaskRuntimeSharedEvidenceStore.resolved(
  request: FeatureTaskRuntimeSharedEvidenceRequest,
  deriver: FeatureTaskRuntimeSharedEvidenceDeriver,
): FeatureTaskRuntimeSharedEvidenceResolution =
  checkNotNull(resolve(request, deriver)) { "the deriver in this test always derives" }

internal object ThrowingDeriver : FeatureTaskRuntimeSharedEvidenceDeriver {
  override fun derive(checkpoint: FeatureTaskRuntimeRepositoryCheckpoint): FeatureTaskRuntimeSharedEvidenceDerivation =
    error("Deriver must not be invoked on a fingerprint hit; resolving '${checkpoint.fingerprint}' traversed.")
}
