package skillbill.workflow.taskruntime.model.skeleton

import skillbill.contracts.JsonCodec
import java.security.MessageDigest

data class PhaseStepPolicy(
  val mutating: Boolean,
  val relaunchOnInvalidOutput: Boolean,
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
          relaunchOnInvalidOutput,
          singleAgentSession,
          readOnlyIdle,
          fileMutating,
          generationScoped,
          outputGateAttempts,
          extendsOwnedInventory,
        ),
      )
    val digest =
      MessageDigest.getInstance("SHA-256")
        .digest(encoded.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "step-policy-v1:$digest"
  }
}
