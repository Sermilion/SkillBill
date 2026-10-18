package skillbill.engine.work.pagination

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.idestatus.model.WorkBoardDiagnostic
import skillbill.ports.idestatus.model.WorkBoardDiagnosticReason
import skillbill.ports.idestatus.model.WorkBoardItem

internal const val IDE_STATUS_BOARD_SEAM = "ide_status_board"
internal const val IDE_STATUS_STATUS_SEAM = "ide_status_status"

internal class AggregateSkipDiagnostics {
  var emitted: Int = 0
  var truncated: Boolean = false
}

private fun isIncompatibleAggregateParse(error: SkillBillRuntimeException): Boolean =
  error.code == WorkflowFailureCode.INVALID_WORK_LIST_ROW ||
    error.code == InstallFailureCode.INVALID_IDE_STATUS_SCHEMA

internal fun skipIncompatibleBoardItem(
  error: SkillBillRuntimeException,
  boardDiagnostics: MutableList<WorkBoardDiagnostic>,
  skipDiagnostics: AggregateSkipDiagnostics,
  runtimeDiagnostics: RuntimeDiagnostics,
  id: String,
): WorkBoardItem? {
  if (!isIncompatibleAggregateParse(error)) throw error
  boardDiagnostics +=
    WorkBoardDiagnostic(
      WorkBoardDiagnosticReason.INCOMPATIBLE_RECORD,
      repositoryIdentity = null,
      workflowId = id,
      invocationId = id.takeIf { error.code == InstallFailureCode.INVALID_IDE_STATUS_SCHEMA },
    )
  skipDiagnostics.emit(runtimeDiagnostics, IDE_STATUS_BOARD_SEAM, id, null, error)
  return null
}

internal fun AggregateSkipDiagnostics.emit(
  runtimeDiagnostics: RuntimeDiagnostics,
  seam: String,
  workflowId: String,
  invocationId: String?,
  error: SkillBillRuntimeException,
) {
  if (emitted < MAX_AGGREGATE_SKIP_DIAGNOSTICS) {
    runtimeDiagnostics.warning(
      "seam=$seam value_expected=readable_row value_used=skipped workflow_id=$workflowId " +
        "invocation_id=${invocationId ?: "<none>"} error=${error.message.orEmpty()}",
    )
    emitted++
  } else if (!truncated) {
    runtimeDiagnostics.warning(
      "seam=$seam value_expected=readable_row value_used=truncated " +
        "skipped_diagnostics_cap=$MAX_AGGREGATE_SKIP_DIAGNOSTICS",
    )
    truncated = true
  }
}

private const val MAX_AGGREGATE_SKIP_DIAGNOSTICS = 32
