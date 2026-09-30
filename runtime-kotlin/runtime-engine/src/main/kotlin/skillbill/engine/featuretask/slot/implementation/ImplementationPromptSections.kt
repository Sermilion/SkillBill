package skillbill.engine.featuretask.slot.implementation

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeImplementationContinuation
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.slot.jsonValueContent

internal object ImplementationPromptSections {
  const val IMPLEMENT_READONLY_REPAIR_SENTENCE: String =
    "Repair evidence is read-only repository facts: do not run builds or tests here."

  const val IMPLEMENT_DIRECTIVE: String =
    "Reconcile the repository to the intended state the upstream plan value describes: read and interpret the " +
      "stuffed executable_plan JSON, make the changes it specifies, treating any already-applied change as a " +
      "no-op. See the mutating-phase idempotency contract below. Emit produced_outputs with a non-blank value " +
      "string carrying the implementation_receipt JSON (same fields as before, stuffed inside value): " +
      "completed_task_ids, normalized changed_paths, tests_added, tests_updated, deviations, unresolved_items, " +
      "reconciliation_evidence, and reconciled_state. repository_checkpoint is runtime-owned: omit it and never " +
      "invent a fingerprint. Every receipt field is a bounded summary, not a transcript. " +
      IMPLEMENT_READONLY_REPAIR_SENTENCE

  const val SIMPLIFY_DIRECTIVE: String =
    "Within the current subtask scoped diff and owned paths only, apply high-confidence local simplifications: " +
      "dead feature-local code, one-use wrappers, unnecessary one-implementation abstractions, hand-rolled " +
      "standard-library behavior, or equivalent local shrinkage. Do not perform whole-repository discovery, edit " +
      "paths outside the boundary, run builds or tests, launch subagents, or delegate review. Never remove or " +
      "weaken governed contracts, typed errors, loud-fail seams, parity tests, validator-backed rules, security " +
      "measures, accessibility requirements, or behavior the spec explicitly requires. Treat edits already " +
      "present as a no-op under the mutating-phase idempotency contract. Emit produced_outputs with a non-blank " +
      "value string carrying the simplification_receipt JSON stuffed inside value: changed_paths, reductions " +
      "with outcome no_edit, addressed, or unresolved, unresolved_items, reconciliation_evidence, and " +
      "reconciled_state. repository_checkpoint is runtime-owned: omit it and never invent a fingerprint. " +
      IMPLEMENT_READONLY_REPAIR_SENTENCE

  private val SIMPLIFY_SCOPE_BOUNDARY: String =
    """
    ## Simplify scope boundary
    The subtask_scope projection and repository checkpoint list the only owned paths and diff context
    for this session. Work exclusively inside that boundary. Forbidden: repository-wide search for
    complexity, edits outside listed paths, `./gradlew` build or check, test execution,
    `skill-bill phase review`, review subagents, delegated review, or spawning other agents.
    """.trimIndent()

  private val IMPLEMENT_VALUE_CONTENT: String =
    jsonValueContent(
      innerJsonExample =
        "{ \"projection_kind\": \"implementation_receipt\",\n" +
          "  \"contract_version\": \"0.2\",\n" +
          "  \"completed_task_ids\": [\"task-1\"], \"changed_paths\": [\"path/Changed.kt\"],\n" +
          "  \"tests_added\": [], \"tests_updated\": [],\n" +
          "  \"tests_executed\": [],\n" +
          "  \"deviations\": [ { \"ref\": \"AC-001\", \"note\": \"<one-line what deviated and why>\" } ],\n" +
          "  \"unresolved_items\": [],\n" +
          "  \"reconciliation_evidence\": { \"reconciled\": true, \"evidence\": \"<tree at target>\" },\n" +
          "  \"reconciled_state\": { \"reconciled\": true, \"evidence\": \"<tree at target>\" } }\n",
      notes =
        "Upstream plan value is structured prose carrying the executable_plan JSON; read and interpret it. " +
          "repository_checkpoint is runtime-owned: omit it entirely. Never invent a fingerprint. " +
          "Compilation and test execution belong exclusively to the validate phase; tests_executed stays []. " +
          "changed_paths are repository-relative; deviations entries are objects { \"ref\", \"note\" }.",
    )

