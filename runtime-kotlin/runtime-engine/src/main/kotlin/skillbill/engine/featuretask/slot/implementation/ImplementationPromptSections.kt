package skillbill.engine.featuretask.slot.implementation

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeImplementationContinuation
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeImplementationTurn
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.phase.prompt.directives.projectAuthoringDisciplineDirective
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress

internal object ImplementationPromptSections {
  const val IMPLEMENT_READONLY_REPAIR_SENTENCE: String =
    "Repair evidence is read-only repository facts: do not run builds or tests here."

  const val NO_CHANGE_CLAIM_DIRECTIVE: String =
    "When the repository already satisfies the owned spec and needs no change, leave the diff empty and " +
      "pass a claim object as the `no_change` argument of feature_task_phase_complete, settling with status " +
      "completed and a prose value as usual. The claim needs `reason` (`out_of_repo`, `already_satisfied` or " +
      "`not_reproducible`), one `criteria` entry per acceptance criterion with `criterion_id`, a `verdict` set " +
      "to the same reason word, and non-blank `evidence`, at least one `path:line` citation in `citations`, and " +
      "a non-blank `boundary_trace`. Add `owning_system` when the owner is known. Never finish an implement " +
      "step that changed nothing without this claim: an empty diff without a claim cannot be committed."

  const val IMPLEMENT_DIRECTIVE: String =
    "Reconcile the repository to the intended state the selected governed spec describes: read the file named " +
      "by spec_reference for its scope, planned details, and acceptance criteria, make the changes it " +
      "specifies, treating any already-applied change as a no-op. See the mutating-phase " +
      "idempotency contract below. Finish with prose for the next phase: what you changed and where, the tests " +
      "you added or updated, any deviation from the plan, anything left unresolved, and the state the working " +
      "tree is in. Keep it a bounded summary, not a transcript. Compilation and build proof belong to build, " +
      "and tests and full validation belong to validate; only the safe scoped authoring commands admitted " +
      "above may run. " +
      IMPLEMENT_READONLY_REPAIR_SENTENCE + " " +
      NO_CHANGE_CLAIM_DIRECTIVE

  const val SIMPLIFY_DIRECTIVE: String =
    "Within the current subtask scoped diff and owned paths only, apply high-confidence local simplifications: " +
      "dead feature-local code, one-use wrappers, unnecessary one-implementation abstractions, hand-rolled " +
      "standard-library behavior, or equivalent local shrinkage. Reading configuration and instructions that " +
      "apply to owned paths is permitted narrow discovery; do not perform whole-repository search for " +
      "simplification opportunities, edit paths outside the boundary, run builds or tests, launch subagents, " +
      "or delegate review. Never remove or " +
      "weaken governed contracts, typed errors, loud-fail seams, parity tests, validator-backed rules, security " +
      "measures, accessibility requirements, or behavior the spec explicitly requires. Treat edits already " +
      "present as a no-op under the mutating-phase idempotency contract. Finish with prose for the next phase: " +
      "the paths you changed, each reduction you made or declined and why, anything left unresolved, and the " +
      "state the working tree is in. " +
      IMPLEMENT_READONLY_REPAIR_SENTENCE

  private val SIMPLIFY_SCOPE_BOUNDARY: String =
    """
    ## Simplify scope boundary
    The subtask_scope projection and repository checkpoint list the only owned paths and diff context
    for this session. Work exclusively inside that boundary. Reading configuration and instructions that
    apply to owned paths is permitted narrow discovery. Forbidden: repository-wide search for
    simplification opportunities, edits outside listed paths, `./gradlew` build or check, test execution,
    `skill-bill phase review`, review subagents, delegated review, or spawning other agents.
    """.trimIndent()

