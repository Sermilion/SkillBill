package skillbill.scaffold

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ContractVersionMismatchError
import skillbill.error.shellcontent.ManifestFailureCode
import skillbill.error.shellcontent.SkillStagingFailureCode
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformPack
import skillbill.infrastructure.skills.scaffold.platformpack.substanceaudit.relative
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.SHELL_CONTRACT_VERSION
import skillbill.infrastructure.skills.scaffold.validation.shape.validateSkillMdShape
import skillbill.model.toPath
import skillbill.testing.repoRootFromTest
import skillbill.testing.seedConformingPlatformPack

class ShellContentLoaderParityTest {
  @Test
  fun `loads valid pack through manifest driven shell contract`() {
    val repo = Files.createTempDirectory("valid-pack-contract")
    seedConformingPlatformPack(repo, "valid-pack")
    val pack = loadPlatformPack(repo.resolve("platform-packs/valid-pack"))

    assertEquals("valid-pack", pack.slug)
    assertEquals(SHELL_CONTRACT_VERSION, pack.contractVersion)
    assertEquals(listOf("architecture"), pack.declaredCodeReviewAreas)
    assertEquals(listOf(".valid-pack", "*.valid-pack"), pack.routingSignals.strong)
    assertEquals("bill-valid-pack-code-review", pack.declaredFiles.baseline?.toPath()?.parent?.name)
  }

  @Test
  fun `loader rejects a malformed specialist through the production conformance contract`() {
    val repo = Files.createTempDirectory("invalid-review-shape")
    seedConformingPlatformPack(repo, "invalid-review-shape")
    val packRoot = repo.resolve("platform-packs/invalid-review-shape")
    Files.writeString(
      packRoot.resolve("code-review/bill-invalid-review-shape-code-review-architecture/content.md"),
      malformedSpecialistContent(),
    )

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformPack(packRoot, enforceGovernedReviewStructure = true)
      }
    assertEquals(SkillStagingFailureCode.INVALID_REVIEW_SKILL_STRUCTURE, error.code)

