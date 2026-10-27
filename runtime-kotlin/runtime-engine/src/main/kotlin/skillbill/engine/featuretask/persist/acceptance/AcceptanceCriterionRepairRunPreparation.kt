package skillbill.engine.featuretask.persist.acceptance

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairBinding
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairHydration
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRefusal
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRunState
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairVersionRecoveryResult
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairWriteResult
import java.nio.file.Path

@Inject
class AcceptanceCriterionRepairRunPreparation(
  private val repairPersistence: AcceptanceCriterionRepairPersistence,
  private val versionRecovery: AcceptanceCriterionRepairVersionRecovery,
) {
  sealed interface Prepared {
    data class Ready(val runState: AcceptanceCriterionRepairRunState) : Prepared

    data class Refused(val reason: AcceptanceCriterionRepairRefusal) : Prepared
  }

  fun recoverSupersededVersion(
    worker: AcquiredFeatureTaskRuntimeWorker,
  ): AcceptanceCriterionRepairVersionRecoveryResult = versionRecovery.recoverSupersededVersion(worker)

  fun prepare(
    worker: AcquiredFeatureTaskRuntimeWorker,
    criteria: List<String>,
    specDigest: String?,
    binding: AcceptanceCriterionRepairBinding?,
    repositoryRoot: Path,
  ): Prepared {
    val existing = repairPersistence.inspectExisting(worker, criteria, specDigest)
    if (existing !is AcceptanceCriterionRepairHydration.Present) {
      return Prepared.Ready(AcceptanceCriterionRepairRunState(existing, binding))
    }
    val bound = requireNotNull(binding)
    val hydration =
      when (val prepared = repairPersistence.prepareBoundInteractionTrace(worker, bound, repositoryRoot)) {
        is AcceptanceCriterionRepairWriteResult.Committed ->
          AcceptanceCriterionRepairHydration.Present(prepared.state)
        is AcceptanceCriterionRepairWriteResult.CommittedWithProjectionFailure ->
          AcceptanceCriterionRepairHydration.Present(prepared.state)
        is AcceptanceCriterionRepairWriteResult.Refused -> return Prepared.Refused(prepared.reason)
      }
    repairPersistence.recoverProjection(repositoryRoot, worker.workflowId, bound)
    return Prepared.Ready(AcceptanceCriterionRepairRunState(hydration, bound))
  }
}
