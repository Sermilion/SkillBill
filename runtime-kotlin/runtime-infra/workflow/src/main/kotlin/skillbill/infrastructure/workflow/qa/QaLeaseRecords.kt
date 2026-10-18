package skillbill.infrastructure.workflow.qa

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import skillbill.contracts.qa.QA_DEVICE_LEASE_CONTRACT_VERSION
import skillbill.error.featuretask.qa.qaDeviceLeaseFailed
import skillbill.infrastructure.workflow.process.runGitProcess
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.format.DateTimeParseException
import skillbill.contracts.qa.QaDeviceLeasePayloadKeys as Keys

private const val MIN_OWNER_TOKEN_LENGTH = 16
private val FINGERPRINT = Regex("^[0-9a-f]{64}$")
private val INVOCATION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
private const val LEASES_DIRECTORY = "leases"

internal data class QaInvocationOwner(
  val invocationId: String,
  val ownerToken: String,
  val process: FeatureTaskRuntimeProcessIdentity,
  val acquiredAt: Instant,
)

internal data class QaLeaseRecord(
  val deviceFingerprint: String,
  val owner: QaInvocationOwner,
)

internal object QaPaths {
  fun qaRoot(repoRoot: Path): Path {
    val probe = runGitProcess(repoRoot, listOf("rev-parse", "--git-common-dir"))
    val output = probe.output.trim()
    val probeFailed = probe.timedOut || probe.readFailure != null || probe.exitCode != 0
    if (probeFailed || output.isEmpty()) {
      throw qaDeviceLeaseFailed("$repoRoot is not a Git working tree: ${probe.readFailure?.message ?: output}")
    }
    return repoRoot.toAbsolutePath().resolve(output).normalize().resolve("skill-bill").resolve("qa")
  }

  fun invocationDirectory(
    qaRoot: Path,
    invocationId: String,
  ): Path {
    if (!INVOCATION_ID.matches(invocationId) || invocationId == LEASES_DIRECTORY) {
      throw qaDeviceLeaseFailed("'$invocationId' is not a QA invocation id.")
    }
    return qaRoot.resolve(invocationId)
  }

  fun ownerFile(invocationDirectory: Path): Path = invocationDirectory.resolve("owner.json")

  fun leaseFile(
    qaRoot: Path,
    deviceFingerprint: String,
  ): Path {
    if (!FINGERPRINT.matches(deviceFingerprint)) {
      throw qaDeviceLeaseFailed("a device fingerprint must be 64 lowercase hex characters.")
    }
    return leasesDirectory(qaRoot).resolve("$deviceFingerprint.json")
  }

  fun leasesDirectory(qaRoot: Path): Path = qaRoot.resolve(LEASES_DIRECTORY)
}

internal object QaLeaseRecordCodec {
  private val mapper = ObjectMapper()

