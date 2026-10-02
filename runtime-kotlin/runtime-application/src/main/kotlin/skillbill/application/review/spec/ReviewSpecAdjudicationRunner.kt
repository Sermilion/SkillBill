package skillbill.application.review.spec

import skillbill.application.review.model.ReviewSpecAdjudicationOutcome
import skillbill.application.review.model.ReviewSpecAdjudicationRunRequest
import skillbill.application.review.model.boundedReviewLane
import skillbill.application.review.packet.toAdjudicationLaunchEnvelope
import skillbill.application.review.preparation.ReviewPreparationService
import skillbill.application.review.verification.appendPromptSuffix
import skillbill.application.review.verification.citedRegionOf
import skillbill.application.review.verification.launchFailureReason
import skillbill.application.review.verification.parseCitationsWithDiagnostics
import skillbill.application.review.verification.parseJsonObject
import skillbill.contracts.JsonCodec
import skillbill.contracts.review.ReviewFindingPayloadKeys
import skillbill.ports.agentrun.model.AgentRunLaunchDenied
import skillbill.ports.agentrun.model.AgentRunLaunchFacts
import skillbill.ports.agentrun.model.SkillRunRequest
import skillbill.ports.agentrun.model.UnsupportedAgentRunLaunch
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.review.ReviewContextEnvelopeValidator
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.execution.SpecIntentProjection
import skillbill.review.context.model.hunk.ReviewDependencyAllowlist
import skillbill.review.context.model.launch.GovernedReviewAdjudicationLaunch
import skillbill.review.context.model.launch.ReviewSpecAdjudicationAdmission
import skillbill.review.context.model.launch.ReviewSpecAdjudicationWorkerResult
import skillbill.review.context.model.packet.ReviewContextPacket
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitationDiagnosticWithFinding
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewStage
import java.nio.file.Path
import java.time.Clock
import kotlin.time.Duration

