package skillbill.infrastructure.workflow.git

import skillbill.infrastructure.contracts.newSha256Digest
import skillbill.infrastructure.host.jvm.requirePathContainedIn
import skillbill.infrastructure.workflow.process.GIT_CHANGED_FILE_SAMPLE_LIMIT
import skillbill.infrastructure.workflow.process.GIT_NUMSTAT_PART_LIMIT
import skillbill.infrastructure.workflow.process.GIT_RENAME_NAME_STATUS_MIN_FIELDS
import skillbill.infrastructure.workflow.process.GIT_STATUS_CODE_LENGTH
import skillbill.infrastructure.workflow.process.GIT_STATUS_MIN_LENGTH
import skillbill.infrastructure.workflow.process.GIT_STATUS_PATH_OFFSET
import skillbill.infrastructure.workflow.process.SelectedDiffBudget
import skillbill.infrastructure.workflow.process.UNTRACKED_FINGERPRINT_BUFFER_BYTES
import skillbill.infrastructure.workflow.process.UNTRACKED_FINGERPRINT_CONTENT_MAX_BYTES
import skillbill.infrastructure.workflow.process.UNTRACKED_LINE_COUNT_BYTE_CAP
import skillbill.infrastructure.workflow.process.UNTRACKED_NON_REGULAR_MARKER
import skillbill.infrastructure.workflow.process.UNTRACKED_UNREADABLE_MARKER
import skillbill.infrastructure.workflow.process.appendSelectedDiffHunks
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.infrastructure.workflow.process.runGitForActivity
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.gitops.RuntimePhaseFileManifestGitOperations
import skillbill.ports.workflow.gitops.SuppressionEvidenceGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.gitops.model.WorkflowScopedPathContent
import skillbill.ports.workflow.gitops.model.WorkflowScopedPathContentsResult
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeNumstatResult
import skillbill.workflow.model.goalobservability.GoalObservabilityChangedFileSummary
import skillbill.workflow.model.goalobservability.GoalObservabilityDiffStat
import skillbill.workflow.model.goalobservability.GoalObservabilityFileDiffStat
import skillbill.workflow.model.goalobservability.GoalObservabilitySelectedDiffHunk
import skillbill.workflow.model.goalobservability.GoalObservabilitySelectedDiffHunks
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal object GitRepositoryFingerprintOperations : RepositoryFingerprintGitOperations {
  override fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult {
    val head = runGitCommand(repoRoot, "rev-parse", "HEAD")
    val staged = runGitCommand(repoRoot, "diff", "--binary", "--cached")
    val unstaged = runGitCommand(repoRoot, "diff", "--binary")
    val untracked = runGitCommand(repoRoot, "ls-files", "--others", "--exclude-standard", "-z")
    val failure = listOf(head, staged, unstaged, untracked).firstOrNull { it !is WorkflowGitOperationResult.Ok }
    if (failure != null) return failure
    return runCatching {
      val digest = newSha256Digest()
      UntrackedFingerprintDigest.digestPart(digest, "head", head.value.orEmpty().toByteArray())
      UntrackedFingerprintDigest.digestPart(digest, "staged", staged.value.orEmpty().toByteArray())
      UntrackedFingerprintDigest.digestPart(digest, "unstaged", unstaged.value.orEmpty().toByteArray())
      val root = repoRoot.normalize()
      untracked.value.orEmpty().split('\u0000').filter(String::isNotBlank).sorted().forEach { path ->
        val resolved = root.resolve(path).normalize()
        if (Files.isSymbolicLink(resolved)) {
          UntrackedFingerprintDigest.digestUntrackedEntry(digest, path, resolved)
          return@forEach
        }
        requirePathContainedIn(resolved, root) { "Untracked path escapes repository root: $path" }
        UntrackedFingerprintDigest.digestUntrackedEntry(digest, path, resolved)
      }
      WorkflowGitOperationResult.Ok(value = digest.digest().joinToString("") { "%02x".format(it) })
    }.getOrElse { error ->
      WorkflowGitOperationResult.Failed(error = "Could not fingerprint repository state: ${error.message}")
    }
  }

  override fun repositoryCheckpointFingerprint(
    repoRoot: Path,
    baseCommit: String?,
    headCommit: String,
    ownedPaths: List<String>,
  ): WorkflowGitOperationResult =
    runCatching {
      val digest = newSha256Digest()
      UntrackedFingerprintDigest.digestPart(digest, "base", baseCommit.orEmpty().toByteArray())
      UntrackedFingerprintDigest.digestPart(digest, "head", headCommit.toByteArray())
      val root = repoRoot.normalize()
      ownedPaths.distinct().sorted().forEach { path ->
        val resolved = root.resolve(path).normalize()
        requirePathContainedIn(resolved, root) { "Checkpoint path escapes repository root: $path" }
        UntrackedFingerprintDigest.digestUntrackedEntry(digest, path, resolved)
      }
      WorkflowGitOperationResult.Ok(value = digest.digest().joinToString("") { "%02x".format(it) })
    }.getOrElse { error ->
      WorkflowGitOperationResult.Failed(
        error = "Could not fingerprint workflow-owned repository checkpoint: ${error.message}",
      )
    }

  fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult {
    val status = runGitCommand(repoRoot, "status", "--porcelain")
    if (status !is WorkflowGitOperationResult.Ok) {
      return WorkflowWorktreeActivityResult(status = WorkflowGitOperationStatus.ERROR, error = status.error)
    }
    val diff = combinedDiffStat(repoRoot)
    return WorkflowWorktreeActivityResult(
      status = WorkflowGitOperationStatus.OK,
      changedFileSummary = parseChangedFileSummary(status.value),
      diffStat = diff,
    )
  }

  fun worktreeNumstat(repoRoot: Path): WorkflowWorktreeNumstatResult {
    val tracked = runGitForActivity(repoRoot, listOf("diff", "--numstat", "HEAD"))
    if (tracked !is WorkflowGitOperationResult.Ok) return numstatFailure(tracked.error)
    val untracked = runGitForActivity(repoRoot, listOf("ls-files", "--others", "--exclude-standard", "-z"))
    if (untracked !is WorkflowGitOperationResult.Ok) return numstatFailure(untracked.error)
    val root = repoRoot.normalize()
    val untrackedEntries =
      untracked.value.split('\u0000')
        .filter(String::isNotBlank)
        .sorted()
        .mapNotNull { path ->
          untrackedLineCount(root, path)?.let { lines ->
            GoalObservabilityFileDiffStat(path = path, insertions = lines, deletions = 0)
          }
        }
    return WorkflowWorktreeNumstatResult(
      status = WorkflowGitOperationStatus.OK,
      files = parseNumstatEntries(tracked.value) + untrackedEntries,
    )
  }

  private fun numstatFailure(error: String): WorkflowWorktreeNumstatResult =
    WorkflowWorktreeNumstatResult(status = WorkflowGitOperationStatus.ERROR, files = emptyList(), error = error)

  private fun untrackedLineCount(
    root: Path,
    path: String,
  ): Int? {
    val resolved = root.resolve(path).normalize()
    val contained =
      runCatching {
        requirePathContainedIn(resolved, root) { "Untracked path escapes repository root: $path" }
      }
    if (contained.isFailure) return null
    if (Files.isSymbolicLink(resolved) || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) return null
    return try {
      Files.newInputStream(resolved).use { input -> countTextLines(input) }
    } catch (_: IOException) {
      null
    }
  }

  private fun countTextLines(input: InputStream): Int? {
    val buffer = ByteArray(UNTRACKED_FINGERPRINT_BUFFER_BYTES)
    var lines = 0
    var consumed = 0L
    var lastByte: Int = -1
    var read = input.read(buffer)
    while (read >= 0 && consumed < UNTRACKED_LINE_COUNT_BYTE_CAP) {
      for (index in 0 until read) {
        val byte = buffer[index].toInt()
        if (byte == 0) return null
        if (byte == NEWLINE_BYTE) lines += 1
        lastByte = byte
      }
      consumed += read
      read = input.read(buffer)
    }
    if (consumed > 0 && lastByte != NEWLINE_BYTE) lines += 1
    return lines
  }

  fun selectedDiffHunks(
    repoRoot: Path,
    request: WorkflowSelectedDiffHunksRequest,
  ): WorkflowSelectedDiffHunksResult {
    if (request.paths.isEmpty() || (!request.includeStaged && !request.includeUnstaged)) {
      return WorkflowSelectedDiffHunksResult(status = WorkflowGitOperationStatus.OK)
    }
    val chunks = mutableListOf<GoalObservabilitySelectedDiffHunk>()
    val results = mutableListOf<WorkflowSelectedDiffHunksResult>()
    val budget = SelectedDiffBudget(request)
    if (request.includeUnstaged) {
      results += appendSelectedDiffHunks(repoRoot, request, staged = false, chunks = chunks, budget = budget)
    }
    if (
      request.includeStaged &&
      results.all { result -> result.status == WorkflowGitOperationStatus.OK } &&
      results.none { result -> result.selectedDiffHunks.truncated }
    ) {
      results += appendSelectedDiffHunks(repoRoot, request, staged = true, chunks = chunks, budget = budget)
    }
    val errorResult = results.firstOrNull { result -> result.status != WorkflowGitOperationStatus.OK }
    return errorResult ?: WorkflowSelectedDiffHunksResult(
      status = WorkflowGitOperationStatus.OK,
      selectedDiffHunks =
        GoalObservabilitySelectedDiffHunks(
          hunks = chunks,
          truncated = results.any { result -> result.selectedDiffHunks.truncated },
        ),
    )
  }
}

