package skillbill.application.review.verification

import skillbill.agent.model.AgentPhaseInput
import skillbill.agent.model.PhaseOutput
import skillbill.application.review.model.ReviewClaimVerificationOutcome
import skillbill.application.review.model.ReviewClaimVerificationRunRequest
import skillbill.application.review.model.ReviewDelegatedStageLaunch
import skillbill.application.review.model.boundedReviewLane
import skillbill.application.review.packet.toVerificationLaunchEnvelope
import skillbill.application.review.preparation.ReviewPreparationService
import skillbill.contracts.JsonCodec
import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.ports.agentrun.model.AgentRunLaunchDenied
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.AgentRunTermination
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.ReviewContextEnvelopeValidator
import skillbill.review.context.model.claim.ReviewClaimVerdictAdmission
import skillbill.review.context.model.claim.ReviewClaimWorkerResult
import skillbill.review.context.model.execution.ResolvedReviewExecutionMode
import skillbill.review.context.model.hunk.ReviewDependencyAllowlist
import skillbill.review.context.model.launch.GovernedReviewVerificationLaunch
import skillbill.review.context.model.launch.ReviewCitedRegion
import skillbill.review.context.model.packet.ReviewContextPacket
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingCitationDiagnosticWithFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageDegradationReason
import skillbill.review.model.ReviewVerificationNonSuccess
import skillbill.review.model.requireRepositoryRelativePath
import skillbill.review.parsing.ReviewFindingFieldCodec
import skillbill.review.stage.ReviewStageDegradationSelection
import java.time.Clock

