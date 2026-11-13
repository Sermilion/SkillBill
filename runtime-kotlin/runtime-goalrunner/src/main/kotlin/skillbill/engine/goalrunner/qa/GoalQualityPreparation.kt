package skillbill.engine.goalrunner.qa

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.engine.featuretask.phaserun.PhaseRunPlanResolver
import skillbill.engine.featuretask.phaserun.StandalonePhaseRegistrationFactory
import skillbill.engine.goal.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRunRequest
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import java.nio.file.Path

@Inject
public class GoalQualityPreparation(
  private val diagnostics: RuntimeDiagnostics,
  private val phasePlans: PhaseRunPlanResolver,
  private val phaseRequests: GoalQualityPhaseRequests,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val gitOperations: WorkflowGitOperations,
  private val invariantsSource: FeatureTaskRuntimeRunInvariantsSource,
  private val projectStore: ProjectStorePort,
  private val registrations: StandalonePhaseRegistrationFactory,
) {
  internal fun prepare(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): Pair<GoalQualityExecutionRecord, FeatureTaskRuntimeRunInvariantsRead> =
    admitPreparedQuality(
      PreparedQualityContext(
        diagnostics,
        phasePlans,
        phaseRequests,
        outcomeStore,
        this::readParentInvariants,
      ),
      gitOperations,
      state,
      request,
      registrations::openCodeIdentity,
    )

  private fun readParentInvariants(
    state: GoalRunnerManifestState,
    request: GoalRunnerRunRequest,
  ): FeatureTaskRuntimeRunInvariantsRead =
    invariantsSource.read(
      resolvedParentSpecPath(
        request.repoRoot,
        Path.of(state.manifest.parentSpecPath),
        projectStore.resolve(request.repoRoot),
      ),
    )
}