internal object UntrackedFingerprintDigest {
  fun digestPart(
    digest: MessageDigest,
    label: String,
    bytes: ByteArray,
  ) {
    digestPartHeader(digest, label, bytes.size.toString())
    digest.update(bytes)
  }

  private fun digestPartHeader(
    digest: MessageDigest,
    label: String,
    length: String,
  ) {
    digest.update(label.toByteArray())
    digest.update(0)
    digest.update(length.toByteArray())
    digest.update(0)
  }

  fun digestUntrackedEntry(
    digest: MessageDigest,
    path: String,
    resolved: Path,
  ) {
    val label = "untracked:$path"
    if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
      digestPart(digest, label, UNTRACKED_NON_REGULAR_MARKER.toByteArray())
      return
    }
    val attributes =
      try {
        Files.readAttributes(resolved, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
      } catch (error: IOException) {
        digestPart(digest, label, "$UNTRACKED_UNREADABLE_MARKER:${error::class.simpleName}".toByteArray())
        return
      }
    if (attributes.size() > UNTRACKED_FINGERPRINT_CONTENT_MAX_BYTES) {
      digestPart(
        digest,
        label,
        "size=${attributes.size()};mtime=${attributes.lastModifiedTime().toMillis()}".toByteArray(),
      )
      return
    }
    digestUntrackedContent(digest, label, resolved, attributes.size())
  }

