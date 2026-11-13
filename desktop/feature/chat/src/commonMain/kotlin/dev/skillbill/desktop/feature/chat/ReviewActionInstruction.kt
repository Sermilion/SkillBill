package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ReviewFindingsAction
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails
import dev.skillbill.desktop.core.domain.model.WorkItem

internal fun reviewActionInstruction(item: WorkItem, action: ReviewFindingsAction): String? {
  if (!item.standaloneExecution || item.control.phaseId != "review") return null
  val invocation = item.control.invocationId ?: return null
  val database = item.control.databaseFile?.path ?: return null
  val cli = "sona --db ${shellArgument(database)}"
  val task = when (action) {
    ReviewFindingsAction.Fix ->
      "Fix the open findings in this review against the current worktree. Preserve unrelated edits. " +
        "Skip findings already resolved. Check each repair before recording it."
    ReviewFindingsAction.Verify ->
      "Verify this review's findings against the current worktree. Do not edit source files or implement repairs. " +
        "Record only fixes supported by current code and relevant checks. Leave unresolved findings open."
  }
  val kind = action.name.lowercase()
  return "$task\n" +
    "Target review invocation $invocation in ${item.checkoutRoot}. " +
    "First run $cli prepare-review --invocation-id ${shellArgument(invocation)} --format json. " +
    "Stop if preparation fails. Prefix every command below with $cli. Use the returned review_run_id. " +
    "Before inspecting or editing findings, run start-review-action --run-id <review_run_id> --action $kind --format json. " +
    "Retain its action_id. Stop if it refuses because all findings are fixed. " +
    "Run triage --run-id <review_run_id> --list --format json for exact finding IDs. " +
    "Record each confirmed fix with record-feedback --run-id <review_run_id> --event fix_applied " +
    "--finding <finding_id> --note <evidence> --format json. " +
    "At the end run finish-review-action --run-id <review_run_id> --action-id <action_id> --format json. " +
    "Pass --failed if you could not finish the requested work. This records activity using stored statuses, " +
    "including unresolved findings. Do not invent IDs, create a new review, commit, push or relaunch it. " +
    "Do not mark rejected, declined or false-positive findings fixed. " +
    "Report which findings changed status, which remain open, and any checks you could not run."

}

internal fun verifyRecordInstruction(item: WorkItem, run: VerifyRunDetails): String? {
  if (run.completenessAudit == null && run.reviewFindings?.reviewRunId == null) return null
  val database = item.control.databaseFile?.path ?: return null
  val cli = "sona --db ${shellArgument(database)}"
  return buildString {
    append("This verify run is finished and immutable; record work done after it beside it. Record only what current code ")
    append("and relevant checks support, with the evidence as the note.")
    run.reviewFindings?.reviewRunId?.let { runId ->
      append(" A fixed review finding: $cli record-feedback --run-id ${shellArgument(runId)} --event fix_applied ")
      append("--finding <finding ref> --note <evidence> --format json.")
    }
    if (run.completenessAudit != null) {
      append(" A PARTIAL or FAIL criterion that is now met: $cli verify-workflow resolve-criterion ")
      append("${shellArgument(run.workflowId)} --criterion <number> --evidence <evidence> --format json.")
    }
    append(" A new verify run re-audits everything.")
  }
}

internal fun shellArgument(value: String): String = "'" + value.replace("'", "'\\''") + "'"
