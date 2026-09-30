package skillbill.engine.featuretask.phase.briefing

import skillbill.application.decomposition.baseBranch
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimeBriefingProjectionInputs
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionInputs

fun briefingProjectionInputs(
  inputs: FeatureTaskRuntimeBriefingProjectionInputs,
): FeatureTaskRuntimeHandoffProjectionInputs =
  FeatureTaskRuntimeHandoffProjectionInputs(
    consumerPhaseId = inputs.handoff.phaseId,
    declarations = inputs.declarations,
    resolvedUpstream = inputs.handoff.upstreamOutputs,
    runInvariants = inputs.handoff.runInvariants,
    resolvedCheckpoint = inputs.handoff.repositoryCheckpoint,
    sharedReviewEvidence = inputs.sharedReviewEvidence,
    expectedCheckpoint = inputs.handoff.expectedRepositoryCheckpoint,
    repairLedger = inputs.handoff.repairLedger,
    recordedFindingVerdicts = inputs.handoff.recordedFindingVerdicts,
    branchIdentity = inputs.handoff.branchIdentity,
    baseBranch = inputs.handoff.baseBranch,
    workflowId = inputs.workflowId,
    planningProjectionValidator = inputs.planningProjectionValidator::validate,
    addonContentBySlug = inputs.addonContentBySlug,
    validationDepth = inputs.handoff.validationDepth,
    unselectedStepIds = inputs.handoff.unselectedStepIds,
  )
