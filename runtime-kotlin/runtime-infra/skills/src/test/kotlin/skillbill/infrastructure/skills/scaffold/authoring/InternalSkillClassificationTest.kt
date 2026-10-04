package skillbill.infrastructure.skills.scaffold.authoring

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.SkillStagingFailureCode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InternalSkillClassificationTest {
  private val tempDirs = mutableListOf<Path>()

  @AfterTest
  fun cleanup() {
    tempDirs.reversed().forEach { dir ->
      if (Files.exists(dir)) {
        Files.walk(dir).use { stream ->
          stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
      }
    }
  }

  @Test
  fun `parseInternalForFrontmatter returns null when key is absent`() {
    val contentFile =
      writeTempContent(
        """
        ---
        name: bill-feature
        description: Listed skill.
        ---

        Body.
        """.trimIndent(),
      )
    assertNull(parseInternalForFrontmatter(contentFile))
  }

  @Test
  fun `parseInternalForFrontmatter returns trimmed value when key is present`() {
    val contentFile =
      writeTempContent(
        """
        ---
        name: bill-feature-task
        description: Internal.
        internal-for: bill-feature
        ---

        Body.
        """.trimIndent(),
      )
    assertEquals("bill-feature", parseInternalForFrontmatter(contentFile))
  }

  @Test
  fun `parseInternalForFrontmatter returns empty string for a blank value`() {
    val contentFile =
      writeTempContent(
        """
        ---
        name: bill-feature-task
        description: Internal.
        internal-for:
        ---

        Body.
        """.trimIndent(),
      )

    assertEquals("", parseInternalForFrontmatter(contentFile))
  }

  @Test
  fun `parseInternalForFrontmatter takes the first occurrence of a duplicated key`() {
    val contentFile =
      writeTempContent(
        """
        ---
        name: bill-feature-task
        description: Internal.
        internal-for: bill-feature
        internal-for: bill-other
        ---

        Body.
        """.trimIndent(),
      )
    assertEquals("bill-feature", parseInternalForFrontmatter(contentFile))
  }

  @Test
  fun `classification passes when no skill declares internal-for`() {
    val targets =
      mapOf(
        "bill-feature" to target("bill-feature", internalFor = null),
        "bill-code-review" to target("bill-code-review", internalFor = null),
      )
    validateInternalSkillClassification(targets)
  }

  @Test
  fun `classification passes for a valid internal child`() {
    val targets =
      mapOf(
        "bill-feature" to target("bill-feature", internalFor = null),
        "bill-feature-task" to target("bill-feature-task", internalFor = "bill-feature"),
      )
    validateInternalSkillClassification(targets)
  }

  @Test
  fun `classification fails when internal-for value is blank`() {
    val targets =
      mapOf(
        "bill-feature-task" to target("bill-feature-task", internalFor = "   "),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateInternalSkillClassification(targets)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertMessageNames(error, "bill-feature-task", "empty value")
  }

  @Test
  fun `classification fails when parent is the skill itself`() {
    val targets =
      mapOf(
        "bill-feature-task" to target("bill-feature-task", internalFor = "bill-feature-task"),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateInternalSkillClassification(targets)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertMessageNames(error, "bill-feature-task", "skill itself")
  }

  @Test
  fun `classification fails when parent is not a discovered skill`() {
    val targets =
      mapOf(
        "bill-feature-task" to target("bill-feature-task", internalFor = "bill-featur"),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateInternalSkillClassification(targets)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertMessageNames(error, "bill-feature-task", "not a discovered skill")
  }

  @Test
  fun `classification fails when parent is itself internal`() {
    val targets =
      mapOf(
        "bill-other" to target("bill-other", internalFor = null),
        "bill-feature" to target("bill-feature", internalFor = "bill-other"),
        "bill-feature-task" to target("bill-feature-task", internalFor = "bill-feature"),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateInternalSkillClassification(targets)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertMessageNames(error, "bill-feature-task", "chained internal-for")
  }

  @Test
  fun `classification passes when a platform-pack skill declares internal-for on a listed base parent`() {
    val targets =
      mapOf(
        "skill-bill" to target("skill-bill", internalFor = null),
        "bill-kotlin-code-review" to
          target(
            "bill-kotlin-code-review",
            internalFor = "skill-bill",
            platform = "kotlin",
          ),
      )
    validateInternalSkillClassification(targets)
  }

  @Test
  fun `classification fails when a platform-pack skill declares an empty internal-for value`() {
    val targets =
      mapOf(
        "bill-code-review" to target("bill-code-review", internalFor = null),
        "bill-kotlin-code-review" to
          target(
            "bill-kotlin-code-review",
            internalFor = "  ",
            platform = "kotlin",
          ),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateInternalSkillClassification(targets)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertMessageNames(error, "bill-kotlin-code-review", "empty value")
  }

  @Test
  fun `classification fails when a platform-pack skill declares itself as parent`() {
    val targets =
      mapOf(
        "bill-kotlin-code-review" to
          target(
            "bill-kotlin-code-review",
            internalFor = "bill-kotlin-code-review",
            platform = "kotlin",
          ),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateInternalSkillClassification(targets)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertMessageNames(error, "bill-kotlin-code-review", "skill itself")
  }

  @Test
  fun `classification fails when a platform-pack skill declares an unknown parent`() {
    val targets =
      mapOf(
        "bill-kotlin-code-review" to
          target(
            "bill-kotlin-code-review",
            internalFor = "bill-no-such-skill",
            platform = "kotlin",
          ),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateInternalSkillClassification(targets)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertMessageNames(error, "bill-kotlin-code-review", "not a discovered skill")
  }

  @Test
  fun `classification fails when a platform-pack skill declares a pack-skill parent`() {
    val targets =
      mapOf(
        "bill-kotlin-code-review" to
          target(
            "bill-kotlin-code-review",
            internalFor = null,
            platform = "kotlin",
          ),
        "bill-kotlin-code-review-security" to
          target(
            "bill-kotlin-code-review-security",
            internalFor = "bill-kotlin-code-review",
            platform = "kotlin",
          ),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateInternalSkillClassification(targets)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertMessageNames(error, "bill-kotlin-code-review-security", "listed base skill")
  }

  @Test
  fun `classification fails when a platform-pack skill chains onto an internal base skill`() {
    val targets =
      mapOf(
        "bill-feature" to target("bill-feature", internalFor = null),
        "bill-feature-task" to target("bill-feature-task", internalFor = "bill-feature"),
        "bill-kotlin-code-review" to
          target(
            "bill-kotlin-code-review",
            internalFor = "bill-feature-task",
            platform = "kotlin",
          ),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateInternalSkillClassification(targets)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertMessageNames(error, "bill-kotlin-code-review", "chained internal-for")
  }

  @Test
  fun `classification fails when the parent is a platform-pack skill`() {
    val targets =
      mapOf(
        "bill-kotlin-code-review" to target("bill-kotlin-code-review", internalFor = null, platform = "kotlin"),
        "bill-feature-task" to target("bill-feature-task", internalFor = "bill-kotlin-code-review"),
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateInternalSkillClassification(targets)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, failure.code)
      }
    assertMessageNames(error, "bill-feature-task", "listed base skill")
  }

  private fun target(
    skillName: String,
    internalFor: String?,
    platform: String = "",
  ): AuthoringTarget =
    AuthoringTarget(
      skillName = skillName,
      packageName = platform.ifBlank { "base" },
      platform = platform,
      displayName = skillName,
      family = "",
      area = "",
      skillFile = Path.of("/repo/skills/$skillName/SKILL.md"),
      contentFile = Path.of("/repo/skills/$skillName/content.md"),
      internalFor = internalFor,
    )

  private fun writeTempContent(text: String): Path {
    val dir = Files.createTempDirectory("skillbill-internal-classification").also(tempDirs::add)
    val file = dir.resolve("content.md")
    Files.writeString(file, text)
    return file
  }

  private fun assertMessageNames(
    error: SkillBillRuntimeException,
    skillName: String,
    ruleFragment: String,
  ) {
    val message = error.message.orEmpty()
    assertTrue(message.contains("'$skillName'"), "expected message to name skill '$skillName'; got: $message")
    assertTrue(
      message.contains(ruleFragment),
      "expected message to mention rule fragment '$ruleFragment'; got: $message",
    )
  }
}
