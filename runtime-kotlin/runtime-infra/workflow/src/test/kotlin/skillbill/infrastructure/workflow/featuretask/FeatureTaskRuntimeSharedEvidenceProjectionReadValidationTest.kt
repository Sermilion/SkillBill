package skillbill.infrastructure.workflow.featuretask

import skillbill.error.featuretask.FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolveOutcome
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureTaskRuntimeSharedEvidenceProjectionReadValidationTest {
  private val repoRoot: Path = createTempDirectory("shared-evidence-schema-read")
  private val store = FileSystemFeatureTaskRuntimeSharedEvidenceStore()

  @Test
  fun `a well-formed stored projection passes validation on read and reuses`() {
    store.resolved(request("fp-valid"), CountingDeriver())

    val reused = store.resolved(request("fp-valid"), ThrowingDeriver)

    assertEquals("fp-valid", reused.artifact.fingerprint)
    assertEquals(FeatureTaskRuntimeSharedEvidenceResolveOutcome.REUSE, reused.outcome)
  }

  @Test
  fun `a schema-invalid stored payload degrades to re-derivation rather than failing the run`() {
    store.resolved(request("fp-invalid"), CountingDeriver())
    val envelope =
      artifactDir(request("fp-invalid"))
        .resolve(FileSystemFeatureTaskRuntimeSharedEvidenceStore.ENVELOPE_FILE_NAME)
    val payloadSize = Files.size(artifactDir(request("fp-invalid")).resolve("diff.patch"))

    Files.writeString(
      envelope,
      """
      {
        "contract_version":"9.9",
        "fingerprint":"fp-invalid",
        "files":[{"path":"src/A.kt","change_kind":"modified"}],
        "hunks":[{"path":"src/A.kt","header":"@@ -1 +1 @@"}],
        "diff_payload":{"relative_path":"diff.patch","size_bytes":$payloadSize}
      }
      """.trimIndent(),
    )
    val deriver = CountingDeriver()

    val resolution = store.resolved(request("fp-invalid"), deriver)

    assertEquals(1, deriver.invocations)
    assertEquals("fp-invalid", resolution.artifact.fingerprint)
    assertEquals(FeatureTaskRuntimeSharedEvidenceResolveOutcome.DERIVATION, resolution.outcome)
  }

  @Test
  fun `a fingerprint that contradicts the addressed location loud-fails and names both fingerprints`() {
    store.resolved(request("fp-addressed"), CountingDeriver())
    val envelope =
      artifactDir(request("fp-addressed"))
        .resolve(FileSystemFeatureTaskRuntimeSharedEvidenceStore.ENVELOPE_FILE_NAME)
    Files.writeString(envelope, Files.readString(envelope).replace("\"fp-addressed\"", "\"fp-recorded\""))

    val error =
      assertFailsWith<FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError> {
        store.resolved(request("fp-addressed"), ThrowingDeriver)
      }

    assertTrue(error.message!!.contains("fp-addressed"), error.message)
    assertTrue(error.message!!.contains("fp-recorded"), error.message)
  }

  @Test
  fun `a miss after a sibling fingerprint is a checkpoint-change re-derivation`() {
    store.resolved(request("fp-old"), CountingDeriver())
    val deriver = CountingDeriver()

    val resolution = store.resolved(request("fp-new"), deriver)

    assertEquals(1, deriver.invocations)
    assertEquals(FeatureTaskRuntimeSharedEvidenceResolveOutcome.CHECKPOINT_CHANGE_REDERIVATION, resolution.outcome)
  }

  private fun request(fingerprint: String) =
    FeatureTaskRuntimeSharedEvidenceRequest(
      repoRoot = repoRoot,
      workflowId = "wf-1",
      checkpoint = FeatureTaskRuntimeRepositoryCheckpoint(fingerprint),
    )
}