class ReviewSpecAdjudicationRunner(
  private val launcher: GoalRunnerSubtaskLauncher,
  private val envelopeValidator: ReviewContextEnvelopeValidator,
  private val clock: Clock,
) {
  internal fun run(request: ReviewSpecAdjudicationRunRequest): ReviewSpecAdjudicationOutcome {
    if (request.projection == null || request.packet == null) {
      return ReviewSpecAdjudicationOutcome(verdicts = emptyList(), skipReason = SPEC_CONTEXT_NONE)
    }
    val survivorState = resolveAdjudicationSurvivors(request)
    survivorState.earlyOutcome?.let { return it }
    val recordedAt = clock.instant().toString()
    val citationDiagnostics = mutableListOf<ReviewFindingCitationDiagnosticWithFinding>()
    val launched =
      survivorState.pending.map { finding ->
        when (
          val job =
            prepareLaunch(
              AdjudicationPrepareInput(
                packet = request.packet,
                finding = finding,
                stage1 = survivorState.stage1ByRef.getValue(finding.fNumber),
                projection = request.projection,
                launch =
                  AdjudicationPrepareLaunch(
                    budget = request.launch.budget,
                    brokerId = request.launch.brokerId,
                    recordedAt = recordedAt,
                  ),
              ),
            )
        ) {
          is PreparedAdjudicationRejected -> job.verdict
          is PreparedAdjudicationReady ->
            launchOne(
              job,
              AdjudicationLaunchEnv(
                repoRoot = request.launch.repoRoot,
                timeout = request.launch.timeout,
                modelOverride = request.launch.modelOverride,
                promptSuffix = request.launch.promptSuffix,
                laneProgressIdleTimeout = request.launch.laneProgressIdleTimeout,
              ),
              recordedAt,
            ).also { outcome -> citationDiagnostics += outcome.citationDiagnostics }
              .verdict
        }
      }
    return ReviewSpecAdjudicationOutcome(
      verdicts = survivorState.survivors.mapNotNull { survivorState.durableAdj[it.fNumber] } + launched,
      citationDiagnostics = citationDiagnostics,
    )
  }

  private fun resolveAdjudicationSurvivors(request: ReviewSpecAdjudicationRunRequest): AdjudicationSurvivorResolution {
    val stage1ByRef =
      request.existingVerdicts
        .filter { it.stage == ReviewStage.VERIFICATION }
        .associateBy { it.findingRef }
    val durableAdj =
      request.existingVerdicts
        .filter { it.stage == ReviewStage.ADJUDICATION }
        .associateBy { it.findingRef }
    val survivors =
      request.findings.sortedBy { it.fNumber }.filter { finding ->
        val stage1 = stage1ByRef[finding.fNumber] ?: return@filter false
        stage1.claimVerdict != ReviewClaimVerdict.REFUTED
      }
    if (survivors.isEmpty()) {
      return AdjudicationSurvivorResolution(
        survivors = emptyList(),
        durableAdj = durableAdj,
        stage1ByRef = stage1ByRef,
        pending = emptyList(),
        earlyOutcome =
          ReviewSpecAdjudicationOutcome(
            verdicts = durableAdj.values.toList(),
            skipReason = "no confirmed or unresolved findings to adjudicate",
          ),
      )
    }
    val pending = survivors.filterNot { it.fNumber in durableAdj.keys }
    if (pending.isEmpty()) {
      return AdjudicationSurvivorResolution(
        survivors = survivors,
        durableAdj = durableAdj,
        stage1ByRef = stage1ByRef,
        pending = emptyList(),
        earlyOutcome =
          ReviewSpecAdjudicationOutcome(
            verdicts = survivors.mapNotNull { durableAdj[it.fNumber] },
            skipReason = "every surviving finding already holds a durable adjudication verdict",
          ),
      )
    }
    return AdjudicationSurvivorResolution(
      survivors = survivors,
      durableAdj = durableAdj,
      stage1ByRef = stage1ByRef,
      pending = pending,
      earlyOutcome = null,
    )
  }

  private fun prepareLaunch(input: AdjudicationPrepareInput): PreparedAdjudication {
    val region =
      citedRegionOf(input.finding)
        ?: return PreparedAdjudicationRejected(
          ReviewFindingVerdict(
            stage = ReviewStage.ADJUDICATION,
            findingRef = input.finding.fNumber,
            claimVerdict = input.stage1.claimVerdict,
            scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
            recordedAt = input.launch.recordedAt,
            rejectionReason = "finding has no cited file:line region",
          ),
        )
    val launch =
      GovernedReviewAdjudicationLaunch(
        packet = input.packet,
        finding = input.finding,
        stage1Verdict = input.stage1,
        specIntentProjection = input.projection,
        citedRegion = region,
        evidenceSurfaceRules = ReviewPreparationService.adjudicationEvidenceSurfaceRules(),
        dependencyAllowlist = ReviewDependencyAllowlist(input.packet.dependencyAllowlist.normalized),
        brokerId = input.launch.brokerId,
        budget = input.launch.budget,
      )
    val envelope = launch.toAdjudicationLaunchEnvelope().asWireMap()
    val launchBytes = JsonCodec.mapToJsonString(envelope).toByteArray(Charsets.UTF_8).size.toLong()
    if (launchBytes > input.launch.budget.maxLaneLaunchBytes) {
      return PreparedAdjudicationRejected(
        ReviewFindingVerdict(
          stage = ReviewStage.ADJUDICATION,
          findingRef = input.finding.fNumber,
          claimVerdict = input.stage1.claimVerdict,
          scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
          recordedAt = input.launch.recordedAt,
          rejectionReason = "adjudication launch exceeded max_lane_launch_bytes",
        ),
      )
    }
    envelopeValidator.validate(envelope, "review adjudication launch for ${input.finding.fNumber}")
    return PreparedAdjudicationReady(input.finding, input.stage1, input.projection, launch)
  }

  private data class AdjudicationLaunchEnv(
    val repoRoot: Path,
    val timeout: Duration?,
    val modelOverride: String?,
    val promptSuffix: String,
    val laneProgressIdleTimeout: Duration,
  )

  private data class AdjudicationFindingOutcome(
    val verdict: ReviewFindingVerdict,
    val citationDiagnostics: List<ReviewFindingCitationDiagnosticWithFinding> = emptyList(),
  )

  private fun launchOne(
    job: PreparedAdjudicationReady,
    env: AdjudicationLaunchEnv,
    recordedAt: String,
  ): AdjudicationFindingOutcome {
    val prompt = appendPromptSuffix(adjudicationPrompt(job.launch), env.promptSuffix)
    val outcome =
      launcher.launch(
        GoalRunnerSubtaskLaunchRequest(
          invokedAgentId = job.launch.brokerId,
          configuredAgentOverrideId = null,
          skillRunRequest =
            SkillRunRequest(
              issueKey = ISSUE_KEY,
              repoRoot = env.repoRoot,
              timeout = env.timeout,
              promptOverride = prompt,
              modelOverride = env.modelOverride,
            ).boundedReviewLane(env.laneProgressIdleTimeout),
        ),
      )
    return when (outcome) {
      is UnsupportedAgentRunLaunch ->
        AdjudicationFindingOutcome(
          ReviewFindingVerdict(
            stage = ReviewStage.ADJUDICATION,
            findingRef = job.finding.fNumber,
            claimVerdict = job.stage1.claimVerdict,
            scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
            recordedAt = recordedAt,
            rejectionReason = "unsupported agent: ${outcome.reason}",
          ),
        )
      is AgentRunLaunchDenied -> error("Review adjudication never launches with a spawn authorization.")
      is AgentRunLaunchFacts -> fromLaunchFacts(job, outcome, recordedAt)
    }
  }

  private fun fromLaunchFacts(
    job: PreparedAdjudicationReady,
    facts: AgentRunLaunchFacts,
    recordedAt: String,
  ): AdjudicationFindingOutcome {
    launchFailureReason(facts)?.let { reason ->
      return AdjudicationFindingOutcome(
        ReviewFindingVerdict(
          stage = ReviewStage.ADJUDICATION,
          findingRef = job.finding.fNumber,
          claimVerdict = job.stage1.claimVerdict,
          scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
          recordedAt = recordedAt,
          rejectionReason = reason,
        ),
      )
    }
    val worker =
      parseAdjudicationWorkerResult(facts.stdout)
        ?: return AdjudicationFindingOutcome(
          ReviewFindingVerdict(
            stage = ReviewStage.ADJUDICATION,
            findingRef = job.finding.fNumber,
            claimVerdict = job.stage1.claimVerdict,
            scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
            recordedAt = recordedAt,
            rejectionReason = "unparseable adjudication output",
          ),
        )
    val diagnostics =
      worker.citationDiagnostics.map { diagnostic ->
        diagnostic.withFindingRef(job.finding.fNumber)
      }
    return AdjudicationFindingOutcome(
      verdict =
        ReviewSpecAdjudicationAdmission.admit(
          job.finding,
          job.stage1,
          job.projection,
          worker,
          recordedAt,
        ),
      citationDiagnostics = diagnostics,
    )
  }

  private fun adjudicationPrompt(launch: GovernedReviewAdjudicationLaunch): String =
    buildString {
      appendLine("Adjudicate exactly one review finding against the spec intent projection.")
      appendLine("Do not inspect sibling findings.")
      appendLine("Do not re-test whether the finding's claim is true; stage 1 settled that.")
      appendLine("Review is read-only: do not build, compile, or run tests.")
      appendLine("Evidence surface: ${launch.evidenceSurfaceRules}")
      appendLine(
        "Finding ${launch.finding.fNumber}: ${launch.finding.severity.displayName} | " +
          "${launch.finding.location} | ${launch.finding.description}",
      )
      appendLine("Stage 1 verdict: ${launch.stage1Verdict.claimVerdict.wireValue}")
      appendLine(
        "Cited region: ${launch.citedRegion.path}:${launch.citedRegion.startLine}-${launch.citedRegion.endLine}",
      )
      appendLine("Spec intent projection:")
      appendLine(JsonCodec.mapToJsonString(launch.specIntentProjection.toProjectionPayload()))
      appendLine(
        "Return a JSON object with exactly one scope_disposition " +
          "(in_scope|out_of_scope_preexisting|spec_deviation|spec_accepted_tradeoff), " +
          "cited_spec_element naming a constraint, non-goal, or deferred item present in the projection, " +
          "and citations as [{path, line}].",
      )
      appendLine(
        "Optional severity_adjustment is {direction: raise|lower, justification} using the same " +
          "structure for raise and lower; cite the justifying spec element for out_of_scope_preexisting, " +
          "spec_deviation, or any severity adjustment.",
      )
      appendLine("Do not change the finding text, severity, or location.")
    }

  companion object {
    const val ISSUE_KEY: String = "code-review-adjudication"
    const val SPEC_CONTEXT_NONE: String = "spec_context: none"
  }
}