  private fun digestUntrackedContent(
    digest: MessageDigest,
    label: String,
    resolved: Path,
    declaredSize: Long,
  ) {
    try {
      Files.newInputStream(resolved).use { input ->
        digestPartHeader(digest, label, declaredSize.toString())
        val buffer = ByteArray(UNTRACKED_FINGERPRINT_BUFFER_BYTES)
        var read = input.read(buffer)
        while (read >= 0) {
          digest.update(buffer, 0, read)
          read = input.read(buffer)
        }
      }
    } catch (error: IOException) {
      digestPart(digest, label, "$UNTRACKED_UNREADABLE_MARKER:${error::class.simpleName}".toByteArray())
    }
  }
}

internal object GitRuntimePhaseFileManifestOperations : RuntimePhaseFileManifestGitOperations {
  override fun runtimePhaseHeadCommit(repoRoot: Path): WorkflowGitOperationResult =
    runGitCommand(repoRoot, "rev-parse", "HEAD")

  override fun runtimePhaseChangedPathsBetweenCommits(
    repoRoot: Path,
    beforeCommit: String,
    afterCommit: String,
  ): WorkflowGitNameListResult {
    if (beforeCommit == afterCommit) return WorkflowGitNameListResult.Listed(emptyList())
    val changed = runGitCommand(repoRoot, "diff", "--name-only", beforeCommit, afterCommit)
    if (changed !is WorkflowGitOperationResult.Ok) return WorkflowGitNameListResult.Failed(changed.error)
    return WorkflowGitNameListResult.Listed(
      changed.value.orEmpty().lineSequence().map(String::trim).filter(String::isNotEmpty).toList(),
    )
  }
}

