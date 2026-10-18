package skillbill.infrastructure.skills.scaffold

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ManifestFailureCode
import skillbill.infrastructure.skills.scaffold.platformpack.loader.parseQaCapability
import skillbill.scaffold.model.QaPlatform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ShellContentLoaderQaCapabilityTest {
  private val packRoot: Path =
    Files.createTempDirectory("skillbill-qa-pack").also { root ->
      Files.createDirectories(root.resolve("qa"))
      Files.writeString(root.resolve("qa/android-qa.md"), "# Android QA\n")
    }

  @AfterTest
  fun cleanUp() {
    packRoot.toFile().deleteRecursively()
  }

  @Test
  fun `an absent qa_capability parses to null and a declared one keeps its platforms and guidance`() {
    assertNull(parseQaCapability(mapOf("platform" to "kmp"), "kmp", packRoot))

    val declared =
      requireNotNull(
        parseQaCapability(
          mapOf("qa_capability" to mapOf("platforms" to listOf("android"), "guidance_file" to "qa/android-qa.md")),
          "kmp",
          packRoot,
        ),
      )

    assertEquals(setOf(QaPlatform.ANDROID), declared.platforms)
    assertEquals("qa/android-qa.md", declared.guidanceFile)
  }

  @Test
  fun `a malformed qa_capability fails the manifest schema`() {
    val malformed =
      listOf(
        mapOf("platforms" to listOf("windows"), "guidance_file" to "qa/android-qa.md"),
        mapOf("platforms" to emptyList<String>(), "guidance_file" to "qa/android-qa.md"),
        mapOf("platforms" to listOf("android"), "guidance_file" to "../outside.md"),
        mapOf("platforms" to listOf("android"), "guidance_file" to "qa/missing.md"),
      )

    malformed.forEach { capability ->
      val failure =
        assertFailsWith<SkillBillRuntimeException> {
          parseQaCapability(mapOf("qa_capability" to capability), "kmp", packRoot)
        }
      assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
    }
  }
}
