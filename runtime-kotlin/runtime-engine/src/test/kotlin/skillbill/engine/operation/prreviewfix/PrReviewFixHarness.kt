package skillbill.engine.operation.prreviewfix

import skillbill.application.review.diffcomment.DiffCommentService
import skillbill.application.review.diffcomment.model.DiffCommentAgentThreadRequest
import skillbill.application.review.diffcomment.model.DiffCommentListResult
import skillbill.application.review.diffcomment.model.DiffCommentThreadResult
import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepFileManifest
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.reviewStepOutput
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.engine.operation.core.NoOperationCardLifecycle
import skillbill.engine.operation.core.OperationArguments
import skillbill.engine.operation.core.OperationConfirmationGate
import skillbill.engine.operation.core.OperationExecutor
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationRegistry
import skillbill.engine.operation.core.OperationRequest
import skillbill.engine.operation.core.OperationStepRunner
import skillbill.infrastructure.sqlite.operation.SqliteOperationProposalRepository
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.infrastructure.workflow.git.GitWorkflowGitOperations
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.operation.UnavailableOperationCardRepository
import skillbill.ports.operation.model.OperationProposal
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.projectstore.FixedProjectStorePort
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.review.diffcomment.DiffCommentWorktreeFiles
import skillbill.ports.review.diffcomment.model.DiffCommentLineObservation
import skillbill.ports.review.pullrequest.PullRequestReviewThreadOperations
import skillbill.ports.review.pullrequest.model.ReviewPullRequest
import skillbill.ports.review.pullrequest.model.ReviewPullRequestResolution
import skillbill.ports.review.pullrequest.model.ReviewThread
import skillbill.ports.review.pullrequest.model.ReviewThreadComment
import skillbill.ports.review.pullrequest.model.ReviewThreadListing
import skillbill.ports.review.pullrequest.model.ReviewThreadReplyResult
import skillbill.ports.workflow.branchworktree.RecordingBranchWorktreeResources
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.diffcomment.model.DiffCommentThreadState
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID
import kotlin.io.path.appendText
import kotlin.io.path.exists
import kotlin.io.path.writeText

internal class PrReviewFixHarness : AutoCloseable {
  private val root: Path = Files.createTempDirectory("pr-review-fix-operation")
  val repo: Path = root.resolve("repo")
  val github = FakeReviewThreads()
  val runner = ScriptedPrReviewFixRunner(repo)
  val validations = mutableListOf<PhaseRunRequest>()
  val pushes = mutableListOf<String>()
  val warnings = mutableListOf<String>()
  var validationBlocks: Boolean = false
  var afterWorktreeStatus: (() -> Unit)? = null
  private val diagnostics =
    object : RuntimeDiagnostics by NoopRuntimeDiagnostics {
      override fun warning(
        message: String,
        error: Throwable?,
      ) {
        warnings += message
      }
    }
  private val realGit = GitWorkflowGitOperations()
  private val git =
    object : WorkflowGitOperations by realGit {
      override fun worktreeStatus(repoRoot: Path): WorkflowGitOperationResult {
        val result = realGit.worktreeStatus(repoRoot)
        val afterStatus = afterWorktreeStatus
        afterWorktreeStatus = null
        afterStatus?.invoke()
        return result
      }

      override fun pushBranch(
        repoRoot: Path,
        branch: String,
      ): WorkflowGitOperationResult {
        pushes += branch
        return WorkflowGitOperationResult.Ok()
      }
    }
  private val database =
    sqliteSessionFactoryForTests(
      userHome = root,
      dbPathOverride = root.resolve("metrics.db").toString(),
      environment = emptyMap(),
    )
  private val diffComments =
    DiffCommentService(
      database = database,
      git = git,
      repositories = TestRepositoryRoot,
      worktreeFiles =
        object : DiffCommentWorktreeFiles {
          override fun lineText(
            worktreeRoot: Path,
            path: String,
            line: Int,
          ): DiffCommentLineObservation = DiffCommentLineObservation.Missing
        },
      clock = Clock.systemUTC(),
      ids = IdentifierGeneratorPort { UUID.randomUUID().toString() },
      diagnostics = diagnostics,
    )
  private val proposals = SqliteOperationProposalRepository(database)
  private val executor =
    OperationExecutor(
      OperationRegistry(
        listOf(
          PrReviewFixOperation(github, diffComments, git, diagnostics, runPhase = { request ->
            validations += request
            if (validationBlocks) {
              PhaseRunResult.Blocked("phr-validation", emptyList(), null, "validation", "check failed")
            } else {
              PhaseRunResult.Completed("phr-validation", listOf("validation"), null, "Validation verdict: pass")
            }
          }),
        ),
      ),
      OperationConfirmationGate(proposals, UnavailableOperationCardRepository, git, Clock.systemUTC()),
      OperationStepRunner(runner, git),
      FixedProjectStorePort(root.resolve("project-store")),
      RecordingBranchWorktreeResources(),
      NoOperationCardLifecycle,
    )

