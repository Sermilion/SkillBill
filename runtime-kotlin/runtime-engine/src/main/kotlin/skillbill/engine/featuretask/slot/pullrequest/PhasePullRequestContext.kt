package skillbill.engine.featuretask.slot.pullrequest

import skillbill.application.telemetry.model.PrDescriptionGeneratedRequest
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.slot.PhaseRepositoryObservations
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration

internal data class PhasePullRequestContext(
  val request: FeatureTaskRuntimeRunFacts,
  val gitOperations: PhaseRepositoryObservations,
  val diagnostics: RuntimeDiagnostics,
  val prDescriptionGenerated: (PrDescriptionGeneratedRequest) -> Unit,
  val transitions: FeatureTaskRuntimeTransitionDeclaration,
  private val branch: FeatureTaskRuntimeResolvedBranch?,
) {
  fun resolvedBranch(): FeatureTaskRuntimeResolvedBranch? = branch
}
