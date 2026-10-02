package skillbill.infrastructure.workflow.featuretask

import com.fasterxml.jackson.databind.ObjectMapper
import me.tatarka.inject.annotations.Inject
import skillbill.contracts.review.ReviewVerificationSignalKeys
import skillbill.error.shellcontent.ReviewHunkEvidenceLocatorMissingError
import skillbill.error.shellcontent.ReviewHunkEvidenceLocatorUnreadableError
import skillbill.infrastructure.host.jvm.deleteRecursively
import skillbill.infrastructure.host.jvm.pathContainedIn
import skillbill.infrastructure.host.jvm.replaceDirectory
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceDeriver
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceLocatorReadPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeSharedEvidenceResolverPort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceDerivation
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceLocatorReadRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceRequest
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolution
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeSharedEvidenceResolveOutcome
import skillbill.workflow.taskruntime.artifact.FeatureTaskRuntimeRunEvidenceAddress
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceArtifact
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceDiffPayloadRef
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

internal val sharedEvidenceStoreLog: Logger =
  Logger.getLogger("skillbill.infrastructure.workflow.FileSystemFeatureTaskRuntimeSharedEvidenceStore")

internal fun degraded(
  seam: String,
  used: String,
  expected: String,
  cause: String,
): Nothing? {
  sharedEvidenceStoreLog.warning(
    "shared review evidence cache degraded: seam=$seam used=$used expected=$expected cause=$cause",
  )
  return null
}

internal const val SHARED_EVIDENCE_ENVELOPE_FILE: String = "evidence.json"
internal const val SHARED_EVIDENCE_PAYLOAD_FILE: String = "diff.patch"