  init {
    Files.createDirectories(repo)
    git("init", "--quiet", "--initial-branch=$PR_BRANCH")
    git("config", "user.email", "review@example.com")
    git("config", "user.name", "Review Test")
    git("config", "commit.gpgsign", "false")
    repo.resolve("README.md").writeText("review fixture\n")
    git("add", "--all")
    git("commit", "--quiet", "-m", "initial")
  }

  fun invoke(
    arguments: OperationArguments = OperationArguments(),
    instructions: String? = null,
  ): OperationOutcome =
    executor.execute(
      OperationRequest("pr-review-fix", repo, "codex", arguments.copy(mode = arguments.mode ?: "remote"), instructions),
    ).outcome

  fun invokeLocal(
    arguments: OperationArguments = OperationArguments(),
    instructions: String? = null,
  ): OperationOutcome =
    executor.execute(OperationRequest("pr-review-fix", repo, "codex", arguments, instructions)).outcome

  fun proposal(token: String): OperationProposal? = proposals.find(token)

  fun addLocalThread(): String =
    when (
      val result =
        diffComments.addAgentThread(
          repo,
          DiffCommentAgentThreadRequest(
            path = null,
            side = null,
            line = null,
            body = "Please address this local comment.",
            sourceRef = "test-local-thread",
          ),
        )
    ) {
      is DiffCommentThreadResult.Saved -> result.thread.id
      else -> error("Unable to create fixture local comment: $result")
    }

  fun localThreadState(threadId: String): DiffCommentThreadState? =
    when (val result = diffComments.list(repo)) {
      is DiffCommentListResult.Listed -> result.threads.firstOrNull { it.id == threadId }?.state
      is DiffCommentListResult.Unavailable -> error("Unable to list fixture local comments: ${result.reason}")
    }

  fun localThreadReplyBody(threadId: String): String? =
    when (val result = diffComments.list(repo)) {
      is DiffCommentListResult.Listed ->
        result.threads.firstOrNull { it.id == threadId }?.comments?.lastOrNull()?.body
      is DiffCommentListResult.Unavailable -> error("Unable to list fixture local comments: ${result.reason}")
    }

  fun closeLocalThread(threadId: String) {
    check(diffComments.setState(threadId, DiffCommentThreadState.DECLINED) is DiffCommentThreadResult.Saved)
  }

  fun status(): String = git("status", "--porcelain")

  fun latestCommitMessage(): String = git("log", "-1", "--format=%s")

  fun checkoutNewBranch(branch: String) {
    git("checkout", "--quiet", "-b", branch)
  }

  fun proposalRowCount(): Int =
    DriverManager.getConnection("jdbc:sqlite:${root.resolve("metrics.db")}").use { connection ->
      val exists =
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { query ->
          query.setString(1, "operation_proposals")
          query.executeQuery().use { rows -> rows.next() }
        }
      if (!exists) return@use 0
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM operation_proposals").use { rows ->
          rows.next()
          rows.getInt(1)
        }
      }
    }

  override fun close() {
    root.toFile().deleteRecursively()
  }

  private fun git(vararg arguments: String): String {
    val process = ProcessBuilder("git", *arguments).directory(repo.toFile()).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes().decodeToString()
    check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
    return output.trim()
  }

  companion object {
    const val PR_BRANCH = "feat/review"
  }
}

private object TestRepositoryRoot : RepositoryEnclosingRootPort {
  override fun enclosingRepositoryRoot(start: Path): Path = start

  override fun canonicalPath(path: Path): Path = path.toAbsolutePath().normalize()

