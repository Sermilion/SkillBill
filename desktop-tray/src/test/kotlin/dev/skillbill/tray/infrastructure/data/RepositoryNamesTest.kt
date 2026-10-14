package dev.skillbill.tray.infrastructure.data

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class RepositoryNamesTest {
  @Test
  fun `linked worktree is named after its main checkout`() {
    val base = Files.createTempDirectory("repository-names")
    val main = base.resolve("capmo-android").createDirectories()
    val worktreeGitDir = main.resolve(".git/worktrees/we5116-ai-assistant-operations").createDirectories()
    worktreeGitDir.resolve("commondir").writeText("../..\n")
    val worktree = main.resolve(".claude/worktrees/we5116-ai-assistant-operations").createDirectories()
    worktree.resolve(".git").writeText("gitdir: $worktreeGitDir\n")

    assertEquals("capmo-android", RepositoryNames.resolve(worktree.toString()))
    assertEquals("capmo-android", RepositoryNames.resolve(main.toString()))
  }

  @Test
  fun `folder without a worktree pointer keeps its own name`() {
    val root = Files.createTempDirectory("repository-names").resolve("plain-repo").createDirectories()

    assertEquals("plain-repo", RepositoryNames.resolve(root.toString()))
  }
}
