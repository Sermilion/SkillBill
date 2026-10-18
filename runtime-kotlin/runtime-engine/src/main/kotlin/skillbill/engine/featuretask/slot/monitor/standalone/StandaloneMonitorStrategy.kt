package skillbill.engine.featuretask.slot.monitor.standalone

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeRunInvariantPromptAllowlist
import skillbill.engine.featuretask.phase.core.MonitorRepairStepRoles
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.PhaseExecutionBindingKind
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.qualitygate.runtimeOwnedValidateAgentPhaseTask
import skillbill.engine.featuretask.slot.state.PhaseAcceptedStepExecution
import skillbill.engine.featuretask.slot.state.PhaseStandaloneMonitorStepBinding
import skillbill.engine.featuretask.slot.state.PhaseStepBinding
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class StandaloneMonitorStrategy(
  private val service: StandaloneMonitorService,
) : PhaseStrategy() {
  private val policies =
    mapOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR to
        PhaseStepPolicy(
          mutating = false,
          singleAgentSession = false,
          readOnlyIdle = false,
          fileMutating = false,
          generationScoped = false,
        ),
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX to
        PhaseStepPolicy(
          mutating = true,
          singleAgentSession = false,
          readOnlyIdle = false,
          fileMutating = true,
          generationScoped = false,
          extendsOwnedInventory = true,
        ),
    )

  internal override val monitorRepairSteps =
    MonitorRepairStepRoles(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
    )

  override val slot: PhaseSlot = PhaseSlot.MONITOR
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun executionBindingKind(stepId: String): PhaseExecutionBindingKind =
    PhaseExecutionBindingKind.STANDALONE_MONITOR.also { policies.policyOf(stepId) }

  override fun stepHooks(stepId: String): PhaseStepHooks = if (stepId == entryStep) PhaseStepHooks.None else repairHooks

  private val repairHooks =
    object : PhaseStepHooks {
      override fun launchPromptSupplement(
        run: PhaseRun,
        context: PhaseAttemptLaunchHookContext,
        state: PhaseStepBinding,
      ): String = (state as PhaseStandaloneMonitorStepBinding).standaloneRepairBrief().let { "\n\n$it" }

      override fun beforeAgentLaunch(
        run: PhaseRun,
        context: PhaseAttemptLaunchHookContext,
        state: PhaseStepBinding,
      ): String? = (state as PhaseStandaloneMonitorStepBinding).guardStandaloneRepairLaunch(run, service.operations)

      override fun settleCompletedOutput(
        run: PhaseRun,
        context: PhaseStepOutputContext,
        state: PhaseAcceptedStepExecution,
        outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
      ): PhaseStepOutputCheck =
        (state as PhaseStandaloneMonitorStepBinding)
          .publishStandaloneRepair(run, service.operations, reportsFixedRepair(outputMap))
          ?.let { PhaseStepOutputCheck.Block(it) }
          ?: PhaseStepOutputCheck.Accept
    }

  override fun directiveFor(stepId: String): String {
    policies.policyOf(stepId)
    return if (stepId == entryStep) {
      StandaloneMonitorDirectives.MONITOR
    } else {
      runtimeOwnedValidateAgentPhaseTask() + " " + StandaloneMonitorDirectives.REPAIR
    }
  }

  override fun promptSections(
    stepId: String,
    inputs: FeatureTaskRuntimePhasePromptComposeInputs,
  ): PhaseStepPromptSections {
    policies.policyOf(stepId)
    return if (stepId == entryStep) {
      PhaseStepPromptSections(taskDirective = directiveFor(stepId), settles = false)
    } else {
      PhaseStepPromptSections(
        taskDirective = directiveFor(stepId),
        runsValidationGate = true,
        valueContent = StandaloneMonitorDirectives.REPAIR_VALUE_CONTENT,
      )
    }
  }

  override fun briefingInvariantFields(stepId: String): Set<FeatureTaskRuntimeRunInvariantPromptField> {
    policies.policyOf(stepId)
    return if (stepId == entryStep) {
      FeatureTaskRuntimeRunInvariantPromptAllowlist.FINALIZATION
    } else {
      super.briefingInvariantFields(stepId)
    }
  }

  override fun runStep(
    run: PhaseRun,
    state: PhaseAcceptedStepExecution,
  ): PhaseOutcome =
    if (run.phaseId == entryStep) {
      (
        state as? PhaseStandaloneMonitorStepBinding
          ?: error("Standalone monitor requires its accepted execution binding.")
      ).runStandaloneMonitor(run, service.operations)
    } else {
      runAgentStep(run, state)
    }

  private fun reportsFixedRepair(outputMap: FeatureTaskRuntimeWorkflowArtifactMap): Boolean {
    val value = JsonCodec.anyToStringAnyMap(outputMap[SharedPayloadKeys.PRODUCED_OUTPUTS])?.get(SharedPayloadKeys.VALUE)
    val finalLine = (value as? String)?.lineSequence()?.map(String::trim)?.lastOrNull(String::isNotEmpty).orEmpty()
    return finalLine.trim('`', '*', ' ').equals(StandaloneMonitorDirectives.REPAIR_FIXED_LINE, ignoreCase = true)
  }

  companion object {
    const val ID = "standalone-monitor-ci"
  }
}
