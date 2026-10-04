package skillbill.infrastructure.workflow.git

import skillbill.infrastructure.host.jvm.requirePathContainedIn
import skillbill.infrastructure.workflow.process.runGitCommand
import skillbill.ports.workflow.gitops.model.ReadinessTreeIdentity
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowReadinessTreeIdentityResult
import skillbill.ports.workflow.gitops.readiness.ReadinessTreeIdentityGitOperations
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

internal object GitReadinessTreeIdentityOperations : ReadinessTreeIdentityGitOperations {
  override fun resolveReadinessTreeIdentity(
    repoRoot: Path,
    baseBranch: String,
    workflowId: String,
  ): WorkflowReadinessTreeIdentityResult =
    identityInputFailure(baseBranch, workflowId)
      ?: resolveIdentity(repoRoot, baseBranch, workflowId)

  private fun resolveIdentity(
    repoRoot: Path,
    baseBranch: String,
    workflowId: String,
  ): WorkflowReadinessTreeIdentityResult {
    val head = resolveRequiredSha(repoRoot, "HEAD", "Readiness identity could not resolve HEAD.")
    if (head !is WorkflowGitOperationResult.Ok) return WorkflowReadinessTreeIdentityResult.Failed(head.error)
    val baseRef =
      resolveRequiredSha(
        repoRoot,
        "origin/$baseBranch",
        "Readiness identity could not resolve origin/$baseBranch.",
      )
    if (baseRef !is WorkflowGitOperationResult.Ok) return WorkflowReadinessTreeIdentityResult.Failed(baseRef.error)
    return resolveSourceTreeIdentity(
      repoRoot,
      workflowId,
      baseRefSha = baseRef.value.orEmpty(),
      headSha = head.value.orEmpty(),
    )
  }

  private fun resolveSourceTreeIdentity(
    repoRoot: Path,
    workflowId: String,
    baseRefSha: String,
    headSha: String,
  ): WorkflowReadinessTreeIdentityResult {
    val tree = computeSourceTreeSha(repoRoot, workflowId)
    if (tree !is WorkflowGitOperationResult.Ok) return WorkflowReadinessTreeIdentityResult.Failed(tree.error)
    val sourceTreeSha = tree.value.orEmpty().trim()
    return if (sourceTreeSha.isBlank()) {
      WorkflowReadinessTreeIdentityResult.Failed("Readiness identity resolved a blank source tree sha.")
    } else {
      WorkflowReadinessTreeIdentityResult.Resolved(
        ReadinessTreeIdentity(sourceTreeSha = sourceTreeSha, baseRefSha = baseRefSha, headSha = headSha),
      )
    }
  }

  override fun readinessChangedPathsAgainstBase(
    repoRoot: Path,
    baseBranch: String,
  ): WorkflowGitNameListResult =
    if (baseBranch.isBlank()) {
      WorkflowGitNameListResult.Failed(
        "Readiness changed-path discovery requires a non-blank base branch.",
      )
    } else {
      discoverChangedPaths(repoRoot, baseBranch)
    }

  private fun discoverChangedPaths(
    repoRoot: Path,
    baseBranch: String,
  ): WorkflowGitNameListResult {
    val base = "origin/$baseBranch"
    val baseResolution = runGitCommand(repoRoot, "rev-parse", base)
    if (baseResolution !is WorkflowGitOperationResult.Ok) {
      return WorkflowGitNameListResult.Failed(
        "Readiness changed-path discovery could not resolve $base: ${baseResolution.error}",
      )
    }
    val tracked = runGitCommand(repoRoot, "diff", "--name-only", "-z", base, "--")
    if (tracked !is WorkflowGitOperationResult.Ok) return WorkflowGitNameListResult.Failed(tracked.error)
    val untracked = runGitCommand(repoRoot, "ls-files", "--others", "--exclude-standard", "-z")
    if (untracked !is WorkflowGitOperationResult.Ok) return WorkflowGitNameListResult.Failed(untracked.error)
    val trackedPaths = tracked.value.orEmpty().split('\u0000').filter(String::isNotBlank)
    val untrackedPaths = untracked.value.orEmpty().split('\u0000').filter(String::isNotBlank)
    return WorkflowGitNameListResult.Listed((trackedPaths + untrackedPaths).distinct())
  }