internal object GitSuppressionEvidenceOperations : SuppressionEvidenceGitOperations {
  override fun scopedPathContentsAgainstBase(
    repoRoot: Path,
    baseRef: String,
    headPaths: List<String>,
  ): WorkflowScopedPathContentsResult {
    if (baseRef.isBlank()) {
      return WorkflowScopedPathContentsResult(
        status = WorkflowGitOperationStatus.ERROR,
        error = "Suppression evidence requires a non-blank base ref.",
      )
    }
    val scoped = headPaths.map(String::trim).filter(String::isNotEmpty).distinct()
    if (scoped.isEmpty()) {
      return WorkflowScopedPathContentsResult(status = WorkflowGitOperationStatus.OK, pairs = emptyList())
    }
    val renameToBase = renameBasePaths(repoRoot, baseRef)
    if (renameToBase.status != WorkflowGitOperationStatus.OK) {
      return WorkflowScopedPathContentsResult(status = WorkflowGitOperationStatus.ERROR, error = renameToBase.error)
    }
    val pairs =
      scoped.map { headPath ->
        val basePath = renameToBase.value[headPath] ?: headPath
        val headContent = readWorktreeContent(repoRoot, headPath)
        val baseContent = readContentAtRef(repoRoot, baseRef, basePath)
        WorkflowScopedPathContent(
          headPath = headPath,
          basePath = basePath.takeIf { baseContent != null },
          headContent = headContent,
          baseContent = baseContent,
        )
      }
    return WorkflowScopedPathContentsResult(status = WorkflowGitOperationStatus.OK, pairs = pairs)
  }

  private data class RenameMapResult(
    val status: WorkflowGitOperationStatus,
    val value: Map<String, String> = emptyMap(),
    val error: String = "",
  )

  private fun renameBasePaths(
    repoRoot: Path,
    baseRef: String,
  ): RenameMapResult {
    val diff = runGitCommand(repoRoot, "diff", "-M", "--name-status", "--find-renames", baseRef)
    if (diff !is WorkflowGitOperationResult.Ok) {
      return RenameMapResult(
        status = WorkflowGitOperationStatus.ERROR,
        error = diff.error.ifBlank { "git diff -M --name-status failed." },
      )
    }
    val renames = linkedMapOf<String, String>()
    diff.value.lineSequence()
      .map(String::trim)
      .filter(String::isNotEmpty)
      .forEach { line ->
        val parts = line.split('\t')
        if (parts.size >= GIT_RENAME_NAME_STATUS_MIN_FIELDS && parts[0].startsWith("R")) {
          val oldPath = parts[1]
          val newPath = parts[2]
          if (oldPath.isNotBlank() && newPath.isNotBlank()) {
            renames[newPath] = oldPath
          }
        }
      }
    return RenameMapResult(status = WorkflowGitOperationStatus.OK, value = renames)
  }

  private fun readWorktreeContent(
    repoRoot: Path,
    path: String,
  ): String? {
    val resolved = repoRoot.resolve(path).normalize()
    if (!resolved.startsWith(repoRoot.normalize())) return null
    return try {
      if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
        null
      } else {
        Files.readString(resolved)
      }
    } catch (_: IOException) {
      null
    }
  }

  private fun readContentAtRef(
    repoRoot: Path,
    baseRef: String,
    path: String,
  ): String? {
    val result = runGitCommand(repoRoot, "show", "$baseRef:$path")
    return if (result is WorkflowGitOperationResult.Ok) result.value else null
  }
}

