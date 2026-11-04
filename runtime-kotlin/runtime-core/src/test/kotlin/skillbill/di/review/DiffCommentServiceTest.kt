package skillbill.di.review

import skillbill.application.review.diffcomment.DiffCommentService
import skillbill.application.review.diffcomment.model.DiffCommentAgentThreadRequest
import skillbill.application.review.diffcomment.model.DiffCommentListResult
import skillbill.application.review.diffcomment.model.DiffCommentPendingThreadRequest
import skillbill.application.review.diffcomment.model.DiffCommentRejection
import skillbill.application.review.diffcomment.model.DiffCommentThreadResult
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.review.diffcomment.DiffCommentWorktreeFiles
import skillbill.ports.review.diffcomment.model.DiffCommentLineObservation
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.diffcomment.model.DiffComment
import skillbill.review.diffcomment.model.DiffCommentOutcome
import skillbill.review.diffcomment.model.DiffCommentSide
import skillbill.review.diffcomment.model.DiffCommentThreadState
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewSeverity
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiffCommentServiceTest {
  @Test
  fun `review findings become anchored threads and reuse their source refs on retry`() {
    val fixture = Fixture()
    val findings =
      listOf(
        reviewFinding("F-001", "src/A.kt", 4),
        reviewFinding("F-002", "src/B.kt", null),
        reviewFinding("F-003", null, null),
      )

    val first = fixture.service.recordReviewFindings(fixture.root, "review:wf:pass-1", "agent", findings)
    val replay = fixture.service.recordReviewFindings(fixture.root, "review:wf:pass-1", "agent", findings)
    val threads = fixture.list().threads.sortedBy { it.findingRef }

    assertEquals(3, first.written)
    assertEquals(0, first.reused)
    assertEquals(0, first.failed)
    assertEquals(0, replay.written)
    assertEquals(3, replay.reused)
    assertEquals(listOf(4, null, null), threads.map { it.line })
    assertEquals(listOf("src/A.kt", "src/B.kt", null), threads.map { it.path })
    assertTrue(threads.last().comments.single().body.startsWith("module.kt:9: "))
  }

  @Test
  fun `oversized review refutations remain valid Unicode and decline the thread on retry`() {
    val fixture = Fixture()
    fixture.service.addAgentThread(fixture.root, fixture.request())
    val body = "Refuted: " + "🙂".repeat(5000)

    val first =
      assertIs<DiffCommentThreadResult.Saved>(
        fixture.service.replyReviewOutcome(fixture.root, "finding", body, DiffCommentOutcome.REFUTED),
      )
    val replay =
      assertIs<DiffCommentThreadResult.Saved>(
        fixture.service.replyReviewOutcome(fixture.root, "finding", body, DiffCommentOutcome.REFUTED),
      )
    val stored = fixture.list().threads.single()
    val savedBody = stored.comments.last().body

    assertTrue(first.changed)
    assertFalse(replay.changed)
    assertEquals(DiffCommentThreadState.DECLINED, stored.state)
    assertEquals(2, stored.comments.size)
    assertTrue(savedBody.toByteArray(Charsets.UTF_8).size <= DiffComment.MAX_BODY_BYTES)
    assertTrue(savedBody.startsWith("Refuted: "))
    assertTrue(savedBody.endsWith("🙂…"))
  }

  @Test
  fun `detached HEAD refuses pending comments and listing without writing a row`() {
    val fixture = Fixture()
    fixture.git.branch = ""

    val added =
      assertIs<DiffCommentThreadResult.Unavailable>(
        fixture.service.addPending(
          fixture.root,
          DiffCommentPendingThreadRequest("file.kt", DiffCommentSide.NEW, 1, "review"),
        ),
      )
    val listed = assertIs<DiffCommentListResult.Unavailable>(fixture.service.list(fixture.root))

    assertTrue(added.reason.contains("detached HEAD"))
    assertTrue(listed.reason.contains("detached HEAD"))
    assertTrue(fixture.storedThreads("").isEmpty())
    assertTrue(fixture.storedThreads("main").isEmpty())
  }

  @Test
  fun `listing another branch does not use the current worktree to mark a file missing`() {
    val fixture = Fixture()
    fixture.service.addAgentThread(fixture.root, fixture.request(line = null))
    fixture.git.branch = "other"
    fixture.observation = DiffCommentLineObservation.Missing

    assertFalse(fixture.list().threads.single().outdated)
  }

  @Test
  fun `a missing line anchor cannot match an empty line that later appears`() {
    val fixture = Fixture()
    fixture.observation = DiffCommentLineObservation.Missing
    fixture.service.addPending(
      fixture.root,
      DiffCommentPendingThreadRequest("file.kt", DiffCommentSide.NEW, 1, "review"),
    )
    fixture.observation = DiffCommentLineObservation.Text("")

    assertTrue(fixture.list().threads.single().outdated)
  }

  @Test
  fun `an old-side comment keeps a deleted line even when it is absent from the worktree`() {
    val fixture = Fixture()
    fixture.observation = DiffCommentLineObservation.Missing
    val saved =
      assertIs<DiffCommentThreadResult.Saved>(
        fixture.service.addAgentThread(fixture.root, fixture.request().copy(side = DiffCommentSide.OLD)),
      )

    assertEquals(1, saved.thread.line)
    assertEquals(DiffCommentSide.OLD, saved.thread.side)
    fixture.git.tip = "new-tip"
    assertTrue(fixture.list().threads.single().outdated)
  }

  @Test
  fun `an invalid severity is rejected without persisting a thread`() {
    val fixture = Fixture()
    val rejected =
      assertIs<DiffCommentThreadResult.Rejected>(
        fixture.service.addAgentThread(fixture.root, fixture.request().copy(severity = "unknown")),
      )

    assertEquals(DiffCommentRejection.INVALID_SEVERITY, rejected.rejection)
    assertTrue(fixture.list().threads.isEmpty())
  }

  @Test
  fun `interleaved source reporters leave only one durable reply and the resolved state`() {
    val fixture = Fixture()
    fixture.service.addAgentThread(fixture.root, fixture.request())
    fixture.beforeNextTransaction = {
      fixture.service.replyToSource(fixture.root, "finding", "fixed", DiffCommentOutcome.FIXED)
    }
    val first =
      assertIs<DiffCommentThreadResult.Saved>(
        fixture.service.replyToSource(fixture.root, "finding", "fixed", DiffCommentOutcome.FIXED),
      )
    val replay =
      assertIs<DiffCommentThreadResult.Saved>(
        fixture.service.replyToSource(fixture.root, "finding", "fixed", DiffCommentOutcome.FIXED),
      )

    assertFalse(first.changed)
    assertFalse(replay.changed)
    val stored = fixture.list().threads.single()
    assertEquals(listOf("review", "fixed"), stored.comments.map { it.body })
    assertEquals(DiffCommentThreadState.RESOLVED, stored.state)
  }

  private class Fixture {
    val root: Path = Files.createTempDirectory("diff-comment-service")
    val git = Git()
    var observation: DiffCommentLineObservation = DiffCommentLineObservation.Text("line")
    var beforeNextTransaction: (() -> Unit)? = null
    private var nextId = 0
    private val delegate =
      sqliteDatabaseSessionFactory(
        userHome = root,
        environment = mapOf("SKILL_BILL_TEST" to "1"),
      )
    private val database =
      object : DatabaseSessionFactory by delegate {
        override fun <T> transaction(block: (UnitOfWork) -> T): T {
          val interleave = beforeNextTransaction
          beforeNextTransaction = null
          interleave?.invoke()
          return delegate.transaction(block)
        }
      }
    val service =
      DiffCommentService(
        database = database,
        git = git,
        repositories = Repositories,
        worktreeFiles =
          object : DiffCommentWorktreeFiles {
            override fun lineText(
              worktreeRoot: Path,
              path: String,
              line: Int,
            ) = observation
          },
        clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        ids = IdentifierGeneratorPort { "comment-${nextId++}" },
        diagnostics = Diagnostics,
      )

    fun request(line: Int? = 1) =
      DiffCommentAgentThreadRequest(
        path = "file.kt",
        side = DiffCommentSide.NEW,
        line = line,
        body = "review",
        sourceRef = "finding",
      )

    fun list(): DiffCommentListResult.Listed = assertIs(service.list(root, "main"))

    fun storedThreads(branch: String) = delegate.read { it.diffComments.listThreads("repository", branch) }
  }

  private fun reviewFinding(
    id: String,
    path: String?,
    line: Int?,
  ) = ParallelReviewMergedFinding(
    fNumber = id,
    agentIds = listOf("reviewer"),
    severity = ParallelReviewSeverity.MAJOR,
    confidence = "high",
    location = if (path == null) "module.kt:9" else "$path${line?.let { ":$it" }.orEmpty()}",
    description = "Description for $id",
    repositoryPath = path,
    line = line,
  )

  private class Git : WorkflowGitOperations by NoopWorkflowGitOperations {
    var branch = "main"
    var tip = "tip"

    override fun currentBranch(repoRoot: Path) = WorkflowGitOperationResult.Ok(branch)

    override fun headCommitSha(repoRoot: Path) = WorkflowGitOperationResult.Ok(tip)

    override fun resolveCommit(
      repoRoot: Path,
      revision: String,
    ) = WorkflowGitOperationResult.Ok(tip)
  }

  private object Repositories : RepositoryEnclosingRootPort {
    override fun enclosingRepositoryRoot(start: Path) = start

    override fun canonicalPath(path: Path) = path

    override fun optionalRealPath(path: Path) = path

    override fun repositoryIdentity(repoRoot: Path) = "repository"

    override fun primaryRepositoryIdentity(repoRoot: Path) = "repository"

    override fun primaryRepositoryRoot(repoRoot: Path) = repoRoot
  }

  private object Diagnostics : RuntimeDiagnostics {
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
}
