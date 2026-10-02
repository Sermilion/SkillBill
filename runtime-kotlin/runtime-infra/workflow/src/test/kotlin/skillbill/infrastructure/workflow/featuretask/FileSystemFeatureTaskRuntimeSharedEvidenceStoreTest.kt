package skillbill.infrastructure.workflow.featuretask

import skillbill.error.featuretask.FeatureTaskRuntimeSharedEvidenceFingerprintContradictionError
import skillbill.error.shellcontent.ReviewHunkEvidenceLocatorMissingError
import skillbill.error.shellcontent.ReviewHunkEvidenceLocatorUnreadableError
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceLocatorReadRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceRequest
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSystemFeatureTaskRuntimeSharedEvidenceStoreTest {
  private val repoRoot: Path = createTempDirectory("shared-evidence-store")
  private val store = FileSystemFeatureTaskRuntimeSharedEvidenceStore()

  @Test
  fun `persists under the workflow id and fingerprint address beneath the repo-local store`() {
    store.resolved(request("fp-1"), CountingDeriver())

    val expected = repoRoot.resolve(".skill-bill").resolve("run-evidence").resolve("wf-1").resolve("fp-1")
    assertTrue(Files.isDirectory(expected))
    val envelope = FileSystemFeatureTaskRuntimeSharedEvidenceStore.ENVELOPE_FILE_NAME
    val payload = FileSystemFeatureTaskRuntimeSharedEvidenceStore.PAYLOAD_FILE_NAME
    assertTrue(Files.isRegularFile(expected.resolve(envelope)))
    assertTrue(Files.isRegularFile(expected.resolve(payload)))
  }

  @Test
  fun `store root resolves beneath the repo root and never beneath the user home`() {
    val resolved = artifactDir(request("fp-1"))

    assertTrue(resolved.startsWith(repoRoot.toAbsolutePath().normalize().resolve(".skill-bill")))
    assertFalse(resolved.startsWith(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()))
  }

  @Test
  fun `a new adapter instance reuses the persisted artifact without re-deriving`() {
    store.resolved(request("fp-durable"), CountingDeriver())

    val artifact =
      FileSystemFeatureTaskRuntimeSharedEvidenceStore()
        .resolved(request("fp-durable"), ThrowingDeriver).artifact

    assertEquals("fp-durable", artifact.fingerprint)
    assertEquals("main", artifact.baseRef)
    assertEquals("src/A.kt", artifact.files.single().path)
  }

  @Test
  fun `an interrupted write leaves no artifact a later resolve would serve`() {
    assertFailsWith<IllegalStateException> { store.resolved(request("fp-interrupted"), ThrowingDeriver) }

    val address = artifactDir(request("fp-interrupted"))
    assertFalse(Files.exists(address))
    val deriver = CountingDeriver()
    assertEquals("fp-interrupted", store.resolved(request("fp-interrupted"), deriver).artifact.fingerprint)
    assertEquals(1, deriver.invocations)
  }

  @Test
  fun `a failure between the staged writes leaves nothing at the address and a later resolve re-derives`() {
    val failing =
      object : FileSystemFeatureTaskRuntimeSharedEvidenceStore() {
        override fun writeStaged(
          staging: Path,
          payloadBytes: ByteArray,
          envelopeJson: String,
        ) {
          Files.write(staging.resolve(FileSystemFeatureTaskRuntimeSharedEvidenceStore.PAYLOAD_FILE_NAME), payloadBytes)
          throw IOException("write interrupted after the payload and before the envelope")
        }
      }

    assertFailsWith<IOException> { failing.resolved(request("fp-midwrite"), CountingDeriver()) }

    val address = artifactDir(request("fp-midwrite"))
    assertFalse(Files.exists(address.resolve(FileSystemFeatureTaskRuntimeSharedEvidenceStore.PAYLOAD_FILE_NAME)))
    assertFalse(Files.exists(address.resolve(FileSystemFeatureTaskRuntimeSharedEvidenceStore.ENVELOPE_FILE_NAME)))
    val deriver = CountingDeriver()
    assertEquals("fp-midwrite", store.resolved(request("fp-midwrite"), deriver).artifact.fingerprint)
    assertEquals(1, deriver.invocations)
  }

  @Test
  fun `every cache degradation emits a record naming the seam and the cause`() {
    val records = mutableListOf<LogRecord>()
    val handler =
      object : Handler() {
        override fun publish(record: LogRecord) {
          records += record
        }

        override fun flush() = Unit

        override fun close() = Unit
      }
    sharedEvidenceStoreLog.addHandler(handler)
    try {
      store.resolved(request("fp-observed"), CountingDeriver())
      val envelope =
        artifactDir(request("fp-observed"))
          .resolve(FileSystemFeatureTaskRuntimeSharedEvidenceStore.ENVELOPE_FILE_NAME)
      Files.writeString(envelope, "{ not json")
      records.clear()

      store.resolved(request("fp-observed"), CountingDeriver())

      val messages = records.map { it.message }
      val parse = messages.single { it.contains("seam=stored_envelope_parse") }
      assertTrue(parse.contains("JsonParseException"), parse)
      assertTrue(messages.any { it.contains("seam=artifact_publish") }, messages.toString())
    } finally {
      sharedEvidenceStoreLog.removeHandler(handler)
    }
  }

  @Test
  fun `a cold miss does not emit a cache degradation record`() {
    val records = mutableListOf<LogRecord>()
    val handler =
      object : Handler() {
        override fun publish(record: LogRecord) {
          records += record
        }

        override fun flush() = Unit

        override fun close() = Unit
      }
    sharedEvidenceStoreLog.addHandler(handler)
    try {
      store.resolved(request("fp-cold"), CountingDeriver())
      assertTrue(
        records.none { it.message.contains("cache degraded") },
        "first resolve at an empty address is derivation, not degradation: ${records.map { it.message }}",
      )
    } finally {
      sharedEvidenceStoreLog.removeHandler(handler)
    }
  }

  @Test
  fun `a leftover staging directory is never served as an artifact`() {
    val address = artifactDir(request("fp-staged"))
    Files.createDirectories(address.parent)
    val staging = Files.createTempDirectory(address.parent, "${address.fileName}.staging.")
    Files.writeString(
      staging.resolve(FileSystemFeatureTaskRuntimeSharedEvidenceStore.ENVELOPE_FILE_NAME),
      "{\"fingerprint\":\"fp-staged\"}",
    )
    val deriver = CountingDeriver()

    store.resolved(request("fp-staged"), deriver)

    assertEquals(1, deriver.invocations)
  }

  @Test
  fun `a contradictory recorded fingerprint loud-fails naming both fingerprints`() {
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
  fun `an envelope with no recorded fingerprint re-derives instead of failing the run`() {
    store.resolved(request("fp-absent-field"), CountingDeriver())
    val envelope =
      artifactDir(request("fp-absent-field"))
        .resolve(FileSystemFeatureTaskRuntimeSharedEvidenceStore.ENVELOPE_FILE_NAME)
    Files.writeString(envelope, "{\"diff_payload\":{\"relative_path\":\"diff.patch\",\"size_bytes\":0}}")
    val deriver = CountingDeriver()

    store.resolved(request("fp-absent-field"), deriver)

    assertEquals(1, deriver.invocations)
  }

  @Test
  fun `persisting mutates no gitignore in the repo root`() {
    val gitignore = repoRoot.resolve(".gitignore")
    Files.writeString(gitignore, "/.skill-bill/\n")
    val before = Files.readAllBytes(gitignore)

    store.resolved(request("fp-gitignore"), CountingDeriver())

    assertTrue(before.contentEquals(Files.readAllBytes(gitignore)))
  }

  @Test
  fun `compose-time locator read of a missing store path fails closed without deriving`() {
    val error =
      assertFailsWith<ReviewHunkEvidenceLocatorMissingError> {
        store.readDiffPayload(
          FeatureTaskRuntimeSharedEvidenceLocatorReadRequest(
            repoRoot,
            ".skill-bill/run-evidence/wf-1/fp-absent",
          ),
        )
      }
    assertEquals(".skill-bill/run-evidence/wf-1/fp-absent", error.storePath)
  }

  @Test
  fun `compose-time locator read of an unreadable payload fails closed instead of re-deriving`() {
    val resolution = store.resolved(request("fp-unread"), CountingDeriver())
    val payload =
      artifactDir(request("fp-unread"))
        .resolve(FileSystemFeatureTaskRuntimeSharedEvidenceStore.PAYLOAD_FILE_NAME)
    Files.delete(payload)
    val error =
      assertFailsWith<ReviewHunkEvidenceLocatorUnreadableError> {
        store.readDiffPayload(
          FeatureTaskRuntimeSharedEvidenceLocatorReadRequest(repoRoot, resolution.storePath!!),
        )
      }
    assertEquals(resolution.storePath, error.storePath)
  }

  private fun request(fingerprint: String) =
    FeatureTaskRuntimeSharedEvidenceRequest(
      repoRoot = repoRoot,
      workflowId = "wf-1",
      checkpoint = FeatureTaskRuntimeRepositoryCheckpoint(fingerprint),
    )
}
