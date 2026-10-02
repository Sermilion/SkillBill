package skillbill.workflow.taskruntime.model.skeleton

import skillbill.contracts.JsonCodec
import skillbill.text.sha256HexUtf8

data class PhaseStepPolicy(
  val mutating: Boolean,
  val singleAgentSession: Boolean,
  val readOnlyIdle: Boolean,
  val fileMutating: Boolean,
  val generationScoped: Boolean,
  val outputGateAttempts: Int = 1,
  val extendsOwnedInventory: Boolean = false,
) {
  fun semanticIdentity(
    strategyId: String,
    semanticRevision: Int,
    stepId: String,
  ): String {
    val encoded =
      JsonCodec.valueToJsonString(
        listOf(
          strategyId,
          semanticRevision,
          stepId,
          mutating,
          singleAgentSession,
          readOnlyIdle,
          fileMutating,
          generationScoped,
          outputGateAttempts,
          extendsOwnedInventory,
        ),
      )
    return "step-policy-v2:${sha256HexUtf8(encoded)}"
  }
}
