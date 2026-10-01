package skillbill.infrastructure.workflow.filesystem

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.host.process.BoundedExternalProcessOutput
import skillbill.infrastructure.host.process.BoundedExternalProcessRequest
import skillbill.infrastructure.host.process.BoundedExternalProcessResult
import skillbill.infrastructure.host.process.BoundedExternalProcessRunner
import skillbill.infrastructure.workflow.review.specialists.checkpointFileIdentity
import skillbill.ports.diff.DiffResolverPort
import skillbill.ports.diff.model.ReviewCommitMetadata
import skillbill.ports.diff.model.ReviewDiffQuery
import skillbill.ports.diff.model.ReviewIndexEntry
import skillbill.ports.review.model.ReviewCheckpointFileIdentity
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

private val diffResolverLog: Logger = Logger.getLogger(FileSystemDiffResolver::class.java.name)

private const val SEAM = "diff_resolver_query"
private const val NUL = '\u0000'
private const val INDEX_METADATA_FIELDS = 3

private val SUCCESS_EXIT = setOf(0)
private val NO_INDEX_EXIT = setOf(0, 1)

@Inject
class FileSystemDiffResolver : DiffResolverPort {
  override fun reviewWorktreeFileIdentities(
    root: Path,
    paths: List<String>,
  ): Map<String, ReviewCheckpointFileIdentity> =
    root.toRealPath().let { realRoot -> paths.associateWith { checkpointFileIdentity(realRoot, it) } }

  override fun readDiff(
    path: Path,
    maxBytes: Long,
  ): String? =
    path.takeIf(Files::isRegularFile)
      ?.takeIf { Files.size(it) <= maxBytes }
      ?.let(Files::readString)
      ?.takeIf(String::isNotBlank)

  override fun resolveCommit(
    repoRoot: Path,
    revision: String,
  ): String? = singleValue("resolveCommit", listOf("git", "rev-parse", "--verify", "$revision^{commit}"), repoRoot)

  override fun mergeBase(
    repoRoot: Path,
    revision: String,
  ): String? = singleValue("mergeBase", listOf("git", "merge-base", "HEAD", revision), repoRoot)

  override fun pullRequestBaseCommit(repoRoot: Path): String? =
    singleValue(
      "pullRequestBaseCommit",
      listOf("gh", "pr", "view", "--json", "baseRefOid", "--jq", ".baseRefOid"),
      repoRoot,
    )

