package dev.skillbill.desktop.core.ui.diff

import dev.skillbill.desktop.core.domain.model.DiffLine
import dev.skillbill.desktop.core.domain.model.DiffLineKind
import kotlin.test.Test
import kotlin.test.assertEquals

class DiffWordsTest {
  private fun context(text: String) = DiffLine(DiffLineKind.Context, 1, 1, text)

  @Test
  fun `only whole case-sensitive occurrences match`() {
    val line = context("foo.bar(foo_bar, foo, Foo)")

    assertEquals(listOf(1..3, 18..20), wordMatchRanges(line, "foo"))
  }

  @Test
  fun `the prefix is ignored and a no-newline marker has no words`() {
    listOf(
      DiffLine(DiffLineKind.Added, null, 1, "foo"),
      DiffLine(DiffLineKind.Removed, 1, null, "foo"),
      context("foo"),
    ).forEach { line ->
      assertEquals(DiffWordHit.Ignored, wordHitAt(line, 0))
      assertEquals(DiffWordHit.Word("foo", 1), wordHitAt(line, 2))
    }
    val marker = DiffLine(DiffLineKind.NoNewlineMarker, null, null, "\\ No newline at end of file")

    assertEquals(DiffWordHit.Ignored, wordHitAt(marker, marker.text.indexOf("file")))
    assertEquals(emptyList(), wordMatchRanges(marker, "file"))
  }

  @Test
  fun `offsets follow tab-expanded text`() {
    val line = context("\tfoo")

    assertEquals(DiffWordHit.Word("foo", 9), wordHitAt(line, 10))
    assertEquals(listOf(9..11), wordMatchRanges(line, "foo"))
  }

  @Test
  fun `only words wholly before the cut are found`() {
    val whole = context(" ".repeat(4_997) + "end tail")
    val crossing = context(" ".repeat(4_998) + "crossing")

    assertEquals(listOf(4_998..5_000), wordMatchRanges(whole, "end"))
    assertEquals(DiffWordHit.Word("end", 4_998), wordHitAt(whole, 4_998))
    assertEquals(emptyList(), wordMatchRanges(crossing, "crossing"))
    assertEquals(DiffWordHit.Ignored, wordHitAt(crossing, 4_999))
    assertEquals(emptyList(), wordMatchRanges(crossing, "more"))
    assertEquals(DiffWordHit.Ignored, wordHitAt(crossing, displayText(crossing).indexOf("more") + 1))
  }
}
