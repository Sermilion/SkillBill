package skillbill.engine.featuretask.slot.monitor.standalone

import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimeRunInvariantPromptAllowlist
import skillbill.engine.featuretask.phase.core.MonitorRepairStepRoles
import skillbill.engine.featuretask.phase.prompt.compose.FeatureTaskRuntimePhasePromptComposeInputs
import skillbill.engine.featuretask.phase.prompt.compose.PhaseStepPromptSections
import skillbill.engine.featuretask.runloop.core.PhaseOutcome
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.slot.MonitorRepairCapability
import skillbill.engine.featuretask.slot.PhaseBeforeLaunchHooks
import skillbill.engine.featuretask.slot.PhaseBindingFamily
import skillbill.engine.featuretask.slot.PhaseLaunchPromptHooks
import skillbill.engine.featuretask.slot.PhaseRuntimeOwnedStepHooks
import skillbill.engine.featuretask.slot.PhaseStepDescriptor
import skillbill.engine.featuretask.slot.PhaseStepHooks
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.slot.PhaseStrategy
import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLaunchHookContext
import skillbill.engine.featuretask.slot.attempt.PhaseStepOutputContext
import skillbill.engine.featuretask.slot.attempt.policyOf
import skillbill.engine.featuretask.slot.attempt.runAgentStep
import skillbill.engine.featuretask.slot.monitor.MonitorRepairPublicationGate
import skillbill.engine.featuretask.slot.monitor.MonitorRepairPublicationGateRequest
import skillbill.engine.featuretask.slot.monitor.publicationBlockingCheck
import skillbill.engine.featuretask.slot.qualitygate.runtimeOwnedValidateAgentPhaseTask
import skillbill.engine.featuretask.slot.qualitygate.savedValidationAgentDirective
import skillbill.engine.featuretask.slot.state.PhaseStandaloneMonitorStepBinding
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariantPromptField
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

public class StandaloneMonitorStrategy(
  private val service: StandaloneMonitorService,
  private val publicationGate: MonitorRepairPublicationGate,
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
    object :
      PhaseStepHooks<PhaseStandaloneMonitorStepBinding>,
      PhaseLaunchPromptHooks<PhaseStandaloneMonitorStepBinding>,
      PhaseBeforeLaunchHooks<PhaseStandaloneMonitorStepBinding>,
      PhaseRuntimeOwnedStepHooks {
      override val carriesPackBuildCommand: Boolean = false
      override val carriesValidationCommand: Boolean = true

      override fun earlyOutput(
        run: PhaseRun,
        iteration: Int,
        outputText: String,
      ): PhaseOutcome? = null

      override fun launchPromptSupplement(
        run: PhaseRun,
        context: PhaseAttemptLaunchHookContext,
        state: PhaseStandaloneMonitorStepBinding,
      ): String = state.standaloneRepairBrief().let { "\n\n$it" }

      override fun beforeAgentLaunch(
        run: PhaseRun,
        context: PhaseAttemptLaunchHookContext,
        state: PhaseStandaloneMonitorStepBinding,
      ): String? {
        state.guardStandaloneRepairLaunch(run, service.operations)?.let { return it }
        if (state.repairValidationLaunchTransport() == null) {
          return "Monitor repair launched without the accepted validation command snapshot."
        }
        return null
      }

      override fun settleCompletedOutput(
        run: PhaseRun,
        context: PhaseStepOutputContext,
        state: PhaseStandaloneMonitorStepBinding,
        outputMap: FeatureTaskRuntimeWorkflowArtifactMap,
      ): PhaseStepOutputCheck {
        val fixed = reportsFixedRepair(outputMap)
        if (fixed) {
          publicationGate
            .verify(
              MonitorRepairPublicationGateRequest(
                run.request.repoRoot,
                state.resolvedBranch()?.baseBranch,
                state.repairValidationLaunchTransport(),
              ),
            )
            .publicationBlockingCheck()
            ?.let { return it }
        }
        return state
          .publishStandaloneRepair(run, service.operations, fixed)
          ?.let { PhaseStepOutputCheck.Block(it) }
          ?: PhaseStepOutputCheck.Accept
      }
    }

  override fun directiveFor(stepId: String): String {
    policies.policyOf(stepId)
    return if (stepId == entryStep) {
      StandaloneMonitorDirectives.MONITOR
    } else {
      runtimeOwnedValidateAgentPhaseTask(null) + " " + StandaloneMonitorDirectives.REPAIR
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
      val (taskDirective, runsBuildGate) =
        savedValidationAgentDirective(
          inputs.validationLaunchTransport,
          inputs.packValidationCommand,
          inputs.validationGateTriage,
        )
      PhaseStepPromptSections(
        taskDirective = taskDirective + " " + StandaloneMonitorDirectives.REPAIR,
        runsValidationGate = true,
        runsBuildGate = runsBuildGate,
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

  public companion object {
    public const val ID: String = "standalone-monitor-ci"
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
