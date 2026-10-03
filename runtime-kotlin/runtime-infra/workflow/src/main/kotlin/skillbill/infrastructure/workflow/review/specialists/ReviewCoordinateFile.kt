package skillbill.infrastructure.workflow.review.specialists

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import skillbill.error.shellcontent.invalidReviewContextSchemaError
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.infrastructure.workflow.review.broker.FileSystemReviewEvidenceBrokerReadState
import skillbill.infrastructure.workflow.review.broker.digest
import skillbill.infrastructure.workflow.review.broker.rejectCheckpointDrift
import skillbill.infrastructure.workflow.review.broker.resolveRepositoryFile
import skillbill.infrastructure.workflow.review.broker.validateRepositoryMapping
import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import skillbill.ports.review.model.ReviewEvidenceCoordinates

internal fun bindReviewCoordinates(
  root: Path,
  coordinates: ReviewEvidenceCoordinates,
): ReviewEvidenceCoordinates =
  when (coordinates) {
    is ReviewEvidenceCoordinates.Checkpoint -> coordinates.copy(files = coordinates.files.toMap())
    is ReviewEvidenceCoordinates.Committed -> {
      val resolved =
        runGitCommand(
          root,
          "rev-parse",
          "--verify",
          "--end-of-options",
          "${coordinates.revision}^{commit}",
        )
      val revision = resolved.value.orEmpty().trim()
      if (!resolved.ok || !revision.matches(Regex("[a-f0-9]{40}|[a-f0-9]{64}"))) {
        throw invalidReviewContextSchemaError("review-source", "Committed evidence revision is unavailable.")
      }
      ReviewEvidenceCoordinates.Committed(revision)
    }
  }

internal fun validateReviewCoordinateFile(
  state: FileSystemReviewEvidenceBrokerReadState,
  coordinates: ReviewEvidenceCoordinates,
  path: String,
) {
  val available =
    when (coordinates) {
      is ReviewEvidenceCoordinates.Committed -> immutableReviewFileExists(state.root, coordinates.revision, path)
      is ReviewEvidenceCoordinates.Checkpoint -> {
        val expected =
          coordinates.files[path] as? ReviewCheckpointFileIdentity.Regular
            ?: throw invalidReviewContextSchemaError(
              "review-source",
              "Expanded path was not a regular checkpoint file.",
            )
        if (coordinates.kind == ReviewEvidenceCoordinates.Checkpoint.Kind.INDEX) {
          readCheckpointIndex(state, path, expected.digest)
        } else if (checkpointFileIdentity(state.root, path) != expected) {
          rejectCheckpointDrift(state, path)
        }
        true
      }
    }
  if (!available) {
    throw invalidReviewContextSchemaError("review-expansion", "Expanded file is absent at the selected revision.")
  }
}

internal fun readReviewCoordinateFile(
  state: FileSystemReviewEvidenceBrokerReadState,
  coordinates: ReviewEvidenceCoordinates,
  path: String,
): ByteArray? =
  when (coordinates) {
    is ReviewEvidenceCoordinates.Committed ->
      readImmutableReviewFile(state.root, coordinates.revision, path, state.budget.maxEvidenceResultBytes)
    is ReviewEvidenceCoordinates.Checkpoint -> readCheckpointFile(state, coordinates, path)
  }

private fun readCheckpointFile(
  state: FileSystemReviewEvidenceBrokerReadState,
  coordinates: ReviewEvidenceCoordinates.Checkpoint,
  path: String,
): ByteArray? {
  if (path !in coordinates.files) {
    throw invalidReviewContextSchemaError("review-source", "Expanded path was absent from the reviewed checkpoint.")
  }
  val identity = coordinates.files.getValue(path)
  if (identity is ReviewCheckpointFileIdentity.Unavailable) {
    throw invalidReviewContextSchemaError(
      "review-source",
      "Expanded path was unavailable in the reviewed checkpoint: ${identity.name}.",
    )
  }
  validateRepositoryMapping(state.root, path)
  val expected = (identity as? ReviewCheckpointFileIdentity.Regular)?.digest
  if (coordinates.kind == ReviewEvidenceCoordinates.Checkpoint.Kind.INDEX) {
    return readCheckpointIndex(state, path, expected)
  }
  val real = resolveRepositoryFile(state.root, path)
  if (real == null) {
    if (expected != null) rejectCheckpointDrift(state, path)
    return null
  }
  val bytes =
    Files.newInputStream(real, NOFOLLOW_LINKS).use {
      it.readNBytes((state.budget.maxEvidenceResultBytes + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
  if (bytes.size.toLong() > state.budget.maxEvidenceResultBytes) return bytes
  val digest = digest(bytes)
  if (expected == null || digest != expected) rejectCheckpointDrift(state, path)
  return bytes
}

private fun readCheckpointIndex(
  state: FileSystemReviewEvidenceBrokerReadState,
  path: String,
  expected: String?,
): ByteArray {
  val entry = runGitCommand(state.root, "--literal-pathspecs", "ls-files", "--stage", "--", path)
  val row = entry.value.orEmpty().trim()
  if (!entry.ok || row.split(' ').getOrNull(1) != expected) rejectCheckpointDrift(state, path)
  if (!row.startsWith("100644 ") && !row.startsWith("100755 ")) {
    throw invalidReviewContextSchemaError("review-source", "Index evidence is not a regular file.")
  }
  return readImmutableReviewCommand(
    state.root,
    listOf(
      "cat-file",
      "blob",
      requireNotNull(expected),
    ),
    state.budget.maxEvidenceResultBytes,
  )
}