  internal fun computeSourceTreeSha(
    repoRoot: Path,
    workflowId: String,
  ): WorkflowGitOperationResult {
    val gitDir = runGitCommand(repoRoot, "rev-parse", "--absolute-git-dir")
    if (gitDir !is WorkflowGitOperationResult.Ok) return gitDir
    val indexPath = runGitCommand(repoRoot, "rev-parse", "--git-path", "index")
    if (indexPath !is WorkflowGitOperationResult.Ok) return indexPath
    val resolvedIndex = repoRoot.resolve(indexPath.value.orEmpty().trim()).normalize()
    val gitDirRoot = Path.of(gitDir.value.orEmpty().trim()).normalize()
    requirePathContainedIn(resolvedIndex, gitDirRoot) { "Git index path escapes the repository git directory." }
    if (!Files.isRegularFile(resolvedIndex)) {
      return WorkflowGitOperationResult.Failed(error = "Git index is missing at '$resolvedIndex'.")
    }
    val tempDir = createTempDirectory("skillbill-readiness-index")
    return try {
      writeSourceTree(repoRoot, resolvedIndex, tempDir, workflowId)
    } finally {
      runCatching { Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
  }

  private fun writeSourceTree(
    repoRoot: Path,
    resolvedIndex: Path,
    tempDir: Path,
    workflowId: String,
  ): WorkflowGitOperationResult {
    val tempIndex = tempDir.resolve("index")
    Files.copy(resolvedIndex, tempIndex)
    val environment = mapOf("GIT_INDEX_FILE" to tempIndex.toString())
    val stagedWorktree = runGitCommand(repoRoot, environment, "add", "--all", "--", ".")
    if (stagedWorktree !is WorkflowGitOperationResult.Ok) return stagedWorktree
    val listed = runGitCommand(repoRoot, environment, "ls-files", "-s", "-z")
    if (listed !is WorkflowGitOperationResult.Ok) return listed
    removeExcludedPaths(repoRoot, environment, listed.value.orEmpty(), workflowId).let { failure ->
      if (failure != null) return failure
    }
    return runGitCommand(repoRoot, environment, "write-tree")
  }

  private fun removeExcludedPaths(
    repoRoot: Path,
    environment: Map<String, String>,
    listed: String,
    workflowId: String,
  ): WorkflowGitOperationResult? {
    val runEvidencePrefix = ".skill-bill/run-evidence/$workflowId/"
    var failure: WorkflowGitOperationResult? = null
    listed.split('\u0000').filter(String::isNotBlank).forEach { entry ->
      val path = entry.substringAfter('\t', missingDelimiterValue = "").trim()
      if (failure == null && path.isNotBlank() && isExcludedFromSourceTree(path, runEvidencePrefix)) {
        val removed =
          runGitCommand(
            repoRoot,
            environment,
            "update-index",
            "--force-remove",
            path,
          )
        if (removed !is WorkflowGitOperationResult.Ok) failure = removed
      }
    }
    return failure
  }

  private fun identityInputFailure(
    baseBranch: String,
    workflowId: String,
  ): WorkflowReadinessTreeIdentityResult? =
    when {
      baseBranch.isBlank() ->
        WorkflowReadinessTreeIdentityResult.Failed("Readiness identity requires a non-blank base branch.")
      workflowId.isBlank() ->
        WorkflowReadinessTreeIdentityResult.Failed("Readiness identity requires a non-blank workflow id.")
      else -> null
    }

  private fun resolveRequiredSha(
    repoRoot: Path,
    reference: String,
    blankError: String,
  ): WorkflowGitOperationResult {
    val result = runGitCommand(repoRoot, "rev-parse", reference)
    if (result !is WorkflowGitOperationResult.Ok) {
      return WorkflowGitOperationResult.Failed(
        error = "$blankError ${result.error.orEmpty()}".trim(),
      )
    }
    val value = result.value.orEmpty().trim()
    return if (value.isBlank()) {
      WorkflowGitOperationResult.Failed(error = blankError)
    } else {
      WorkflowGitOperationResult.Ok(value = value)
    }
  }

  internal fun isExcludedFromSourceTree(
    path: String,
    runEvidencePrefix: String,
  ): Boolean = path.endsWith("agent/history.md") || path.startsWith(runEvidencePrefix)
}