  fun encodeOwner(owner: QaInvocationOwner): String =
    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ownerNode(owner))

  fun encodeLease(lease: QaLeaseRecord): String {
    val node = ownerNode(lease.owner)
    node.put(Keys.DEVICE_FINGERPRINT, lease.deviceFingerprint)
    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
  }

  fun decodeOwner(
    source: String,
    label: String,
  ): QaInvocationOwner = ownerOf(objectOf(source, label, Keys.OWNER_FIELDS), label)

  fun decodeLease(
    source: String,
    label: String,
  ): QaLeaseRecord {
    val root = objectOf(source, label, Keys.LEASE_FIELDS)
    val fingerprint = text(root, Keys.DEVICE_FINGERPRINT, label)
    if (!FINGERPRINT.matches(fingerprint)) fail(label, "device_fingerprint is not a sha256 hex digest")
    return QaLeaseRecord(fingerprint, ownerOf(root, label))
  }

  private fun ownerNode(owner: QaInvocationOwner): ObjectNode {
    val root = mapper.createObjectNode()
    root.put(Keys.CONTRACT_VERSION, QA_DEVICE_LEASE_CONTRACT_VERSION)
    root.put(Keys.INVOCATION_ID, owner.invocationId)
    root.put(Keys.OWNER_TOKEN, owner.ownerToken)
    val process = root.putObject(Keys.PROCESS)
    process.put(Keys.HOST_IDENTITY, owner.process.hostIdentity)
    process.put(Keys.BOOT_IDENTITY, owner.process.bootIdentity)
    process.put(Keys.PID, owner.process.pid)
    process.put(Keys.PROCESS_BIRTH_TOKEN, owner.process.processBirthToken)
    root.put(Keys.ACQUIRED_AT, owner.acquiredAt.toString())
    return root
  }

  private fun objectOf(
    source: String,
    label: String,
    fields: Set<String>,
  ): JsonNode {
    val root =
      try {
        mapper.readTree(source)
      } catch (failure: JsonProcessingException) {
        fail(label, "the file is not valid JSON", failure)
      }
    if (root == null || !root.isObject) fail(label, "the root is not an object")
    requireExactKeys(root, fields, label)
    val version = text(root, Keys.CONTRACT_VERSION, label)
    if (version != QA_DEVICE_LEASE_CONTRACT_VERSION) {
      fail(label, "contract_version '$version' is not $QA_DEVICE_LEASE_CONTRACT_VERSION")
    }
    return root
  }

  private fun ownerOf(
    root: JsonNode,
    label: String,
  ): QaInvocationOwner {
    val invocationId = text(root, Keys.INVOCATION_ID, label)
    if (!INVOCATION_ID.matches(invocationId)) fail(label, "invocation_id is malformed")
    val ownerToken = text(root, Keys.OWNER_TOKEN, label)
    if (ownerToken.length < MIN_OWNER_TOKEN_LENGTH) fail(label, "owner_token is too short")
    val process = root.path(Keys.PROCESS)
    if (!process.isObject) fail(label, "process is not an object")
    requireExactKeys(process, Keys.PROCESS_FIELDS, label)
    val pid = process.path(Keys.PID)
    if (!pid.isIntegralNumber || pid.asLong() < 1) fail(label, "process.pid is not a positive integer")
    val acquiredAt =
      try {
        Instant.parse(text(root, Keys.ACQUIRED_AT, label))
      } catch (failure: DateTimeParseException) {
        fail(label, "acquired_at is not an instant", failure)
      }
    return QaInvocationOwner(
      invocationId = invocationId,
      ownerToken = ownerToken,
      process =
        FeatureTaskRuntimeProcessIdentity(
          hostIdentity = text(process, Keys.HOST_IDENTITY, label),
          bootIdentity = text(process, Keys.BOOT_IDENTITY, label),
          pid = pid.asLong(),
          processBirthToken = text(process, Keys.PROCESS_BIRTH_TOKEN, label),
        ),
      acquiredAt = acquiredAt,
    )
  }

  private fun requireExactKeys(
    node: JsonNode,
    fields: Set<String>,
    label: String,
  ) {
    val names = node.fieldNames().asSequence().toSet()
    val unknown = names - fields
    val missing = fields - names
    if (unknown.isNotEmpty() || missing.isNotEmpty()) {
      fail(label, "unexpected fields $unknown or missing fields $missing")
    }
  }

  private fun text(
    node: JsonNode,
    field: String,
    label: String,
  ): String =
    node.path(field).takeIf { it.isTextual && it.asText().isNotBlank() }?.asText()
      ?: fail(label, "$field is not a non-blank string")

  private fun fail(
    label: String,
    reason: String,
    cause: Throwable? = null,
  ): Nothing = throw qaDeviceLeaseFailed("$label is malformed: $reason", cause)
}

internal fun readQaRecord(file: Path): String? =
  try {
    if (Files.exists(file)) Files.readString(file) else null
  } catch (failure: IOException) {
    throw qaDeviceLeaseFailed("could not read $file: ${failure.message}", failure)
  }

internal fun writeQaRecordAtomically(
  file: Path,
  content: String,
) {
  val temporary = file.resolveSibling("${file.fileName}.tmp")
  try {
    Files.createDirectories(file.parent)
    Files.writeString(temporary, content)
    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  } catch (failure: IOException) {
    throw qaDeviceLeaseFailed("could not write $file: ${failure.message}", failure)
  }
}
