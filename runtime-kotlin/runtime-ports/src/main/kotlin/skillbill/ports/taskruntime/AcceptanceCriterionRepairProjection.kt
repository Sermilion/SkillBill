package skillbill.ports.taskruntime

import skillbill.ports.taskruntime.model.AcceptanceCriterionRepairProjectionRequest

interface AcceptanceCriterionRepairProjection {
  fun write(request: AcceptanceCriterionRepairProjectionRequest)
}
