package skillbill.workflow.taskruntime.model.handoff

import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpointPolicy

data class PhaseHandoffProjectionTemplate(
  val consumerPhaseId: String,
  val producingPhaseId: String,
  val name: String,
  val contractId: String,
  val fields: List<String>,
  val checkpointPolicy: FeatureTaskRuntimeRepositoryCheckpointPolicy =
    FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
  val required: Boolean = true,
  val contractVersion: String? = null,
)
