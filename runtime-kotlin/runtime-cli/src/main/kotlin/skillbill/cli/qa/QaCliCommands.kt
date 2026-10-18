package skillbill.cli.qa

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import me.tatarka.inject.annotations.Inject
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.DocumentedNoOpCliCommand
import skillbill.cli.kernel.cli.formatOption
import skillbill.cli.kernel.cli.resolveCliRepositoryRoot
import skillbill.cli.model.CliRunInputs
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.qa.QaDeviceLeasePayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.qa.isQaDeviceLeaseFailure
import skillbill.error.featuretask.qa.qaDeviceLeaseReason
import skillbill.ports.qa.QaDeviceLeasePort
import skillbill.ports.qa.model.QaDeviceLease
import skillbill.ports.qa.model.QaLeaseReleaseOutcome
import skillbill.ports.qa.model.QaLeaseResult
import java.security.MessageDigest

@Inject
class QaCommand(
  lease: QaLeaseCommand,
) : DocumentedNoOpCliCommand("qa", "Device helpers for a running `skill-bill phase qa` agent.") {
  init {
    subcommands(lease)
  }
}

@Inject
class QaLeaseCommand(
  private val leases: QaDeviceLeasePort,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "lease",
    "Acquire or release the device lease of one QA invocation. The serial is hashed before it is stored or " +
      "printed. Acquire exits 0 for both acquired and contended; a contended device is a device_contended skip. " +
      "Release exits 0 for both released and not_held.",
  ) {
  private val action by argument(name = "action", help = "acquire or release.").choice(ACQUIRE, RELEASE)
  private val invocation by option("--invocation", help = "QA invocation id from the phase briefing.").required()
  private val device by option("--device", help = "Raw device serial or simulator UDID.").required()
  private val repoRoot by option("--repo-root", help = "Repository root. Defaults to the invocation repository root.")
  private val format by formatOption()

  override fun run() {
    val root = resolveCliRepositoryRoot(repoRoot, inputs)
    val fingerprint = deviceFingerprint(device)
    val (status, detail) =
      try {
        if (action == ACQUIRE) {
          when (val result = leases.acquire(root, fingerprint, invocation)) {
            is QaLeaseResult.Acquired -> LeaseStatus.ACQUIRED to null
            is QaLeaseResult.Contended -> LeaseStatus.CONTENDED to "held by ${result.ownerInvocationId}"
          }
        } else {
          when (val outcome = leases.release(root, QaDeviceLease(fingerprint, invocation))) {
            is QaLeaseReleaseOutcome.Released -> LeaseStatus.RELEASED to null
            is QaLeaseReleaseOutcome.NotHeld -> LeaseStatus.NOT_HELD to "no lease of this invocation"
            is QaLeaseReleaseOutcome.Failed -> LeaseStatus.RETAINED to outcome.detail
          }
        }
      } catch (failure: SkillBillRuntimeException) {
        failure.rethrowUnless(failure.isQaDeviceLeaseFailure)
        LeaseStatus.FAILED to failure.qaDeviceLeaseReason
      }
    state.complete(
      buildMap {
        put(SharedPayloadKeys.STATUS, status.wireValue)
        put(QaDeviceLeasePayloadKeys.DEVICE_FINGERPRINT, fingerprint)
        detail?.let { put(QaDeviceLeasePayloadKeys.REASON, it) }
      },
      format,
      exitCode = if (status.failed) 1 else 0,
    )
  }

  private enum class LeaseStatus(
    val wireValue: String,
    val failed: Boolean = false,
  ) {
    ACQUIRED("acquired"),
    CONTENDED("contended"),
    RELEASED("released"),
    NOT_HELD("not_held"),
    RETAINED("retained", failed = true),
    FAILED("failed", failed = true),
  }

  private companion object {
    const val ACQUIRE = "acquire"
    const val RELEASE = "release"

    fun deviceFingerprint(serial: String): String =
      MessageDigest.getInstance("SHA-256").digest(serial.trim().toByteArray()).joinToString("") { "%02x".format(it) }
  }
}
