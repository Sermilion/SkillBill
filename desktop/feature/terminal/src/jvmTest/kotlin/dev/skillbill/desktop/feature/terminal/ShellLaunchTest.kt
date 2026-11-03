package dev.skillbill.desktop.feature.terminal

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShellLaunchTest {
  @Test
  fun `missing goal worktree uses its primary checkout while an ordinary missing directory stays missing`() {
    val root = Files.createTempDirectory("terminal-shell-launch")
    try {
      val missing = root.resolve("goal")
      val goal = tabStart(missing, root, null) { Files.isDirectory(it) }
      val plain = tabStart(missing, null, null) { Files.isDirectory(it) }

      assertEquals(root, goal.directory)
      assertEquals("This goal's worktree was removed. This terminal is in $root.", goal.notice)
      assertEquals(missing, plain.directory)
      assertNull(plain.notice)
    } finally {
      root.toFile().deleteRecursively()
    }
  }
}
