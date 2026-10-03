package skillbill.infrastructure.skills.scaffold

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.SkillStagingFailureCode
import skillbill.infrastructure.skills.scaffold.validation.shape.validateSkillMdShape

class SkillMdShapeValidatorTest {
  @Test
  fun `valid content_md with rich body markdown passes when body shape is not enforced`() {
    val contentFile =
      writeFile(
        "content.md",
        """
        ---
        name: bill-example
        description: An example skill description.
        ---

        # Top-level heading

        Some intro paragraph before any H2.

        ### Subheading

        | col1 | col2 |
        | ---- | ---- |
        | a    | b    |

        ```kotlin
        fun example(): Int = 42
        ```
        """.trimIndent() + "\n",
      )

    validateSkillMdShape(contentFile, validateBodyShape = false)
  }

  @Test
  fun `valid wrapper SKILL_md passes when body shape is enforced`() {
    val skillFile =
      writeFile(
        "SKILL.md",
        """
        ---
        name: bill-example
        description: An example skill description.
        ---

        ## Descriptor

        Governed skill: `bill-example`

        ## Execution

        Run the skill.

        ## Ceremony

        Report findings.
        """.trimIndent() + "\n",
      )
    validateSkillMdShape(skillFile, validateBodyShape = true)
  }

  @Test
  fun `missing frontmatter block fails`() {
    val contentFile =
      writeFile(
        "content.md",
        """
        # No frontmatter here

        Body without a leading YAML block.
        """.trimIndent() + "\n",
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateSkillMdShape(contentFile, validateBodyShape = false)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_SKILL_MD_SHAPE, failure.code)
      }
    assertContains(error.message.orEmpty(), "must begin with a YAML frontmatter block")

    assertContains(error.message.orEmpty(), "content.md")
  }

  @Test
  fun `missing name key fails`() {
    val contentFile =
      writeFile(
        "content.md",
        """
        ---
        description: Has description but no name.
        ---

        # Body
        """.trimIndent() + "\n",
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateSkillMdShape(contentFile, validateBodyShape = false)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_SKILL_MD_SHAPE, failure.code)
      }
    assertContains(error.message.orEmpty(), "name")
  }

  @Test
  fun `missing description key fails`() {
    val contentFile =
      writeFile(
        "content.md",
        """
        ---
        name: bill-example
        ---

        # Body
        """.trimIndent() + "\n",
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateSkillMdShape(contentFile, validateBodyShape = false)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_SKILL_MD_SHAPE, failure.code)
      }
    assertContains(error.message.orEmpty(), "description")
  }

  @Test
  fun `wrapper body containing fenced code fails when body shape is enforced`() {
    val skillFile =
      writeFile(
        "SKILL.md",
        """
        ---
        name: bill-example
        description: An example skill description.
        ---

        ## Descriptor

        ```kotlin
        // wrappers must not contain fenced code blocks
        ```

        ## Execution

        Run the skill.

        ## Ceremony

        Report findings.
        """.trimIndent() + "\n",
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateSkillMdShape(skillFile, validateBodyShape = true)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_SKILL_MD_SHAPE, failure.code)
      }
    assertContains(error.message.orEmpty(), "fenced code")
    assertContains(error.message.orEmpty(), "SKILL.md")
  }

  @Test
  fun `wrapper body fence rule does not fire when body shape is not enforced`() {
    val skillFile =
      writeFile(
        "SKILL.md",
        """
        ---
        name: bill-example
        description: An example skill description.
        ---

        ## Descriptor

        ```kotlin
        // permissive mode tolerates fenced code blocks
        ```
        """.trimIndent() + "\n",
      )
    validateSkillMdShape(skillFile, validateBodyShape = false)
  }

  @Test
  fun `same wrapper text fails strict mode body shape rules`() {
    val skillFile =
      writeFile(
        "SKILL.md",
        """
        ---
        name: bill-example
        description: An example skill description.
        ---

        ## Descriptor

        ```kotlin
        // permissive mode tolerates fenced code blocks
        ```
        """.trimIndent() + "\n",
      )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        validateSkillMdShape(skillFile, validateBodyShape = true)
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.INVALID_SKILL_MD_SHAPE, failure.code)
      }
    assertContains(error.message.orEmpty(), "fenced code blocks are not allowed")
    assertContains(error.message.orEmpty(), "SKILL.md")
  }

  private fun writeFile(
    name: String,
    body: String,
  ): Path {
    val dir = Files.createTempDirectory("skill-md-shape-validator-test")
    val file = dir.resolve(name)
    Files.writeString(file, body)
    return file
  }
}
