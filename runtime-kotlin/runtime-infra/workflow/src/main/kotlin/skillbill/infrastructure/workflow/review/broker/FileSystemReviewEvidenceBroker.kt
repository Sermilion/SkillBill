package skillbill.infrastructure.workflow.review.broker

import skillbill.ports.review.evidence.ReviewEvidenceBroker
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceBrokerBinding
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewRefusedOperationRecord
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.review.context.model.accounting.ReviewBudgetEvaluator
import skillbill.review.context.model.accounting.ReviewBudgetKind
import skillbill.review.context.model.accounting.ReviewBudgetOutcome
import skillbill.review.context.model.accounting.ReviewLaneIdentity
import skillbill.review.context.model.execution.ReviewOperationPolicy
import skillbill.review.context.model.execution.ReviewRequestedOperation
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import skillbill.review.context.model.packet.LANE_EVIDENCE_BYTES_DIMENSION
import skillbill.review.model.requireRepositoryRelativePath
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class FileSystemReviewEvidenceBroker(binding: ReviewEvidenceBrokerBinding) : ReviewEvidenceBroker {
  private val root: Path = binding.repoRoot.toRealPath()
  private val assignment = binding.assignment
  private val budget = binding.budget
  private val identity = ReviewLaneIdentity.of(assignment)
  private val policy = ReviewOperationPolicy(assignment, binding.laneRubricId, binding.namedDependencies)
  private val authorizedExpansionLedger = binding.trustedExpansionLedger.toMutableList()
  private val projectedHunks = binding.projectedHunks
  private val completeFileCheckpoint =
    (
      assignment.assignedPaths + assignment.dependencyAllowlist.normalized
    ).distinct().associateWith { checkpointDigest(root, it) }
  private val hunkCommitById =
    assignment.assignedBundle.entries
      .flatMap { entry -> entry.hunkIds.map { it to entry.commitSha } }
      .toMap()
  private val readState =
    FileSystemReviewEvidenceBrokerReadState(
      FileSystemReviewEvidenceBrokerReadStateInit(
        root = root,
        assignment = assignment,
        budget = budget,
        identity = identity,
        policy = policy,
        authorizedExpansionLedger = authorizedExpansionLedger,
        projectedHunks = projectedHunks,
        locatorReader = binding.locatorReader,
        bodyExtractor = binding.bodyExtractor,
        completeFileCheckpoint = completeFileCheckpoint,
        hunkCommitById = hunkCommitById,
      ),
    )
  private val reads = FileSystemReviewEvidenceBrokerReads(readState)
  private var refusedOperationCount = 0
  private var resultBytes = 0L
  private var laneResultObserved = false
  private var toolCalls = 0
  private var modelTurns = 0
  private val refusalLedger = mutableListOf<ReviewRefusedOperationRecord>()

  init {
    val admitted =
      assignment.assignedPaths + assignment.dependencyAllowlist.normalized +
        assignment.evidenceTargets.map { it.path } + assignment.expansions.map { it.requestedPath }
    admitted.distinct().forEach { validateRepositoryMapping(root, it) }
  }

  @Synchronized
  override fun authorizeExpansion(request: ReviewExpansionAuthorizationRequest): ReviewExpansionRecord {
    require(request.lane == assignment.lane) { "Expansion lane does not own this assignment." }
    require(request.reachabilityReason.isNotBlank()) { "Expansion reachability reason must not be blank." }
    requireRepositoryRelativePath(request.path)
    require(policy.isReachable(request.path)) { "Expansion path is outside the assignment evidence surface." }
    val existing =
      authorizedExpansionLedger.singleOrNull {
        it.requestedPath == request.path && it.reachabilityReason == request.reachabilityReason
      }
    if (existing != null) return existing
    val sequence = (authorizedExpansionLedger.maxOfOrNull { it.sequence } ?: -1) + 1
    val expansion =
      ReviewExpansionRecord(
        expansionId = stableReviewExpansionId(assignment.digest, request.path, request.reachabilityReason),
        assignmentDigest = assignment.digest,
        requestedPath = request.path,
        reachabilityReason = request.reachabilityReason,
        authorized = true,
        sequence = sequence,
      )
    authorizedExpansionLedger += expansion
    return expansion
  }

  @Synchronized
  override fun readBatch(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult {
    require(request.lane == assignment.lane) { "Evidence lane does not own this assignment." }
    readState.terminalOutcome?.let { outcome ->
      val terminated =
        request.requests.map {
          terminalResult(outcome, readState.cumulativeBytes, readState.expansionLedger.size)
        }
      return buildBatchResult(terminated, outcome)
    }
    val results = mutableListOf<ReviewEvidenceResult>()
    for (evidenceRequest in request.requests) {
      val result = reads.readOne(evidenceRequest)
      results += result
      val exceeded = result.budgetExceeded
      if (exceeded != null) {
        val served = results.size
        request.requests.drop(served).forEach {
          results += terminalResult(exceeded, readState.cumulativeBytes, readState.expansionLedger.size)
        }
        break
      }
    }
    return buildBatchResult(results, readState.terminalOutcome)
  }

  @Synchronized
  override fun recordToolCall(call: ReviewToolCall): ReviewToolCallResult {
    require(call.lane == assignment.lane) { "Tool call lane does not own this assignment." }
    readState.terminalOutcome?.let { return ReviewToolCallResult(budgetExceeded = it) }
    policy.classify(ReviewRequestedOperation(call.kind, call.target, searchScopes = call.searchScopes))?.let {
      return ReviewToolCallResult(forbidden = it)
    }
    toolCalls += 1
    val outcome =
      ReviewBudgetEvaluator.exceededOrNull(
        identity,
        ReviewBudgetKind.SPECIALIST_TOOL_CALLS,
        budget.maxSpecialistToolCalls.toLong(),
        toolCalls.toLong(),
      )
    return ReviewToolCallResult(budgetExceeded = outcome?.also { readState.terminalOutcome = it })
  }

  @Synchronized
  override fun recordModelTurn(): ReviewBudgetOutcome? {
    readState.terminalOutcome?.let { return it }
    modelTurns += 1
    return ReviewBudgetEvaluator.exceededOrNull(
      identity,
      ReviewBudgetKind.SPECIALIST_MODEL_TURNS,
      budget.maxSpecialistModelTurns.toLong(),
      modelTurns.toLong(),
    )?.also { readState.terminalOutcome = it }
  }

  @Synchronized
  override fun validateLaneResult(result: String): ReviewBudgetOutcome? {
    laneResultObserved = true
    readState.terminalOutcome?.let { return it }
    resultBytes = maxOf(resultBytes, result.toByteArray(StandardCharsets.UTF_8).size.toLong())
    return ReviewBudgetEvaluator.laneResultOutcome(identity, budget, resultBytes)
      ?.also { readState.terminalOutcome = it }
  }

  @Synchronized
  override fun observeLaneResultChunk(chunk: String): ReviewBudgetOutcome? {
    laneResultObserved = true
    readState.terminalOutcome?.let { return it }
    resultBytes += chunk.toByteArray(StandardCharsets.UTF_8).size.toLong()
    return ReviewBudgetEvaluator.laneResultOutcome(identity, budget, resultBytes)
      ?.also { readState.terminalOutcome = it }
  }

  @Synchronized
  override fun hasObservedLaneResult(): Boolean = laneResultObserved

  @Synchronized
  override fun accounting(): ReviewLaneAccounting {
    val terminal = readState.terminalOutcome
    val evidenceIncomplete = terminal?.budgetKind == ReviewBudgetKind.LANE_EVIDENCE_BYTES
    return ReviewLaneAccounting(
      lane = assignment.lane,
      authorizedReadCount = readState.authorizedReadCount,
      refusedOperationCount = refusedOperationCount,
      refusals = refusalLedger.toList(),
      evidenceBytes = readState.cumulativeBytes,
      expansions = readState.expansionLedger.toList(),
      toolCalls = toolCalls,
      modelTurns = modelTurns,
      resultBytes = resultBytes,
      terminalOutcome = terminal,
      budgetDimension = if (evidenceIncomplete) LANE_EVIDENCE_BYTES_DIMENSION else null,
      unreviewedUnits = if (evidenceIncomplete) readState.deniedUnits.distinct() else emptyList(),
    )
  }

  @Synchronized
  override fun terminalOutcome(): ReviewBudgetOutcome? = readState.terminalOutcome

  private fun buildBatchResult(
    results: List<ReviewEvidenceResult>,
    outcome: ReviewBudgetOutcome?,
  ): ReviewEvidenceBatchResult {
    refusedOperationCount += results.count { it.forbidden != null || it.budgetExceeded != null }
    return reviewEvidenceBatchResult(
      results = results,
      outcome = outcome,
      cumulativeBytes = readState.cumulativeBytes,
      expansionLedger = readState.expansionLedger.toList(),
      refusalLedger = refusalLedger,
    )
  }
}