  private val SIMPLIFY_VALUE_CONTENT: String =
    jsonValueContent(
      innerJsonExample =
        "{ \"projection_kind\": \"simplification_receipt\",\n" +
          "  \"contract_version\": \"0.1\",\n" +
          "  \"changed_paths\": [\"path/Changed.kt\"],\n" +
          "  \"reductions\": [ { \"path\": \"path/Changed.kt\", \"outcome\": \"addressed\",\n" +
          "    \"note\": \"<one-line reduction>\" } ],\n" +
          "  \"unresolved_items\": [],\n" +
          "  \"reconciliation_evidence\": { \"reconciled\": true, \"evidence\": \"<tree at target>\" },\n" +
          "  \"reconciled_state\": { \"reconciled\": true, \"evidence\": \"<tree at target>\" } }\n",
      notes =
        "Outcome on each reduction is no_edit, addressed, or unresolved. repository_checkpoint is " +
          "runtime-owned: omit it entirely. Never invent a fingerprint. Do not run builds or tests here.",
    )

  fun implement(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective = IMPLEMENT_DIRECTIVE,
      testValueDiscipline = true,
      continuation = continuationFor(stepId, inputs, SegmentKind.IMPLEMENTATION),
      valueContent = IMPLEMENT_VALUE_CONTENT,
      schemaFailureCorrection = ::unreconciledReceipt,
    )

  fun simplify(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective = SIMPLIFY_DIRECTIVE,
      scopeBoundary = SIMPLIFY_SCOPE_BOUNDARY,
      continuation = continuationFor(stepId, inputs, SegmentKind.SIMPLIFICATION),
      valueContent = SIMPLIFY_VALUE_CONTENT,
      schemaFailureCorrection = ::unreconciledReceipt,
    )

  fun unreconciledReceipt(priorSchemaFailure: String): String {
    val namesReconciled =
      priorSchemaFailure.contains("reconciliation_evidence.reconciled") ||
        priorSchemaFailure.contains("reconciliation_evidence/reconciled")
    if (!namesReconciled || !priorSchemaFailure.contains("must be the constant value")) {
      return ""
    }
    return """

      A 'completed' implementation_receipt asserts a reconciled working tree: reconciliation_evidence.reconciled
      must be true, and 'completed' is the only status that may carry this receipt. Do not report 'completed'
      with reconciled false, and do not flip the flag to true unless the tree really is at target. If the work
      is genuinely incomplete, leave this phase through a 'blocked' or 'failed' envelope instead.
      """.trimIndent()
  }

  private fun continuationFor(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
    kind: SegmentKind,
  ): String =
    inputs.implementationContinuation
      ?.takeIf { inputs.correctiveRepairContext == null && it.phaseId == stepId }
      ?.let { continuationDirective(it, kind) }
      .orEmpty()

  fun continuationDirective(
    continuation: FeatureTaskRuntimeImplementationContinuation,
    kind: SegmentKind,
  ): String {
    val segments =
      continuation.priorValueSegments.withIndex().joinToString("\n\n") { (index, value) ->
        "Segment ${index + 1} value:\n$value"
      }
    val prompt = continuation.latestPrompt?.let { "Latest optional prompt: $it" } ?: "No optional prompt recorded."
    val disposition = continuation.failureDisposition ?: "none"
    val label = kind.label
    return """
      ## Continue this $label — segment ${continuation.segmentNumber}
      A prior segment of this same $label ran and did real work. It was NOT rejected and its
      output was NOT malformed: continue from where it stopped. Do not restart the $label and
      do not re-apply changes already present — the mutating-phase idempotency contract still governs.

      Prior stuffed value segments:
      $segments

      $prompt
      Failure disposition from the latest segment: $disposition

      Emit a new non-blank value string carrying your updated ${kind.receiptKind} JSON stuffed inside
      value.
      """.trimIndent()
  }

  enum class SegmentKind(val label: String, val receiptKind: String) {
    IMPLEMENTATION("implementation", "implementation_receipt"),
    SIMPLIFICATION("simplification", "simplification_receipt"),
  }
}
