package dev.skillbill.desktop.core.designsystem.detail

import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityFeedFoldTest {
  @Test
  fun `newest first keeps the newest entries while default order keeps the latest tail`() {
    val entries = (1..10).toList()

    val newestFirst = activityFeedFold(entries, showAll = false, newestFirst = true)
    val default = activityFeedFold(entries, showAll = false, newestFirst = false)

    assertEquals((1..8).toList(), newestFirst.shown)
    assertEquals(2, newestFirst.hidden)
    assertEquals((3..10).toList(), default.shown)
    assertEquals(2, default.hidden)
  }
}
