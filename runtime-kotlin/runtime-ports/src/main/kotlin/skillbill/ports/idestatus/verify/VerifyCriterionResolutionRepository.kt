package skillbill.ports.idestatus.verify

import skillbill.ports.idestatus.model.VerifyCriterionResolution

/**
 * Operator resolutions of acceptance criteria that a finished verify run graded PARTIAL or FAIL.
 *
 * A completed verify run is immutable, so a criterion met after the run is recorded here and shown over the
 * run's grade. One resolution exists per `(workflowId, criterionNumber)`; recording again replaces it.
 */
interface VerifyCriterionResolutionRepository {
  fun record(
    workflowId: String,
    resolution: VerifyCriterionResolution,
  )

  fun resolutions(workflowId: String): List<VerifyCriterionResolution>
}
