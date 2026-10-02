package skillbill.infrastructure.workflow.review.broker

import skillbill.error.shellcontent.ReviewHunkEvidenceIntegrityError
import skillbill.error.shellcontent.ReviewHunkEvidenceLocatorMissingError
import skillbill.infrastructure.workflow.featuretask.storePath
import skillbill.ports.review.evidence.ReviewStoredHunkBodyExtractor
import skillbill.ports.review.model.ReviewEvidenceRequest
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceLocatorReadRequest
import skillbill.review.context.model.accounting.ReviewBudgetKind
import skillbill.review.context.model.accounting.ReviewBudgetOutcome
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.accounting.ReviewLaneIdentity
import skillbill.review.context.model.commit.ReviewAssignment
import skillbill.review.context.model.execution.ForbiddenReviewOperation
import skillbill.review.context.model.execution.ReviewOperationKind
import skillbill.review.context.model.execution.ReviewOperationPolicy
import skillbill.review.context.model.execution.ReviewRequestedOperation
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import skillbill.review.model.requireRepositoryRelativePath
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

internal class FileSystemReviewEvidenceBrokerReads(
  private val state: FileSystemReviewEvidenceBrokerReadState,
) {
  fun readOne(request: ReviewEvidenceRequest): ReviewEvidenceResult = readOneEvidence(state, request)
}

internal class FileSystemReviewEvidenceBrokerReadState(
  init: FileSystemReviewEvidenceBrokerReadStateInit,
) {
  val root: Path = init.root
  val assignment: ReviewAssignment = init.assignment
  val budget: ReviewContextBudgetPolicy = init.budget
  val identity: ReviewLaneIdentity = init.identity
  val policy: ReviewOperationPolicy = init.policy
  val authorizedExpansionLedger: List<ReviewExpansionRecord> = init.authorizedExpansionLedger
  val projectedHunks: List<ReviewChangedHunk> = init.projectedHunks
  val locatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort? = init.locatorReader
  val bodyExtractor: ReviewStoredHunkBodyExtractor = init.bodyExtractor
  val completeFileCheckpoint: Map<String, String?> = init.completeFileCheckpoint
  val hunkCommitById: Map<String, String> = init.hunkCommitById
  var cumulativeBytes: Long = 0L
  var authorizedReadCount: Int = 0
  var terminalOutcome: ReviewBudgetOutcome? = null
  val expansionLedger = mutableListOf<ReviewExpansionRecord>()
  val servedEvidenceScopes = mutableMapOf<String, ReviewEvidenceScope>()
  val deniedUnits = mutableListOf<String>()
}

internal enum class ReviewEvidenceScope {
  PROJECTED_HUNKS,
  COMPLETE_FILE,
}

private fun readOneEvidence(
  state: FileSystemReviewEvidenceBrokerReadState,
  request: ReviewEvidenceRequest,
): ReviewEvidenceResult {
  val exactPath = request.path
  if (!exactPath.startsWith('/') && !exactPath.startsWith('\\')) {
    requireRepositoryRelativePath(exactPath)
  }
  val operation = ReviewRequestedOperation(ReviewOperationKind.FILE_READ, exactPath, request.reachabilityReason)
  state.policy.classify(operation)?.let { return refusedEvidence(state, it) }
  requireRepositoryRelativePath(exactPath)
  val normalizedTarget = normalizeEvidenceIdentity(exactPath)
  val servedScope = state.servedEvidenceScopes[normalizedTarget]
  val assigned = state.policy.isAssigned(exactPath)
  val expansion = request.authorizedExpansion
  val scope =
    if (assigned && expansion == null) {
      ReviewEvidenceScope.PROJECTED_HUNKS
    } else {
      ReviewEvidenceScope.COMPLETE_FILE
    }
  if (servedScope != null && servedScope != scope) {
    return refusedEvidence(
      state,
      ForbiddenReviewOperation(
        "evidence_scope_expansion_repeat",
        exactPath,
        "The normalized evidence target was already served at ${servedScope.name.lowercase()} scope; " +
          "a repeat may not re-scope it to ${scope.name.lowercase()}.",
      ),
    )
  }
  if (!assigned || expansion != null) {
    admitExpansion(state, request, exactPath, alreadyServed = servedScope != null)?.let { return it }
  }
  state.servedEvidenceScopes[normalizedTarget] = scope
  return readAdmittedFile(state, exactPath, assigned, expansion != null, repeat = servedScope != null)
}

private fun admitExpansion(
  state: FileSystemReviewEvidenceBrokerReadState,
  request: ReviewEvidenceRequest,
  exactPath: String,
  alreadyServed: Boolean,
): ReviewEvidenceResult? {
  val expansion =
    requireNotNull(request.authorizedExpansion) {
      "Unassigned evidence requires an authorized expansion record."
    }
  require(expansion.authorized) { "Expansion '${expansion.expansionId}' is not authorized." }
  require(expansion.assignmentDigest == state.assignment.digest) {
    "Expansion '${expansion.expansionId}' does not belong to this assignment."
  }
  require(expansion.requestedPath == exactPath) {
    "Expansion '${expansion.expansionId}' does not authorize '$exactPath'."
  }
  require(expansion.reachabilityReason == request.reachabilityReason) {
    "Expansion '${expansion.expansionId}' reason provenance changed before admission."
  }
  require(expansion in state.authorizedExpansionLedger) {
    "Expansion '${expansion.expansionId}' was not authorized by this assignment's measured broker."
  }
  if (alreadyServed) {
    require(expansion in state.expansionLedger) {
      "Expansion '${expansion.expansionId}' did not authorize the already-served read of '$exactPath'."
    }
    return null
  }
  require(expansion !in state.expansionLedger) { "Expansion '${expansion.expansionId}' was already admitted." }
  state.expansionLedger += expansion
  if (state.expansionLedger.size > state.budget.maxAssignmentExpansions) {
    return exceededEvidence(
      state,
      ReviewBudgetKind.ASSIGNMENT_EXPANSIONS,
      state.budget.maxAssignmentExpansions.toLong(),
      state.expansionLedger.size.toLong(),
    )
  }
  return null
}

