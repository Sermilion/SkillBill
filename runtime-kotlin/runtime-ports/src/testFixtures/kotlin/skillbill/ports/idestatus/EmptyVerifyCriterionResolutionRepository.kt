package skillbill.ports.idestatus

import skillbill.ports.idestatus.model.VerifyCriterionResolution
import skillbill.ports.idestatus.verify.VerifyCriterionResolutionRepository

object EmptyVerifyCriterionResolutionRepository : VerifyCriterionResolutionRepository {
  override fun record(
    workflowId: String,
    resolution: VerifyCriterionResolution,
  ) = Unit

  override fun resolutions(workflowId: String): List<VerifyCriterionResolution> = emptyList()
}
