package dev.skillbill.desktop.core.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilePathsTest {
  @Test
  fun `file must be a descendant without parent segments`() {
    assertTrue(isFileUnderDirectory("/a/b/nested/report.png", "/a/b"))
    assertFalse(isFileUnderDirectory("/a/b/../secret.png", "/a/b"))
    assertFalse(isFileUnderDirectory("/a/b-other/x", "/a/b"))
    assertFalse(isFileUnderDirectory("/a/b/x", ""))
  }
}
