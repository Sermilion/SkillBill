package skillbill.engine.featuretask.slot

import skillbill.application.review.diffcomment.DiffCommentService
import skillbill.application.review.diffcomment.model.DiffCommentListResult
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.review.diffcomment.DiffCommentWorktreeFiles
import skillbill.ports.review.diffcomment.model.DiffCommentLineObservation
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.diffcomment.model.DiffCommentThread
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertIs

class DiffCommentTestStore(
  currentBranch: () -> String = { BRANCH },
) {
  val home: Path = Files.createTempDirectory("skillbill-diff-comment-store")
  private var nextId = 0
  private val database = sqliteDatabaseSessionFactory(userHome = home, environment = mapOf("SKILL_BILL_TEST" to "1"))

  val service: DiffCommentService =
    DiffCommentService(
      database = database,
      git = BranchGit(currentBranch),
      repositories = SingleRepository,
      worktreeFiles =
        object : DiffCommentWorktreeFiles {
          override fun lineText(
            worktreeRoot: Path,
            path: String,
            line: Int,
          ) = DiffCommentLineObservation.Text("line $line")
        },
      clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
      ids = IdentifierGeneratorPort { "comment-${nextId++}" },
      diagnostics = RecordingDiagnostics(),
    )

  fun threads(repoRoot: Path): List<DiffCommentThread> =
    assertIs<DiffCommentListResult.Listed>(service.list(repoRoot, BRANCH)).threads

  fun threadBySourceRef(
    repoRoot: Path,
    sourceRef: String,
  ): DiffCommentThread = threads(repoRoot).single { it.sourceRef == sourceRef }

  fun close() {
    home.toFile().deleteRecursively()
  }

  private class BranchGit(
    private val currentBranch: () -> String,
  ) : WorkflowGitOperations by NoopWorkflowGitOperations {
    override fun currentBranch(repoRoot: Path) = WorkflowGitOperationResult.Ok(currentBranch())

    override fun headCommitSha(repoRoot: Path) = WorkflowGitOperationResult.Ok(TIP)

    override fun resolveCommit(
      repoRoot: Path,
      revision: String,
    ) = WorkflowGitOperationResult.Ok(TIP)
  }

  private object SingleRepository : RepositoryEnclosingRootPort {
    override fun enclosingRepositoryRoot(start: Path) = start

    override fun canonicalPath(path: Path) = path

    override fun optionalRealPath(path: Path) = path

    override fun repositoryIdentity(repoRoot: Path) = REPOSITORY

    override fun primaryRepositoryIdentity(repoRoot: Path) = REPOSITORY

    override fun primaryRepositoryRoot(repoRoot: Path) = repoRoot
  }

  class RecordingDiagnostics : RuntimeDiagnostics {
    val warnings = mutableListOf<String>()

    override fun info(message: String) = Unit

    override fun warning(
      message: String,
      error: Throwable?,
    ) {
      warnings += message
    }

    override fun error(
      message: String,
      error: Throwable?,
    ) {
      warnings += message
    }
  }

  companion object {
    const val BRANCH = "main"
    private const val TIP = "tip"
    private const val REPOSITORY = "repository"
  }
}