class ReviewClaimVerificationRunner(
  private val launcher: GoalRunnerSubtaskLauncher,
  private val envelopeValidator: ReviewContextEnvelopeValidator,
  private val clock: Clock,
) {
  internal fun run(request: ReviewClaimVerificationRunRequest): ReviewClaimVerificationOutcome {
    if (request.packet == null) {
      return ReviewClaimVerificationOutcome(
        verdicts = emptyList(),
        skipReason = "the review compiled no packet, so there is no delta to verify against",
      )
    }
    if (request.findings.isEmpty()) {
      return when {
        !reviewOutputNeedsProseVerification(request.reviewOutput) ->
          ReviewClaimVerificationOutcome(
            verdicts = emptyList(),
            skipReason = "the review pass emitted no findings, so there is nothing to verify",
          )
        else ->
          verifyReviewOutput(
            ProseVerificationInput(
              packet = request.packet,
              reviewOutput = request.reviewOutput,
              mode = request.mode,
              launch = request.launch,
            ),
          )
      }
    }
    val durableRefs =
      request.existingVerdicts
        .filter { it.stage == ReviewStage.VERIFICATION }
        .map { it.findingRef }
        .toSet()
    val pending = request.findings.sortedBy { it.fNumber }.filterNot { it.fNumber in durableRefs }
    if (pending.isEmpty()) {
      return ReviewClaimVerificationOutcome(
        verdicts = request.existingVerdicts.filter { it.stage == ReviewStage.VERIFICATION },
        skipReason = "every finding already holds a durable verification verdict",
      )
    }
    val recordedAt = clock.instant().toString()
    val citationDiagnostics = mutableListOf<ReviewFindingCitationDiagnosticWithFinding>()
    val verdicts =
      pending.map { finding ->
        verifyOne(
          VerificationFindingInput(
            packet = request.packet,
            reviewOutput = request.reviewOutput,
            finding = finding,
            mode = request.mode,
            launch = request.launch,
            recordedAt = recordedAt,
          ),
        ).also { outcome ->
          citationDiagnostics += outcome.citationDiagnostics
        }.verdict
      }
    return ReviewClaimVerificationOutcome(
      verdicts =
        request.existingVerdicts.filter {
          it.stage == ReviewStage.VERIFICATION && it.findingRef in durableRefs
        } + verdicts,
      citationDiagnostics = citationDiagnostics,
    )
  }

  private fun verifyReviewOutput(input: ProseVerificationInput): ReviewClaimVerificationOutcome {
    if (input.reviewOutput.isBlank()) {
      return nonSuccessOutcome(
        ReviewStageDegradationReason.REVIEW_PASS_OUTPUT_ABSENT,
        "the review phase produced no output to verify",
      )
    }
    val phaseInput =
      AgentPhaseInput(
        input = input.reviewOutput,
        requestedAction = VERIFY_CLAIMS_ACTION,
      )
    val prompt =
      appendPromptSuffix(
        proseVerificationPrompt(input.packet, phaseInput, input.mode),
        input.launch.promptSuffix,
      )
    if (prompt.toByteArray(Charsets.UTF_8).size.toLong() > input.launch.budget.maxLaneLaunchBytes) {
      return nonSuccessOutcome(
        ReviewStageDegradationReason.WORKER_LAUNCH_BUDGET_EXCEEDED,
        "verification phase launch exceeded max_lane_launch_bytes",
      )
    }
    val outcome =
      launcher.launch(
        GoalRunnerSubtaskLaunchRequest(
          invokedAgentId = input.launch.brokerId,
          configuredAgentOverrideId = null,
          skillRunRequest =
            SkillRunRequest(
              issueKey = ISSUE_KEY,
              repoRoot = input.launch.repoRoot,
              timeout = input.launch.timeout,
              promptOverride = prompt,
              modelOverride = input.launch.modelOverride,
            ).boundedReviewLane(input.launch.laneProgressIdleTimeout),
        ),
      )
    return when (outcome) {
      is UnsupportedAgentRunLaunch ->
        nonSuccessOutcome(
          ReviewStageDegradationReason.WORKER_LAUNCH_OR_RETURN_FAILED,
          "unsupported agent: ${outcome.reason}",
        )
      is AgentRunLaunchDenied -> error("Review claim verification never launches with a spawn authorization.")
      is AgentRunLaunchFacts -> proseVerificationOutcome(outcome)
    }
  }

  private fun proseVerificationOutcome(facts: AgentRunLaunchFacts): ReviewClaimVerificationOutcome {
    launchFailureReason(facts)?.let { reason ->
      val classified =
        ReviewStageDegradationSelection.workerFailureReason(reason)
          ?: ReviewStageDegradationReason.WORKER_LAUNCH_OR_RETURN_FAILED
      return nonSuccessOutcome(classified, reason, PhaseOutput(value = facts.stdout))
    }
    if (facts.stdout.isBlank()) {
      return nonSuccessOutcome(
        ReviewStageDegradationReason.WORKER_OUTPUT_UNUSABLE,
        "the verification worker returned without publishing a verification result",
        PhaseOutput(value = facts.stdout),
      )
    }
    return ReviewClaimVerificationOutcome(verdicts = emptyList(), output = PhaseOutput(value = facts.stdout))
  }

  private fun nonSuccessOutcome(
    reason: ReviewStageDegradationReason,
    detail: String,
    output: PhaseOutput? = null,
  ): ReviewClaimVerificationOutcome =
    ReviewClaimVerificationOutcome(
      verdicts = emptyList(),
      output = output,
      skipReason = detail,
      nonSuccess = ReviewVerificationNonSuccess(reason, detail),
    )

  private data class VerificationFindingOutcome(
    val verdict: ReviewFindingVerdict,
    val citationDiagnostics: List<ReviewFindingCitationDiagnosticWithFinding> = emptyList(),
  )

  private fun verifyOne(input: VerificationFindingInput): VerificationFindingOutcome {
    val region =
      citedRegionOf(input.finding)
        ?: return VerificationFindingOutcome(
          unresolved(input.finding, input.recordedAt, "finding has no cited file:line region"),
        )
    val launch =
      GovernedReviewVerificationLaunch(
        packet = input.packet,
        finding = input.finding,
        citedRegion = region,
        evidenceSurfaceRules = ReviewPreparationService.verificationEvidenceSurfaceRules(input.mode),
        dependencyAllowlist = ReviewDependencyAllowlist(input.packet.dependencyAllowlist.normalized),
        brokerId = input.launch.brokerId,
        budget = input.launch.budget,
      )
    val envelope = launch.toVerificationLaunchEnvelope().asWireMap()
    val launchBytes = JsonCodec.mapToJsonString(envelope).toByteArray(Charsets.UTF_8).size.toLong()
    if (launchBytes > input.launch.budget.maxLaneLaunchBytes) {
      return VerificationFindingOutcome(
        ReviewFindingVerdict(
          stage = ReviewStage.VERIFICATION,
          findingRef = input.finding.fNumber,
          claimVerdict = ReviewClaimVerdict.UNRESOLVED,
          recordedAt = input.recordedAt,
          rejectionReason = "verification launch exceeded max_lane_launch_bytes",
        ),
      )
    }
    envelopeValidator.validate(envelope, "review verification launch for ${input.finding.fNumber}")
    val prompt =
      appendPromptSuffix(
        verificationPrompt(launch, AgentPhaseInput(input.reviewOutput, VERIFY_CLAIMS_ACTION)),
        input.launch.promptSuffix,
      )
    val outcome =
      launcher.launch(
        GoalRunnerSubtaskLaunchRequest(
          invokedAgentId = input.launch.brokerId,
          configuredAgentOverrideId = null,
          skillRunRequest =
            SkillRunRequest(
              issueKey = ISSUE_KEY,
              repoRoot = input.launch.repoRoot,
              timeout = input.launch.timeout,
              promptOverride = prompt,
              modelOverride = input.launch.modelOverride,
            ).boundedReviewLane(input.launch.laneProgressIdleTimeout),
        ),
      )
    return when (outcome) {
      is UnsupportedAgentRunLaunch ->
        VerificationFindingOutcome(
          unresolved(input.finding, input.recordedAt, "unsupported agent: ${outcome.reason}"),
        )
      is AgentRunLaunchDenied -> error("Review claim verification never launches with a spawn authorization.")
      is AgentRunLaunchFacts -> fromLaunchFacts(input.finding, outcome, input.recordedAt)
    }
  }

  private fun fromLaunchFacts(
    finding: ParallelReviewMergedFinding,
    facts: AgentRunLaunchFacts,
    recordedAt: String,
  ): VerificationFindingOutcome {
    launchFailureReason(facts)?.let { reason ->
      return VerificationFindingOutcome(unresolved(finding, recordedAt, reason))
    }
    val worker =
      parseWorkerResult(facts.stdout)
        ?: return VerificationFindingOutcome(unresolved(finding, recordedAt, "unparseable verification output"))
    val diagnostics =
      worker.citationDiagnostics.map { diagnostic ->
        diagnostic.withFindingRef(finding.fNumber)
      }
    return VerificationFindingOutcome(
      verdict = ReviewClaimVerdictAdmission.admit(finding, worker, recordedAt).verdict,
      citationDiagnostics = diagnostics,
    )
  }

  private fun verificationPrompt(
    launch: GovernedReviewVerificationLaunch,
    phaseInput: AgentPhaseInput,
  ): String =
    buildString {
      appendLine("Verify exactly one review finding against the cited region and the delta.")
      appendLine("Phase input:")
      appendLine(phaseInput.input)
      appendLine("Requested action: ${phaseInput.requestedAction}")
      appendLine("The structured finding below is optional enrichment; the phase input is authoritative.")
      appendLine("Do not receive or use a spec intent projection or parent transcript.")
      appendLine("Do not inspect sibling findings.")
      appendLine("Review is read-only: do not build, compile, or run tests.")
      appendLine("Evidence surface: ${launch.evidenceSurfaceRules}")
      appendLine(
        "Finding ${launch.finding.fNumber}: ${launch.finding.severity.displayName} | " +
          "${launch.finding.location} | ${launch.finding.description}",
      )
      appendLine(
        "Cited region: ${launch.citedRegion.path}:" +
          "${launch.citedRegion.startLine}-${launch.citedRegion.endLine}",
      )
      appendLine("Delta: ${launch.packet.baseRevision}..${launch.packet.headRevision}")
      appendLine(
        "Return free-form verification prose stating exactly one of confirmed, refuted, or unresolved. " +
          "Name each supporting location as path:line.",
      )
      appendLine("A refuted verdict must cite the file:line construct that makes the code safe.")
      appendLine("Do not change the finding text, severity, or location.")
    }

  private fun proseVerificationPrompt(
    packet: ReviewContextPacket,
    phaseInput: AgentPhaseInput,
    mode: ResolvedReviewExecutionMode,
  ): String =
    buildString {
      appendLine("Verify each claim in the review phase output against the repository delta.")
      appendLine("Phase input:")
      appendLine(phaseInput.input)
      appendLine("Requested action: ${phaseInput.requestedAction}")
      appendLine("Review is read-only: do not build, compile, or run tests.")
      appendLine("Verification depth: ${mode.name.lowercase()}.")
      appendLine("Delta: ${packet.baseRevision}..${packet.headRevision}")
      appendLine("Return free-form verification prose. The output string is authoritative.")
    }

  companion object {
    const val ISSUE_KEY: String = "code-review-verification"
    const val VERIFY_CLAIMS_ACTION: String = "Verify each claim in that input against the repository delta."
  }
}

