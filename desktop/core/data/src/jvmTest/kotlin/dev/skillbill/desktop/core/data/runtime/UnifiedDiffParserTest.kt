package dev.skillbill.desktop.core.data.runtime

import dev.skillbill.desktop.core.domain.model.DiffLine
import dev.skillbill.desktop.core.domain.model.DiffLineKind.Added
import dev.skillbill.desktop.core.domain.model.DiffLineKind.Context
import dev.skillbill.desktop.core.domain.model.DiffLineKind.NoNewlineMarker
import dev.skillbill.desktop.core.domain.model.DiffLineKind.Removed
import kotlin.test.Test
import kotlin.test.assertEquals

class UnifiedDiffParserTest {
  @Test
  fun `numbers lines across hunks, skips headers and counts the tally git reports`() {
    val patch = listOf(
      "diff --git a/old.kt b/new.kt",
      "similarity index 90%",
      "rename from old.kt",
      "rename to new.kt",
      "index 1111111..2222222 100644",
      "--- a/old.kt",
      "+++ b/new.kt",
      "@@ -3,4 +3,5 @@ fun a()",
      " keep",
      "---x",
      "+added one",
      "+added two",
      " keep two",
      " keep three",
      "@@ -20 +21,2 @@",
      "-last",
      "+last changed",
      "+tail",
      "\\ No newline at end of file",
    ).joinToString("\n", postfix = "\n")

    val diff = parseUnifiedDiff(patch)

    assertEquals(listOf("@@ -3,4 +3,5 @@ fun a()", "@@ -20 +21,2 @@"), diff.hunks.map { it.header })
    assertEquals(
      listOf(
        DiffLine(Context, 3, 3, "keep"),
        DiffLine(Removed, 4, null, "--x"),
        DiffLine(Added, null, 4, "added one"),
        DiffLine(Added, null, 5, "added two"),
        DiffLine(Context, 5, 6, "keep two"),
        DiffLine(Context, 6, 7, "keep three"),
      ),
      diff.hunks[0].lines,
    )
    assertEquals(
      listOf(
        DiffLine(Removed, 20, null, "last"),
        DiffLine(Added, null, 21, "last changed"),
        DiffLine(Added, null, 22, "tail"),
        DiffLine(NoNewlineMarker, null, null, "\\ No newline at end of file"),
      ),
      diff.hunks[1].lines,
    )
    assertEquals(4, diff.insertions)
    assertEquals(2, diff.deletions)
  }
}
