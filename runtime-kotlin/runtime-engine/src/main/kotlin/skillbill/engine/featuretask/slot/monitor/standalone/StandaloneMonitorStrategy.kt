package skillbill.engine.featuretask.slot.monitor.standalone

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeRunInvariantPromptAllowlist
import skillbill.engine.featuretask.phase.core.MonitorRepairStepRoles
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.MonitorRepairCapability
import skillbill.engine.featuretask.slot.PhaseBindingFamily
import skillbill.engine.featuretask.slot.PhaseStepDescriptor
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.qualitygate.runtimeOwnedValidateAgentPhaseTask
import skillbill.engine.featuretask.slot.state.PhaseStandaloneMonitorStepBinding
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

class StandaloneMonitorStrategy(
  private val service: StandaloneMonitorService,
) : PhaseStrategy(),
  MonitorRepairCapability by MonitorRepairRoles {
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

  override val slot: PhaseSlot = PhaseSlot.MONITOR
  override val strategyId: String = ID
  override val steps: List<String> = policies.keys.toList()
  override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR

  override fun policyFor(stepId: String): PhaseStepPolicy = policies.policyOf(stepId)

  override fun stepDescriptor(stepId: String): PhaseStepDescriptor<*> {
    policies.policyOf(stepId)
    return if (stepId == entryStep) {
      PhaseStepDescriptor(PhaseBindingFamily.StandaloneMonitor, PhaseStepHooks.None) { run, state ->
        state.runStandaloneMonitor(run, service.operations)
      }
    } else {
      PhaseStepDescriptor(PhaseBindingFamily.StandaloneMonitor, repairHooks) { run, state ->
        runAgentStep(run, state)
      }
    }
  }

  private val repairHooks =
    object : PhaseStepHooks<PhaseStandaloneMonitorStepBinding> {
      override fun launchPromptSupplement(
        run: PhaseRun,
        context: PhaseAttemptLaunchHookContext,
        state: PhaseStandaloneMonitorStepBinding,
      ): String = state.standaloneRepairBrief().let { "\n\n$it" }

      override fun beforeAgentLaunch(
        run: PhaseRun,
        context: PhaseAttemptLaunchHookContext,
        state: PhaseStandaloneMonitorStepBinding,
      ): String? = state.guardStandaloneRepairLaunch(run, service.operations)

      override fun settleCompletedOutput(
        run: PhaseRun,
        context: PhaseStepOutputContext,
        state: PhaseStandaloneMonitorStepBinding,
        outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
      ): PhaseStepOutputCheck =
        state
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

  private fun reportsFixedRepair(outputMap: FeatureTaskRuntimeWorkflowArtifactMap): Boolean {
    val value = JsonCodec.anyToStringAnyMap(outputMap[SharedPayloadKeys.PRODUCED_OUTPUTS])?.get(SharedPayloadKeys.VALUE)
    val finalLine = (value as? String)?.lineSequence()?.map(String::trim)?.lastOrNull(String::isNotEmpty).orEmpty()
    return finalLine.trim('`', '*', ' ').equals(StandaloneMonitorDirectives.REPAIR_FIXED_LINE, ignoreCase = true)
  }

  companion object {
    const val ID = "standalone-monitor-ci"
  }
}

private object MonitorRepairRoles : MonitorRepairCapability {
  override val monitorRepairSteps =
    MonitorRepairStepRoles(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_MONITOR_FIX,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
    )
}
