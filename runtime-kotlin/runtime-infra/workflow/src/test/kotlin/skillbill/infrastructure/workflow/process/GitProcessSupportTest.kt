package skillbill.infrastructure.workflow.process

import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitProcessSupportTest {
  @Test
  fun `commit and push wait long enough for a pre-commit hook`() {
    assertEquals(GIT_TIMEOUT_SECONDS, gitTimeoutSeconds(listOf("status", "--porcelain")))
    assertEquals(GIT_TIMEOUT_SECONDS, gitTimeoutSeconds(listOf("rev-parse", "HEAD")))
    assertEquals(GIT_HOOKED_COMMAND_TIMEOUT_SECONDS, gitTimeoutSeconds(listOf("commit", "-m", "msg")))
    assertEquals(GIT_HOOKED_COMMAND_TIMEOUT_SECONDS, gitTimeoutSeconds(listOf("commit", "--amend", "--no-edit")))
    assertEquals(GIT_HOOKED_COMMAND_TIMEOUT_SECONDS, gitTimeoutSeconds(listOf("push", "-u", "origin", "feat/x")))
    assertTrue(GIT_HOOKED_COMMAND_TIMEOUT_SECONDS > GIT_TIMEOUT_SECONDS)
  }

  @Test
  fun `worktree remove waits long enough to delete a large checkout`() {
    assertEquals(
      GIT_HOOKED_COMMAND_TIMEOUT_SECONDS,
      gitTimeoutSeconds(listOf("worktree", "remove", "--force", "/tmp/checkout")),
    )
    assertEquals(GIT_TIMEOUT_SECONDS, gitTimeoutSeconds(listOf("worktree", "list", "--porcelain")))
    assertEquals(GIT_TIMEOUT_SECONDS, gitTimeoutSeconds(listOf("worktree", "prune")))
  }

  @Test
  fun `withValue changes only successful results`() {
    val failed = WorkflowGitOperationResult.Failed(error = "failure", value = "diagnostic")

    assertEquals(failed, failed.withValue("replacement"))
    assertEquals("replacement", WorkflowGitOperationResult.Ok("original").withValue("replacement").value)
  }
}