  override fun currentBranchName(repoRoot: Path): String? =
    singleValue("currentBranchName", listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), repoRoot)

  override fun firstParentCommits(
    repoRoot: Path,
    base: String,
    head: String,
  ): List<String>? =
    runQuery(
      "firstParentCommits",
      listOf("git", "rev-list", "--first-parent", "--reverse", "$base..$head"),
      repoRoot,
    )?.lines()?.map(String::trim)?.filter(String::isNotEmpty)

  override fun commitMetadata(
    repoRoot: Path,
    sha: String,
  ): ReviewCommitMetadata? =
    runQuery("commitMetadata", listOf("git", "show", "-s", "--format=%P%n%s", sha), repoRoot)?.let { output ->
      val lines = output.lines()
      ReviewCommitMetadata(
        parentShas = lines.firstOrNull().orEmpty().split(' ').filter(String::isNotBlank),
        subject = lines.drop(1).joinToString("\n").trim(),
      )
    }

  override fun indexEntries(repoRoot: Path): List<ReviewIndexEntry>? =
    runQuery("indexEntries", listOf("git", "ls-files", "--stage", "-z"), repoRoot)?.let { output ->
      val records = output.split(NUL).filter(String::isNotEmpty)
      val entries = records.map { parseIndexEntry(it) }
      if (entries.any { it == null }) {
        recordUnavailable(
          query = "indexEntries",
          used = "null",
          expected = "<mode> <object> <stage>\\t<path> records",
          cause = "malformed index record",
        )
        null
      } else {
        entries.filterNotNull()
      }
    }

  override fun untrackedPaths(repoRoot: Path): List<String>? =
    runQuery("untrackedPaths", listOf("git", "ls-files", "-o", "--exclude-standard", "-z"), repoRoot)
      ?.split(NUL)?.filter(String::isNotEmpty)

  override fun diff(
    repoRoot: Path,
    query: ReviewDiffQuery,
  ): String? =
    when (query) {
      ReviewDiffQuery.Staged -> runQuery("diff.staged", listOf("git", "diff", "--cached"), repoRoot)
      ReviewDiffQuery.Unstaged -> runQuery("diff.unstaged", listOf("git", "diff"), repoRoot)
      is ReviewDiffQuery.CommitRange ->
        runQuery("diff.commitRange", listOf("git", "diff", query.base, query.head), repoRoot)
      is ReviewDiffQuery.PullRequest -> pullRequestDiff(repoRoot, query)
      is ReviewDiffQuery.WorkingTree -> runQuery("diff.workingTree", workingTreeArgv(query), repoRoot)
      is ReviewDiffQuery.UntrackedFile ->
        runQuery(
          "diff.untrackedFile",
          listOf("git", "diff", "--binary", "--no-index", "/dev/null", query.path),
          repoRoot,
          acceptedExitCodes = NO_INDEX_EXIT,
        )
    }

  private fun pullRequestDiff(
    repoRoot: Path,
    query: ReviewDiffQuery.PullRequest,
  ): String? =
    runQuery("diff.pullRequest", listOf("git", "diff", query.base, query.head), repoRoot)
      ?: run {
        recordUnavailable(
          query = "diff.pullRequest",
          used = "gh pr diff",
          expected = "git diff ${query.base} ${query.head}",
          cause = "git diff unavailable, falling back to gh pr diff",
        )
        runQuery("diff.pullRequest.fallback", listOf("gh", "pr", "diff"), repoRoot)
      }

  private fun workingTreeArgv(query: ReviewDiffQuery.WorkingTree): List<String> =
    buildList {
      add("git")
      add("diff")
      if (query.includeBinary) add("--binary")
      add(query.base)
      if (query.pathspec.isNotEmpty()) {
        add("--")
        addAll(query.pathspec)
      }
    }

  private fun singleValue(
    query: String,
    argv: List<String>,
    workDir: Path,
  ): String? = runQuery(query, argv, workDir)?.trim()?.takeIf(String::isNotBlank)

  private fun parseIndexEntry(record: String): ReviewIndexEntry? {
    val tab = record.indexOf('\t')
    val metadata = if (tab < 0) emptyList() else record.substring(0, tab).split(' ')
    val stage = metadata.getOrNull(2)?.toIntOrNull()
    return if (metadata.size != INDEX_METADATA_FIELDS || stage == null) {
      null
    } else {
      ReviewIndexEntry(path = record.substring(tab + 1), mode = metadata[0], objectId = metadata[1], stage = stage)
    }
  }

  private fun runQuery(
    query: String,
    argv: List<String>,
    workDir: Path,
    acceptedExitCodes: Set<Int> = SUCCESS_EXIT,
  ): String? {
    if (Thread.currentThread().isInterrupted) {
      throw InterruptedException("Interrupted before diff query $query")
    }
    val outputFile = createOutputFile(query) ?: return null
    return try {
      val result =
        BoundedExternalProcessRunner.run(
          BoundedExternalProcessRequest(
            argv = argv,
            workingDirectory = workDir,
            mergeStderr = false,
            deadlineSeconds = PROCESS_TIMEOUT_SECONDS,
            output = BoundedExternalProcessOutput.RedirectToFile(outputFile),
          ),
        )
      acceptedOutput(query, argv, result, outputFile, acceptedExitCodes)
    } catch (error: IOException) {
      recordUnavailable(query, "null", "process output", "I/O failure", error)
      null
    } catch (error: InterruptedException) {
      Thread.currentThread().interrupt()
      throw error
    } finally {
      deleteOutputFile(query, outputFile)
    }
  }

  private fun createOutputFile(query: String): Path? =
    try {
      Files.createTempFile("skillbill-diff", ".out")
    } catch (error: IOException) {
      recordUnavailable(query, "null", "process output", "temp file creation failed", error)
      null
    }

  private fun acceptedOutput(
    query: String,
    argv: List<String>,
    result: BoundedExternalProcessResult,
    outputFile: Path,
    acceptedExitCodes: Set<Int>,
  ): String? {
    val readFailure = result.readFailure?.takeUnless { result.timedOut || result.launchFailure }
    val rejection =
      when {
        readFailure != null -> "output could not be read completely"
        result.timedOut -> "timed out after ${PROCESS_TIMEOUT_SECONDS}s"
        result.launchFailure -> "launch failed: ${result.output.take(MAX_CAUSE_CHARS)}"
        result.exitCode !in acceptedExitCodes -> "exit code ${result.exitCode}, expected one of $acceptedExitCodes"
        Files.size(outputFile) > MAX_DIFF_BYTES -> "output exceeds $MAX_DIFF_BYTES bytes"
        else -> null
      }
    if (rejection == null) return result.output
    val expected = "output of ${argv.first()} ${argv.getOrNull(1).orEmpty()}"
    recordUnavailable(query, "null", expected, rejection, readFailure)
    return null
  }

  private fun deleteOutputFile(
    query: String,
    outputFile: Path,
  ) {
    try {
      Files.deleteIfExists(outputFile)
    } catch (error: IOException) {
      diffResolverLog.log(
        Level.WARNING,
        "seam=$SEAM query=$query value_used=leaked_temp_file value_expected=deleted_temp_file " +
          "cause=cleanup failed file=$outputFile",
        error,
      )
    }
  }

  private fun recordUnavailable(
    query: String,
    used: String,
    expected: String,
    cause: String,
    error: Throwable? = null,
  ) {
    val message = "seam=$SEAM query=$query value_used=$used value_expected=$expected cause=$cause"
    if (error == null) {
      diffResolverLog.log(Level.WARNING, message)
    } else {
      diffResolverLog.log(Level.WARNING, message, error)
    }
  }

  private companion object {
    const val PROCESS_TIMEOUT_SECONDS = 120L
    const val MAX_DIFF_BYTES = 50L * 1024 * 1024
    const val MAX_CAUSE_CHARS = 200
  }
}
