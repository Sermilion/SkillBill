package skillbill.review.spec

import skillbill.review.spec.GovernedSpecSectionParser.ACCEPTANCE_CRITERIA_PREFIX
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GovernedSpecSectionParserTest {
  @Test
  fun `a fenced heading inside a section does not end it`() {
    val spec =
      """
      ## Acceptance Criteria
      1. The report ends with this section:
      ```text
      ## Unblock
      ```
      2. The desktop shows it.

      ## Non-Goals
      - Nothing else.
      """.trimIndent()

    assertEquals(
      listOf("The report ends with this section: ```text ## Unblock ```", "The desktop shows it."),
      GovernedSpecSectionParser.parseListSection(spec) { it.startsWith(ACCEPTANCE_CRITERIA_PREFIX) },
    )
  }

  @Test
  fun `a fenced heading is not taken as the start of a section`() {
    val spec =
      """
      ## Format
      ~~~~markdown
      ## Acceptance Criteria
      - Example only.
      ~~~~

      ## Acceptance Criteria
      - The real criterion.
      """.trimIndent()

    assertEquals(
      listOf("The real criterion."),
      GovernedSpecSectionParser.parseListSection(spec) { it.startsWith(ACCEPTANCE_CRITERIA_PREFIX) },
    )
  }

  @Test
  fun `an unclosed fence falls back to plain heading boundaries`() {
    val spec = "## Acceptance Criteria\n- One.\n```text\n## Non-Goals\n- Two."

    assertEquals(
      listOf("One. ```text"),
      GovernedSpecSectionParser.parseListSection(spec) { it.startsWith(ACCEPTANCE_CRITERIA_PREFIX) },
    )
    assertNull(GovernedSpecSectionParser.sectionBody(spec) { it == "missing" })
  }

  @Test
  fun `a tilde fence stays open across a shorter or backtick marker`() {
    val lines = listOf("~~~~", "```", "## Inside", "~~~", "~~~~", "## Outside")

    assertContentEquals(
      booleanArrayOf(false, false, false, false, false, true),
      MarkdownFences.outsideFenceMask(lines),
    )
  }
}
