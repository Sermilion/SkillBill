package dev.skillbill.desktop.feature.changes

import dev.skillbill.desktop.core.domain.model.CommentSide
import dev.skillbill.desktop.core.domain.model.DiffLine
import dev.skillbill.desktop.core.domain.model.DiffLineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiffCommentAuthoringTest {
  @Test
  fun `anchors removed lines to old and added or context lines to new`() {
    assertEquals(CommentAnchor(CommentSide.Old, 7), commentAnchorFor(DiffLine(DiffLineKind.Removed, 7, null, "old")))
    assertEquals(CommentAnchor(CommentSide.New, 8), commentAnchorFor(DiffLine(DiffLineKind.Added, null, 8, "new")))
    assertEquals(CommentAnchor(CommentSide.New, 9), commentAnchorFor(DiffLine(DiffLineKind.Context, 9, 9, "context")))
    assertNull(commentAnchorFor(DiffLine(DiffLineKind.Removed, null, null, "missing old number")))
    assertNull(commentAnchorFor(DiffLine(DiffLineKind.Added, null, null, "missing new number")))
    assertNull(commentAnchorFor(DiffLine(DiffLineKind.NoNewlineMarker, null, null, "")))
  }
}
