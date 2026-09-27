package skillbill.workflow.taskruntime.model.core

data class PhaseStepPolicy(
  val mutating: Boolean,
  val relaunchOnInvalidOutput: Boolean,
  val singleAgentSession: Boolean,
  val readOnlyIdle: Boolean,
  val fileMutating: Boolean,
  val generationScoped: Boolean,
  val outputGateAttempts: Int = 1,
  val extendsOwnedInventory: Boolean = false,
)