private data class ProseVerificationInput(
  val packet: ReviewContextPacket,
  val reviewOutput: String,
  val mode: ResolvedReviewExecutionMode,
  val launch: ReviewDelegatedStageLaunch,
)

private data class VerificationFindingInput(
  val packet: ReviewContextPacket,
  val reviewOutput: String,
  val finding: ParallelReviewMergedFinding,
  val mode: ResolvedReviewExecutionMode,
  val launch: ReviewDelegatedStageLaunch,
  val recordedAt: String,
)

internal fun appendPromptSuffix(
  prompt: String,
  suffix: String,
): String {
  if (suffix.isEmpty()) return prompt
  return prompt.trimEnd() + "\n\n" + suffix
}

internal fun citedRegionOf(finding: ParallelReviewMergedFinding): ReviewCitedRegion? {
  val rawPath =
    finding.repositoryPath ?: finding.location.substringBefore(
      ':',
    ).takeIf { it.isNotBlank() } ?: return null
  val path =
    runCatching {
      requireRepositoryRelativePath(rawPath.trim())
      rawPath.trim()
    }.getOrNull() ?: return null
  val line = finding.line ?: finding.location.substringAfter(':', "").toIntOrNull()?.takeIf { it >= 1 } ?: return null
  return runCatching { ReviewCitedRegion(path, line, line) }.getOrNull()
}