  override fun optionalRealPath(path: Path): Path? = path.toRealPath()

  override fun repositoryIdentity(repoRoot: Path): String = repoRoot.toRealPath().toString()

  override fun primaryRepositoryIdentity(repoRoot: Path): String = repositoryIdentity(repoRoot)

  override fun primaryRepositoryRoot(repoRoot: Path): Path = repoRoot.toRealPath()
}

internal class FakeReviewThreads : PullRequestReviewThreadOperations {
  var headOid: String = "head-1"
  var threads: List<ReviewThread> =
    listOf(
      thread("PRRT_resolved", "a.kt", 1, resolved = true),
      thread("PRRT_b", "b.kt", 4),
      thread("PRRT_a", "a.kt", 9),
      thread("PRRT_outdated", "c.kt", 2, outdated = true),
    )
  val replies = mutableListOf<Pair<String, String>>()

  override fun resolvePullRequest(
    repoRoot: Path,
    reference: String?,
  ): ReviewPullRequestResolution =
    ReviewPullRequestResolution.Found(
      ReviewPullRequest(
        42,
        "https://github.com/acme/repo/pull/42",
        "acme",
        "repo",
        PrReviewFixHarness.PR_BRANCH,
        "main",
        headOid,
      ),
    )

  override fun reviewThreads(
    repoRoot: Path,
    pullRequest: ReviewPullRequest,
  ): ReviewThreadListing = ReviewThreadListing.Ok(threads)

  override fun replyToThread(
    repoRoot: Path,
    threadId: String,
    body: String,
  ): ReviewThreadReplyResult {
    replies += threadId to body
    return ReviewThreadReplyResult.Posted("https://github.com/acme/repo/pull/42#reply-$threadId")
  }

  companion object {
    fun thread(
      id: String,
      path: String,
      line: Int,
      resolved: Boolean = false,
      outdated: Boolean = false,
    ): ReviewThread =
      ReviewThread(
        id = id,
        isResolved = resolved,
        isOutdated = outdated,
        path = path,
        line = line,
        originalLine = line,
        comments =
          listOf(
            ReviewThreadComment("C-$id", "reviewer", "Please fix $path.", "https://example.com/$id", "2026-09-01"),
          ),
      )
  }
}

internal class ScriptedPrReviewFixRunner(
  private val repo: Path,
) : PhaseRunner {
  val inputs = mutableListOf<PhaseStepInput>()
  var matrix: String =
    "Thread T1 — a.kt:9\nVerdict: agree\nOptions:\n1. (recommended) Rename as asked.\n2. Keep the name.\n" +
      "Thread T2 — b.kt:4\nVerdict: agree\nOptions:\n1. (recommended) Rename as asked.\n2. Keep the name.\n"
  var localReply: String = "outcome: fixed\nAddressed the local comment.\n"
  var editDuringAnalysis: Boolean = false
  var editSharedFile: Boolean = false

  override fun run(
    input: PhaseStepInput,
    state: PhaseLaunchState,
  ): PhaseStepOutput {
    inputs += input
    if (input.stepName != THREAD_STEP) {
      if (editDuringAnalysis) repo.resolve("analysis-edit.txt").writeText("edited")
      return reviewStepOutput(matrix).copy(fileManifest = PhaseStepFileManifest(emptyList(), emptyList()))
    }
    val ordinal = Regex("""thread (T\d+)""").find(input.directive)?.groupValues?.get(1) ?: "T?"
    val path = "fix-$ordinal.txt"
    repo.resolve(path).writeText(ordinal)
    val manifest =
      if (editSharedFile) {
        val shared = repo.resolve(SHARED_FILE)
        val before = listOf(SHARED_FILE).filter { shared.exists() }
        if (shared.exists()) shared.appendText("$ordinal\n") else shared.writeText("$ordinal\n")
        PhaseStepFileManifest(before, listOf(path, SHARED_FILE))
      } else {
        PhaseStepFileManifest(emptyList(), listOf(path))
      }
    val reply =
      if ("# PR review fix: local thread" in input.directive) {
        localReply
      } else {
        "Renamed as asked in $ordinal.\n"
      }
    return reviewStepOutput(reply).copy(fileManifest = manifest)
  }

  companion object {
    const val SHARED_FILE = "shared.txt"
  }
}
