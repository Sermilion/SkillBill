package dev.skillbill.desktop.feature.detail

import kotlin.test.Test
import kotlin.test.assertEquals

class ReportTextTest {
  @Test
  fun `a label line followed by bullets renders as a paragraph and a list, with wrapped bullets joined`() {
    val blocks = reportBlocks(
      """
      The review is done.

      **Checks that came back clean:**
      - **Removed enum value:** nothing references it.
        All branches still cover every case.
      - **Translations:** all ten locales.
      ```
      ./gradlew check
      ```
      """.trimIndent(),
    )

    assertEquals(
      listOf(
        ReportBlock.Paragraph("The review is done."),
        ReportBlock.Paragraph("**Checks that came back clean:**"),
        ReportBlock.Bullets(
          listOf("**Removed enum value:** nothing references it. All branches still cover every case.", "**Translations:** all ten locales."),
        ),
        ReportBlock.Code("./gradlew check"),
      ),
      blocks,
    )
  }
}
