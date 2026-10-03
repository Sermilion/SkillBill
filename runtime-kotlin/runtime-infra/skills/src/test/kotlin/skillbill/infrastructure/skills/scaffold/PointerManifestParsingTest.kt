package skillbill.infrastructure.skills.scaffold

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ManifestFailureCode
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest

class PointerManifestParsingTest {
  private val temp: Path = Files.createTempDirectory("skillbill-pointer-manifest-")

  @AfterTest
  fun cleanup() {
    if (Files.exists(temp)) {
      Files.walk(temp).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
      }
    }
  }

  @Test
  fun `parses a valid pointers block into PointerSpec entries`() {
    val pack =
      writePack(
        slug = "fixturepack",
        pointersYaml =
          """
          pointers:
            code-review/bill-fixturepack-code-review:
              - name: shell-ceremony.md
                target: orchestration/shell-content-contract/shell-ceremony.md
              - name: telemetry-contract.md
                target: orchestration/telemetry-contract/PLAYBOOK.md
          """.trimIndent(),
      )

    val manifest = loadPlatformManifest(pack)

    assertEquals(2, manifest.pointers.size)
    assertEquals("shell-ceremony.md", manifest.pointers[0].name)
    assertEquals("orchestration/shell-content-contract/shell-ceremony.md", manifest.pointers[0].target)
    assertEquals("code-review/bill-fixturepack-code-review", manifest.pointers[0].skillRelativeDir)
    assertEquals("telemetry-contract.md", manifest.pointers[1].name)
  }

  @Test
  fun `omitting pointers block defaults to an empty list`() {
    val pack = writePack(slug = "fixturepack", pointersYaml = "")
    val manifest = loadPlatformManifest(pack)
    assertTrue(manifest.pointers.isEmpty())
  }

  @Test
  fun `rejects pointer entry with name that does not end in dot md`() {
    val pack =
      writePack(
        slug = "fixturepack",
        pointersYaml =
          """
          pointers:
            code-review/bill-fixturepack-code-review:
              - name: shell-ceremony.txt
                target: orchestration/shell-content-contract/shell-ceremony.md
          """.trimIndent(),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformManifest(pack)
      }.also { failure ->
        assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      }
    assertContains(error.message.orEmpty(), "shell-ceremony.txt")
    assertContains(error.message.orEmpty(), ".md")
  }

  @Test
  fun `rejects pointer entry missing the name field`() {
    val pack =
      writePack(
        slug = "fixturepack",
        pointersYaml =
          """
          pointers:
            code-review/bill-fixturepack-code-review:
              - target: orchestration/x.md
          """.trimIndent(),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformManifest(pack)
      }.also { failure ->
        assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      }
    assertContains(error.message.orEmpty(), "name")
  }

  @Test
  fun `rejects pointer entry missing the target field`() {
    val pack =
      writePack(
        slug = "fixturepack",
        pointersYaml =
          """
          pointers:
            code-review/bill-fixturepack-code-review:
              - name: shell-ceremony.md
          """.trimIndent(),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformManifest(pack)
      }.also { failure ->
        assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      }
    assertContains(error.message.orEmpty(), "target")
  }

  @Test
  fun `rejects pointer entry with blank target`() {
    val pack =
      writePack(
        slug = "fixturepack",
        pointersYaml =
          "pointers:\n" +
            "  code-review/bill-fixturepack-code-review:\n" +
            "    - name: shell-ceremony.md\n" +
            "      target: \"\"\n",
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformManifest(pack)
      }.also { failure ->
        assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      }
    assertContains(error.message.orEmpty(), "non-empty 'target'")
  }

  @Test
  fun `rejects pointer name containing dot dot`() {
    val pack =
      writePack(
        slug = "fixturepack",
        pointersYaml =
          """
          pointers:
            code-review/bill-fixturepack-code-review:
              - name: ..evil.md
                target: orchestration/x.md
          """.trimIndent(),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformManifest(pack)
      }.also { failure ->
        assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      }
    assertContains(error.message.orEmpty(), "..")
  }

  @Test
  fun `rejects pointer name containing forward slash`() {
    val pack =
      writePack(
        slug = "fixturepack",
        pointersYaml =
          """
          pointers:
            code-review/bill-fixturepack-code-review:
              - name: nested/inner.md
                target: orchestration/x.md
          """.trimIndent(),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformManifest(pack)
      }.also { failure ->
        assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      }
    assertContains(error.message.orEmpty(), "nested/inner.md")
  }

  @Test
  fun `rejects duplicate pointer pair within the same skill-relative dir`() {
    val pack =
      writePack(
        slug = "fixturepack",
        pointersYaml =
          """
          pointers:
            code-review/bill-fixturepack-code-review:
              - name: shell-ceremony.md
                target: orchestration/a.md
              - name: shell-ceremony.md
                target: orchestration/b.md
          """.trimIndent(),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformManifest(pack)
      }.also { failure ->
        assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      }
    assertContains(error.message.orEmpty(), "duplicate pointer entry")
  }

  @Test
  fun `rejects pointer target with absolute path`() {
    val pack =
      writePack(
        slug = "fixturepack",
        pointersYaml =
          """
          pointers:
            code-review/bill-fixturepack-code-review:
              - name: shell-ceremony.md
                target: /etc/passwd
          """.trimIndent(),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformManifest(pack)
      }.also { failure ->
        assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      }
    assertContains(error.message.orEmpty(), "repo-relative")
  }

  @Test
  fun `rejects pointer target with dot dot traversal`() {
    val pack =
      writePack(
        slug = "fixturepack",
        pointersYaml =
          """
          pointers:
            code-review/bill-fixturepack-code-review:
              - name: shell-ceremony.md
                target: ../../../etc/passwd
          """.trimIndent(),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformManifest(pack)
      }.also { failure ->
        assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      }
    assertContains(error.message.orEmpty(), "..")
  }

  @Test
  fun `rejects pointers skill-relative dir with dot dot traversal`() {
    val pack =
      writePack(
        slug = "fixturepack",
        pointersYaml =
          """
          pointers:
            ../escape:
              - name: shell-ceremony.md
                target: orchestration/x.md
          """.trimIndent(),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformManifest(pack)
      }.also { failure ->
        assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      }
    assertContains(error.message.orEmpty(), "..")
  }

  private fun writePack(
    slug: String,
    pointersYaml: String,
  ): Path {
    val packRoot = temp.resolve(slug)
    Files.createDirectories(packRoot)
    val pointersBlock = if (pointersYaml.isBlank()) "" else "\n$pointersYaml\n"
    val manifest =
      """
      platform: $slug
      contract_version: "1.8"

      routing_signals:
        strong:
          - ".fixture"

      declared_code_review_areas: []

      declared_files:
        baseline: code-review/content.md

      area_metadata: {}
      """.trimIndent() + pointersBlock
    Files.writeString(packRoot.resolve("platform.yaml"), manifest)
    return packRoot
  }
}
