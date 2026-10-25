package skillbill.engine.featuretask.persist.acceptance

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairBinding
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairMutation
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairWriteResult
import skillbill.engine.operation.unittestvalue.UnitTestPathClassifier
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionInteractionTrace
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionEvidencePaths
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionTarget
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionSameFilePair
import skillbill.workflow.taskruntime.model.audit.interaction.requireInteractionTrace

internal object AcceptanceCriterionInteractionTargets {
  fun all(
    state: AcceptanceCriterionRepairState,
    binding: AcceptanceCriterionRepairBinding,
  ): List<AcceptanceCriterionInteractionTarget> {
    val scenarios = scenarioTargets(state, binding)
    return scenarios + sameFilePairs(state).take(pairRoom(scenarios)).map { it.target() }
  }

  fun droppedPairCount(
    state: AcceptanceCriterionRepairState,
    binding: AcceptanceCriterionRepairBinding,
  ): Int = (sameFilePairs(state).size - pairRoom(scenarioTargets(state, binding))).coerceAtLeast(0)

  private fun pairRoom(scenarios: List<AcceptanceCriterionInteractionTarget>): Int =
    AcceptanceCriterionInteractionTrace.MAX_SCENARIO_REFS - scenarios.size

  fun prepare(
    state: AcceptanceCriterionRepairState,
    binding: AcceptanceCriterionRepairBinding,
  ): AcceptanceCriterionRepairState =
    if (state.stage == AcceptanceCriterionRepairStage.ACCEPTED) {
      state.requireInteractionTrace(all(state, binding), buildable(state, binding))
    } else {
      state
    }

  fun skipped(
    previous: AcceptanceCriterionRepairState?,
    next: AcceptanceCriterionRepairState,
    mutation: AcceptanceCriterionRepairMutation,
  ): Boolean =
    mutation !is AcceptanceCriterionRepairMutation.InteractionTrace &&
      next.stage == AcceptanceCriterionRepairStage.ACCEPTED &&
      next.interactionTrace.tracedSpecDigest == next.specDigest &&
      previous?.interactionTrace?.tracedSpecDigest != next.specDigest

  fun flagSkipped(
    result: AcceptanceCriterionRepairWriteResult,
    previous: AcceptanceCriterionRepairState?,
    mutation: AcceptanceCriterionRepairMutation,
  ): AcceptanceCriterionRepairWriteResult =
    if (result is AcceptanceCriterionRepairWriteResult.Committed && !result.replayed &&
      skipped(previous, result.state, mutation)
    ) {
      result.copy(interactionTraceSkipped = true)
    } else {
      result
    }

  fun recordDroppedPairs(
    diagnostics: RuntimeDiagnostics,
    workflowId: String,
    previous: AcceptanceCriterionRepairState,
    next: AcceptanceCriterionRepairState,
    binding: AcceptanceCriterionRepairBinding,
  ) {
    if (previous.stage == next.stage) return
    val dropped = droppedPairCount(next, binding)
    if (dropped == 0) return
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=interaction_trace workflow=${workflowId.take(MAX_DIAGNOSTIC_ID)} record_kind=truncated " +
        "value_expected=all_same_file_pairs value_used=capped dropped_pairs=$dropped",
    )
  }

  fun recordSkip(
    diagnostics: RuntimeDiagnostics,
    workflowId: String,
  ) {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "seam=interaction_trace workflow=${workflowId.take(MAX_DIAGNOSTIC_ID)} record_kind=skip " +
        "value_expected=interaction_targets value_used=none",
    )
  }

  fun sameFilePairs(state: AcceptanceCriterionRepairState): List<AcceptanceCriterionSameFilePair> =
    AcceptanceCriterionEvidencePaths.sharedFilePairs(state.criteria, UnitTestPathClassifier::isUnitTest)

  fun pending(
    state: AcceptanceCriterionRepairState,
    binding: AcceptanceCriterionRepairBinding,
  ): List<AcceptanceCriterionInteractionTarget> {
    val scenarios = scenarioTargets(state, binding).associateBy { it.scenarioRef }
    return state.interactionTrace.pendingScenarioRefs.mapNotNull { ref ->
      scenarios[ref] ?: AcceptanceCriterionInteractionTarget.pairOf(ref, state.criteria)
    }
  }

  fun buildable(
    state: AcceptanceCriterionRepairState,
    binding: AcceptanceCriterionRepairBinding,
  ): (String) -> Boolean {
    val scenarioRefs = scenarioTargets(state, binding).map { it.scenarioRef }.toSet()
    return { ref -> ref in scenarioRefs || AcceptanceCriterionInteractionTarget.pairOf(ref, state.criteria) != null }
  }

  private fun scenarioTargets(
    state: AcceptanceCriterionRepairState,
    binding: AcceptanceCriterionRepairBinding,
  ): List<AcceptanceCriterionInteractionTarget> {
    val criteria = state.criteria.associateBy { it.canonicalRef }
    return binding.interactionScenarios.mapNotNull { scenario ->
      val known = scenario.criterionRefs.filter { it in criteria }
      known.takeIf { it.size >= MIN_OWNERS }?.let { refs ->
        AcceptanceCriterionInteractionTarget(
          scenario.scenarioRef,
          refs.filter { criteria.getValue(it).sourceDisposition == AcceptanceCriterionSourceDisposition.SOURCE_CLOSED },
        )
      }
    }
  }

  private const val MIN_OWNERS = 2
  private const val MAX_DIAGNOSTIC_ID = 128
}
