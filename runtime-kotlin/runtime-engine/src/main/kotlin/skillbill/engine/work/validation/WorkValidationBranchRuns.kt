package skillbill.engine.work.validation

import skillbill.ports.idestatus.model.StandalonePhaseStatusRecord
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition

internal fun WorkDetailItem.withValidationBranchRuns(
  standalone: StandalonePhaseStatusRecord?,
  unit: UnitOfWork,
): WorkDetailItem =
  if (standalone?.phaseId != SkeletonDefinition.VALIDATION.id) {
    this
  } else {
    copy(
      validationBranchRuns = unit.standalonePhaseStatuses.readValidationBranchRuns(standalone.invocationId),
      selectedValidationResult = standalone.terminalResult,
    )
  }