internal fun combinedDiffStat(repoRoot: Path): GoalObservabilityDiffStat {
  val unstaged = runCatchingDiffStat(repoRoot, "diff", "--numstat")
  val staged = runCatchingDiffStat(repoRoot, "diff", "--cached", "--numstat")
  return GoalObservabilityDiffStat(
    filesChanged = unstaged.filesChanged + staged.filesChanged,
    insertions = unstaged.insertions + staged.insertions,
    deletions = unstaged.deletions + staged.deletions,
  )
}

internal fun runCatchingDiffStat(
  repoRoot: Path,
  vararg args: String,
): GoalObservabilityDiffStat {
  val result = runGitForActivity(repoRoot, args.toList())
  return if (result is WorkflowGitOperationResult.Ok) {
    parseDiffStat(result.value)
  } else {
    GoalObservabilityDiffStat(0, 0, 0)
  }
}

internal fun parseChangedFileSummary(statusOutput: String): GoalObservabilityChangedFileSummary {
  var added = 0
  var modified = 0
  var deleted = 0
  var renamed = 0
  var untracked = 0
  val paths = mutableListOf<String>()
  statusOutput.lineSequence()
    .map(String::trimEnd)
    .filter { line -> line.length >= GIT_STATUS_MIN_LENGTH }
    .forEach { line ->
      val status = line.take(GIT_STATUS_CODE_LENGTH)
      val path = line.drop(GIT_STATUS_PATH_OFFSET).substringAfterLast(" -> ").trim()
      if (path.isNotBlank()) paths += path
      when {
        status == "??" -> {
          added += 1
          untracked += 1
        }
        'R' in status -> renamed += 1
        'D' in status -> deleted += 1
        'A' in status -> added += 1
        'M' in status -> modified += 1
      }
    }
  return GoalObservabilityChangedFileSummary(
    total = paths.size,
    added = added,
    modified = modified,
    deleted = deleted,
    renamed = renamed,
    untracked = untracked,
    samplePaths = paths.take(GIT_CHANGED_FILE_SAMPLE_LIMIT),
  )
}

internal fun parseDiffStat(numstatOutput: String): GoalObservabilityDiffStat {
  val entries = parseNumstatEntries(numstatOutput)
  return GoalObservabilityDiffStat(
    filesChanged = entries.size,
    insertions = entries.sumOf(GoalObservabilityFileDiffStat::insertions),
    deletions = entries.sumOf(GoalObservabilityFileDiffStat::deletions),
  )
}

internal fun parseNumstatEntries(numstatOutput: String): List<GoalObservabilityFileDiffStat> =
  numstatOutput.lineSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .mapNotNull { line ->
      val parts = line.split(Regex("\\s+"), limit = GIT_NUMSTAT_PART_LIMIT)
      if (parts.size < GIT_NUMSTAT_PART_LIMIT) return@mapNotNull null
      val insertions = parts[0].toIntOrNull() ?: return@mapNotNull null
      val deletions = parts[1].toIntOrNull() ?: return@mapNotNull null
      GoalObservabilityFileDiffStat(
        path = numstatRenameTarget(parts[2]),
        insertions = insertions,
        deletions = deletions,
      )
    }
    .toList()

private fun numstatRenameTarget(rawPath: String): String {
  if (NUMSTAT_BRACE_RENAME.containsMatchIn(rawPath)) {
    return rawPath.replace(NUMSTAT_BRACE_RENAME, "$2").replace("//", "/")
  }
  return if (" => " in rawPath) rawPath.substringAfter(" => ") else rawPath
}

private val NUMSTAT_BRACE_RENAME = Regex("\\{([^{}]*) => ([^{}]*)\\}")
private const val NEWLINE_BYTE: Int = '\n'.code
