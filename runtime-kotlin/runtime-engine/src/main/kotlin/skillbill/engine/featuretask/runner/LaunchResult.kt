package skillbill.engine.featuretask.runner

import skillbill.engine.featuretask.phase.core.FeatureTaskRuntimePhaseFileManifest
import skillbill.engine.featuretask.runloop.core.CapturedPhaseOutput
import skillbill.engine.featuretask.runloop.core.RecordRejection
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeChildOutput
import skillbill.engine.featuretask.slot.state.PhaseSettledEnvelopeRead
import skillbill.engine.featuretask.slot.state.RequiredPhaseWrite
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition

internal sealed interface LaunchResult {
  data class Captured(
    val stdout: String,
    val stdoutBytes: ByteArray,
    val stdoutTruncated: Boolean,
    val stdoutByteSize: Long,
    val stdoutSha256: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest,
    val settledEnvelope: PhaseSettledEnvelopeRead = PhaseSettledEnvelopeRead.None,
  ) : LaunchResult

  data class InfraFailure(
    val reason: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest?,
    val disposition: FeatureTaskRuntimeFailureDisposition,
    val neverLaunched: Boolean,
    val childOutput: FeatureTaskRuntimeChildOutput? = null,
  ) : LaunchResult

  data class RecordRejected(
    val rejection: RecordRejection,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
  ) : LaunchResult

  data class ProviderLimited(
    val reason: String,
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest?,
  ) : LaunchResult

  data class RequiredWriteRejected(
    val rejection: RequiredPhaseWrite.Rejected,
  ) : LaunchResult {
    override val fileManifest: FeatureTaskRuntimePhaseFileManifest? = null
  }

  val capturedStdout: String? get() = (this as? Captured)?.stdout
  val capturedStdoutBytes: ByteArray? get() = (this as? Captured)?.stdoutBytes
  val capturedStdoutTruncated: Boolean get() = (this as? Captured)?.stdoutTruncated == true
  val capturedStdoutByteSize: Long? get() = (this as? Captured)?.stdoutByteSize
  val capturedStdoutSha256: String? get() = (this as? Captured)?.stdoutSha256
  val infraFailureReason: String? get() = (this as? InfraFailure)?.reason
  val infraFailureChildOutput: FeatureTaskRuntimeChildOutput? get() = (this as? InfraFailure)?.childOutput
  val providerLimitReason: String? get() = (this as? ProviderLimited)?.reason
  val recordRejection: RecordRejection? get() = (this as? RecordRejected)?.rejection
  val requiredWriteRejection: RequiredPhaseWrite.Rejected? get() = (this as? RequiredWriteRejected)?.rejection
  val capturedSettledEnvelope: PhaseSettledEnvelopeRead
    get() = (this as? Captured)?.settledEnvelope ?: PhaseSettledEnvelopeRead.None

  val childNeverLaunched: Boolean
    get() = (this as? InfraFailure)?.neverLaunched == true
  val failureDisposition: FeatureTaskRuntimeFailureDisposition
    get() = (this as? InfraFailure)?.disposition ?: FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE
  val fileManifest: FeatureTaskRuntimePhaseFileManifest?
  val capturedPhaseOutput: CapturedPhaseOutput? get() =
    (this as? Captured)?.let(
      CapturedPhaseOutput::fromLaunchCaptured,
    )

  companion object {
    fun captured(
      captured: CapturedPhaseOutput,
      fileManifest: FeatureTaskRuntimePhaseFileManifest,
      settledEnvelope: PhaseSettledEnvelopeRead = PhaseSettledEnvelopeRead.None,
    ): LaunchResult =
      Captured(
        captured.text,
        captured.bytes,
        captured.truncated,
        captured.byteSize,
        captured.sha256,
        fileManifest,
        settledEnvelope,
      )

    fun infraFailure(
      reason: String,
      fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
      childNeverLaunched: Boolean,
      childOutput: FeatureTaskRuntimeChildOutput? = null,
    ): LaunchResult =
      InfraFailure(
        reason,
        fileManifest,
        FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE,
        childNeverLaunched,
        childOutput,
      )

    fun providerLimited(
      reason: String,
      fileManifest: FeatureTaskRuntimePhaseFileManifest? = null,
    ): LaunchResult = ProviderLimited(reason, fileManifest)

    fun projectionRejected(reason: String): LaunchResult =
      InfraFailure(reason, null, FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION, neverLaunched = true)

    fun recordRejected(
      rejectionClass: String,
      rejectionDetail: String,
    ): LaunchResult = RecordRejected(RecordRejection(rejectionClass, rejectionDetail))
  }
}
