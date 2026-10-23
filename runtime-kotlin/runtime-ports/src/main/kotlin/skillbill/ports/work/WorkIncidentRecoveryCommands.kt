package skillbill.ports.work

import skillbill.ports.work.model.WorkIncidentRecoveryOutcome
import skillbill.ports.work.model.WorkRecoveryPreviewResult
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentPrerequisiteKind

interface WorkIncidentRecoveryCommands {
  fun preview(
    workId: String,
    incidentToken: String,
    confirmedPrerequisites: Set<BlockIncidentPrerequisiteKind>,
  ): WorkRecoveryPreviewResult

  fun recover(
    workId: String,
    incidentToken: String,
    incidentRevision: Long,
    confirmedPrerequisites: Set<BlockIncidentPrerequisiteKind>,
    agentId: String?,
  ): WorkIncidentRecoveryOutcome
}