private data class AdjudicationSurvivorResolution(
  val survivors: List<ParallelReviewMergedFinding>,
  val durableAdj: Map<String, ReviewFindingVerdict>,
  val stage1ByRef: Map<String, ReviewFindingVerdict>,
  val pending: List<ParallelReviewMergedFinding>,
  val earlyOutcome: ReviewSpecAdjudicationOutcome?,
)

private data class AdjudicationPrepareInput(
  val packet: ReviewContextPacket,
  val finding: ParallelReviewMergedFinding,
  val stage1: ReviewFindingVerdict,
  val projection: SpecIntentProjection,
  val launch: AdjudicationPrepareLaunch,
)

private data class AdjudicationPrepareLaunch(
  val budget: ReviewContextBudgetPolicy,
  val brokerId: String,
  val recordedAt: String,
)

private sealed class PreparedAdjudication

private data class PreparedAdjudicationReady(
  val finding: ParallelReviewMergedFinding,
  val stage1: ReviewFindingVerdict,
  val projection: SpecIntentProjection,
  val launch: GovernedReviewAdjudicationLaunch,
) : PreparedAdjudication()

private data class PreparedAdjudicationRejected(val verdict: ReviewFindingVerdict) : PreparedAdjudication()

internal fun parseAdjudicationWorkerResult(stdout: String): ReviewSpecAdjudicationWorkerResult? {
  val payload = parseJsonObject(stdout) ?: return null
  val finding = JsonCodec.anyToStringAnyMap(payload["finding"])
  val adjustment = JsonCodec.anyToStringAnyMap(payload[ReviewFindingPayloadKeys.SEVERITY_ADJUSTMENT])
  val dispositionField = stringList(payload[ReviewFindingPayloadKeys.SCOPE_DISPOSITION])
  val extraDispositions = stringList(payload["dispositions"])
  val primary = dispositionField.firstOrNull()
  val decodedCitations = parseCitationsWithDiagnostics(payload[ReviewFindingPayloadKeys.CITATIONS])
  return ReviewSpecAdjudicationWorkerResult(
    scopeDisposition = primary,
    dispositionValues = (dispositionField.drop(1) + extraDispositions),
    citedSpecElement = payload["cited_spec_element"] as? String,
    citations = decodedCitations.citations,
    citationDiagnostics = decodedCitations.diagnostics,
    severityAdjustmentDirection = adjustment?.get("direction") as? String,
    severityAdjustmentJustification = adjustment?.get("justification") as? String,
    adjustedSeverity = (adjustment?.get("adjusted_severity") as? String) ?: payload["adjusted_severity"] as? String,
    findingRef = (finding?.get("finding_ref") as? String) ?: payload["finding_ref"] as? String,
    severity = (finding?.get("severity") as? String) ?: payload["severity"] as? String,
    location = (finding?.get("location") as? String) ?: payload["location"] as? String,
    description = (finding?.get("description") as? String) ?: payload["description"] as? String,
  )
}

private fun stringList(raw: Any?): List<String> =
  when (raw) {
    is String -> listOf(raw)
    is List<*> -> raw.mapNotNull { it as? String }
    else -> emptyList()
  }