private fun readAdmittedFile(
  state: FileSystemReviewEvidenceBrokerReadState,
  normalized: String,
  assigned: Boolean,
  completeFileAuthorized: Boolean,
  repeat: Boolean,
): ReviewEvidenceResult {
  state.authorizedReadCount += 1
  return if (assigned && !completeFileAuthorized) {
    readProjectedHunks(state, normalized, repeat)
  } else {
    readCompleteFile(state, normalized, assigned, repeat)
  }
}

private fun readProjectedHunks(
  state: FileSystemReviewEvidenceBrokerReadState,
  path: String,
  repeat: Boolean,
): ReviewEvidenceResult {
  val hunks =
    state.projectedHunks
      .filter { it.path == path }
      .sortedWith(compareBy({ it.newStart }, { it.oldStart }, { it.hunkId }))
  val delivered = mutableListOf<String>()
  for (hunk in hunks) {
    val body = materializeAssignedHunk(state, hunk)
    val bytes = body.toByteArray(StandardCharsets.UTF_8).size.toLong()
    if (repeat) {
      delivered += body
      continue
    }
    assignedHunkBudgetOutcome(state, bytes, unitForHunk(state, hunk))?.let { exceeded ->
      return if (delivered.isEmpty()) {
        exceeded
      } else {
        val content = delivered.joinToString("\n")
        ReviewEvidenceResult(
          content,
          content.toByteArray(StandardCharsets.UTF_8).size.toLong(),
          state.cumulativeBytes,
          state.expansionLedger.size,
          budgetExceeded = exceeded.budgetExceeded,
        )
      }
    }
    state.cumulativeBytes += bytes
    delivered += body
  }
  val content = delivered.joinToString("\n")
  return ReviewEvidenceResult(
    content,
    content.toByteArray(StandardCharsets.UTF_8).size.toLong(),
    state.cumulativeBytes,
    state.expansionLedger.size,
  )
}

private fun materializeAssignedHunk(
  state: FileSystemReviewEvidenceBrokerReadState,
  hunk: ReviewChangedHunk,
): String {
  val locator = hunk.evidenceLocator
  val body =
    if (state.locatorReader != null) {
      val payload =
        state.locatorReader.readDiffPayload(
          FeatureTaskRuntimeSharedEvidenceLocatorReadRequest(state.root, locator.storePath, locator.payloadFile),
        )
      state.bodyExtractor.extract(payload, hunk)
    } else {
      val fallback = hunk.content.replace("\r\n", "\n")
      if (fallback.isEmpty()) throw ReviewHunkEvidenceLocatorMissingError(locator.storePath)
      fallback
    }
  val normalized = body.replace("\r\n", "\n")
  val observed = ReviewChangedHunk.digestOfBody(normalized)
  if (observed != hunk.contentDigest) {
    throw ReviewHunkEvidenceIntegrityError(locator.storePath, hunk.contentDigest, observed)
  }
  return normalized
}

private fun readCompleteFile(
  state: FileSystemReviewEvidenceBrokerReadState,
  path: String,
  assigned: Boolean,
  repeat: Boolean,
): ReviewEvidenceResult {
  val real = resolveRepositoryFile(state.root, path)
  val expectedDigest = state.completeFileCheckpoint.getValue(path)
  if (real == null) {
    if (expectedDigest != null) rejectCheckpointDrift(state, path)
    require(assigned) { "Expanded evidence path must be a repository file." }
    return unavailableResult(state.cumulativeBytes, state.expansionLedger.size)
  }
  val contentBytes = Files.readAllBytes(real)
  if (expectedDigest == null || digest(contentBytes) != expectedDigest) {
    rejectCheckpointDrift(state, path)
  }
  return serveEvidence(state, path, contentBytes, repeat)
}

private fun serveEvidence(
  state: FileSystemReviewEvidenceBrokerReadState,
  path: String,
  contentBytes: ByteArray,
  repeat: Boolean,
): ReviewEvidenceResult {
  val bytes = contentBytes.size.toLong()
  if (!repeat) {
    evidenceBudgetOutcome(state, bytes, unitAtPath(state, path))?.let { return it }
    state.cumulativeBytes += bytes
  }
  return ReviewEvidenceResult(
    contentBytes.toString(StandardCharsets.UTF_8),
    bytes,
    state.cumulativeBytes,
    state.expansionLedger.size,
  )
}

private fun refusedEvidence(
  state: FileSystemReviewEvidenceBrokerReadState,
  forbidden: ForbiddenReviewOperation,
): ReviewEvidenceResult = forbiddenResult(forbidden, state.cumulativeBytes, state.expansionLedger.size)
