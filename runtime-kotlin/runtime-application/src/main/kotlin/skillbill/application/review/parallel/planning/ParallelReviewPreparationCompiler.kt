package skillbill.application.review.parallel.planning

import skillbill.application.review.model.ReviewPrelaunchExpansion
import skillbill.application.review.model.ReviewPreparationRequest
import skillbill.application.review.model.ReviewPreparationResult
import skillbill.application.review.model.ReviewRubricProjection
import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.application.review.model.ReviewWorkerKind
import skillbill.application.review.preparation.ReviewPreparationService
import skillbill.application.review.preparation.deriveSpecialistBudget
import skillbill.application.review.preparation.model.ReviewLaneSelection
import skillbill.application.review.preparation.model.ReviewPreparationFacts
import skillbill.application.review.preparation.model.ReviewScopeFacts
import skillbill.application.review.preparation.model.ReviewStackRoutingFacts
import skillbill.application.reviewevidence.ResolvedCommitSequence
import skillbill.application.reviewevidence.model.ReviewDiffEvidence
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.review.ReviewContextEnvelopeValidator
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.review.context.model.accounting.ReviewContextBudgetPolicy
import skillbill.review.context.model.commit.ReviewCommitLaneRoutingMatrix
import skillbill.review.context.model.commit.ReviewLaneDecision
import skillbill.review.context.model.execution.SpecIntentAbsenceReason
import skillbill.review.context.model.execution.SpecIntentResolution
import skillbill.review.context.model.hunk.ReviewBaselineUntrackedPolicy
import skillbill.review.context.model.hunk.ReviewChangedHunk
import skillbill.review.context.model.hunk.ReviewLearningsReference
import skillbill.review.context.model.hunk.ReviewRevision
import skillbill.review.plan.ReviewCommitLaneRoutingPolicy
import skillbill.review.plan.model.ReviewLaunchLane
import skillbill.review.plan.model.ReviewRoutedLane
import skillbill.text.RECORD_FIELD_SEPARATOR
import java.nio.file.Path
import java.security.MessageDigest

internal object ParallelReviewPreparationCompiler {
  internal fun compile(
    input: ParallelReviewPreparationInput,
    budget: ReviewContextBudgetPolicy,
    envelopeValidator: ReviewContextEnvelopeValidator,
    specialistContract: String,
    hunkLocatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort? = null,
  ): List<ReviewSpecialistLaunchRequest> {
    val hunks = input.commitSequence.units.flatMap { it.hunks }
    val candidates = specialistRoutes(input)
    val routingMatrix =
      ReviewCommitLaneRoutingPolicy.route(
        input.commitSequence.units,
        candidates.map { ReviewRoutedLane(it.lane, it.descriptor) },
      )
    val routes = narrowToFocusedCommits(input, candidates, routingMatrix)
    val decisions =
      routes.map { route ->
        ReviewLaneDecision(
          route.lane,
          true,
          "selected non-empty ${route.rubric.area ?: "generic"} specialist lane",
          listOf("parallel-review", route.rubric.area ?: "generic"),
          route.ownedPaths,
          orderIndex = route.descriptor.orderIndex,
          required = route.descriptor.required,
          originLayerChains = route.originLayerChains,
          owningPack = route.descriptor.packSlug,
          specialistSkillName = route.descriptor.skillName,
          addOns = route.descriptor.addOns,
        )
      }
    val revisionId =
      digest(
        "${input.baseRevision}$RECORD_FIELD_SEPARATOR${input.headRevision}$RECORD_FIELD_SEPARATOR${input.diff}",
      )
    val selection = ReviewLaneSelection(decisions, routingMatrix)
    val preparation =
      prepareReview(
        PrepareReviewCompileInput(
          input = input,
          hunks = hunks,
          routes = routes,
          selection = selection,
          revisionId = revisionId,
          envelopeValidator = envelopeValidator,
          hunkLocatorReader = hunkLocatorReader,
        ),
      )
    return launchRequests(input, preparation, routes, budget, specialistContract)
  }