private val VERDICT_WORD = Regex("""\b(confirmed|refuted|unresolved)\b""")
private val NEGATED_REFUTATION = Regex("""\b(?:not|never|cannot be|can't be|un)\s*refuted\b""")
private val PROSE_CITATION = Regex("""([A-Za-z0-9_./-]*[A-Za-z0-9_-]\.[A-Za-z0-9]+):(\d+)""")

internal fun parseWorkerResult(stdout: String): ReviewClaimWorkerResult? {
  if (stdout.isBlank()) return null
  val payload = parseJsonObject(stdout) ?: return parseProseWorkerResult(stdout)
  val finding = JsonCodec.anyToStringAnyMap(payload["finding"])
  val decodedCitations = ReviewFindingFieldCodec.decodeCitations(payload[ReviewFindingPayloadKeys.CITATIONS])
  return ReviewClaimWorkerResult(
    claimVerdict = payload[ReviewFindingPayloadKeys.CLAIM_VERDICT] as? String,
    citations = decodedCitations.citations,
    citationDiagnostics = decodedCitations.diagnostics,
    findingRef = (finding?.get("finding_ref") as? String) ?: payload["finding_ref"] as? String,
    severity = (finding?.get("severity") as? String) ?: payload["severity"] as? String,
    location = (finding?.get("location") as? String) ?: payload["location"] as? String,
    description = (finding?.get("description") as? String) ?: payload["description"] as? String,
  )
}

private fun parseProseWorkerResult(prose: String): ReviewClaimWorkerResult {
  val lowered = prose.lowercase()
  val verdicts = VERDICT_WORD.findAll(lowered).map { it.value }.toMutableSet()
  if (NEGATED_REFUTATION.containsMatchIn(lowered)) verdicts += "unresolved"
  val citations =
    PROSE_CITATION.findAll(prose).mapNotNull { match ->
      runCatching { ReviewFindingCitation(match.groupValues[1], match.groupValues[2].toInt()) }.getOrNull()
    }.distinct().toList()
  return ReviewClaimWorkerResult(claimVerdict = verdicts.singleOrNull(), citations = citations)
}

internal fun parseJsonObject(stdout: String): Map<String, Any?>? {
  val trimmed = stdout.trim()
  JsonCodec.parseObjectOrNull(trimmed)?.let {
    return JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(it))
  }
  val start = trimmed.indexOf('{')
  val end = trimmed.lastIndexOf('}')
  if (start < 0 || end <= start) return null
  return JsonCodec.parseObjectOrNull(trimmed.substring(start, end + 1))
    ?.let { JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(it)) }
}

internal fun parseCitations(raw: Any?): List<ReviewFindingCitation> = ReviewFindingFieldCodec.citationsOf(raw)

internal fun parseCitationsWithDiagnostics(raw: Any?) = ReviewFindingFieldCodec.decodeCitations(raw)

internal fun intValue(raw: Any?): Int? =
  when (raw) {
    is Int -> raw
    is Long -> raw.toInt()
    is String -> raw.toIntOrNull()
    else -> null
  }

internal fun launchFailureReason(facts: AgentRunLaunchFacts): String? =
  when (val termination = facts.termination) {
    AgentRunTermination.TimedOut -> "agent timed out"
    AgentRunTermination.SpawnFailed -> "agent process failed to spawn"
    AgentRunTermination.Interrupted -> "agent was interrupted"
    is AgentRunTermination.Exited ->
      when {
        termination.code != 0 -> "agent exited with status ${termination.code}"
        facts.stdoutTruncated -> "agent output exceeded the retention cap before completion"
        else -> null
      }
  }

internal fun unresolved(
  finding: ParallelReviewMergedFinding,
  recordedAt: String,
  reason: String,
): ReviewFindingVerdict =
  ReviewFindingVerdict(
    stage = ReviewStage.VERIFICATION,
    findingRef = finding.fNumber,
    claimVerdict = ReviewClaimVerdict.UNRESOLVED,
    recordedAt = recordedAt,
    rejectionReason = reason,
  )
