package skillbill.application.review.diffcomment

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.diffcomment.model.DiffCommentAgentThreadRequest
import skillbill.application.review.diffcomment.model.DiffCommentCountResult
import skillbill.application.review.diffcomment.model.DiffCommentDeleteResult
import skillbill.application.review.diffcomment.model.DiffCommentListResult
import skillbill.application.review.diffcomment.model.DiffCommentPendingThreadRequest
import skillbill.application.review.diffcomment.model.DiffCommentRejection
import skillbill.application.review.diffcomment.model.DiffCommentThreadResult
import skillbill.application.review.diffcomment.model.ReviewFindingThreadCounts
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.review.diffcomment.DiffCommentStore
import skillbill.ports.review.diffcomment.DiffCommentWorktreeFiles
import skillbill.ports.review.diffcomment.model.DiffCommentLineObservation
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.diffcomment.model.DiffComment
import skillbill.review.diffcomment.model.DiffCommentAuthor
import skillbill.review.diffcomment.model.DiffCommentOutcome
import skillbill.review.diffcomment.model.DiffCommentSide
import skillbill.review.diffcomment.model.DiffCommentThread
import skillbill.review.diffcomment.model.DiffCommentThreadState
import skillbill.review.model.ParallelReviewMergedFinding
import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

@Inject
class DiffCommentService(
  private val database: DatabaseSessionFactory,
  private val git: WorkflowGitOperations,
  private val repositories: RepositoryEnclosingRootPort,
  private val worktreeFiles: DiffCommentWorktreeFiles,
  private val clock: Clock,
  private val ids: IdentifierGeneratorPort,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun recordReviewFindings(
    repoRoot: Path,
    sourceRefPrefix: String,
    agentId: String?,
    findings: List<ParallelReviewMergedFinding>,
  ): ReviewFindingThreadCounts {
    var written = 0
    var reused = 0
    var failed = 0
    var firstFailureReason: String? = null
    findings.forEach { finding ->
      catchingNonCancellation {
        val result = addAgentThread(repoRoot, reviewThreadRequest(sourceRefPrefix, agentId, finding))
        when (result) {
          is DiffCommentThreadResult.Saved -> if (result.changed) written++ else reused++
          is DiffCommentThreadResult.Rejected -> {
            failed++
            if (firstFailureReason == null) firstFailureReason = result.rejection.name
          }
          is DiffCommentThreadResult.Unavailable -> {
            failed++
            if (firstFailureReason == null) firstFailureReason = result.reason
          }
          DiffCommentThreadResult.NotFound -> {
            failed++
            if (firstFailureReason == null) firstFailureReason = "thread not found"
          }
        }
      }.onFailure { error ->
        failed++
        if (firstFailureReason == null) firstFailureReason = error.message ?: error.javaClass.simpleName
      }
    }
    return ReviewFindingThreadCounts(written, reused, failed, firstFailureReason)
  }

  private fun reviewThreadRequest(
    sourceRefPrefix: String,
    agentId: String?,
    finding: ParallelReviewMergedFinding,
  ): DiffCommentAgentThreadRequest {
    val path = finding.repositoryPath?.let(::relativeReviewPath)
    val locationPrefix = if (path == null && finding.location.isNotBlank()) "${finding.location}: " else ""
    return DiffCommentAgentThreadRequest(
      path = path,
      side = if (path != null && finding.line != null) DiffCommentSide.NEW else null,
      line = if (path != null) finding.line else null,
      body = reviewCommentBody(locationPrefix + finding.description),
      agentId = agentId,
      sourceRef = "$sourceRefPrefix:${finding.fNumber}",
      sourceLabel = "phase:review",
      severity = finding.severity.displayName.lowercase(),
      findingRef = finding.fNumber,
    )
  }

  fun replyReviewOutcome(
    repoRoot: Path,
    sourceRef: String,
    body: String,
    outcome: DiffCommentOutcome,
    agentId: String? = null,
  ): DiffCommentThreadResult = replyToSource(repoRoot, sourceRef, reviewCommentBody(body), outcome, agentId)

  private fun reviewCommentBody(value: String): String {
    val limit = DiffComment.MAX_BODY_BYTES
    if (value.toByteArray(Charsets.UTF_8).size <= limit) return value
    val suffix = "…"
    val budget = limit - suffix.toByteArray(Charsets.UTF_8).size
    val result = StringBuilder()
    var bytes = 0
    var truncated = false
    value.codePoints().forEach { point ->
      val chunk = String(Character.toChars(point))
      val size = chunk.toByteArray(Charsets.UTF_8).size
      if (!truncated && bytes + size <= budget) {
        result.append(chunk)
        bytes += size
      } else {
        truncated = true
      }
    }
    return result.append(suffix).toString()
  }

  private fun relativeReviewPath(candidate: String): String? =
    try {
      val path = Path.of(candidate)
      candidate.takeIf { it.isNotBlank() && !path.isAbsolute && path.none { part -> part.toString() == ".." } }
    } catch (_: InvalidPathException) {
      null
    }

  fun list(
    repoRoot: Path,
    branch: String? = null,
  ): DiffCommentListResult {
    val anchor =
      when (val result = anchor(repoRoot, branch)) {
        is AnchorResolution.Available -> result.anchor
        is AnchorResolution.Unavailable -> return DiffCommentListResult.Unavailable(result.reason)
      }
    val threads =
      database.read { it.diffComments.listThreads(anchor.identity, anchor.branch) }
        .map { thread -> thread.copy(outdated = outdated(repoRoot, anchor, thread)) }
        .sortedWith(compareBy<DiffCommentThread>({ it.path != null }, { it.path }, { it.line }, { it.createdAt }))
    return DiffCommentListResult.Listed(anchor.identity, anchor.branch, threads)
  }

  fun addPending(
    repoRoot: Path,
    request: DiffCommentPendingThreadRequest,
  ): DiffCommentThreadResult {
    val path = request.path
    val side = request.side
    val line = request.line
    val body = request.body
    validateThreadInput(path, line, body)?.let { return DiffCommentThreadResult.Rejected(it) }
    val anchor =
      when (val result = pendingAnchor(repoRoot, request.expectedBranch)) {
        is AnchorResolution.Available -> result.anchor
        is AnchorResolution.Unavailable -> return DiffCommentThreadResult.Unavailable(result.reason)
      }
    val lineText =
      if (line != null && side == DiffCommentSide.NEW) {
        when (val observation = worktreeFiles.lineText(repoRoot, path, line)) {
          is DiffCommentLineObservation.Text -> observation.value
          DiffCommentLineObservation.Missing -> null
          DiffCommentLineObservation.Rejected ->
            return DiffCommentThreadResult.Rejected(DiffCommentRejection.INVALID_PATH)
        }
      } else {
        null
      }
    val at = clock.instant()
    val threadId = ids.randomToken()
    val thread =
      DiffCommentThread(
        id = threadId,
        repositoryIdentity = anchor.identity,
        headBranch = anchor.branch,
        path = path,
        side = line?.let { side },
        line = line,
        anchorCommitSha = anchor.tip,
        anchorLineText = lineText,
        state = DiffCommentThreadState.PENDING,
        sourceRef = null,
        sourceLabel = null,
        severity = null,
        findingRef = null,
        createdAt = at,
        updatedAt = at,
        comments =
          listOf(
            DiffComment(
              id = ids.randomToken(), threadId = threadId, position = 1, author = DiffCommentAuthor.USER,
              agentId = null, body = body, outcome = null, createdAt = at, editedAt = null,
            ),
          ),
        outdated = false,
      )
    return DiffCommentThreadResult.Saved(database.transaction { it.diffComments.insertThread(thread) }, true)
  }

  fun addAgentThread(
    repoRoot: Path,
    request: DiffCommentAgentThreadRequest,
  ): DiffCommentThreadResult {
    validateThreadInput(request.path, request.line, request.body)?.let {
      return DiffCommentThreadResult.Rejected(it)
    }
    val anchor =
      when (val result = anchor(repoRoot, null)) {
        is AnchorResolution.Available -> result.anchor
        is AnchorResolution.Unavailable -> return DiffCommentThreadResult.Unavailable(result.reason)
      }
    if (!DiffCommentThread.acceptsSeverity(request.severity)) {
      return DiffCommentThreadResult.Rejected(DiffCommentRejection.INVALID_SEVERITY)
    }
    return database.transaction { unit -> insertAgentThread(unit.diffComments, repoRoot, anchor, request) }
  }

  private fun insertAgentThread(
    store: DiffCommentStore,
    repoRoot: Path,
    anchor: Anchor,
    request: DiffCommentAgentThreadRequest,
  ): DiffCommentThreadResult {
    val sourceRef = request.sourceRef
    val path = request.path
    val line = request.line
    val side = request.side
    if (sourceRef != null) {
      val existing = store.findThreadBySourceRef(anchor.identity, anchor.branch, sourceRef)
      if (existing != null) return DiffCommentThreadResult.Saved(existing, false)
    }
    var actualLine = line
    var actualSide = if (actualLine == null) null else side ?: DiffCommentSide.NEW
    var anchorText: String? = null
    if (actualLine != null && actualSide == DiffCommentSide.NEW) {
      when (val observation = worktreeFiles.lineText(repoRoot, checkNotNull(path), actualLine)) {
        is DiffCommentLineObservation.Text -> anchorText = observation.value
        DiffCommentLineObservation.Missing -> {
          diagnostics.warning(
            "seam=diff_comment_line_anchor expected=line value_used=file cause=line_missing",
          )
          actualLine = null
          actualSide = null
        }
        DiffCommentLineObservation.Rejected ->
          return DiffCommentThreadResult.Rejected(DiffCommentRejection.INVALID_PATH)
      }
    }
    val thread = agentThread(anchor, request, actualSide, actualLine, anchorText)
    return DiffCommentThreadResult.Saved(store.insertThread(thread), true)
  }

  private fun agentThread(
    anchor: Anchor,
    request: DiffCommentAgentThreadRequest,
    side: DiffCommentSide?,
    line: Int?,
    anchorText: String?,
  ): DiffCommentThread {
    val at = clock.instant()
    val threadId = ids.randomToken()
    return DiffCommentThread(
      id = threadId,
      repositoryIdentity = anchor.identity,
      headBranch = anchor.branch,
      path = request.path,
      side = side,
      line = line,
      anchorCommitSha = anchor.tip,
      anchorLineText = anchorText,
      state = DiffCommentThreadState.OPEN,
      sourceRef = request.sourceRef,
      sourceLabel = request.sourceLabel,
      severity = request.severity,
      findingRef = request.findingRef,
      createdAt = at,
      updatedAt = at,
      comments =
        listOf(
          DiffComment(
            id = ids.randomToken(), threadId = threadId, position = 1, author = DiffCommentAuthor.AGENT,
            agentId = request.agentId, body = request.body, outcome = null, createdAt = at, editedAt = null,
          ),
        ),
      outdated = false,
    )
  }

  fun editComment(
    commentId: String,
    body: String,
  ): DiffCommentThreadResult {
    validateBody(body)?.let { return DiffCommentThreadResult.Rejected(it) }
    return database.transaction { unit ->
      val thread =
        unit.diffComments.findThreadByCommentId(commentId)
          ?: return@transaction DiffCommentThreadResult.NotFound
      val comment = thread.comments.firstOrNull { it.id == commentId }
      if (thread.state != DiffCommentThreadState.PENDING || comment?.author != DiffCommentAuthor.USER) {
        return@transaction DiffCommentThreadResult.Rejected(DiffCommentRejection.NOT_EDITABLE)
      }
      unit.diffComments.updateCommentBody(commentId, body, clock.instant())
      DiffCommentThreadResult.Saved(unit.diffComments.findThread(thread.id)!!, true)
    }
  }

  fun deleteComment(commentId: String): DiffCommentDeleteResult =
    database.transaction { unit ->
      val thread =
        unit.diffComments.findThreadByCommentId(commentId)
          ?: return@transaction DiffCommentDeleteResult.NotFound
      val first = thread.comments.minByOrNull { it.position }?.id == commentId
      if (first) unit.diffComments.deleteThread(thread.id) else unit.diffComments.deleteComment(commentId)
      DiffCommentDeleteResult.Deleted(first)
    }

  fun deleteThread(threadId: String): DiffCommentDeleteResult =
    database.transaction {
      if (it.diffComments.deleteThread(threadId)) {
        DiffCommentDeleteResult.Deleted(true)
      } else {
        DiffCommentDeleteResult.NotFound
      }
    }

  fun submitReview(
    repoRoot: Path,
    branch: String? = null,
  ): DiffCommentCountResult {
    val anchor =
      when (val result = anchor(repoRoot, branch)) {
        is AnchorResolution.Available -> result.anchor
        is AnchorResolution.Unavailable -> return DiffCommentCountResult.Unavailable(result.reason)
      }
    val count =
      database.transaction {
        it.diffComments.openPendingThreads(anchor.identity, anchor.branch, clock.instant())
      }
    return DiffCommentCountResult.Counted(count)
  }

  fun discardPending(
    repoRoot: Path,
    branch: String? = null,
  ): DiffCommentCountResult {
    val anchor =
      when (val result = anchor(repoRoot, branch)) {
        is AnchorResolution.Available -> result.anchor
        is AnchorResolution.Unavailable -> return DiffCommentCountResult.Unavailable(result.reason)
      }
    val count = database.transaction { it.diffComments.deletePendingThreads(anchor.identity, anchor.branch) }
    return DiffCommentCountResult.Counted(count)
  }

  fun reply(
    threadId: String,
    author: DiffCommentAuthor,
    agentId: String? = null,
    body: String,
    outcome: DiffCommentOutcome? = null,
  ): DiffCommentThreadResult {
    validateBody(body)?.let { return DiffCommentThreadResult.Rejected(it) }
    if (author == DiffCommentAuthor.USER && outcome != null) {
      return DiffCommentThreadResult.Rejected(DiffCommentRejection.OUTCOME_NOT_ALLOWED)
    }
    return database.transaction { unit ->
      val thread = unit.diffComments.findThread(threadId) ?: return@transaction DiffCommentThreadResult.NotFound
      appendReply(unit.diffComments, thread, replyComment(thread, author, agentId, body, outcome))
    }
  }

  private fun appendReply(
    store: DiffCommentStore,
    thread: DiffCommentThread,
    comment: DiffComment,
  ): DiffCommentThreadResult {
    if (thread.state == DiffCommentThreadState.PENDING) {
      return DiffCommentThreadResult.Rejected(DiffCommentRejection.PENDING_THREAD)
    }
    val author = comment.author
    val outcome = comment.outcome
    val state =
      when {
        author == DiffCommentAuthor.USER && thread.state == DiffCommentThreadState.RESOLVED ->
          DiffCommentThreadState.OPEN
        author == DiffCommentAuthor.AGENT && outcome == DiffCommentOutcome.FIXED ->
          DiffCommentThreadState.RESOLVED
        author == DiffCommentAuthor.AGENT && outcome == DiffCommentOutcome.REFUTED ->
          DiffCommentThreadState.DECLINED
        else -> thread.state
      }
    store.insertComment(comment)
    store.updateThreadState(thread.id, state, comment.createdAt)
    return DiffCommentThreadResult.Saved(checkNotNull(store.findThread(thread.id)), true)
  }

  private fun replyComment(
    thread: DiffCommentThread,
    author: DiffCommentAuthor,
    agentId: String?,
    body: String,
    outcome: DiffCommentOutcome?,
  ): DiffComment =
    DiffComment(
      id = ids.randomToken(),
      threadId = thread.id,
      position = (thread.comments.maxOfOrNull { it.position } ?: 0) + 1,
      author = author,
      agentId = agentId,
      body = body,
      outcome = outcome,
      createdAt = clock.instant(),
      editedAt = null,
    )

  fun setState(
    threadId: String,
    state: DiffCommentThreadState,
  ): DiffCommentThreadResult =
    database.transaction { unit ->
      val thread = unit.diffComments.findThread(threadId) ?: return@transaction DiffCommentThreadResult.NotFound
      val allowed =
        when (thread.state) {
          DiffCommentThreadState.PENDING -> false
          DiffCommentThreadState.OPEN ->
            state == DiffCommentThreadState.RESOLVED || state == DiffCommentThreadState.DECLINED
          DiffCommentThreadState.RESOLVED, DiffCommentThreadState.DECLINED -> state == DiffCommentThreadState.OPEN
        }
      if (!allowed) return@transaction DiffCommentThreadResult.Rejected(DiffCommentRejection.INVALID_TRANSITION)
      unit.diffComments.updateThreadState(threadId, state, clock.instant())
      DiffCommentThreadResult.Saved(unit.diffComments.findThread(threadId)!!, true)
    }

  fun replyToSource(
    repoRoot: Path,
    sourceRef: String,
    body: String,
    outcome: DiffCommentOutcome,
    agentId: String? = null,
  ): DiffCommentThreadResult {
    validateBody(body)?.let { return DiffCommentThreadResult.Rejected(it) }
    val anchor =
      when (val result = anchor(repoRoot, null)) {
        is AnchorResolution.Available -> result.anchor
        is AnchorResolution.Unavailable -> return DiffCommentThreadResult.Unavailable(result.reason)
      }
    return database.transaction { unit ->
      val existing =
        unit.diffComments.findThreadBySourceRef(anchor.identity, anchor.branch, sourceRef)
          ?: return@transaction DiffCommentThreadResult.NotFound
      val latest = existing.comments.maxByOrNull { it.position }
      if (latest?.author == DiffCommentAuthor.AGENT && latest.body == body && latest.outcome == outcome) {
        return@transaction DiffCommentThreadResult.Saved(existing, false)
      }
      appendReply(
        unit.diffComments,
        existing,
        replyComment(existing, DiffCommentAuthor.AGENT, agentId, body, outcome),
      )
    }
  }

  private data class Anchor(val identity: String, val branch: String, val tip: String, val currentBranch: String)

  private sealed interface AnchorResolution {
    data class Available(val anchor: Anchor) : AnchorResolution

    data class Unavailable(val reason: String) : AnchorResolution
  }

  private fun pendingAnchor(
    root: Path,
    expectedBranch: String?,
  ): AnchorResolution =
    when (val result = anchor(root, null)) {
      is AnchorResolution.Unavailable -> result
      is AnchorResolution.Available ->
        if (expectedBranch != null && result.anchor.branch != expectedBranch) {
          anchorUnavailable("authoring_branch", "Check out $expectedBranch to comment")
        } else {
          result
        }
    }

  private fun anchor(
    root: Path,
    explicitBranch: String?,
  ): AnchorResolution {
    return try {
      val current =
        when (val result = git.currentBranch(root)) {
          is WorkflowGitOperationResult.Ok -> result.value.trim()
          is WorkflowGitOperationResult.Failed ->
            return anchorUnavailable("current_branch", "current branch unavailable", result.error)
        }
      if (current.isBlank()) return anchorUnavailable("current_branch", "detached HEAD")
      val head =
        when (val result = git.headCommitSha(root)) {
          is WorkflowGitOperationResult.Ok -> result.value.trim()
          is WorkflowGitOperationResult.Failed ->
            return anchorUnavailable("head_commit", "HEAD unavailable", result.error)
        }
      anchorAtBranch(root, explicitBranch, current, head)
    } catch (failure: CancellationException) {
      throw failure
    } catch (failure: IOException) {
      anchorUnavailable("repository_access", "repository access unavailable", failure.message)
    } catch (failure: SecurityException) {
      anchorUnavailable("repository_access", "repository access unavailable", failure.message)
    }
  }

  private fun anchorAtBranch(
    root: Path,
    explicitBranch: String?,
    current: String,
    head: String,
  ): AnchorResolution {
    val branch = explicitBranch ?: current
    val tip =
      if (explicitBranch == null) {
        head
      } else {
        when (val result = git.resolveCommit(root, "refs/heads/$branch")) {
          is WorkflowGitOperationResult.Ok -> result.value.trim()
          is WorkflowGitOperationResult.Failed ->
            return anchorUnavailable("branch_tip", "branch $branch unavailable", result.error)
        }
      }
    return AnchorResolution.Available(Anchor(repositories.primaryRepositoryIdentity(root), branch, tip, current))
  }

  private fun anchorUnavailable(
    seam: String,
    reason: String,
    detail: String? = null,
  ): AnchorResolution.Unavailable {
    val boundedCause =
      detail?.replace('\n', ' ')?.replace('\r', ' ')?.take(MAX_DIAGNOSTIC_CAUSE_LENGTH)?.takeIf { it.isNotBlank() }
    diagnostics.warning(
      "seam=diff_comment_anchor expected=available value_used=unavailable stage=$seam cause=${boundedCause ?: reason}",
    )
    val unavailableReason =
      if (boundedCause == null || reason == "detached HEAD") reason else "$reason: $boundedCause"
    return AnchorResolution.Unavailable(unavailableReason)
  }

  private fun outdated(
    root: Path,
    anchor: Anchor,
    thread: DiffCommentThread,
  ): Boolean {
    val path = thread.path ?: return false
    val line = thread.line
    if (line == null) {
      return anchor.currentBranch == thread.headBranch &&
        worktreeFiles.lineText(root, path, 1) == DiffCommentLineObservation.Missing
    }
    if (thread.side == DiffCommentSide.NEW && anchor.currentBranch == thread.headBranch) {
      val expected = thread.anchorLineText?.let { DiffCommentLineObservation.Text(it) }
      return worktreeFiles.lineText(root, path, line) != expected
    }
    return anchor.tip != thread.anchorCommitSha
  }

  private fun validateBody(body: String): DiffCommentRejection? =
    when {
      body.isBlank() -> DiffCommentRejection.BLANK_BODY
      body.toByteArray(Charsets.UTF_8).size > DiffComment.MAX_BODY_BYTES -> DiffCommentRejection.BODY_TOO_LARGE
      else -> null
    }

  private fun validateThreadInput(
    path: String?,
    line: Int?,
    body: String,
  ): DiffCommentRejection? {
    val bodyRejection = validateBody(body)
    if (bodyRejection != null) return bodyRejection
    if (line != null && (line < 1 || path == null)) return DiffCommentRejection.INVALID_PATH
    return path?.let(::validatePath)
  }

  private fun validatePath(path: String): DiffCommentRejection? =
    when {
      path.isBlank() || '\u0000' in path -> DiffCommentRejection.INVALID_PATH
      Path.of(path).isAbsolute -> DiffCommentRejection.INVALID_PATH
      path.split('/').any { it == ".." } -> DiffCommentRejection.INVALID_PATH
      else -> null
    }

  private companion object {
    const val MAX_DIAGNOSTIC_CAUSE_LENGTH = 160
  }
}
