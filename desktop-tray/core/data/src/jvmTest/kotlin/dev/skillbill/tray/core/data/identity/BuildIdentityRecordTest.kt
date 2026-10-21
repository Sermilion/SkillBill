package dev.skillbill.tray.core.data.identity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildIdentityRecordTest {
  private fun dirty(runId: String) = BuildIdentityRecord("abc123", dirty = true, releaseTag = "", installRunId = runId)

  private fun clean(commit: String, tag: String = "") = BuildIdentityRecord(commit, dirty = false, releaseTag = tag, installRunId = "")

  @Test
  fun `dirty builds of one commit match only through the same install run`() {
    assertFalse(dirty("run-1").sameBuildAs(dirty("run-2")))
    assertFalse(dirty("").sameBuildAs(dirty("")))
    assertTrue(dirty("run-1").sameBuildAs(dirty("run-1")))
    assertFalse(dirty("run-1").sameBuildAs(clean("abc123")))
  }

  @Test
  fun `clean builds match on commit and release tag`() {
    assertTrue(clean("abc123", "v1.0.0").sameBuildAs(clean("abc123", "v1.0.0")))
    assertFalse(clean("abc123", "v1.0.0").sameBuildAs(clean("abc123")))
    assertFalse(clean("abc123").sameBuildAs(clean("def456")))
  }
}
