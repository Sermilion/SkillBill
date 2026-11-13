package skillbill.infrastructure.workflow.featuretask

import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.taskruntime.model.PreplanEvidenceReference
import skillbill.ports.taskruntime.model.PreplanEvidenceWriteRequest
import skillbill.ports.taskruntime.model.PreplanEvidenceWriteResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileSystemFeatureTaskRuntimePreplanEvidenceStoreTest {
  private val checkout = Files.createTempDirectory("skillbill-preplan-evidence-checkout")
  private val storeRoot = Files.createTempDirectory("skillbill-preplan-evidence-store")
  private val store =
    FileSystemFeatureTaskRuntimePreplanEvidenceStore(SilentDiagnostics, FixedProjectStorePort(storeRoot))

  @Test
  fun `write copies cited checkout ranges verbatim with their own line numbers and skips unreadable references`() {
    val source = checkout.resolve("src/Feature.kt")
    Files.createDirectories(source.parent)
    Files.writeString(source, (1..12).joinToString("\n") { line -> if (line == 4) "```" else "line $line" })
    val outside = Files.createTempFile("skillbill-outside", ".kt")

    val written =
      assertIs<PreplanEvidenceWriteResult.Written>(
        store.write(
          PreplanEvidenceWriteRequest(
            repoRoot = checkout,
            workflowId = "wf-1",
            checkoutHead = "abc123",
            digest = "DIGEST BODY",
            references =
              listOf(
                PreplanEvidenceReference("src/Feature.kt", 3, 5),
                PreplanEvidenceReference("src/Feature.kt", 11, 40),
                PreplanEvidenceReference("src/Missing.kt"),
                PreplanEvidenceReference(outside.toString()),
              ),
          ),
        ),
      )

    assertTrue(written.path.startsWith(storeRoot))
    assertEquals(listOf("src/Feature.kt:3-5", "src/Feature.kt:11-12"), written.excerpts.map { it.label })
    assertEquals(listOf("src/Missing.kt", outside.toString()), written.skipped.map { it.label })
    val body = Files.readString(written.path)
    assertTrue(body.contains("checkout_head: abc123"))
    assertTrue(body.contains("DIGEST BODY"))
    assertTrue(body.contains("````\n3  line 3\n4  ```\n5  line 5\n````"))
    assertTrue(body.contains("11  line 11\n12  line 12\n```"))
  }

  @Test
  fun `an unwritable store degrades instead of failing the launch`() {
    Files.writeString(storeRoot.resolve("run-evidence"), "not-a-directory")

    assertIs<PreplanEvidenceWriteResult.Degraded>(
      store.write(PreplanEvidenceWriteRequest(checkout, "wf-2", null, "DIGEST", emptyList())),
    )
  }
}

private object SilentDiagnostics : RuntimeDiagnostics {
  override fun info(message: String) = Unit

  override fun warning(
    message: String,
    error: Throwable?,
  ) = Unit

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}