    assertContains(error.message.orEmpty(), "specialist H2 sequence")
  }

  @Test
  fun `declared governed skill paths must point directly at content_md`() {
    val cases: List<Pair<String, (Path) -> Unit>> =
      listOf(
        "declared_files.baseline" to { manifest ->
          Files.writeString(
            manifest,
            Files.readString(manifest).replace(
              "baseline: code-review/content.md",
              "baseline: code-review/SKILL.md",
            ),
          )
        },
        "declared_files.areas.architecture" to { manifest ->
          Files.writeString(
            manifest,
            Files.readString(manifest).replace(
              "architecture: code-review/architecture/content.md",
              "architecture: code-review/architecture.md",
            ),
          )
        },
        "declared_quality_check_file" to { manifest ->
          Files.writeString(
            manifest,
            Files.readString(manifest).replace(
              "declared_quality_check_file: quality-check/content.md",
              "declared_quality_check_file: quality-check/SKILL.md",
            ),
          )
        },
      )

    cases.forEach { (field, mutateManifest) ->
      val fixtureName =
        if (field == "declared_quality_check_file") {
          "code_review_and_quality_check"
        } else {
          "valid_pack"
        }
      val root = copyFixture(fixtureName)
      val manifest = root.resolve("platform.yaml")
      mutateManifest(manifest)

      val error =
        assertFailsWith<SkillBillRuntimeException>(field) {
          loadPlatformPack(root)
        }
      assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, error.code)
      assertContains(error.message.orEmpty(), field)
      assertContains(error.message.orEmpty(), "content.md")
    }
  }

  @Test
  fun `loud fails with named shell content contract errors`() {
    assertNamedFailure(ManifestFailureCode.MISSING_MANIFEST, "missing_manifest", "platform.yaml")
    assertNamedFailure(SkillStagingFailureCode.MISSING_CONTENT_FILE, "missing_content_file", "baseline")
    assertNamedContractVersionFailure("bad_version", "9.99")
    assertNamedFailure(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, "invalid_schema", "routing_signals")
    assertNamedFailure(
      ManifestFailureCode.INVALID_MANIFEST_SCHEMA,
      "schema_areas_wrong_type",
      "declared_code_review_areas",
    )
    assertNamedFailure(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, "schema_unapproved_area", "laravel")
    assertNamedFailure(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, "extra_area", "performance")
  }

  @Test
  fun `loader validates content_md frontmatter instead of generated wrapper body shape`() {
    val shapeRoot = copyFixture("valid_pack")
    val shapeContent = shapeRoot.resolve("code-review").resolve("content.md")
    Files.writeString(
      shapeContent,
      Files.readString(shapeContent).replace(
        Regex("(?m)^description:.*$"),
        "description:",
      ),
    )
    val shapeError =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformPack(shapeRoot)
      }
    assertEquals(SkillStagingFailureCode.INVALID_SKILL_MD_SHAPE, shapeError.code)
    assertContains(shapeError.message.orEmpty(), "description")
    assertContains(shapeError.message.orEmpty(), "content.md")
  }

  @Test
  fun `loader requires non empty authored content`() {
    val root = copyFixture("valid_pack")
    val contentFile = root.resolve("code-review").resolve("content.md")
    Files.writeString(
      contentFile,
      "---\nname: code-review\ndescription: Empty authored content fixture.\n---\n",
    )

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformPack(root)
      }
    assertEquals(SkillStagingFailureCode.MISSING_REQUIRED_SECTION, error.code)
    assertContains(error.message.orEmpty(), "authored content")
    assertContains(error.message.orEmpty(), "content.md")
  }

  @Test
  fun `loader rejects title only authored content`() {
    val root = copyFixture("valid_pack")
    val contentFile = root.resolve("code-review").resolve("content.md")
    Files.writeString(
      contentFile,
      """
      ---
      name: code-review
      description: Title-only authored content fixture.
      ---

      # Fixture Review Content
      """.trimIndent() + "\n",
    )

    val error =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformPack(root)
      }
    assertEquals(SkillStagingFailureCode.MISSING_REQUIRED_SECTION, error.code)
    assertContains(error.message.orEmpty(), "authored guidance beyond the title heading")
    assertContains(error.message.orEmpty(), "content.md")
  }

  @Test
  fun `loader rejects generated wrapper boilerplate and self referential content pointer`() {
    val wrapperRoot = copyFixture("valid_pack")
    val wrapperContent = wrapperRoot.resolve("code-review").resolve("content.md")
    Files.writeString(
      wrapperContent,
      """
      ---
      name: code-review
      description: Wrapper boilerplate fixture.
      ---

      # Fixture Review Content

      Review the fixture implementation.

      ## Ceremony

      Generated wrapper ceremony does not belong here.
      """.trimIndent() + "\n",
    )

    val wrapperError =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformPack(wrapperRoot)
      }
    assertEquals(SkillStagingFailureCode.MISSING_REQUIRED_SECTION, wrapperError.code)
    assertContains(wrapperError.message.orEmpty(), "generated wrapper boilerplate heading '## Ceremony'")

    val pointerRoot = copyFixture("valid_pack")
    val pointerContent = pointerRoot.resolve("code-review").resolve("architecture").resolve("content.md")
    Files.writeString(
      pointerContent,
      """
      ---
      name: code-review
      description: Self-referential pointer fixture.
      ---

      Follow the instructions in [content.md](content.md).
      """.trimIndent() + "\n",
    )

    val pointerError =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformPack(pointerRoot)
      }
    assertEquals(SkillStagingFailureCode.MISSING_REQUIRED_SECTION, pointerError.code)
    assertContains(pointerError.message.orEmpty(), "self-referential wrapper pointer text")
  }

  @Test
  fun `area declarations require non empty authored content`() {
    val areaRoot = copyFixture("valid_pack")
    val areaContent = areaRoot.resolve("code-review").resolve("architecture").resolve("content.md")
    Files.writeString(
      areaContent,
      "---\nname: code-review\ndescription: Empty architecture area fixture.\n---\n",
    )

    val areaError =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformPack(areaRoot)
      }
    assertEquals(SkillStagingFailureCode.MISSING_REQUIRED_SECTION, areaError.code)
    assertContains(areaError.message.orEmpty(), "authored content")
    assertContains(areaError.message.orEmpty(), "code-review/architecture/content.md")
  }

  @Test
  fun `invalid skill md shape rejects frontmatter violations on content_md`() {
    val cases =
      listOf(
        Triple(
          "disallowed frontmatter key",
          { text: String -> text.replace("---\n", "---\nextra: nope\n", ignoreCase = false) },
          "extra",
        ),
        Triple(
          "missing name key",
          { text: String -> text.replace(Regex("(?m)^name:.*$"), "name:") },
          "name",
        ),
        Triple(
          "missing description key",
          { text: String -> text.replace(Regex("(?m)^description:.*$"), "description:") },
          "description",
        ),
      )
    cases.forEach { (label, mutate, discriminator) ->
      val root = copyFixture("valid_pack")
      val contentFile = root.resolve("code-review").resolve("content.md")
      Files.writeString(contentFile, mutate(Files.readString(contentFile)))

      val error =
        assertFailsWith<SkillBillRuntimeException>(label) {
          loadPlatformPack(root)
        }
      assertEquals(SkillStagingFailureCode.INVALID_SKILL_MD_SHAPE, error.code)
      val message = error.message.orEmpty()
      assertTrue(message.isNotBlank(), label)
      assertContains(message, discriminator, message = label)
      assertContains(message, "content.md", message = label)
    }
  }

  @Test
  fun `valid frontmatter passes shape validator regardless of body markdown`() {
    val root = copyFixture("valid_pack")
    val contentFile = root.resolve("code-review").resolve("content.md")
    val richBody =
      """
      |---
      |name: code-review
      |description: Fixture content with rich markdown to confirm the shape validator no longer rejects body markdown.
      |---
      |
      |# Top-level heading
      |
      |Some intro paragraph before any H2.
      |
      |### Subheading
      |
      || col1 | col2 |
      || ---- | ---- |
      || a    | b    |
      |
      |```kotlin
      |fun example(): Int = 42
      |```
      |
      """.trimMargin()
    Files.writeString(contentFile, richBody)

    validateSkillMdShape(contentFile, validateBodyShape = false)
  }
}

