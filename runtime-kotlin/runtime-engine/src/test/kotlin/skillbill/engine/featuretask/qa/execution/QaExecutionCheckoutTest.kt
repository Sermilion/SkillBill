package skillbill.engine.featuretask.qa.execution

import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.model.execution.QaPolicy
import skillbill.engine.featuretask.model.execution.QaSkipReason
import skillbill.engine.featuretask.phaserun.qa.InMemoryQaWorkspaces
import skillbill.engine.featuretask.phaserun.qa.RecordingQaLeases
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.featuretask.runner.kotlinPackWithValidationGate
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.scaffold.model.QaCapabilityDeclaration
import skillbill.scaffold.model.QaPlatform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QaExecutionCheckoutTest {
  private val root: Path = Files.createTempDirectory("qa-execution-checkout")
  private val repo: Path = root.resolve("repo").createDirectories()
  private val git = GitWorkflowGitOperations()
  private val androidPack =
    kotlinPackWithValidationGate()
      .copy(qaCapability = QaCapabilityDeclaration(setOf(QaPlatform.ANDROID), "qa/android-qa.md"))

  init {
    run("init", "--quiet", "--initial-branch=main")
    run("config", "user.email", "qa@example.com")
    run("config", "user.name", "QA Test")
    run("config", "commit.gpgsign", "false")
    repo.resolve(".gitignore").writeText("local.properties\n")
    repo.resolve("src/main/kotlin").createDirectories().resolve("Chat.kt").writeText("fun send() = \"sent\"\n")
    run("add", "-A")
    run("commit", "--quiet", "-m", "base")
    repo.resolve("src/main/kotlin/Chat.kt").writeText("fun send() = \"queued\"\n")
    repo.resolve("local.properties").writeText("sdk.dir=/sdk\n")
  }

  @AfterTest
  fun tearDown() {
    root.toFile().deleteRecursively()
  }

  @Test
  fun `QA edits its own checkout of the uncommitted tree and the caller checkout stays unchanged`() {
    val status = run("status", "--porcelain")
    var checkout: Path? = null

    val result =
      lifecycle(git).withExecution(repo, QaPolicy.AUTO) { qa ->
        val target = assertIs<QaExecutionTarget.Supported>(qa.target)
        checkout = target.checkout
        assertEquals("fun send() = \"queued\"\n", target.checkout.resolve("src/main/kotlin/Chat.kt").readText())
        assertEquals("sdk.dir=/sdk\n", target.checkout.resolve("local.properties").readText())
        target.checkout.resolve("src/main/kotlin/Chat.kt").writeText("fun send(): String = error(\"stream\")\n")
        target.checkout.resolve("src/main/kotlin/Fault.kt").writeText("val forcedFailure = true\n")
      }

    assertEquals(identity(repo), result.expected.treeIdentity)
    assertEquals(status, run("status", "--porcelain"))
    assertEquals("fun send() = \"queued\"\n", repo.resolve("src/main/kotlin/Chat.kt").readText())
    assertFalse(result.warnings.any { "repository change" in it }, result.warnings.toString())
    assertFalse(requireNotNull(checkout).exists())
    assertTrue(result.retainedResources.isEmpty())
  }

  @Test
  fun `a change to the caller checkout during QA is still reported`() {
    val result =
      lifecycle(git).withExecution(repo, QaPolicy.AUTO) {
        repo.resolve("src/main/kotlin/Chat.kt").writeText("fun send() = \"changed by someone\"\n")
      }

    assertContains(result.warnings.joinToString(), "QA kept a repository change made while it ran")
  }

  @Test
  fun `a checkout that cannot be created skips QA with isolation_unavailable`() {
    val broken =
      object : WorkflowGitOperations by git {
        override fun addDetachedWorktree(
          repoRoot: Path,
          worktree: Path,
          revision: String,
        ): WorkflowGitOperationResult = WorkflowGitOperationResult.Failed("worktree add refused")
      }

    val result = lifecycle(broken).withExecution(repo, QaPolicy.AUTO) { it.target }

    val skipped = assertIs<QaExecutionTarget.Skipped>(result.value)
    assertEquals(QaSkipReason.ISOLATION_UNAVAILABLE, skipped.reason)
    assertContains(skipped.detail, "worktree add refused")
  }

  private fun lifecycle(gitOperations: WorkflowGitOperations): QaExecutionLifecycle =
    testQaExecutionLifecycle(
      gitOperations,
      InMemoryQaWorkspaces(root.resolve("qa")),
      RecordingQaLeases(),
      QaCapabilityResolver(gitOperations) { listOf(androidPack) },
    )

  private fun identity(path: Path): String =
    assertIs<WorkflowGitOperationResult.Ok>(git.repositoryTreeIdentity(path)).value.trim()

  private fun run(vararg args: String): String {
    val process = ProcessBuilder(listOf("git", "-C", repo.toString()) + args).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    return output
  }
}
