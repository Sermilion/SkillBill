package skillbill.infrastructure.workflow.git

import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeReadinessEvidenceSchemaError
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowReadinessTreeIdentityResult
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeReadinessCheckResult
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeReadinessCheckStatus
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeReadinessEvidence
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitReadinessTreeIdentityOperationsTest {
  @Test
  fun `history-only agent history md does not change source_tree_sha`() {
    val repoRoot = Files.createTempDirectory("skillbill-readiness-tree")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.createDirectories(repoRoot.resolve("runtime-kotlin/agent"))
    Files.writeString(repoRoot.resolve("runtime-kotlin/source.kt"), "source\n")
    Files.writeString(repoRoot.resolve("runtime-kotlin/agent/history.md"), "history one\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")

    val before =
      requireNotNull(
        (
          GitReadinessTreeIdentityOperations.computeSourceTreeSha(
            repoRoot,
            "wf",
          ) as WorkflowGitOperationResult.Ok
        ).value,
      )
    Files.writeString(repoRoot.resolve("runtime-kotlin/agent/history.md"), "history two\n")
    val after =
      requireNotNull(
        (
          GitReadinessTreeIdentityOperations.computeSourceTreeSha(
            repoRoot,
            "wf",
          ) as WorkflowGitOperationResult.Ok
        ).value,
      )

    assertEquals(before, after)
  }

  @Test
  fun `linked worktree resolves source_tree_sha from its index outside the worktree root`() {
    val mainRoot = Files.createTempDirectory("skillbill-readiness-main")
    git(mainRoot, "init")
    git(mainRoot, "config", "user.email", "skill-bill@example.test")
    git(mainRoot, "config", "user.name", "Skill Bill")
    Files.writeString(mainRoot.resolve("source.kt"), "source\n")
    git(mainRoot, "add", ".")
    git(mainRoot, "commit", "-m", "initial")
    val worktreeRoot = Files.createTempDirectory("skillbill-readiness-linked").resolve("linked")
    git(mainRoot, "worktree", "add", "-b", "feature", worktreeRoot.toString())

    val result = GitReadinessTreeIdentityOperations.computeSourceTreeSha(worktreeRoot, "wf")

    assertTrue(result is WorkflowGitOperationResult.Ok && !result.value.isNullOrBlank(), "$result")
  }

  @Test
  fun `stale base ref diagnostic carries captured versus current base and head`() {
    val evidence =
      FeatureTaskRuntimeReadinessEvidence(
        sourceTreeSha = "tree-captured",
        baseRefSha = "base-captured",
        headSha = "head-captured",
        selectedChecks = listOf("pack-collect-all"),
        checkResults =
          listOf(
            FeatureTaskRuntimeReadinessCheckResult(
              "pack-collect-all",
              "./gradlew check",
              0,
              FeatureTaskRuntimeReadinessCheckStatus.PASSED,
            ),
          ),
      )
    val failure =
      assertFailsWith<InvalidFeatureTaskRuntimeReadinessEvidenceSchemaError> {
        evidence.requireReady(
          "commit_push",
          expectedSourceTreeSha = "tree-current",
          expectedBaseRefSha = "base-current",
          expectedHeadSha = "head-current",
        )
      }
    assertTrue(failure.message.orEmpty().contains("tree-captured"))
    assertTrue(failure.message.orEmpty().contains("tree-current"))
    assertTrue(failure.message.orEmpty().contains("base-captured"))
    assertTrue(failure.message.orEmpty().contains("base-current"))
    assertTrue(failure.message.orEmpty().contains("head-captured"))
    assertTrue(failure.message.orEmpty().contains("head-current"))
  }

  @Test
  fun `changed paths include branch diff and untracked files against origin base`() {
    val repoRoot = Files.createTempDirectory("skillbill-readiness-paths")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("base.txt"), "base\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    git(repoRoot, "update-ref", "refs/remotes/origin/main", "HEAD")
    Files.writeString(repoRoot.resolve("committed-branch-change.txt"), "branch\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "branch change")
    Files.writeString(repoRoot.resolve("untracked-change.txt"), "untracked\n")

    val result =
      assertIs<WorkflowGitNameListResult.Listed>(
        GitReadinessTreeIdentityOperations.readinessChangedPathsAgainstBase(repoRoot, "main"),
      )

    assertEquals(setOf("committed-branch-change.txt", "untracked-change.txt"), result.names.toSet())
  }

  @Test
  fun `readiness identity resolves the tree, base and head shas as one typed record`() {
    val repoRoot = Files.createTempDirectory("skillbill-readiness-identity")
    git(repoRoot, "init")
    git(repoRoot, "config", "user.email", "skill-bill@example.test")
    git(repoRoot, "config", "user.name", "Skill Bill")
    Files.writeString(repoRoot.resolve("source.kt"), "source\n")
    git(repoRoot, "add", ".")
    git(repoRoot, "commit", "-m", "initial")
    git(repoRoot, "update-ref", "refs/remotes/origin/main", "HEAD")
    val base = git(repoRoot, "rev-parse", "HEAD")
    Files.writeString(repoRoot.resolve("source.kt"), "source on the branch\n")
    git(repoRoot, "commit", "-am", "branch change")

    val resolved =
      assertIs<WorkflowReadinessTreeIdentityResult.Resolved>(
        GitReadinessTreeIdentityOperations.resolveReadinessTreeIdentity(repoRoot, "main", "wf"),
      )

    assertEquals(git(repoRoot, "rev-parse", "HEAD"), resolved.identity.headSha)
    assertEquals(base, resolved.identity.baseRefSha)
    assertEquals(git(repoRoot, "rev-parse", "HEAD^{tree}"), resolved.identity.sourceTreeSha)
  }
}
