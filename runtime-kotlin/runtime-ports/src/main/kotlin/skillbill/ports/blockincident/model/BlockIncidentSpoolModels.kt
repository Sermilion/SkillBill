package skillbill.ports.blockincident.model

import skillbill.error.blockincident.BlockIncidentFailureCode
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import java.time.Instant

data class BlockIncidentSpoolRecord(
  val occurrenceToken: String,
  val alias: BlockIncidentAlias,
  val workflowId: String?,
  val parentWorkflowId: String?,
  val phaseId: String?,
  val invocationId: String?,
  val attempt: Int?,
  val origin: BlockIncidentOrigin,
  val reason: String,
  val failureCode: String?,
  val failureDisposition: String?,
  val throwableType: String?,
  val exitCode: Int?,
  val observedAt: Instant,
  val captureFailureCode: BlockIncidentFailureCode,
  val additionalAliases: List<BlockIncidentAlias> = emptyList(),
)

data class BlockIncidentSpoolEntry(
  val recordId: String,
  val record: BlockIncidentSpoolRecord,
)

sealed interface BlockIncidentSpoolAppend {
  data class Appended(val recordId: String) : BlockIncidentSpoolAppend

  data object DroppedFull : BlockIncidentSpoolAppend

  data class Failed(val message: String) : BlockIncidentSpoolAppend
}
