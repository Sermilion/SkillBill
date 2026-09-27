package skillbill.workflow.taskruntime.model.handoff.task

import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairLedger
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint

data class FeatureTaskRuntimeHandoffAssemblyRequest(
  val declaration: FeatureTaskRuntimePhaseDeclaration,
  val runInvariants: FeatureTaskRuntimeRunInvariants,
  val recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
  val drivingVerdict: FeatureTaskRuntimeVerdict? = null,
  val repairLedger: FeatureTaskRuntimeRepairLedger? = null,
  val repositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  val expectedRepositoryCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  val branchIdentity: String? = null,
  val baseBranch: String = "main",
  val validationDepth: ValidationDepth = ValidationDepth.DEFAULT,
  val unselectedStepIds: Set<String> = emptySet(),
)
