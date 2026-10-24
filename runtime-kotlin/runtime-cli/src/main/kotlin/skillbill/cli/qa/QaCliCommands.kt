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
import skillbill.contracts.qa.QaOwnedResourcePayloadKeys
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.qa.isQaDeviceLeaseFailure
import skillbill.error.featuretask.qa.isQaOwnedResourceFailure
import skillbill.error.featuretask.qa.qaDeviceLeaseReason
import skillbill.error.featuretask.qa.qaOwnedResourceReason
import skillbill.ports.qa.QaDeviceLeasePort
import skillbill.ports.qa.QaOwnedResourcePort
import skillbill.ports.qa.model.QaDeviceLease
import skillbill.ports.qa.model.QaLeaseReleaseOutcome
import skillbill.ports.qa.model.QaLeaseResult
import skillbill.ports.qa.model.QaOwnedResource
import skillbill.ports.qa.model.QaOwnedResourceKind
import skillbill.ports.qa.model.QaResourceRegistration
import skillbill.ports.qa.model.QaResourceReleaseOutcome
import java.nio.file.Path
import java.security.MessageDigest

@Inject
class QaCommand(
  lease: QaLeaseCommand,
  resource: QaResourceCommand,
) : DocumentedNoOpCliCommand("qa", "Device and environment helpers for a running `skill-bill phase qa` agent.") {
  init {
    subcommands(lease, resource)
  }
}

@Inject
class QaResourceCommand(
  private val resources: QaOwnedResourcePort,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "resource",
    "Register or release one environment resource (Compose project, container, volume, network, process or " +
      "directory) of one QA invocation. Register exits 0 for registered and conflict and 1 for rejected; a " +
      "conflict names the owning invocation. Release tears the resource down and exits 0 for released and " +
      "not_held and 1 for retained. The runtime tears down everything still registered when QA ends.",
  ) {
  private val action by argument(name = "action", help = "register or release.").choice(REGISTER, RELEASE)
  private val invocation by option("--invocation", help = "QA invocation id from the phase briefing.").required()
  private val kind by option("--kind", help = "Resource kind.")
    .choice(QaOwnedResourceKind.entries.associateBy { it.wireValue })
    .required()
  private val identity by option("--identity", help = "Resource name, process id or directory path.").required()
  private val repoRoot by option("--repo-root", help = "Repository root. Defaults to the invocation repository root.")
  private val format by formatOption()

  override fun run() {
    val root = resolveCliRepositoryRoot(repoRoot, inputs)
    val resource = QaOwnedResource(kind, identity)
    val reply =
      try {
        if (action == REGISTER) register(root, resource) else release(root, resource)
      } catch (failure: SkillBillRuntimeException) {
        failure.rethrowUnless(failure.isQaOwnedResourceFailure)
        ResourceReply(ResourceStatus.FAILED, QaDeviceLeasePayloadKeys.REASON, failure.qaOwnedResourceReason)
      }
    state.complete(
      buildMap {
        put(QaOwnedResourcePayloadKeys.STATUS, reply.status.wireValue)
        put(QaOwnedResourcePayloadKeys.KIND, resource.kind.wireValue)
        put(QaOwnedResourcePayloadKeys.IDENTITY, resource.identity)
        reply.extraKey?.let { key -> reply.extraValue?.let { value -> put(key, value) } }
      },
      format,
      exitCode = if (reply.status.failed) 1 else 0,
    )
  }

  private fun register(
    root: Path,
    resource: QaOwnedResource,
  ): ResourceReply =
    when (val result = resources.register(root, invocation, resource)) {
      QaResourceRegistration.Registered -> ResourceReply(ResourceStatus.REGISTERED)
      is QaResourceRegistration.Conflict ->
        ResourceReply(
          ResourceStatus.CONFLICT,
          QaOwnedResourcePayloadKeys.OWNER_INVOCATION_ID,
          result.ownerInvocationId,
        )
      is QaResourceRegistration.Rejected ->
        ResourceReply(ResourceStatus.REJECTED, QaDeviceLeasePayloadKeys.REASON, result.reason)
    }

  private fun release(
    root: Path,
    resource: QaOwnedResource,
  ): ResourceReply =
    when (val outcome = resources.release(root, invocation, resource)) {
      is QaResourceReleaseOutcome.Released -> ResourceReply(ResourceStatus.RELEASED)
      is QaResourceReleaseOutcome.NotHeld -> ResourceReply(ResourceStatus.NOT_HELD)
      is QaResourceReleaseOutcome.Failed ->
        ResourceReply(ResourceStatus.RETAINED, QaDeviceLeasePayloadKeys.REASON, outcome.detail)
    }

  private data class ResourceReply(
    val status: ResourceStatus,
    val extraKey: String? = null,
    val extraValue: String? = null,
  )

  private enum class ResourceStatus(
    val wireValue: String,
    val failed: Boolean = false,
  ) {
    REGISTERED("registered"),
    CONFLICT("conflict"),
    REJECTED("rejected", failed = true),
    RELEASED("released"),
    NOT_HELD("not_held"),
    RETAINED("retained", failed = true),
    FAILED("failed", failed = true),
  }

  private companion object {
    const val REGISTER = "register"
    const val RELEASE = "release"
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
