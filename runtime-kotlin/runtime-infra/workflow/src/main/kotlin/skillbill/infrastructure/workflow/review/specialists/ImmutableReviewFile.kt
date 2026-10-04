package skillbill.infrastructure.workflow.review.specialists

import skillbill.error.shellcontent.invalidReviewContextSchemaError
import skillbill.infrastructure.host.process.BoundedExternalProcessOutput
import skillbill.infrastructure.host.process.BoundedExternalProcessRequest
import skillbill.infrastructure.host.process.BoundedExternalProcessRunner
import skillbill.infrastructure.workflow.process.GIT_TIMEOUT_SECONDS
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.infrastructure.workflow.review.broker.validateRepositoryMapping
import java.nio.file.Files
import java.nio.file.Path

internal fun readImmutableReviewFile(
  root: Path,
  revision: String,
  path: String,
  maxBytes: Long,
): ByteArray? {
  if (!immutableReviewFileExists(root, revision, path)) return null
  return readImmutableReviewCommand(root, listOf("cat-file", "blob", "$revision:$path"), maxBytes)
}

internal fun immutableReviewFileExists(
  root: Path,
  revision: String,
  path: String,
): Boolean {
  validateRepositoryMapping(root, path)
  val entry = runGitCommand(root, "--literal-pathspecs", "ls-tree", revision, "--", path)
  if (!entry.ok) throw invalidReviewContextSchemaError("review-expansion", "Immutable revision is unavailable.")
  val row = entry.value.orEmpty()
  if (row.isBlank()) return false
  if (!row.startsWith("100644 blob ") && !row.startsWith("100755 blob ")) {
    throw invalidReviewContextSchemaError("review-expansion", "Immutable evidence is not a regular file.")
  }
  return true
}

internal fun readImmutableReviewDelta(
  root: Path,
  base: String,
  head: String,
  path: String,
  maxBytes: Long,
): ByteArray? {
  if (!base.matches(Regex("[a-f0-9]{40,64}")) || !head.matches(Regex("[a-f0-9]{40,64}"))) return null
  validateRepositoryMapping(root, path)
  return readImmutableReviewCommand(
    root,
    listOf("diff", "--no-ext-diff", "--no-textconv", base, head, "--", ":(literal)$path"),
    maxBytes,
  )
}

internal fun readImmutableReviewCommand(
  root: Path,
  args: List<String>,
  maxBytes: Long,
): ByteArray {
  val output = Files.createTempFile("skill-bill-evidence", ".blob")
  try {
    val result =
      BoundedExternalProcessRunner.run(
        BoundedExternalProcessRequest(
          argv = listOf("git", "-C", root.toString()) + args,
          deadlineSeconds = GIT_TIMEOUT_SECONDS,
          output = BoundedExternalProcessOutput.RedirectToFile(output),
        ),
      )
    if (result.timedOut) {
      throw invalidReviewContextSchemaError("review-expansion", "Immutable evidence read timed out.")
    }
    if (result.exitCode != 0) {
      throw invalidReviewContextSchemaError(
        "review-expansion",
        "Immutable evidence read failed.",
      )
    }
    return Files.newInputStream(output).use { it.readNBytes(minOf(maxBytes + 1, Int.MAX_VALUE.toLong()).toInt()) }
  } finally {
    Files.deleteIfExists(output)
  }
}
