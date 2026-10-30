package skillbill.application.review.parallel.planning

import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.ReviewSpecialistLaunchRequest
import skillbill.application.runtimepersistence.RuntimeOwnedPersistenceBoundary
import skillbill.contracts.review.REVIEW_CONTEXT_CONTRACT_VERSION
import skillbill.review.context.model.execution.SpecIntentResolution
import skillbill.review.context.model.packet.ReviewRunLaneSegmentAccountingJson
import skillbill.review.model.ReviewLaneResolutionState
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.review.model.ReviewRunLane
import skillbill.review.model.ReviewSpecProjectionReference
import skillbill.review.model.ReviewStage
import skillbill.review.model.ReviewStageBoundary
import skillbill.review.model.ReviewStageReached
import java.time.Clock

@Inject
class ParallelCodeReviewRunnerLanePlanRecording(
  private val runtimeOwnedPersistence: RuntimeOwnedPersistenceBoundary,
  private val clock: Clock,
) {
  internal fun recordSpecIntent(
    reviewRunId: String?,
    resolution: SpecIntentResolution,
  ) {
    if (reviewRunId == null) return
    val reference =
      when (resolution) {
        is SpecIntentResolution.Resolved ->
          ReviewSpecProjectionReference(
            specPath = resolution.projection.provenance.specPath,
            contentDigest = resolution.projection.provenance.contentDigest,
          )
        is SpecIntentResolution.None -> ReviewSpecProjectionReference(absenceReason = resolution.reason.wireValue)
      }
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.recordSpecIntent",
      expected = "runtime-owned review spec projection reference",
    ) { unitOfWork ->
      unitOfWork.reviews.recordSpecProjectionReference(reviewRunId, reference)
      if (resolution is SpecIntentResolution.None) {
        unitOfWork.reviews.recordStageBoundary(
          reviewRunId,
          ReviewStageBoundary(
            stage = ReviewStage.ADJUDICATION,
            reached = ReviewStageReached.NOT_REACHED,
            recordedAt = clock.instant().toString(),
            contractVersion = REVIEW_CONTEXT_CONTRACT_VERSION,
          ),
        )
      }
    }
  }

  internal fun selectLaunchesForResume(
    reviewRunId: String?,
    launches: List<ReviewSpecialistLaunchRequest>,
    existing: List<ReviewRunLane> = recordedLanes(reviewRunId),
  ): List<ReviewSpecialistLaunchRequest> {
    if (reviewRunId == null || launches.isEmpty() || existing.isEmpty()) return launches
    val completeNames =
      existing
        .filter { it.reviewDisposition == ReviewLaneReviewDisposition.COMPLETE }
        .map { it.laneSkillName }
        .toSet()
    return launches.filterNot { launch ->
      launch.assignment.laneDecision.specialistSkillName in completeNames
    }
  }

  internal fun recordPlannedLanes(
    reviewRunId: String?,
    plannedRubrics: List<PlannedReviewRubric>,
    launches: List<ReviewSpecialistLaunchRequest>,
    existing: List<ReviewRunLane> = recordedLanes(reviewRunId),
  ) {
    if (reviewRunId == null || launches.isEmpty()) return
    val preservedComplete =
      existing.filter {
        it.reviewDisposition == ReviewLaneReviewDisposition.COMPLETE
      }
    val completionBySkill =
      launches.associate { launch ->
        requireNotNull(launch.assignment.laneDecision.specialistSkillName) to
          parallelCodeReviewGovernedLaunchFor(launch).completionState
      }
    val relaunchNames = completionBySkill.keys
    val pending =
      plannedRubrics
        .filter { it.descriptor.skillName in relaunchNames }
        .map { planned ->
          val completion = completionBySkill.getValue(planned.descriptor.skillName)
          ReviewRunLane(
            laneSkillName = planned.descriptor.skillName,
            packSlug = planned.descriptor.packSlug,
            area = planned.descriptor.area,
            depth = planned.descriptor.depth,
            required = planned.descriptor.required,
            orderIndex = planned.descriptor.orderIndex,
            originLayerChain = planned.descriptor.originLayerChain,
            resolutionState = ReviewLaneResolutionState.RESOLVED,
            reviewDisposition = ReviewLaneReviewDisposition.INCOMPLETE,
            bundleCompositionDigest = completion.bundleCompositionDigest,
            segmentAccountingJson = ReviewRunLaneSegmentAccountingJson.encode(completion.segments),
            unreviewedSegmentIds = completion.unreviewedSegmentIds,
            budgetDimension = completion.budgetDimension,
          )
        }
    val merged = preservedComplete.filter { it.laneSkillName !in relaunchNames } + pending
    runtimeOwnedPersistence.requiredWrite(
      seam = "ParallelCodeReviewRunner.recordPlannedLanes.write",
      expected = "runtime-owned review lane plan",
    ) { unitOfWork -> unitOfWork.reviews.replaceReviewRunLanes(reviewRunId, merged) }
  }

  internal fun recordedLanes(reviewRunId: String?): List<ReviewRunLane> {
    if (reviewRunId == null) return emptyList()
    return runtimeOwnedPersistence.requiredRead(
      seam = "ParallelCodeReviewRunner.recordedLanes",
      expected = "runtime-owned review lane dispositions",
    ) { unitOfWork -> unitOfWork.reviews.fetchReviewRunLanes(reviewRunId) }
  }
}
