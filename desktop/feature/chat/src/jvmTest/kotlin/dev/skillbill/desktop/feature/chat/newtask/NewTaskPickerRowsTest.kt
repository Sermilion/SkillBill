package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.RepositoryBranch
import dev.skillbill.desktop.core.domain.model.RepositoryBranches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class NewTaskPickerRowsTest {
  @Test
  fun `trimmed case insensitive filter keeps visible values and drops empty group heads`() {
    val read = RepositoryBranches(
      "/work/app",
      "main",
      listOf(RepositoryBranch("feature/One", Instant.fromEpochMilliseconds(1))),
      listOf(RepositoryBranch("origin/rel", Instant.fromEpochMilliseconds(2))),
    )

    val listing = branchListing(read, " REL ")

    assertNull(listing.default)
    assertEquals(listOf("Remote"), listing.groups.map { it.head })
    assertEquals(listOf("origin/rel"), listing.rows.map { it.value })
  }

  @Test
  fun `default keyword selects the default row while unmatched branch groups disappear`() {
    val read = RepositoryBranches(
      "/work/app",
      "main",
      listOf(RepositoryBranch("feature/One", Instant.fromEpochMilliseconds(1))),
      listOf(RepositoryBranch("origin/rel", Instant.fromEpochMilliseconds(2))),
    )

    val listing = branchListing(read, "default")

    assertEquals(BranchRow.Default("main"), listing.default)
    assertEquals(emptyList(), listing.groups)
    assertEquals(listOf(""), listing.rows.map { it.value })
  }

  @Test
  fun `highlight stays on a branch when rows change and wraps from no selection`() {
    val highlight = PickerHighlight()
    val first = listOf("", "feature/a", "origin/rel")
    assertEquals(0, highlight.index(first, ""))
    highlight.move(1, first, "")

    assertEquals(2, highlight.index(listOf("", "origin/rel", "feature/a"), ""))
    highlight.resetToFirst(emptyList())
    assertNull(highlight.index(first, ""))
    highlight.move(-1, first, "")
    assertEquals(2, highlight.index(first, ""))
    highlight.move(1, first, "")
    assertEquals(0, highlight.index(first, ""))
  }
}
