package skillbill.infrastructure.workflow.git.standard

import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.infrastructure.workflow.git.git
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class GitCheckoutIndexPreservationTest {
  private lateinit var repo: Path

  @BeforeTest
  fun setUp() {
    repo = Files.createTempDirectory("skillbill-checkout-index")
    git(repo, "init", "--initial-branch", "main")
    git(repo, "config", "user.email", "runtime@skill-bill.test")
    git(repo, "config", "user.name", "Skill Bill Runtime")
    repo.resolve("Tracked.kt").writeText("base\n")
    repo.resolve("Deleted.kt").writeText("delete me\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-m", "base")
    git(repo, "branch", "feature")
  }

  @AfterTest
  fun tearDown() {
    repo.toFile().deleteRecursively()
  }

  @Test
  fun `checkout replays surviving staged paths after an added file moves`() {
    for (branch in listOf("main", "feature")) {
      repo.resolve("Old.kt").writeText("resolver\n")
      repo.resolve("Live.kt").writeText("live\n")
      git(repo, "add", "--", "Old.kt", "Live.kt")
      Files.move(repo.resolve("Old.kt"), repo.resolve("New.kt"), StandardCopyOption.REPLACE_EXISTING)

      val result = GitWorkflowGitOperations().checkoutBranch(repo, branch)

      assertIs<WorkflowGitOperationResult.Ok>(result, result.error)
      assertEquals(branch, git(repo, "branch", "--show-current"))
      assertEquals("", git(repo, "ls-files", "--", "Old.kt"))
      assertEquals("live", git(repo, "show", ":Live.kt"))
      assertFalse(Files.exists(repo.resolve("Old.kt")))
      assertEquals("resolver\n", repo.resolve("New.kt").readText())
      assertEquals("New.kt", git(repo, "ls-files", "--others", "--exclude-standard"))
    }
  }

  @Test
  fun `checkout stages merged target content and preserves staged deletions`() {
    val base = (1..20).map { "line $it" }
    repo.resolve("Tracked.kt").writeText(base.joinToString("\n", postfix = "\n"))
    git(repo, "commit", "-am", "multiline base")
    git(repo, "branch", "-f", "feature")
    git(repo, "checkout", "feature")
    val target = base.toMutableList().apply { this[1] = "target change" }
    repo.resolve("Tracked.kt").writeText(target.joinToString("\n", postfix = "\n"))
    repo.resolve("TargetOnly.kt").writeText("target only\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-m", "target change")
    git(repo, "checkout", "main")
    val local = base.toMutableList().apply { this[18] = "local change" }
    repo.resolve("Tracked.kt").writeText(local.joinToString("\n", postfix = "\n"))
    Files.delete(repo.resolve("Deleted.kt"))
    git(repo, "add", "--", "Tracked.kt", "Deleted.kt")

    assertIs<WorkflowGitOperationResult.Ok>(GitWorkflowGitOperations().checkoutBranch(repo, "feature"))

    val merged = target.toMutableList().apply { this[18] = "local change" }.joinToString("\n")
    assertEquals(merged, git(repo, "show", ":Tracked.kt"))
    assertEquals("$merged\n", repo.resolve("Tracked.kt").readText())
    assertEquals("target only", git(repo, "show", ":TargetOnly.kt"))
    assertEquals("", git(repo, "ls-files", "--", "Deleted.kt"))
    assertFalse(Files.exists(repo.resolve("Deleted.kt")))
  }
}
