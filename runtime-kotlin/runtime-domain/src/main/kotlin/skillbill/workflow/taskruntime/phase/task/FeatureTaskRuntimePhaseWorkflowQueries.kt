package skillbill.workflow.taskruntime.phase.task

import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeAuditCeremony
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeCeremonyScaling
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePreplanCeremony
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeReviewScope
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeBackwardEdge

object FeatureTaskRuntimePhaseWorkflowQueries {
  fun backwardEdgeForLoop(loopId: String): FeatureTaskRuntimeBackwardEdge? =
    FeatureTaskRuntimePhaseWorkflowTransitions.backwardEdgeForLoop(
      FeatureTaskRuntimePhaseWorkflowDefinition.transitions,
      loopId,
    )

  fun ceremonyScaling(featureSize: FeatureTaskRuntimeFeatureSize): FeatureTaskRuntimeCeremonyScaling =
    when (featureSize) {
      FeatureTaskRuntimeFeatureSize.SMALL ->
        FeatureTaskRuntimeCeremonyScaling(
          preplanCeremony = FeatureTaskRuntimePreplanCeremony.LIGHT,
          reviewScope = FeatureTaskRuntimeReviewScope.CURRENT_UNIT_OF_WORK,
          auditCeremony = FeatureTaskRuntimeAuditCeremony.LIGHT,
        )
      FeatureTaskRuntimeFeatureSize.MEDIUM,
      FeatureTaskRuntimeFeatureSize.LARGE,
      ->
        FeatureTaskRuntimeCeremonyScaling(
          preplanCeremony = FeatureTaskRuntimePreplanCeremony.FULL,
          reviewScope = FeatureTaskRuntimeReviewScope.BRANCH_DIFF,
          auditCeremony = FeatureTaskRuntimeAuditCeremony.FULL_PER_CRITERION,
        )
    }

  fun phaseDeclaration(
    phaseId: String,
    featureSize: FeatureTaskRuntimeFeatureSize,
  ): FeatureTaskRuntimePhaseDeclaration {
    val base =
      FeatureTaskRuntimePhaseWorkflowDefinition.phaseDeclarations[phaseId]
        ?: error("No phase declaration for runtime phase '$phaseId'.")
    if (phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW) {
      return base
    }
    val reviewKey =
      when (ceremonyScaling(featureSize).reviewScope) {
        FeatureTaskRuntimeReviewScope.CURRENT_UNIT_OF_WORK -> "current_unit_of_work"
        FeatureTaskRuntimeReviewScope.BRANCH_DIFF -> "diff"
      }
    return base.copy(derivedContextKeys = listOf(reviewKey))
  }

  fun phaseDeclarationWithoutSteps(
    phaseId: String,
    featureSize: FeatureTaskRuntimeFeatureSize,
    omittedStepIds: Set<String>,
  ): FeatureTaskRuntimePhaseDeclaration {
    val base = phaseDeclaration(phaseId, featureSize)
    if (omittedStepIds.isEmpty()) return base
    return base.copy(
      projectionDeclarations =
        base.projectionDeclarations.filter { declaration ->
          val source = declaration.sourceRef as? FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput
          source?.producingPhaseId !in omittedStepIds
        },
    )
  }
}
