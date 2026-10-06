package skillbill.infrastructure.workflow.git.scoped

import skillbill.infrastructure.workflow.process.GitProcessResult
import skillbill.infrastructure.workflow.process.gitTimedOutError
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.infrastructure.workflow.process.runGitCommandWithStdin
import skillbill.infrastructure.workflow.process.runGitProcess
import skillbill.ports.workflow.gitops.ScopedStagingGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshot
import skillbill.ports.workflow.gitops.model.WorkflowGitIndexSnapshotResult
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowPathContentIdentitiesResult
import java.nio.file.Files
import java.nio.file.Path

internal const val GIT_NUL: Char = '\u0000'

private const val INDEX_REMOVAL_MODE = "0"
private const val INDEX_REMOVAL_OBJECT = "0000000000000000000000000000000000000000"

private const val PATHSPEC_BATCH_SIZE = 200

private const val FEATURE_SPEC_ROOT = ".feature-specs"

internal object GitScopedStagingOperations : ScopedStagingGitOperations {
  override fun stagePaths(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowGitOperationResult {
    val normalized = paths.filter(String::isNotBlank).distinct()
    if (normalized.isEmpty()) return WorkflowGitOperationResult.Ok(value = "")
    val resolved = resolveStageablePaths(repoRoot, normalized)
    if (resolved !is WorkflowGitOperationResult.Ok) return resolved
    val stageable = resolved.value.orEmpty().split(GIT_NUL).filter(String::isNotBlank)
    stageable.chunked(PATHSPEC_BATCH_SIZE).forEach { batch ->
      val staged = runGitCommand(repoRoot, listOf("add", "--all", "--") + batch)
      if (staged !is WorkflowGitOperationResult.Ok) return staged
    }
    return WorkflowGitOperationResult.Ok(value = "")
  }

  override fun captureIndexState(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowGitIndexSnapshotResult {
    val normalized = paths.filter(String::isNotBlank).distinct()
    if (normalized.isEmpty()) return WorkflowGitIndexSnapshotResult.Captured(WorkflowGitIndexSnapshot.EMPTY)
    val entries = mutableListOf<String>()
    normalized.chunked(PATHSPEC_BATCH_SIZE).forEach { batch ->
      val listed = runGitCommand(repoRoot, listOf("ls-files", "--stage", "-z", "--") + batch)
      if (listed !is WorkflowGitOperationResult.Ok) return WorkflowGitIndexSnapshotResult.Failed(listed.error)
      entries += listed.value.orEmpty().split(GIT_NUL).filter(String::isNotBlank)
    }
    return WorkflowGitIndexSnapshotResult.Captured(
      WorkflowGitIndexSnapshot(entries.joinToString(GIT_NUL.toString())),
    )
  }

  override fun restoreIndexState(
    repoRoot: Path,
    paths: List<String>,
    snapshot: WorkflowGitIndexSnapshot,
  ): WorkflowGitOperationResult {
    val normalized = paths.filter(String::isNotBlank).distinct()
    if (normalized.isEmpty()) return WorkflowGitOperationResult.Ok(value = "")
    val entries = snapshot.encoded.split(GIT_NUL).filter(String::isNotBlank)
    val snapshotPaths = entries.mapNotNull(::indexEntryPath).toSet()

    val removals =
      normalized.filterNot { it in snapshotPaths }
        .map { "$INDEX_REMOVAL_MODE $INDEX_REMOVAL_OBJECT\t$it" }
    val records = entries + removals
    if (records.isEmpty()) return WorkflowGitOperationResult.Ok(value = "")
    val stdin = records.joinToString(GIT_NUL.toString(), postfix = GIT_NUL.toString()).toByteArray()
    return runGitCommandWithStdin(repoRoot, listOf("update-index", "-z", "--index-info"), stdin)
  }

  override fun stagedPaths(repoRoot: Path): WorkflowGitNameListResult {
    val staged = runGitCommand(repoRoot, "diff", "--cached", "--name-only", "-z")
    if (staged !is WorkflowGitOperationResult.Ok) return WorkflowGitNameListResult.Failed(staged.error)
    return WorkflowGitNameListResult.Listed(staged.value.orEmpty().split(GIT_NUL).filter(String::isNotEmpty))
  }

  override fun pathContentIdentities(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowPathContentIdentitiesResult {
    val present =
      paths.filter(String::isNotBlank).distinct().sorted()
        .filter { Files.isRegularFile(repoRoot.resolve(it)) }
    if (present.isEmpty()) return WorkflowPathContentIdentitiesResult.Resolved(emptyMap())
    val identities = linkedMapOf<String, String>()
    present.chunked(PATHSPEC_BATCH_SIZE).forEach { batch ->
      val hashed = runGitCommand(repoRoot, listOf("hash-object", "--") + batch)
      if (hashed !is WorkflowGitOperationResult.Ok) return WorkflowPathContentIdentitiesResult.Failed(hashed.error)
      val hashes = hashed.value.orEmpty().lineSequence().map(String::trim).filter(String::isNotBlank).toList()
      if (hashes.size != batch.size) {
        return WorkflowPathContentIdentitiesResult.Failed(
          "git hash-object returned ${hashes.size} identities for ${batch.size} paths.",
        )
      }
      identities.putAll(batch.zip(hashes))
    }
    return WorkflowPathContentIdentitiesResult.Resolved(identities)
  }

  override fun gitignoredFeatureSpecPaths(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowGitOperationResult {
    val ignored = gitignoredFeatureSpecs(repoRoot, paths)
    if (ignored !is WorkflowGitOperationResult.Ok) return ignored
    return WorkflowGitOperationResult.Ok(
      value = ignored.value.orEmpty().split(GIT_NUL).filter(String::isNotBlank).joinToString("\n"),
    )
  }

  private fun indexEntryPath(entry: String): String? =
    entry.substringAfter('\t', missingDelimiterValue = "").takeIf(String::isNotBlank)

  private fun resolveStageablePaths(
    repoRoot: Path,
    normalized: List<String>,
  ): WorkflowGitOperationResult {
    val indexed = mutableSetOf<String>()
    for (batch in normalized.chunked(PATHSPEC_BATCH_SIZE)) {
      val listed = runGitCommand(repoRoot, listOf("ls-files", "--stage", "-z", "--") + batch)
      if (listed !is WorkflowGitOperationResult.Ok) return listed
      listed.value.orEmpty().split(GIT_NUL).filter(String::isNotBlank).forEach { entry ->
        indexEntryPath(entry)?.let { indexed += it }
      }
    }
    val materialized = materializePathspecs(repoRoot, normalized)
    val presentOrIndexed = materialized.filter { Files.isRegularFile(repoRoot.resolve(it)) || it in indexed }
    val ignoredUntracked = ignoredUntrackedPaths(repoRoot, presentOrIndexed)
    if (ignoredUntracked !is WorkflowGitOperationResult.Ok) return ignoredUntracked
    val ignoredFeatureSpecs = gitignoredFeatureSpecs(repoRoot, presentOrIndexed)
    if (ignoredFeatureSpecs !is WorkflowGitOperationResult.Ok) return ignoredFeatureSpecs
    val ignoredSet =
      (
        ignoredUntracked.value.orEmpty().split(GIT_NUL) +
          ignoredFeatureSpecs.value.orEmpty().split(GIT_NUL)
      ).filter(String::isNotBlank).toSet()
    val stageable = presentOrIndexed.filterNot { it in ignoredSet }
    return WorkflowGitOperationResult.Ok(value = stageable.joinToString(GIT_NUL.toString()))
  }

  private fun materializePathspecs(
    repoRoot: Path,
    paths: List<String>,
  ): List<String> {
    val materialized = LinkedHashSet<String>()
    for (raw in paths) {
      val relative = raw.trim().removeSuffix("/")
      if (relative.isEmpty()) continue
      val resolved = repoRoot.resolve(relative)
      if (Files.isDirectory(resolved)) {
        Files.walk(resolved).use { walk ->
          walk.filter { Files.isRegularFile(it) }.forEach { file ->
            materialized += repoRoot.relativize(file).toString().replace('\\', '/')
          }
        }
      } else {
        materialized += relative
      }
    }
    return materialized.toList()
  }

  private fun ignoredUntrackedPaths(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowGitOperationResult = checkIgnore(repoRoot, paths, noIndex = false)

  private fun gitignoredFeatureSpecs(
    repoRoot: Path,
    paths: List<String>,
  ): WorkflowGitOperationResult = checkIgnore(repoRoot, paths.filter(::isFeatureSpecPath), noIndex = true)

  private fun checkIgnore(
    repoRoot: Path,
    paths: List<String>,
    noIndex: Boolean,
  ): WorkflowGitOperationResult {
    if (paths.isEmpty()) return WorkflowGitOperationResult.Ok(value = "")
    val args =
      buildList {
        add("check-ignore")
        add("-z")
        if (noIndex) add("--no-index")
        add("--stdin")
      }
    val ignored = mutableListOf<String>()
    for (batch in paths.chunked(PATHSPEC_BATCH_SIZE)) {
      val stdin = batch.joinToString(separator = GIT_NUL.toString(), postfix = GIT_NUL.toString()).toByteArray()
      val parsed = parseCheckIgnore(args, runGitProcess(repoRoot, args, stdin))
      if (parsed !is WorkflowGitOperationResult.Ok) return parsed
      ignored += parsed.value.orEmpty().split(GIT_NUL).filter(String::isNotBlank)
    }
    return WorkflowGitOperationResult.Ok(value = ignored.joinToString(GIT_NUL.toString()))
  }

  private fun parseCheckIgnore(
    args: List<String>,
    result: GitProcessResult,
  ): WorkflowGitOperationResult =
    when {
      result.timedOut -> WorkflowGitOperationResult.Failed(error = gitTimedOutError(args))
      result.readFailure != null ->
        WorkflowGitOperationResult.Failed(
          error = result.readFailure.message.orEmpty(),
        )
      result.exitCode == 0 -> WorkflowGitOperationResult.Ok(value = result.output)
      result.exitCode == 1 -> WorkflowGitOperationResult.Ok(value = "")
      else ->
        WorkflowGitOperationResult.Failed(
          error = "git check-ignore failed with exit code ${result.exitCode}: ${result.output}",
        )
    }
}

private fun isFeatureSpecPath(path: String): Boolean {
  val normalized = path.trim().trimEnd('/').removeSurrounding("\"").removePrefix("./")
  val dotted = if (normalized.startsWith(".")) normalized else ".$normalized"
  return dotted == FEATURE_SPEC_ROOT || dotted.startsWith("$FEATURE_SPEC_ROOT/")
}
