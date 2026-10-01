package skillbill.engine.operation.prreviewfix

import skillbill.engine.featuretask.phaserun.PhaseRunRequest
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.slot.PhaseRunner
import skillbill.engine.featuretask.slot.PhaseStepFileManifest
import skillbill.engine.featuretask.slot.PhaseStepInput
import skillbill.engine.featuretask.slot.PhaseStepOutput
import skillbill.engine.featuretask.slot.reviewStepOutput
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
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
import skillbill.ports.operation.model.OperationProposal
import skillbill.ports.review.pullrequest.PullRequestReviewThreadOperations
import skillbill.ports.review.pullrequest.model.ReviewPullRequest
import skillbill.ports.review.pullrequest.model.ReviewPullRequestResolution
import skillbill.ports.review.pullrequest.model.ReviewThread
import skillbill.ports.review.pullrequest.model.ReviewThreadComment
import skillbill.ports.review.pullrequest.model.ReviewThreadListing
import skillbill.ports.review.pullrequest.model.ReviewThreadReplyResult
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
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
  var validationBlocks: Boolean = false
  private val realGit = GitWorkflowGitOperations()
  private val git =
    object : WorkflowGitOperations by realGit {
      override fun pushBranch(
        repoRoot: Path,
        branch: String,
      ): WorkflowGitOperationResult {
        pushes += branch
        return WorkflowGitOperationResult.Ok()
      }
    }
  private val proposals =
    SqliteOperationProposalRepository(
      sqliteSessionFactoryForTests(
        userHome = root,
        dbPathOverride = root.resolve("metrics.db").toString(),
        environment = emptyMap(),
      ),
    )
  private val executor =
    OperationExecutor(
      OperationRegistry(
        listOf(
          PrReviewFixOperation(github, git) { request ->
            validations += request
            if (validationBlocks) {
              PhaseRunResult.Blocked("phr-validation", emptyList(), null, "validation", "check failed")
            } else {
              PhaseRunResult.Completed("phr-validation", listOf("validation"), null, "Validation verdict: pass")
            }
          },
        ),
      ),
      OperationConfirmationGate(proposals, git, Clock.systemUTC()),
      OperationStepRunner(runner, git),
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
    executor.execute(OperationRequest("pr-review-fix", repo, "codex", arguments, instructions)).outcome

  fun proposal(token: String): OperationProposal? = proposals.find(token)

  fun status(): String = git("status", "--porcelain")

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
  var matrix: String = "Thread T1 — a.kt:9\nVerdict: agree\n"
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
    return reviewStepOutput("Renamed as asked in $ordinal.\n").copy(fileManifest = manifest)
  }

  companion object {
    const val SHARED_FILE = "shared.txt"
  }
}
