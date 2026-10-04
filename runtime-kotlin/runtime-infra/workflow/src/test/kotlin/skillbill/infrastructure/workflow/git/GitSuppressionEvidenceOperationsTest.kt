package skillbill.infrastructure.workflow.git

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitSuppressionEvidenceOperationsTest {
  @Test
  fun `reads base and head content for a scoped path`() {
    withTempRepo { repo ->
      val path = "src/A.kt"
      Files.createDirectories(repo.resolve("src"))
      repo.resolve(path).writeText("fun base() = 1\n")
      git(repo, "add", path)
      git(repo, "commit", "-m", "base")
      val baseSha = git(repo, "rev-parse", "HEAD").trim()
      repo.resolve(path).writeText("@Suppress(\"X\")\nfun head() = 2\n")
      val evidence = GitWorkflowGitOperations().scopedPathContentsAgainstBase(repo, baseSha, listOf(path))
      assertEquals(WorkflowGitOperationStatus.OK, evidence.status, evidence.error)
      val pair = evidence.pairs.single()
      assertEquals(path, pair.headPath)
      assertEquals(path, pair.basePath)
      assertTrue(pair.baseContent!!.contains("fun base()"))
      assertTrue(pair.headContent!!.contains("@Suppress"))
    }
  }

  @Test
  fun `rename maps head path to base path without inventing a new base identity`() {
    withTempRepo { repo ->
      val oldPath = "src/Old.kt"
      val newPath = "src/New.kt"
      Files.createDirectories(repo.resolve("src"))
      repo.resolve(oldPath).writeText("@Suppress(\"X\")\nfun f() = 1\n")
      git(repo, "add", oldPath)
      git(repo, "commit", "-m", "base")
      val baseSha = git(repo, "rev-parse", "HEAD").trim()
      git(repo, "mv", oldPath, newPath)
      git(repo, "commit", "-m", "rename")
      val evidence = GitWorkflowGitOperations().scopedPathContentsAgainstBase(repo, baseSha, listOf(newPath))
      assertEquals(WorkflowGitOperationStatus.OK, evidence.status, evidence.error)
      val pair = evidence.pairs.single()
      assertEquals(newPath, pair.headPath)
      assertEquals(oldPath, pair.basePath)
      assertNotNull(pair.baseContent)
      assertTrue(pair.baseContent!!.contains("@Suppress"))
    }
  }

  @Test
  fun `paths outside the inventory are not returned`() {
    withTempRepo { repo ->
      repo.resolve("kept.kt").writeText("kept\n")
      repo.resolve("other.kt").writeText("other\n")
      git(repo, "add", ".")
      git(repo, "commit", "-m", "base")
      val baseSha = git(repo, "rev-parse", "HEAD").trim()
      val evidence = GitWorkflowGitOperations().scopedPathContentsAgainstBase(repo, baseSha, listOf("kept.kt"))
      assertEquals(listOf("kept.kt"), evidence.pairs.map { it.headPath })
    }
  }

  private fun withTempRepo(block: (Path) -> Unit) {
    val dir = Files.createTempDirectory("skill-bill-suppression-git")
    try {
      git(dir, "init")
      git(dir, "config", "user.email", "test@example.com")
      git(dir, "config", "user.name", "test")
      git(dir, "config", "commit.gpgsign", "false")
      block(dir)
    } finally {
      dir.toFile().deleteRecursively()
    }
  }

  private fun git(
    repo: Path,
    vararg args: String,
  ): String {
    val process =
      ProcessBuilder(listOf("git") + args.toList())
        .directory(repo.toFile())
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()
    check(code == 0) { "git ${args.joinToString(" ")} failed ($code): $output" }
    return output
  }
}
