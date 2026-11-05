package skillbill.cli.diffcomment

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parsers.CommandLineParser
import skillbill.application.review.diffcomment.DiffCommentService
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.review.DiffCommentContract
import skillbill.contracts.review.DiffCommentPayloadKeys
import skillbill.contracts.review.DiffCommentThreadContract
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.infrastructure.workflow.filesystem.FileSystemDiffCommentWorktreeFiles
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.diffcomment.model.DiffCommentAuthor
import skillbill.review.diffcomment.model.DiffCommentThreadState
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DiffCommentCommandTest {
  @Test
  fun `invalid list state is a usage error naming allowed values`() {
    val fixture = Fixture()
    val error =
      assertFailsWith<UsageError> {
        fixture.run(listOf("list", "--state", "bogus"))
      }
    DiffCommentThreadState.entries.forEach { state ->
      assertContains(error.message.orEmpty(), state.wireValue)
    }
  }

  @Test
  fun `add then list json round trips shared thread keys`() {
    val fixture = Fixture()
    fixture.run(
      listOf(
        "add",
        "--body",
        "note",
        "--path",
        "src/A.kt",
        "--line",
        "1",
      ),
    )
    val state = fixture.run(listOf("list", "--format", "json"))
    val result = checkNotNull(state.result)
    assertEquals(0, result.exitCode)
    val threads = result.payload?.get(DiffCommentPayloadKeys.THREADS) as List<*>
    val thread = threads.single() as Map<*, *>
    assertEquals(expectedThreadKeys(), thread.keys)
    assertEquals(DiffCommentThreadState.OPEN.wireValue, thread[DiffCommentPayloadKeys.STATE])
    val comment = (thread[DiffCommentPayloadKeys.COMMENTS] as List<*>).single() as Map<*, *>
    assertEquals(expectedCommentKeys(), comment.keys)
    assertEquals(DiffCommentAuthor.AGENT.wireValue, comment[DiffCommentPayloadKeys.AUTHOR])
  }

  private fun expectedThreadKeys() =
    DiffCommentThreadContract(
      threadId = "",
      state = "",
      outdated = false,
      path = null,
      side = null,
      line = null,
      sourceLabel = null,
      severity = null,
      findingRef = null,
      comments = emptyList(),
    ).toPayload().keys

  private fun expectedCommentKeys() =
    DiffCommentContract(
      commentId = "",
      author = "",
      agentId = null,
      body = "",
      outcome = null,
      createdAt = "",
    ).toPayload().keys

  private class Fixture {
    private val userHome = Files.createTempDirectory("diff-comment-cli")
    val repo = initGitRepo(userHome, "feat/a")
    private var nextId = 0
    private val database =
      sqliteDatabaseSessionFactory(
        userHome = userHome,
        environment = mapOf("SKILL_BILL_TEST" to "1"),
      )
    private val service =
      DiffCommentService(
        database = database,
        git = TestGit(),
        repositories = RepositoryRoots,
        worktreeFiles = FileSystemDiffCommentWorktreeFiles(NoopDiagnostics),
        clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        ids = IdentifierGeneratorPort { "thread-${nextId++}" },
        diagnostics = NoopDiagnostics,
      )

    fun run(arguments: List<String>): CliRunState {
      val state = CliRunState(stdinText = null)
      val inputs =
        CliRunInputs(
          databasePath = null,
          environment = mapOf("SKILL_BILL_TEST" to "1"),
          userHome = userHome,
          repositoryRoot = repo,
          liveStdout = {},
          liveStderr = {},
        )
      val command =
        DiffCommentCommand(
          DiffCommentListCommand(service, state, inputs),
          DiffCommentAddCommand(service, state, inputs),
          DiffCommentReplyCommand(service, state, inputs),
          DiffCommentSetStateCommand(service, state, inputs),
        )
      CommandLineParser.parseAndRun(command, arguments) { parsed -> parsed.run() }
      return state
    }
  }

  private object NoopDiagnostics : RuntimeDiagnostics {
    override fun warning(
      message: String,
      error: Throwable?,
    ) = Unit

    override fun error(
      message: String,
      error: Throwable?,
    ) = Unit

    override fun info(message: String) = Unit
  }

  private object RepositoryRoots : RepositoryEnclosingRootPort {
    override fun enclosingRepositoryRoot(start: Path) = start

    override fun canonicalPath(path: Path) = path

    override fun optionalRealPath(path: Path) = path

    override fun repositoryIdentity(repoRoot: Path) = repoRoot.toAbsolutePath().normalize().toString()

    override fun primaryRepositoryIdentity(repoRoot: Path) = repositoryIdentity(repoRoot)

    override fun primaryRepositoryRoot(repoRoot: Path) = repoRoot
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
  Files.writeString(repo.resolve("src/A.kt"), "anchor\n")
  runGit(repo, "add", "src/A.kt")
  runGit(repo, "commit", "-m", "init")
  return repo
}

private fun runGit(
  repo: Path,
  vararg args: String,
): String {
  val process =
    ProcessBuilder(listOf("git", "-c", "core.hooksPath=/dev/null", "-C", repo.toString()) + args)
      .redirectErrorStream(true)
      .apply { environment().putAll(mapOf("GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_CONFIG_SYSTEM" to "/dev/null")) }
      .start()
  val output = process.inputStream.bufferedReader().readText()
  check(process.waitFor() == 0) { output }
  return output
}

private class TestGit : WorkflowGitOperations by NoopWorkflowGitOperations {
  override fun currentBranch(repoRoot: Path) = WorkflowGitOperationResult.Ok("feat/a")

  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(runGit(repoRoot, "rev-parse", "HEAD").trim())

  override fun resolveCommit(
    repoRoot: Path,
    revision: String,
  ) = headCommitSha(repoRoot)
}
