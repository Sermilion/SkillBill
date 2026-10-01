package skillbill.engine.featuretask.validation

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput

internal fun repairSegmentOutput(
  run: PhaseRun,
  iteration: Int,
): FeatureTaskRuntimePhaseOutput =
  FeatureTaskRuntimePhaseOutput(
    phaseId = run.phaseId,
    iteration = iteration,
    payload =
      """{"${SharedPayloadKeys.CONTRACT_VERSION}":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",""" +
        """"${SharedPayloadKeys.PHASE_ID}":"${run.phaseId}",""" +
        """"${SharedPayloadKeys.STATUS}":"${WorkflowStepStatus.COMPLETED.wireValue}",""" +
        """"${SharedPayloadKeys.SUMMARY}":"Gate repair segment.",""" +
        """"${SharedPayloadKeys.PRODUCED_OUTPUTS}":{}}""",
  )
