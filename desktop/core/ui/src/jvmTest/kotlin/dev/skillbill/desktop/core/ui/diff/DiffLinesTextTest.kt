package dev.skillbill.desktop.core.ui.diff

import dev.skillbill.desktop.core.domain.model.DiffLine
import dev.skillbill.desktop.core.domain.model.DiffLineKind
import kotlin.test.Test
import kotlin.test.assertEquals

class DiffLinesTextTest {
  @Test
  fun `a line over the limit is cut and says how much is hidden`() {
    val shown = displayText(DiffLine(DiffLineKind.Added, null, 1, "x".repeat(5_007)))

    assertEquals("x".repeat(5_000) + " … 7 more characters", shown)
  }

  @Test
  fun `a short line keeps its text with tabs expanded`() {
    assertEquals("        a", displayText(DiffLine(DiffLineKind.Context, 1, 1, "\ta")))
  }
}
