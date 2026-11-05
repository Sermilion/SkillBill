package skillbill.mcp.review

import skillbill.contracts.review.DiffCommentPayloadKeys
import skillbill.mcp.shared.McpRuntimeContext
import skillbill.mcp.shared.callToolPayload
import skillbill.review.diffcomment.model.DiffCommentOutcome
import skillbill.review.diffcomment.model.DiffCommentThreadState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class McpDiffCommentToolsTest {
  @Test
  fun `reply with fixed resolves the thread on list`() {
    val userHome = Files.createTempDirectory("mcp-diff-comment")
    val repo = initGitRepo(userHome, "feat/a")
    val context =
      McpRuntimeContext(
        environment = mapOf("SKILL_BILL_TEST" to "1"),
        userHome = userHome,
        repositoryRoot = repo,
      )
    val added =
      context.callToolPayload(
        "diff_comment_add",
        mapOf(
          DiffCommentPayloadKeys.REPO_ROOT to repo.toString(),
          DiffCommentPayloadKeys.BODY to "review",
          DiffCommentPayloadKeys.PATH to "src/A.kt",
          DiffCommentPayloadKeys.LINE to 10,
        ),
      )
    val threadId = added[DiffCommentPayloadKeys.THREAD_ID].toString()
    context.callToolPayload(
      "diff_comment_reply",
      mapOf(
        DiffCommentPayloadKeys.THREAD_ID to threadId,
        DiffCommentPayloadKeys.BODY to "fixed",
        DiffCommentPayloadKeys.OUTCOME to DiffCommentOutcome.FIXED.wireValue,
      ),
    )
    val listed =
      context.callToolPayload(
        "diff_comment_list",
        mapOf(DiffCommentPayloadKeys.REPO_ROOT to repo.toString()),
      )
    val threads = listed[DiffCommentPayloadKeys.THREADS] as List<*>
    val thread = threads.single() as Map<*, *>
    assertEquals(DiffCommentThreadState.RESOLVED.wireValue, thread[DiffCommentPayloadKeys.STATE])
  }
}

private fun initGitRepo(
  userHome: Path,
  branch: String,
): Path {
  val repo = Files.createTempDirectory(userHome, "repo")
  runGit(repo, "init", "-b", branch)
  runGit(repo, "config", "user.email", "test@test.com")
  runGit(repo, "config", "user.name", "Test")
  Files.createDirectories(repo.resolve("src"))
  Files.writeString(repo.resolve("src/A.kt"), (1..12).joinToString("\n") { "line $it" })
  runGit(repo, "add", "src/A.kt")
  runGit(repo, "commit", "-m", "init")
  return repo
}

private fun runGit(
  repo: Path,
  vararg args: String,
) {
  val process =
    ProcessBuilder(listOf("git", "-c", "core.hooksPath=/dev/null", "-C", repo.toString()) + args)
      .redirectErrorStream(true)
      .apply { environment().putAll(mapOf("GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_CONFIG_SYSTEM" to "/dev/null")) }
      .start()
  val output = process.inputStream.bufferedReader().readText()
  check(process.waitFor() == 0) { output }
}