@Inject
open class FileSystemFeatureTaskRuntimeSharedEvidenceStore :
  FeatureTaskRuntimeSharedEvidenceResolverPort,
  FeatureTaskRuntimeSharedEvidenceLocatorReadPort {
  private val mapper: ObjectMapper by lazy { ObjectMapper() }

  override fun resolve(
    request: FeatureTaskRuntimeSharedEvidenceRequest,
    deriver: FeatureTaskRuntimeSharedEvidenceDeriver,
  ): FeatureTaskRuntimeSharedEvidenceResolution? {
    val fingerprint = request.checkpoint.fingerprint
    val artifactDir = artifactDir(request)
    val storePath = storePath(request.repoRoot, artifactDir)
    readStored(mapper, artifactDir, fingerprint, request.workflowId, storePath)?.let {
      return it.copy(storePath = storePath, outcome = FeatureTaskRuntimeSharedEvidenceResolveOutcome.REUSE)
    }
    val outcome =
      if (siblingFingerprintsExist(artifactDir)) {
        FeatureTaskRuntimeSharedEvidenceResolveOutcome.CHECKPOINT_CHANGE_REDERIVATION
      } else {
        FeatureTaskRuntimeSharedEvidenceResolveOutcome.DERIVATION
      }
    val derivation = deriver.derive(request.checkpoint) ?: return null
    return persist(artifactDir, fingerprint, derivation)
      .copy(storePath = storePath, outcome = outcome)
  }

  override fun readDiffPayload(request: FeatureTaskRuntimeSharedEvidenceLocatorReadRequest): String {
    val repoRoot = request.repoRoot.toAbsolutePath().normalize()
    val artifactDir = repoRoot.resolve(request.storePath).normalize()
    val storeRoot = repoRoot.resolve(".skill-bill").resolve("run-evidence").normalize()
    if (!pathContainedIn(artifactDir, storeRoot) || !Files.isDirectory(artifactDir)) {
      throw ReviewHunkEvidenceLocatorMissingError(request.storePath)
    }
    val fingerprint = artifactDir.fileName.toString()
    val workflowId = artifactDir.parent.fileName.toString()
    val publishedPath = storePath(request.repoRoot, artifactDir)
    val stored =
      readStored(mapper, artifactDir, fingerprint, workflowId, publishedPath)
        ?: throw ReviewHunkEvidenceLocatorUnreadableError(
          request.storePath,
          "stored artifact is missing, truncated, or unreadable",
        )
    val payloadPath = artifactDir.resolve(request.payloadFile)
    if (!Files.isRegularFile(payloadPath)) {
      throw ReviewHunkEvidenceLocatorUnreadableError(request.storePath, "payload file is not a regular file")
    }
    return stored.diffPayload
  }

  private fun siblingFingerprintsExist(artifactDir: Path): Boolean {
    val parent = artifactDir.parent ?: return false
    if (!Files.isDirectory(parent)) return false
    return Files.list(parent).use { stream ->
      stream.anyMatch { candidate ->
        candidate.fileName.toString() != artifactDir.fileName.toString() &&
          !candidate.fileName.toString().contains(STAGING_SUFFIX) &&
          Files.isDirectory(candidate)
      }
    }
  }

  private fun persist(
    artifactDir: Path,
    fingerprint: String,
    derivation: FeatureTaskRuntimeSharedEvidenceDerivation,
  ): FeatureTaskRuntimeSharedEvidenceResolution {
    val payloadBytes = derivation.diffPayload.toByteArray()
    val artifact =
      FeatureTaskRuntimeSharedEvidenceArtifact(
        fingerprint = fingerprint,
        baseRef = derivation.baseRef,
        headRef = derivation.headRef,
        files = derivation.files,
        hunks = derivation.hunks,
        diffPayload =
          FeatureTaskRuntimeSharedEvidenceDiffPayloadRef(
            SHARED_EVIDENCE_PAYLOAD_FILE,
            payloadBytes.size.toLong(),
          ),
      )
    Files.createDirectories(artifactDir.parent)
    val staging = Files.createTempDirectory(artifactDir.parent, "${artifactDir.fileName}$STAGING_SUFFIX")
    try {
      writeStaged(staging, payloadBytes, mapper.writeValueAsString(envelopeOf(artifact)))
      publish(staging, artifactDir)
    } finally {
      deleteRecursively(staging)
    }
    return FeatureTaskRuntimeSharedEvidenceResolution(artifact, derivation.diffPayload)
  }

  internal open fun writeStaged(
    staging: Path,
    payloadBytes: ByteArray,
    envelopeJson: String,
  ) {
    Files.write(staging.resolve(SHARED_EVIDENCE_PAYLOAD_FILE), payloadBytes)
    Files.writeString(staging.resolve(SHARED_EVIDENCE_ENVELOPE_FILE), envelopeJson)
  }

  private fun publish(
    staging: Path,
    artifactDir: Path,
  ) {
    replaceDirectory(staging, artifactDir) { cause ->
      degraded(
        seam = "artifact_publish",
        used = "directory replacement fallback at $artifactDir",
        expected = "ATOMIC_MOVE of $staging",
        cause = cause,
      )
    }
  }

  private fun envelopeOf(artifact: FeatureTaskRuntimeSharedEvidenceArtifact): Map<String, Any?> =
    linkedMapOf(
      ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT to artifact.fingerprint,
      "base_ref" to artifact.baseRef,
      "head_ref" to artifact.headRef,
      "files" to artifact.files.map { mapOf("path" to it.path, "change_kind" to it.changeKind) },
      "hunks" to artifact.hunks.map { mapOf("path" to it.path, "header" to it.header) },
      "diff_payload" to
        mapOf(
          "relative_path" to artifact.diffPayload.relativePath,
          "size_bytes" to artifact.diffPayload.sizeBytes,
        ),
    )

  internal companion object {
    const val ENVELOPE_FILE_NAME: String = SHARED_EVIDENCE_ENVELOPE_FILE
    const val PAYLOAD_FILE_NAME: String = SHARED_EVIDENCE_PAYLOAD_FILE
    private const val STAGING_SUFFIX: String = ".staging."
  }
}

internal fun storePath(
  repoRoot: Path,
  artifactDir: Path,
): String =
  runCatching { repoRoot.toAbsolutePath().normalize().relativize(artifactDir).toString() }
    .getOrNull()
    ?.takeIf { it.isNotBlank() && !it.startsWith("..") }
    ?: artifactDir.toString()

internal fun artifactDir(request: FeatureTaskRuntimeSharedEvidenceRequest): Path =
  request.repoRoot
    .resolve(".skill-bill")
    .resolve("run-evidence")
    .resolve(FeatureTaskRuntimeRunEvidenceAddress.pathSegment(request.workflowId))
    .resolve(FeatureTaskRuntimeRunEvidenceAddress.pathSegment(request.checkpoint.fingerprint))
    .toAbsolutePath()
    .normalize()
