package skillbill.application.review.parallel.verification

import me.tatarka.inject.annotations.Inject
import skillbill.application.agentoutput.agentFailureExcerpt
import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.application.review.parallel.runner.LANE_FINDING_PARSE_SEAM
import skillbill.application.review.parallel.runner.NO_OP_RESUME_TERMINAL_STATUS
import skillbill.application.review.parallel.runner.PARALLEL_REVIEW_FIRST_SOURCE_LINE
import skillbill.application.review.parallel.runner.PARALLEL_REVIEW_REGISTER_ABSENCE_EXCERPT_MAX_LENGTH
import skillbill.application.review.parallel.runner.PARALLEL_REVIEW_STDERR_EXCERPT_MAX_LENGTH
import skillbill.application.review.parallel.runner.ParallelCodeReviewInlineParentLaunch
import skillbill.application.review.parallel.runner.ParallelCodeReviewSoftRegisterAdmission
import skillbill.error.core.failureCodeLabel
import skillbill.goalrunner.terminalStatus
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.review.model.ParallelReviewLaneOutcome
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.review.context.model.accounting.ReviewAccountingTerminalOutcome
import skillbill.review.context.model.packet.ReviewLaneAssembledBundle
import skillbill.review.model.ParallelReviewParseResult
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.review.parallel.ParallelReviewFindingParser
import kotlin.coroutines.cancellation.CancellationException

@Inject
class ParallelCodeReviewRunnerFailureAdmission(
  private val registerParse: (String) -> ParallelReviewParseResult,
) {
  internal fun softAdmitFindings(
    stdout: String,
    launch: ParallelCodeReviewInlineParentLaunch,
  ): ParallelCodeReviewSoftRegisterAdmission =
    when (val parse = parseLaneRegisterSeam(stdout, launch.assignment.lane, registerParse)) {
      is LaneRegisterParse.Parsed -> {
        val parsed = parse.result
        ParallelCodeReviewSoftRegisterAdmission(
          findings = attributeLaneFindings(parsed, launch.selected),
          droppedCandidateDiagnostic = rejectedCandidateDiagnostic(parsed),
          rejectedCandidateCount = parsed.rejections.size,
          citationDiagnostics = parsed.citationDiagnostics,
        )
      }
      is LaneRegisterParse.Failed -> ParallelCodeReviewSoftRegisterAdmission(emptyList(), null, 0, emptyList())
    }

  private fun attributeLaneFindings(
    parsed: ParallelReviewParseResult,
    selected: List<ReviewSpecialistLaunchRequest>,
  ): List<ParallelReviewRawFinding> {
    val fallbackLane = selected.minByOrNull { it.assignment.laneDecision.orderIndex }
    val fallbackPath =
      fallbackLane?.assignment?.assignedPaths?.firstOrNull()
        ?: ParallelReviewFindingParser.UNASSIGNED_REPOSITORY_PATH
    return parsed.findings.map { finding ->
      val findingPath = finding.repositoryPath
      val pathOwners =
        selected.filter { launch ->
          findingPath != null && launch.assignment.assignedPaths.any { path -> path == findingPath }
        }.distinctBy { it.assignment.laneDecision.specialistSkillName }
      val owner =
        resolveLaneFindingOwner(finding.specialistSkillName, pathOwners, selected)
          ?: fallbackLane
      val path =
        when {
          findingPath != null &&
            findingPath != ParallelReviewFindingParser.UNASSIGNED_REPOSITORY_PATH -> findingPath
          else -> fallbackPath
        }
      val line = finding.line ?: PARALLEL_REVIEW_FIRST_SOURCE_LINE
      finding.copy(
        specialistSkillName =
          owner?.assignment?.laneDecision?.specialistSkillName
            ?: finding.specialistSkillName,
        originLayerChains = owner?.assignment?.laneDecision?.originLayerChains.orEmpty(),
        repositoryPath = path,
        line = line,
        location = "$path:$line",
      )
    }
  }

  private fun resolveLaneFindingOwner(
    declaredSpecialist: String?,
    pathOwners: List<ReviewSpecialistLaunchRequest>,
    selected: List<ReviewSpecialistLaunchRequest>,
  ): ReviewSpecialistLaunchRequest? {
    val selectedByName = selected.distinctBy { it.assignment.laneDecision.specialistSkillName }
    if (declaredSpecialist != null) {
      selectedByName.singleOrNull { it.assignment.laneDecision.specialistSkillName == declaredSpecialist }
        ?.let { return it }
    }
    return pathOwners.minByOrNull { it.assignment.laneDecision.orderIndex }
  }

  fun laneFailureReason(facts: AgentRunLaunchFacts): String? =
    when (val termination = facts.termination) {
      AgentRunTermination.TimedOut -> "agent timed out"
      AgentRunTermination.SpawnFailed ->
        buildString {
          append("agent process failed to spawn")
          appendAgentFailureExcerpt(facts)
        }
      AgentRunTermination.Interrupted -> "agent was interrupted"
      is AgentRunTermination.Exited ->
        when {
          termination.code != 0 ->
            buildString {
              append("agent exited with status ${termination.code}")
              appendAgentFailureExcerpt(facts)
            }
          facts.stdoutTruncated -> "agent output exceeded the retention cap before completion"
          else -> null
        }
    }

  private fun StringBuilder.appendAgentFailureExcerpt(facts: AgentRunLaunchFacts) {
    agentFailureExcerpt(
      facts.stderr,
      facts.stdout,
      PARALLEL_REVIEW_STDERR_EXCERPT_MAX_LENGTH,
    )?.let { excerpt ->
      append(" — ${excerpt.lineSequence().first().take(PARALLEL_REVIEW_STDERR_EXCERPT_MAX_LENGTH)}")
    }
  }

  private fun rejectedCandidateDiagnostic(parsed: ParallelReviewParseResult): String? {
    val rejection = parsed.rejections.firstOrNull() ?: return null
    return "dropped ${parsed.rejections.size} of ${parsed.candidateCount} [F-XXX] candidate line(s); " +
      "first at line ${rejection.linePosition} rejected as ${rejection.reason.wireValue}: " +
      rejection.lineText.take(PARALLEL_REVIEW_REGISTER_ABSENCE_EXCERPT_MAX_LENGTH)
  }
}