  private fun narrowToFocusedCommits(
    input: ParallelReviewPreparationInput,
    candidates: List<SpecialistRoute>,
    routingMatrix: ReviewCommitLaneRoutingMatrix,
  ): List<SpecialistRoute> {
    val routes =
      candidates.mapNotNull { candidate ->
        val focused = routingMatrix.focusedCommits(candidate.lane).toSet()
        val owned = candidate.ownedPaths.toSet()
        val ownedPaths =
          input.commitSequence.units
            .filter { it.commitSha in focused }
            .flatMap { unit -> unit.hunks.map { it.path }.filter { it in owned } }
            .distinct()
            .sorted()
        candidate.copy(ownedPaths = ownedPaths).takeIf { ownedPaths.isNotEmpty() }
      }
    require(routes.isNotEmpty()) { "Commit/lane routing focused no specialist lane onto any commit." }
    val surviving = routes.map { it.lane }.toSet()
    val droppedRequired = candidates.filter { it.descriptor.required && it.lane !in surviving }.map { it.lane }
    require(droppedRequired.isEmpty()) {
      "Sparse routing dropped required baseline lanes: ${droppedRequired.sorted()}."
    }
    return routes
  }

  private fun specialistRoutes(input: ParallelReviewPreparationInput): List<SpecialistRoute> {
    val selectedRubrics =
      input.lanes.mapNotNull { planned ->
        val authoritativePaths = planned.descriptor.ownedPaths
        planned.takeIf { authoritativePaths.isNotEmpty() }?.let { SelectedRubric(it, authoritativePaths) }
      }
    require(selectedRubrics.isNotEmpty()) { "Review routing selected no non-empty specialist lane." }
    return input.agents.flatMap { agentId ->
      selectedRubrics.map { selected ->
        SpecialistRoute(
          "$agentId:${selected.planned.descriptor.skillName}",
          agentId,
          selected.planned.rubric,
          selected.planned.descriptor,
          selected.planned.originLayerChains,
          selected.ownedPaths,
          selected.planned.workerKind,
        )
      }
    }
  }

  private fun prepareReview(compileInput: PrepareReviewCompileInput) =
    ReviewPreparationService(
      reviewPreparationFacts(compileInput.input, compileInput.hunks, compileInput.selection),
      compileInput.envelopeValidator,
      compileInput.hunkLocatorReader,
    ).prepare(
      ReviewPreparationRequest(
        reviewId = compileInput.input.reviewRunId ?: "code-review-${compileInput.revisionId}",
        reviewRevision = ReviewRevision(compileInput.revisionId, 1),
        criteriaReferences = criteriaReferences(compileInput.routes, compileInput.input.specIntentResolution),
        baselineUntrackedPolicy = compileInput.input.baselineUntrackedPolicy,
        specIntentProjection =
          (compileInput.input.specIntentResolution as? SpecIntentResolution.Resolved)
            ?.projection,
        evidenceStorePath = compileInput.input.evidenceStorePath,
        repoRoot = compileInput.input.repoRoot,
      ),
    )

  private fun reviewPreparationFacts(
    input: ParallelReviewPreparationInput,
    hunks: List<ReviewChangedHunk>,
    selection: ReviewLaneSelection,
  ): ReviewPreparationFacts {
    val decisions = selection.decisions
    return ReviewPreparationFacts(
      scope =
        ReviewScopeFacts(
          input.repositoryEnclosingRootPort.repositoryIdentity(input.repoRoot),
          input.baseRevision,
          input.headRevision,
          "authoritative supplied parallel-review diff",
          hunks,
          input.commitSequence.units,
          input.commitSequence.coverageFact,
        ),
      stackRouting =
        ReviewStackRoutingFacts(
          input.stack,
          input.routedPacks.joinToString("+"),
          decisions.flatMap { it.addOns }.distinct(),
          decisions.flatMap { it.originLayerChains }.flatten().distinct(),
        ),
      laneSelection = selection,
      learningsReferences = input.learningsReferences,
    )
  }

