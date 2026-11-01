package skillbill.engine.featuretask.persist.acceptance

import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairStepRoles
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.slot.state.AcceptanceCriterionRepairCycleRecords
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import java.nio.file.Path

public class AcceptanceCriterionRepairSession(
  public val accounting: AcceptanceCriterionRepairRunState,
  public val worker: AcquiredFeatureTaskRuntimeWorker,
  public val root: Path,
  public val steps: AcceptanceCriterionRepairStepRoles,
  public val recorder: AcceptanceCriterionRepairPhaseRecorder,
) : AcceptanceCriterionRepairCycleRecords {
  init {
    accounting.bindSteps(steps)
  }

  override fun requiresExplicitStart(phaseId: String): Boolean = worker.ownsAuditStep(phaseId)

  override fun isRepairStep(phaseId: String): Boolean = phaseId == steps.repair

  internal fun start(phase: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite? = recorder.start(this, phase)

  internal fun complete(phase: FeatureTaskRuntimePhaseStateRequest): RequiredPhaseWrite? =
    recorder.complete(this, phase)

  override fun settleUnsuccessfulRepair(
    phase: FeatureTaskRuntimePhaseStateRequest,
    kind: AcceptanceCriterionRepairOutcomeKind,
  ): Boolean? = recorder.unsuccessful(this, phase, kind)
}