private const val CAUSE_DETAIL_MAX_LENGTH: Int = 200

internal sealed interface LaneRegisterParse {
  data class Parsed(val result: ParallelReviewParseResult) : LaneRegisterParse

  data class Failed(
    val seam: String,
    val lane: String,
    val detail: String,
  ) : LaneRegisterParse {
    init {
      require(seam.isNotBlank() && lane.isNotBlank()) {
        "Review register parse seam failure must name its seam and lane."
      }
    }
  }
}

internal fun parseLaneRegisterSeam(
  stdout: String,
  lane: String,
  parse: (String) -> ParallelReviewParseResult = ParallelReviewFindingParser::parse,
): LaneRegisterParse =
  try {
    LaneRegisterParse.Parsed(parse(stdout))
  } catch (thrown: IllegalArgumentException) {
    laneRegisterFailure(lane, thrown)
  } catch (thrown: IllegalStateException) {
    laneRegisterFailure(lane, thrown)
  }

private fun laneRegisterFailure(
  lane: String,
  cause: Throwable,
): LaneRegisterParse.Failed =
  LaneRegisterParse.Failed(
    seam = LANE_FINDING_PARSE_SEAM,
    lane = lane,
    detail =
      "Review register parse seam '$LANE_FINDING_PARSE_SEAM' failed for lane '$lane': " +
        "${cause.failureCodeLabel() ?: cause::class.simpleName}: " +
        (cause.message?.take(CAUSE_DETAIL_MAX_LENGTH) ?: "no detail"),
  )

internal fun parallelCodeReviewNoOpResumeOutcome(agentId: String) =
  ParallelReviewLaneOutcome(
    success = true,
    rawOutput = "",
    accounting =
      ReviewLaneAccounting(
        lane = agentId,
        evidenceBytes = 0,
        expansions = emptyList(),
        toolCalls = 0,
        modelTurns = 0,
        resultBytes = 0,
        terminalStatus = NO_OP_RESUME_TERMINAL_STATUS.wireValue,
        reviewDisposition = ReviewLaneReviewDisposition.COMPLETE,
        bundleCompositionDigest = ReviewLaneAssembledBundle.EMPTY.compositionDigest,
      ),
    reviewDisposition = ReviewLaneReviewDisposition.COMPLETE,
    bundleCompositionDigest = ReviewLaneAssembledBundle.EMPTY.compositionDigest,
  )

internal fun parallelCodeReviewInlineTerminalStatus(
  facts: AgentRunLaunchFacts,
  disposition: ReviewLaneReviewDisposition,
): ReviewAccountingTerminalOutcome =
  when {
    disposition == ReviewLaneReviewDisposition.INCOMPLETE -> ReviewAccountingTerminalOutcome.INCOMPLETE
    facts.termination == AgentRunTermination.TimedOut -> ReviewAccountingTerminalOutcome.TIMEOUT
    facts.termination == AgentRunTermination.Interrupted -> ReviewAccountingTerminalOutcome.INTERRUPTED
    facts.termination == AgentRunTermination.SpawnFailed -> ReviewAccountingTerminalOutcome.SPAWN_FAILURE
    facts.termination != AgentRunTermination.Exited(0) -> ReviewAccountingTerminalOutcome.PROCESS_FAILURE
    else -> ReviewAccountingTerminalOutcome.COMPLETED
  }

internal fun parallelCodeReviewCaptureLane(lane: () -> ParallelReviewLaneOutcome): ParallelReviewLaneOutcome {
  val outcome = runCatching { lane() }
  if (outcome.isSuccess) return outcome.getOrThrow()
  val error = outcome.exceptionOrNull()!!
  val terminal =
    when (error) {
      is CancellationException, is InterruptedException -> error
      is Exception -> return ParallelReviewLaneOutcome(
        success = false,
        rawOutput = "",
        failureReason =
          "lane launch threw ${error.failureCodeLabel() ?: error::class.simpleName}: ${error.message ?: "no detail"}",
      )
      else -> error
    }
  throw terminal
}
