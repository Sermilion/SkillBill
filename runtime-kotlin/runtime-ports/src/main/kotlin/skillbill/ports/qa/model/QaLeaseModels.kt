package skillbill.ports.qa.model

data class QaDeviceLease(
  val deviceFingerprint: String,
  val invocationId: String,
)

sealed interface QaLeaseResult {
  data class Acquired(val lease: QaDeviceLease) : QaLeaseResult

  data class Contended(val ownerInvocationId: String) : QaLeaseResult
}

sealed interface QaLeaseReleaseOutcome {
  data class Released(val deviceFingerprint: String) : QaLeaseReleaseOutcome

  data class NotHeld(val deviceFingerprint: String) : QaLeaseReleaseOutcome

  data class Failed(
    val retainedIdentity: String,
    val detail: String,
  ) : QaLeaseReleaseOutcome
}
