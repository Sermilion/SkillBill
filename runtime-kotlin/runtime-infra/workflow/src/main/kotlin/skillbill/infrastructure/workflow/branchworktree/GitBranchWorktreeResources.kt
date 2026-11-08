package skillbill.infrastructure.workflow.branchworktree

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.host.jvm.resolveTelemetryStateDir
import skillbill.infrastructure.workflow.monitor.GitRun
import skillbill.infrastructure.workflow.monitor.NO_PROMPT_ENVIRONMENT
import skillbill.infrastructure.workflow.monitor.monitorGit
import skillbill.infrastructure.workflow.process.GIT_HOOKED_COMMAND_TIMEOUT_SECONDS
import skillbill.infrastructure.workflow.process.invokeGitProcess
import skillbill.infrastructure.workflow.repositoryStateKey
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.branchworktree.BranchWorktreeResources
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeCreation
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeHolder
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeObservation
import skillbill.ports.workflow.branchworktree.model.BranchWorktreeRelease
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

@Inject
class GitBranchWorktreeResources(
  environment: EnvironmentContext,
  private val diagnostics: RuntimeDiagnostics,
) : BranchWorktreeResources {
  private val lockDirectory: Path =
    resolveTelemetryStateDir(environment.environment, environment.userHome).resolve(LOCK_DIRECTORY)

  override fun create(
    repoRoot: Path,
    branch: String,
    holder: BranchWorktreeHolder,
  ): BranchWorktreeCreation {
    if (branch.startsWith("-") || !monitorGit(repoRoot, "check-ref-format", "refs/heads/$branch").ok) {
      return BranchWorktreeCreation.Failed("'$branch' is not a valid branch name.")
    }
    val repository =
      repositoryOf(repoRoot) ?: return BranchWorktreeCreation.Failed("$repoRoot is not a Git working tree.")
    val local = monitorGit(repository.primaryRoot, "show-ref", "--verify", "--quiet", "refs/heads/$branch").ok
    val fetch =
      monitorGit(
        repository.primaryRoot,
        "fetch",
        "--no-tags",
        ORIGIN,
        "+refs/heads/$branch:refs/remotes/$ORIGIN/$branch",
      )
    if (!local && !fetch.ok) return unfetched(repository, branch, fetch)
    if (local && !fetch.ok) {
      diagnostics.info("seam=branch_worktree.fetch outcome=failed_using_local branch=$branch reason=${fetch.output}")
    }
    return try {
      locked(repository) { add(repository, branch, local, holder) }
    } catch (failure: IOException) {
      diagnostics.warning("seam=branch_worktree.create outcome=failed branch=$branch", failure)
      BranchWorktreeCreation.Failed("Could not create a worktree for $branch: ${failure.message}")
    }
  }

  override fun hold(
    checkoutRoot: Path,
    holder: BranchWorktreeHolder,
  ): Boolean {
    val owned = ownedCheckout(checkoutRoot) ?: return false
    return try {
      locked(owned.repository) {
        val record = readRecord(owned.adminDirectory) ?: return@locked false
        val token = holder.token()
        if (token in record.holders) {
          false
        } else {
          writeRecord(owned.adminDirectory, record.copy(holders = record.holders + token))
          true
        }
      }
    } catch (failure: IOException) {
      diagnostics.warning("seam=branch_worktree.hold outcome=failed path=$checkoutRoot", failure)
      false
    }
  }

  override fun observe(
    checkoutRoot: Path,
    holder: BranchWorktreeHolder,
  ): BranchWorktreeObservation {
    val top = monitorGit(checkoutRoot, "rev-parse", "--show-toplevel")
    val branch = monitorGit(checkoutRoot, "symbolic-ref", "--short", "HEAD")
    val head = monitorGit(checkoutRoot, "rev-parse", "HEAD")
    val repository = repositoryOf(checkoutRoot)
    return when {
      !top.ok || !head.ok || repository == null ->
        BranchWorktreeObservation.Unavailable("The checkout is missing or Git could not read its identity.")
      !branch.ok ->
        BranchWorktreeObservation.Unavailable(
          "The checkout is on a detached HEAD; check out a branch before starting this operation.",
        )
      else ->
        observeOwnership(
          checkoutRoot,
          repository,
          ObservedCheckout(realPath(Path.of(top.output.trim())), branch.output.trim(), head.output.trim()),
          holder,
        )
    }
  }

  private fun observeOwnership(
    checkoutRoot: Path,
    repository: Repository,
    checkout: ObservedCheckout,
    holder: BranchWorktreeHolder,
  ): BranchWorktreeObservation {
    val root = checkout.root
    val actualBranch = checkout.branch
    val admin =
      adminDirectoryOf(root)
        ?: return BranchWorktreeObservation.Unavailable("The checkout ownership record is unavailable.")
    if (!Files.exists(admin.resolve(RECORD_FILE))) {
      return BranchWorktreeObservation.CallerOwned(root, repository.primaryRoot, actualBranch, checkout.head)
    }
    return try {
      locked(repository) {
        val record =
          readRecord(admin)
            ?: return@locked BranchWorktreeObservation.Unavailable("The checkout ownership record is unreadable.")
        if (realPath(Path.of(record.path)) != root || record.branch != actualBranch) {
          BranchWorktreeObservation.Unavailable("The managed checkout path or branch changed.")
        } else {
          BranchWorktreeObservation.RuntimeManaged(
            root,
            repository.primaryRoot,
            actualBranch,
            checkout.head,
            holder.token() in record.holders,
          )
        }
      }
    } catch (failure: IOException) {
      diagnostics.warning("seam=branch_worktree.observe outcome=unreadable path=$checkoutRoot", failure)
      BranchWorktreeObservation.Unavailable("The checkout ownership record could not be read.")
    }
  }

  override fun release(
    checkoutRoot: Path,
    holder: BranchWorktreeHolder,
  ): BranchWorktreeRelease {
    val owned = ownedCheckout(checkoutRoot) ?: return BranchWorktreeRelease.NotHeld
    return try {
      locked(owned.repository) {
        val record = readRecord(owned.adminDirectory) ?: return@locked BranchWorktreeRelease.NotHeld
        val token = holder.token()
        if (token !in record.holders) return@locked BranchWorktreeRelease.NotHeld
        val remaining = record.holders - token
        writeRecord(owned.adminDirectory, record.copy(holders = remaining))
        if (remaining.isEmpty()) remove(owned, record) else BranchWorktreeRelease.StillHeld
      }
    } catch (failure: IOException) {
      diagnostics.warning("seam=branch_worktree.release outcome=failed path=$checkoutRoot", failure)
      BranchWorktreeRelease.Kept(checkoutRoot, "its ownership record could not be updated: ${failure.message}")
    }
  }

  private fun unfetched(
    repository: Repository,
    branch: String,
    fetch: GitRun,
  ): BranchWorktreeCreation {
    val listed = monitorGit(repository.primaryRoot, "ls-remote", "--exit-code", "--heads", ORIGIN, "refs/heads/$branch")
    return if (listed.exitCode == LS_REMOTE_NO_MATCH) {
      BranchWorktreeCreation.Missing
    } else {
      BranchWorktreeCreation.Failed("Could not fetch $branch from $ORIGIN: ${fetch.output.trim()}")
    }
  }

  private fun add(
    repository: Repository,
    branch: String,
    local: Boolean,
    holder: BranchWorktreeHolder,
  ): BranchWorktreeCreation {
    val path =
      siblingPath(repository.primaryRoot, branch) ?: return BranchWorktreeCreation.Failed(
        "${repository.primaryRoot} has no parent directory for a $branch worktree.",
      )
    val arguments =
      if (local) {
        listOf("worktree", "add", path.toString(), branch)
      } else {
        listOf("worktree", "add", "--track", "-b", branch, path.toString(), "$ORIGIN/$branch")
      }
    val added =
      invokeGitProcess(
        repository.primaryRoot,
        arguments,
        stdin = null,
        extraEnvironment = WORKTREE_ADD_ENVIRONMENT,
        deadlineSeconds = GIT_HOOKED_COMMAND_TIMEOUT_SECONDS,
      )
    if (added.timedOut || added.readFailure != null || added.exitCode != 0) {
      val detail = added.readFailure?.message ?: if (added.timedOut) "git timed out" else added.output.trim()
      return BranchWorktreeCreation.Failed("Could not create a worktree for $branch at $path: $detail")
    }
    val adminDirectory = adminDirectoryOf(path)
    if (adminDirectory == null) {
      monitorGit(repository.primaryRoot, "worktree", "remove", path.toString())
      return BranchWorktreeCreation.Failed("Could not read the Git directory of the new worktree at $path.")
    }
    writeRecord(adminDirectory, OwnershipRecord(branch, path.toString(), setOf(holder.token())))
    return BranchWorktreeCreation.Created(path)
  }

  private fun remove(
    owned: OwnedCheckout,
    record: OwnershipRecord,
  ): BranchWorktreeRelease {
    val path = owned.root
    val current = monitorGit(path, "symbolic-ref", "--short", "HEAD")
    val problem =
      when {
        record.path != path.toString() -> "it moved from ${record.path}"
        !current.ok || current.output.trim() != record.branch -> "it no longer has ${record.branch} checked out"
        else -> null
      }
    if (problem != null) return BranchWorktreeRelease.Kept(path, problem)
    val removal = monitorGit(owned.repository.primaryRoot, "worktree", "remove", path.toString())
    return if (removal.ok) {
      BranchWorktreeRelease.Removed(path)
    } else {
      BranchWorktreeRelease.Kept(path, "git worktree remove failed: ${removal.output.trim()}")
    }
  }

  private fun ownedCheckout(checkoutRoot: Path): OwnedCheckout? {
    val adminDirectory = adminDirectoryOf(checkoutRoot) ?: return null
    if (!Files.isRegularFile(adminDirectory.resolve(RECORD_FILE))) return null
    val top = monitorGit(checkoutRoot, "rev-parse", "--show-toplevel")
    val repository = repositoryOf(checkoutRoot)
    if (!top.ok || repository == null) {
      diagnostics.warning("seam=branch_worktree.lookup outcome=unreadable path=$checkoutRoot reason=${top.output}")
      return null
    }
    return OwnedCheckout(realPath(Path.of(top.output.trim())), adminDirectory, repository)
  }

  private fun adminDirectoryOf(checkoutRoot: Path): Path? =
    monitorGit(checkoutRoot, "rev-parse", "--absolute-git-dir")
      .takeIf(GitRun::ok)
      ?.output
      ?.trim()
      ?.takeIf(String::isNotEmpty)
      ?.let(Path::of)

  private fun repositoryOf(root: Path): Repository? {
    val probe = monitorGit(root, "rev-parse", "--git-common-dir")
    val output = probe.output.trim()
    if (!probe.ok || output.isEmpty()) return null
    val commonDirectory = realPath(root.toAbsolutePath().resolve(output).normalize())
    val primary = if (commonDirectory.fileName?.toString() == GIT_DIRECTORY) commonDirectory.parent else commonDirectory
    return primary?.let { Repository(it, commonDirectory) }
  }

  private fun siblingPath(
    primaryRoot: Path,
    branch: String,
  ): Path? {
    val parent = primaryRoot.parent ?: return null
    val slug =
      BRANCH_SLUG_UNSAFE.replace(branch.lowercase(), "-").trim('-').take(MAX_SLUG_LENGTH).trimEnd('-')
        .ifEmpty { FALLBACK_SLUG }
    val base = "${primaryRoot.fileName}-$slug"
    return generateSequence(1) { it + 1 }
      .map { suffix -> parent.resolve(if (suffix == 1) base else "$base-$suffix") }
      .first { !Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
  }

  private inline fun <T> locked(
    repository: Repository,
    body: () -> T,
  ): T {
    Files.createDirectories(lockDirectory)
    val lockFile = lockDirectory.resolve("${repositoryStateKey(repository.commonDirectory)}.lock")
    return synchronized(PROCESS_LOCK) {
      FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
        channel.lock().use { body() }
      }
    }
  }

  private fun readRecord(adminDirectory: Path): OwnershipRecord? {
    val file = adminDirectory.resolve(RECORD_FILE)
    if (!Files.isRegularFile(file)) return null
    val fields =
      Files.readAllLines(file).mapNotNull { line ->
        line.indexOf(' ').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) }
      }
    val branch = fields.firstOrNull { it.first == BRANCH_FIELD }?.second
    val path = fields.firstOrNull { it.first == PATH_FIELD }?.second
    if (branch == null || path == null) {
      diagnostics.warning("seam=branch_worktree.record outcome=malformed file=$file")
      return null
    }
    val holders = fields.filter { it.first == HOLDER_FIELD }.mapTo(LinkedHashSet()) { it.second }
    return OwnershipRecord(branch, path, holders)
  }

  private fun writeRecord(
    adminDirectory: Path,
    record: OwnershipRecord,
  ) {
    val lines =
      listOf("$BRANCH_FIELD ${record.branch}", "$PATH_FIELD ${record.path}") +
        record.holders.map { "$HOLDER_FIELD $it" }
    val temporary = adminDirectory.resolve("$RECORD_FILE.tmp")
    Files.write(temporary, lines)
    Files.move(
      temporary,
      adminDirectory.resolve(RECORD_FILE),
      StandardCopyOption.ATOMIC_MOVE,
      StandardCopyOption.REPLACE_EXISTING,
    )
  }

  private fun realPath(path: Path): Path =
    try {
      path.toRealPath()
    } catch (_: IOException) {
      path
    }

  private fun BranchWorktreeHolder.token(): String = "${kind.wireValue}:$id"

  private data class Repository(val primaryRoot: Path, val commonDirectory: Path)

  private data class OwnedCheckout(val root: Path, val adminDirectory: Path, val repository: Repository)

  private data class ObservedCheckout(val root: Path, val branch: String, val head: String)

  private data class OwnershipRecord(val branch: String, val path: String, val holders: Set<String>)

  private companion object {
    const val ORIGIN = "origin"
    const val GIT_DIRECTORY = ".git"
    const val LOCK_DIRECTORY = "branch-worktrees"
    const val RECORD_FILE = "skill-bill-branch-worktree"
    const val BRANCH_FIELD = "branch"
    const val PATH_FIELD = "path"
    const val HOLDER_FIELD = "holder"
    const val LS_REMOTE_NO_MATCH = 2
    const val MAX_SLUG_LENGTH = 60
    const val FALLBACK_SLUG = "branch"
    val BRANCH_SLUG_UNSAFE = Regex("[^a-z0-9]+")
    val WORKTREE_ADD_ENVIRONMENT = NO_PROMPT_ENVIRONMENT + ("LC_ALL" to "C")
    val PROCESS_LOCK = Any()
  }
}
