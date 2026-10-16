package skillbill.engine.featuretask.persist.acceptance

import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairStepRoles
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.slot.state.AcceptanceCriterionRepairCycleRecords
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import java.nio.file.Path

internal class AcceptanceCriterionRepairSession(
  val accounting: AcceptanceCriterionRepairRunState,
  val worker: AcquiredFeatureTaskRuntimeWorker,
  val root: Path,
  val steps: AcceptanceCriterionRepairStepRoles,
  private val recorder: AcceptanceCriterionRepairPhaseRecorder,
) : AcceptanceCriterionRepairCycleRecords {
  init {
    accounting.bindSteps(steps)
  }

  override fun requiresExplicitStart(phaseId: String): Boolean = worker.ownsAuditStep(phaseId)

  override fun isRepairStep(phaseId: String): Boolean = phaseId == steps.repair

  fun start(phase: FeatureTaskRuntimePhaseStateRequest): Boolean? = recorder.start(this, phase)

  fun complete(phase: FeatureTaskRuntimePhaseStateRequest): Boolean? = recorder.complete(this, phase)

  override fun settleUnsuccessfulRepair(
    phase: FeatureTaskRuntimePhaseStateRequest,
    kind: AcceptanceCriterionRepairOutcomeKind,
  ): Boolean? = recorder.unsuccessful(this, phase, kind)
}
