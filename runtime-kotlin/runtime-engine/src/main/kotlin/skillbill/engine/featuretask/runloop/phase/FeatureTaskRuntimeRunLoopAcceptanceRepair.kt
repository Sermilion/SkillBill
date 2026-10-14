package skillbill.engine.featuretask.runloop.phase

import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairStepRoles
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.persist.sha256Hex
import skillbill.engine.featuretask.runloop.checkpoint.FeatureTaskRuntimeRunLoopCheckpointRemediation
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
import skillbill.engine.featuretask.runloop.state.coupledRunTransitions
import skillbill.engine.featuretask.runloop.state.coupledSession
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot

internal object FeatureTaskRuntimeRunLoopAcceptanceRepair {
  sealed interface Routing {
    data class Reenter(val phaseId: String) : Routing

    data object Continue : Routing

    data object Stop : Routing
  }

  fun ownsCompletedPhase(
    phaseId: String,
    steps: AcceptanceCriterionRepairStepRoles?,
  ): Boolean =
    phaseId in
      setOf(
        steps?.audit,
        steps?.repair,
      )

  fun resume(context: FeatureTaskRuntimeRunLoopContext): String? {
    val scope = (context.request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair ?: return null
    val state = scope.snapshot ?: return null
    val steps = scope.steps ?: return null
    val target =
      if (state.pendingClarificationQuestion != null) {
        steps.audit
      } else {
        when (state.stage) {
          AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED ->
            if (state.mode == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY) {
              steps.audit
            } else {
              steps.planning
            }
          AcceptanceCriterionRepairStage.REPAIR_RESERVED ->
            steps.repair
          else -> steps.audit
        }
      }
    context.runState.coupledRunTransitions.reenterAcceptanceSteps(listOf(target))
    return target
  }

  fun next(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
  ): Routing {
    val scope = (context.request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair
    val state = scope?.snapshot
    val steps = scope?.steps
    val inactive = scope == null || state == null || steps == null || !ownsCompletedPhase(phaseId, steps)
    if (inactive) return Routing.Continue
    requireNotNull(state)
    requireNotNull(scope)
    requireNotNull(steps)
    if (state.stage == AcceptanceCriterionRepairStage.FINAL_ACCEPTED) {
      if (!finalAuditMatchesCurrentSource(context, state)) {
        context.runState.coupledRunTransitions.reenterAcceptanceSteps(listOf(steps.audit))
        return Routing.Reenter(steps.audit)
      }
      return Routing.Continue
    }
    if (state.stage == AcceptanceCriterionRepairStage.BUDGET_BLOCKED) {
      FeatureTaskRuntimeRunLoopPhaseBlocking.blockAt(
        context.request,
        context.state,
        context.runState.coupledSession(),
        phaseId,
        "Acceptance criterion repair exhausted its three unsuccessful outcomes. ${scope.currentCriterionContext}",
      )
      return Routing.Stop
    }
    val target =
      when (state.stage) {
        AcceptanceCriterionRepairStage.DISCOVERY_ADMITTED ->
          if (state.mode == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY) {
            steps.audit
          } else {
            steps.planning
          }
        else -> steps.audit
      }
    if (target == steps.planning) {
      if (!FeatureTaskRuntimeRunLoopCheckpointRemediation.establishRemediationCheckpoint(
          context,
          phaseId,
          steps.repairLoopId,
        )
      ) {
        return Routing.Stop
      }
      context.runState.coupledRunTransitions.reenterAcceptanceSteps(
        listOf(
          target,
          steps.repair,
          steps.audit,
        ),
      )
    } else {
      context.runState.coupledRunTransitions.reenterAcceptanceSteps(listOf(target))
    }
    return Routing.Reenter(target)
  }

  fun reviewEntryBlockReason(
    context: FeatureTaskRuntimeRunLoopContext,
    phaseId: String,
  ): String? {
    if (context.runState.selectedOwnerOf(phaseId)?.slot != PhaseSlot.CODE_REVIEW) return null
    val scope = (context.request as? FeatureTaskRuntimeRunRequest)?.acceptanceCriterionRepair ?: return null
    val state = scope.snapshot ?: return "The authored acceptance catalog has not completed its final source audit."
    if (state.stage != AcceptanceCriterionRepairStage.FINAL_ACCEPTED || !finalAuditMatchesCurrentSource(context, state)) {
      return "The current source has no committed final acceptance audit. Resume at the full audit before review."
    }
    return null
  }

  private fun finalAuditMatchesCurrentSource(
    context: FeatureTaskRuntimeRunLoopContext,
    state: AcceptanceCriterionRepairState,
  ): Boolean {
    val audit = state.finalAudits.lastOrNull() ?: return false
    val fingerprint = context.gitOperations.repositoryFingerprint(context.request.repoRoot)
    return fingerprint.ok && fingerprint.value.isNotBlank() &&
      sha256Hex(fingerprint.value) == audit.treeDigest
  }
}