  fun implement(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections {
    val turn = inputs.implementationTurn ?: return wholeSpecImplement(stepId, inputs)
    return if (turn.check) {
      PhaseStepPromptSections(taskDirective = checkDirective(turn))
    } else {
      PhaseStepPromptSections(
        taskDirective = workerDirective(turn),
        authoringDiscipline = projectAuthoringDisciplineDirective(),
        testValueDiscipline = true,
      )
    }
  }

  private fun workerDirective(turn: FeatureTaskRuntimeImplementationTurn): String {
    val gap =
      turn.latestGap?.let {
        "A fresh read-only check found this criterion unsatisfied after the previous worker. Latest gap: $it "
      }.orEmpty()
    val cap = FeatureTaskRuntimeImplementationProgress.MAX_WORKERS_PER_CRITERION
    return "You are worker ${turn.worker} of at most $cap for one acceptance criterion of the selected " +
      "governed spec. Implement only this criterion:\n\n" +
      "${turn.criterion}\n\n" +
      "Read the full spec at `${turn.specReference}` for its scope and planned details, and work against the " +
      "current working tree, which already holds the changes made for earlier criteria. Make the changes this " +
      "criterion needs and treat changes already present as a no-op under the mutating-phase idempotency " +
      "contract below. Leave other criteria to their own workers. $gap" +
      "Settle through feature_task_phase_complete with status completed and a short prose value: what you " +
      "changed and where, the tests you added or updated, and anything left unresolved. Do not pass a " +
      "`no_change` claim; a separate read-only check decides whether ${turn.criterionId} is satisfied. " +
      "Compilation and build proof belong to build, and tests and full validation belong to validate. " +
      IMPLEMENT_READONLY_REPAIR_SENTENCE
  }

  private fun checkDirective(turn: FeatureTaskRuntimeImplementationTurn): String =
    "You are a fresh read-only check of one acceptance criterion of the selected governed spec. Check only " +
      "this criterion against the current source:\n\n${turn.criterion}\n\n" +
      "The full spec at `${turn.specReference}` gives context. Do not edit, create, or delete any file, and do " +
      "not run builds or tests: a check that changes the working tree cannot close the criterion. Settle " +
      "through feature_task_phase_complete with status completed and verdict `satisfied` when the source fully " +
      "meets ${turn.criterionId}, or verdict `unsatisfied` with a short prose value that names the remaining " +
      "gap for the next worker. Any other verdict is rejected."

  private fun wholeSpecImplement(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective = IMPLEMENT_DIRECTIVE,
      authoringDiscipline = projectAuthoringDisciplineDirective(),
      testValueDiscipline = true,
      continuation = continuationFor(stepId, inputs, SegmentKind.IMPLEMENTATION),
      stepContext = checklistGuidance(inputs, reconcile = true),
    )

  fun simplify(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections =
    PhaseStepPromptSections(
      taskDirective = SIMPLIFY_DIRECTIVE,
      authoringDiscipline = projectAuthoringDisciplineDirective(),
      scopeBoundary = SIMPLIFY_SCOPE_BOUNDARY,
      continuation = continuationFor(stepId, inputs, SegmentKind.SIMPLIFICATION),
      stepContext = checklistGuidance(inputs, reconcile = false),
    )

  private fun checklistGuidance(
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
    reconcile: Boolean,
  ): String {
    val address =
      inputs.implementationChecklistAddress
        ?: return ""
    val action =
      if (reconcile) {
        "Seed or tick boxes at `$address`. Ticks are a private projection; they never mark workflow tasks complete."
      } else {
        "Read `$address` and reconcile ticks with remaining work. Do not treat ticks as completion."
      }
    return "## Runtime-private checklist\n$action"
  }

  private fun continuationFor(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
    kind: SegmentKind,
  ): String =
    inputs.implementationContinuation
      ?.takeIf { it.phaseId == stepId }
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

      Prior segment summaries:
      $segments

      $prompt
      Failure disposition from the latest segment: $disposition

      Finish with prose covering the whole $label so far, including what this segment added.
      """.trimIndent()
  }

  enum class SegmentKind(val label: String) {
    IMPLEMENTATION("implementation"),
    SIMPLIFICATION("simplification"),
  }
}