  private fun launchRequests(
    input: ParallelReviewPreparationInput,
    preparation: ReviewPreparationResult,
    routes: List<SpecialistRoute>,
    budget: ReviewContextBudgetPolicy,
    specialistContract: String,
  ): List<ReviewSpecialistLaunchRequest> {
    val routesByLane =
      routes.associateBy(SpecialistRoute::lane).also {
        require(it.size == routes.size) { "Prepared specialist routes contain duplicate lane keys." }
      }
    val validExpansionSelectors =
      routes.flatMap { route ->
        listOf(route.lane, route.lane.substringAfter(':'))
      }.toSet() + PARALLEL_REVIEW_SELECTOR
    input.prelaunchExpansions.forEach { expansion ->
      require(expansion.lane in validExpansionSelectors) {
        "Prelaunch expansion selector '${expansion.lane}' does not match '$PARALLEL_REVIEW_SELECTOR', " +
          "a prepared assignment lane, or a prepared specialist skill."
      }
    }
    return preparation.assignments.map { assignment ->
      val route =
        requireNotNull(routesByLane[assignment.lane]) {
          "Prepared assignment '${assignment.lane}' has no selected specialist route."
        }
      require(assignment.laneDecision.specialistSkillName == route.rubric.rubricId) {
        "Prepared assignment '${assignment.lane}' drifted from rubric '${route.rubric.rubricId}'."
      }
      require(assignment.assignedPaths == route.ownedPaths) {
        "Prepared assignment '${assignment.lane}' drifted from resolved ownership."
      }
      ReviewSpecialistLaunchRequest(
        packet = preparation.packet,
        assignment = assignment,
        specialistContract = specialistContract,
        rubrics = listOf(route.rubric),
        brokerId = "review-evidence-${assignment.digest}",
        budget = deriveSpecialistBudget(budget, assignment, preparation.packet),
        agentId = route.agentId,
        workerKind = route.workerKind,
        logicalWorkerName =
          route.descriptor.skillName.takeIf {
            route.workerKind == ReviewWorkerKind.PROVIDER_NATIVE
          },
        repoRoot = input.repoRoot,
        prelaunchExpansions =
          input.prelaunchExpansions
            .filter {
              it.lane == PARALLEL_REVIEW_SELECTOR ||
                it.lane == assignment.lane ||
                it.lane == assignment.lane.substringAfter(':')
            }
            .map { ReviewExpansionAuthorizationRequest(assignment.lane, it.path, it.reachabilityReason) },
      )
    }.also { launches ->
      val selectedLaneCount = preparation.packet.selectedLanes.size
      require(launches.size == selectedLaneCount) {
        "Review must launch exactly one specialist worker per selected lane ($selectedLaneCount); " +
          "synthesized ${launches.size} launch(es). Commit or segment count must not multiply worker launches."
      }
    }
  }

  private fun digest(value: String): String =
    MessageDigest.getInstance("SHA-256")
      .digest(value.replace("\r\n", "\n").toByteArray())
      .joinToString("") { "%02x".format(it) }

  private const val PARALLEL_REVIEW_SELECTOR = "parallel-code-review"
}

private data class PrepareReviewCompileInput(
  val input: ParallelReviewPreparationInput,
  val hunks: List<ReviewChangedHunk>,
  val routes: List<SpecialistRoute>,
  val selection: ReviewLaneSelection,
  val revisionId: String,
  val envelopeValidator: ReviewContextEnvelopeValidator,
  val hunkLocatorReader: FeatureTaskRuntimeSharedEvidenceLocatorReadPort?,
)

internal fun criteriaReferences(
  routes: List<SpecialistRoute>,
  resolution: SpecIntentResolution,
): Map<String, List<String>> {
  val criteria =
    when (resolution) {
      is SpecIntentResolution.Resolved ->
        resolution.projection.acceptanceCriteria
      is SpecIntentResolution.None -> emptyList()
    }
  return routes.associate { it.lane to criteria }
}

private data class SelectedRubric(val planned: PlannedReviewRubric, val ownedPaths: List<String>)

internal data class SpecialistRoute(
  val lane: String,
  val agentId: String,
  val rubric: ReviewRubricProjection,
  val descriptor: ReviewLaunchLane,
  val originLayerChains: List<List<String>>,
  val ownedPaths: List<String>,
  val workerKind: ReviewWorkerKind,
)

internal data class PlannedReviewRubric(
  val descriptor: ReviewLaunchLane,
  val rubric: ReviewRubricProjection,
  val originLayerChains: List<List<String>> = listOf(descriptor.originLayerChain),
  val workerKind: ReviewWorkerKind = ReviewWorkerKind.PROVIDER_NATIVE,
)

internal data class ParallelReviewPreparationInput(
  val diff: String,
  val evidence: ReviewDiffEvidence,
  val commitSequence: ResolvedCommitSequence,
  val stack: String?,
  val agents: List<String>,
  val repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  val repoRoot: Path,
  val routedPacks: List<String>,
  val lanes: List<PlannedReviewRubric>,
  val reviewRunId: String? = null,
  val baseRevision: String,
  val headRevision: String,
  val prelaunchExpansions: List<ReviewPrelaunchExpansion> = emptyList(),
  val baselineUntrackedPolicy: ReviewBaselineUntrackedPolicy = ReviewBaselineUntrackedPolicy.EMPTY,
  val specIntentResolution: SpecIntentResolution =
    SpecIntentResolution.None(
      SpecIntentAbsenceReason.NOT_APPLICABLE_SCOPE,
    ),
  val evidenceStorePath: String? = null,
  val learningsReferences: List<ReviewLearningsReference> = emptyList(),
)
