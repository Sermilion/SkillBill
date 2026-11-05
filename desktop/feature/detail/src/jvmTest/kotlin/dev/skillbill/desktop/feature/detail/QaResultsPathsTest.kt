package dev.skillbill.desktop.feature.detail

import kotlin.test.Test
import kotlin.test.assertEquals

class QaResultsPathsTest {
  private val dir = "/srv/qa/qa-4f7e0edf"

  @Test
  fun `paths under the directory and user roots read as file names`() {
    assertEquals("Evidence: benchmark.log, app.png.", shortenPaths("Evidence: $dir/benchmark.log, $dir/shots/app.png.", dir))
    assertEquals("see `b.kt:12` now", shortenPaths("see `/home/me/src/b.kt:12` now", dir))
    assertEquals("in qa-4f7e0edf and qa-4f7e0edf", shortenPaths("in $dir and $dir/", dir))
    assertEquals("x.log", shortenPaths("file:///tmp/run/x.log", null))
    assertEquals("Ran in qa-4f7e0edf. Then qa-4f7e0edf: done", shortenPaths("Ran in $dir. Then $dir: done", dir))
  }

  @Test
  fun `urls api routes and sibling directories stay intact`() {
    val text = "GET https://host/home/x and /api/v1/users from $dir-2/a.log and $dir.bak"
    assertEquals(text, shortenPaths(text, dir))
  }
}