private fun malformedSpecialistContent(): String =
  """
  ---
  name: bill-invalid-review-shape-code-review-architecture
  description: Malformed architecture specialist fixture.
  internal-for: skill-bill
  ---

  # Malformed Architecture Specialist

  ## Focus

  Missing the governed specialist skeleton.
  """.trimIndent()

private fun assertNamedFailure(
  expectedCode: RuntimeFailureCode,
  fixtureName: String,
  expectedMessage: String,
) {
  val error = assertFailsWith<SkillBillRuntimeException> { loadPlatformPack(fixture(fixtureName)) }
  assertEquals(expectedCode, error.code)
  assertContains(error.message.orEmpty(), fixtureName)
  assertContains(error.message.orEmpty(), expectedMessage)
}

private fun assertNamedContractVersionFailure(
  fixtureName: String,
  expectedMessage: String,
) {
  val error =
    assertFailsWith<ContractVersionMismatchError> {
      loadPlatformPack(fixture(fixtureName))
    }
  assertContains(error.message.orEmpty(), fixtureName)
  assertContains(error.message.orEmpty(), expectedMessage)
}

private fun fixture(name: String): Path =
  repoRootFromTest().resolve("tests").resolve("fixtures").resolve("shell_content_contract").resolve(name)

private fun copyFixture(name: String): Path {
  val source = fixture(name)
  val target = Files.createTempDirectory("skillbill-shell-content-fixture").resolve(name)
  Files.walk(source).use { stream ->
    stream.sorted().forEach { path ->
      val relative = source.relativize(path)
      val destination = target.resolve(relative)
      when {
        Files.isDirectory(path) -> Files.createDirectories(destination)
        Files.isSymbolicLink(path) -> Files.createSymbolicLink(destination, Files.readSymbolicLink(path))
        else -> {
          Files.createDirectories(destination.parent)
          Files.copy(path, destination)
        }
      }
    }
  }
  return target
}
