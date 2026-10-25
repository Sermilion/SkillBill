package skillbill.engine.featuretask.persist.acceptance

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairMutation
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairWriteResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionVerdict

internal object AcceptanceCriterionTraceDiagnostics {
  const val MAX_REASON = 200

  fun record(
    diagnostics: RuntimeDiagnostics,
    workflowId: String,
    mutation: AcceptanceCriterionRepairMutation,
    committed: AcceptanceCriterionRepairWriteResult.Committed,
  ) {
    if (committed.replayed) return
    committed.newAnyChangeRechecks.forEach { ref ->
      warn(diagnostics, workflowId, Warning("recheck_no_path", "cited_production_path", "any_change", "criterion=$ref"))
    }
    committed.droppedRechecks.forEach { ref ->
      warn(diagnostics, workflowId, Warning("recheck_dropped", "recheck_all_touched", "capped", "criterion=$ref"))
    }
    if (mutation is AcceptanceCriterionRepairMutation.InteractionTrace) {
      mutation.settlement.entries
        .filter { it.verdict == AcceptanceCriterionInteractionVerdict.UNKNOWN }
        .forEach { entry ->
          val reason = entry.traceRef.replace(WHITESPACE, " ").trim().take(MAX_REASON)
          val detail = "scenario=${entry.scenarioRef} reason=$reason"
          warn(diagnostics, workflowId, Warning("unknown_scenario", "traced_verdict", "unknown", detail))
        }
    }
  }

  fun warn(
    diagnostics: RuntimeDiagnostics,
    workflowId: String,
    warning: Warning,
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=interaction_trace workflow=${workflowId.take(MAX_DIAGNOSTIC_ID)} record_kind=${warning.recordKind} " +
        "value_expected=${warning.expected} value_used=${warning.used}" +
        if (warning.detail.isEmpty()) "" else " ${warning.detail}",
    )
  }

  data class Warning(
    val recordKind: String,
    val expected: String,
    val used: String,
    val detail: String = "",
  )

  private val WHITESPACE = Regex("""\s+""")
  private const val MAX_DIAGNOSTIC_ID = 128
}
