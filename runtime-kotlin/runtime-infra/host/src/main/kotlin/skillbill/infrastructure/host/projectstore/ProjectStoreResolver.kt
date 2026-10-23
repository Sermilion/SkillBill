package skillbill.infrastructure.host.projectstore

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.contracts.projectstore.PROJECT_STORE_RECORD_CONTRACT_VERSION
import skillbill.contracts.projectstore.ProjectStoreLayout
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.failureCodeLabel
import skillbill.error.projectstore.ProjectStoreFailureCode
import skillbill.infrastructure.host.jvm.atomicMoveReplacing
import skillbill.infrastructure.host.resolveTelemetryStateDir
import skillbill.infrastructure.host.withProcessDefaults
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.RepositoryLineagePort
import skillbill.ports.projectstore.model.ProjectStoreLocation
import skillbill.ports.projectstore.model.RepositoryLineage
import skillbill.ports.repository.RepositoryEnclosingRootPort
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

@Inject
class ProjectStoreResolver(
  private val repositories: RepositoryEnclosingRootPort,
  private val lineages: RepositoryLineagePort,
  private val diagnostics: RuntimeDiagnostics,
  context: EnvironmentContext,
  private val clock: Clock,
) : ProjectStorePort {
  private val resolvedContext = context.withProcessDefaults()
  private val storeParent: Path =
    resolveTelemetryStateDir(resolvedContext.environment, resolvedContext.userHome).resolve(PROJECTS_DIRECTORY)
  private val cache = ConcurrentHashMap<Path, ProjectStoreLocation>()
  private val lock = Any()

  override fun resolve(workingDirectory: Path): ProjectStoreLocation {
    val checkout =
      runCatching { locate(workingDirectory) }.getOrElse { failure ->
        return gitUnavailable(workingDirectory, failure.rethrowIfCancellation())
      }
    cache[checkout.commonDir]?.let { return it }
    return synchronized(lock) {
      cache[checkout.commonDir] ?: runCatching {
        bind(checkout).also {
          noteLegacyState(checkout, it)
          cache[checkout.commonDir] = it
        }
      }.getOrElse { failure -> gitUnavailable(workingDirectory, failure.rethrowIfCancellation()) }
    }
  }

  override fun link(
    workingDirectory: Path,
    projectId: String,
  ): ProjectStoreLocation =
    synchronized(lock) {
      val checkout = locate(workingDirectory)
      val targetDir = storeParent.resolve(projectId)
      val target = readTarget(projectId, targetDir)
      val lineage = checkout.gitRoot?.let(lineages::lineage)
      val commonDir = checkout.commonDir.toString()
      scan().filter { it.id != projectId && it.record.commonDir == commonDir && it.record.linkedTo != projectId }
        .forEach { writeRecord(it.dir, it.record.copy(linkedTo = projectId)) }
      writeRecord(
        targetDir,
        ProjectStoreRecord(
          projectId = projectId,
          keyKind = keyKindFor(checkout, lineage),
          commonDir = commonDir,
          rootCommits = lineage?.rootCommits.orEmpty(),
          remoteUrl = lineage?.remoteUrl,
          lastSeen = now(),
          legacyStateNoticeAt = target.legacyStateNoticeAt,
        ),
      )
      cache.clear()
      location(projectId)
    }

  private fun locate(workingDirectory: Path): Checkout {
    val start = repositories.canonicalPath(workingDirectory)
    val enclosing = repositories.enclosingRepositoryRoot(start)
    if (!Files.exists(enclosing.resolve(GIT_MARKER))) return Checkout(start, null)
    val primary = repositories.primaryRepositoryRoot(enclosing)
    val commonDir = repositories.canonicalPath(primary.resolve(GIT_MARKER).takeIf(Files::isDirectory) ?: primary)
    return Checkout(commonDir, enclosing)
  }

  private fun bind(checkout: Checkout): ProjectStoreLocation {
    val gitRoot = checkout.gitRoot ?: return bindNonGit(checkout, NON_GIT_OUTCOME)
    val stores = scan()
    val exact = exactMatch(stores, checkout)
    if (exact != null && exact.record.keyKind != ProjectKeyKind.NO_COMMITS_PATH) return touch(exact)
    val lineage = lineages.lineage(gitRoot)
    return when {
      exact != null -> touchOrRekey(exact, lineage)
      lineage == null -> createStore(NO_COMMITS_OUTCOME, noCommitsProjectId(checkout.commonDir), checkout, null)
      else -> bindLineage(stores, checkout, lineage)
    }
  }

  private fun exactMatch(
    stores: List<StoredProject>,
    checkout: Checkout,
  ): StoredProject? =
    stores.firstOrNull { it.record.commonDir == checkout.commonDir.toString() && it.record.linkedTo == null }

  private fun bindNonGit(
    checkout: Checkout,
    createOutcome: String?,
  ): ProjectStoreLocation {
    val exact = exactMatch(scan(), checkout)
    return if (exact != null) {
      touch(exact)
    } else {
      createStore(createOutcome, nonGitProjectId(checkout.commonDir), checkout, null)
    }
  }

  private fun bindLineage(
    stores: List<StoredProject>,
    checkout: Checkout,
    lineage: RepositoryLineage,
  ): ProjectStoreLocation {
    val candidates = lineageCandidates(stores, lineage)
    val baseId = lineageProjectId(lineage)
    val only = candidates.singleOrNull()
    return when {
      candidates.isEmpty() -> createStore(null, baseId, checkout, lineage)
      only != null && !Files.exists(Path.of(only.record.commonDir)) -> adopt(only, checkout, lineage)
      only != null -> createStore("$SECOND_CLONE_OUTCOME matched=${only.id}", baseId, checkout, lineage)
      else ->
        createStore(
          "$MULTIPLE_CANDIDATES_OUTCOME candidates=${candidates.joinToString(",") { it.id }} hint=$LINK_HINT",
          baseId,
          checkout,
          lineage,
        )
    }
  }

  private fun adopt(
    candidate: StoredProject,
    checkout: Checkout,
    lineage: RepositoryLineage,
  ): ProjectStoreLocation {
    writeRecord(
      candidate.dir,
      candidate.record.copy(
        commonDir = checkout.commonDir.toString(),
        rootCommits = lineage.rootCommits,
        remoteUrl = lineage.remoteUrl,
        lastSeen = now(),
      ),
    )
    identityWarning(ADOPTED_OUTCOME)
    return location(candidate.id)
  }

  private fun touchOrRekey(
    exact: StoredProject,
    lineage: RepositoryLineage?,
  ): ProjectStoreLocation {
    if (lineage == null) return touch(exact)
    val baseId = lineageProjectId(lineage)
    val derivedId = noCommitsProjectId(Path.of(exact.record.commonDir))
    val isDerivedId = exact.id == derivedId || exact.id.startsWith("$derivedId-")
    val newId = if (isDerivedId) freeId(baseId) else exact.id
    if (newId != exact.id) {
      try {
        atomicMoveReplacing(exact.dir, storeParent.resolve(newId))
      } catch (_: NoSuchFileException) {
        val rekeyed =
          scan().firstOrNull { it.isRekeyedMatchOf(exact) } ?: throw NoSuchFileException(exact.dir.toString())
        return touch(rekeyed)
      }
    }
    writeRecord(
      storeParent.resolve(newId),
      exact.record.copy(
        projectId = newId,
        keyKind = ProjectKeyKind.LINEAGE,
        rootCommits = lineage.rootCommits,
        remoteUrl = lineage.remoteUrl,
        lastSeen = now(),
      ),
    )
    identityWarning(REKEYED_OUTCOME)
    return location(newId)
  }

  private fun StoredProject.isRekeyedMatchOf(previous: StoredProject): Boolean =
    id != previous.id &&
      record.commonDir == previous.record.commonDir &&
      record.linkedTo == null &&
      record.keyKind != ProjectKeyKind.NO_COMMITS_PATH

  private fun touch(store: StoredProject): ProjectStoreLocation {
    writeRecord(store.dir, store.record.copy(lastSeen = now()))
    return location(store.id)
  }

  private fun createStore(
    outcome: String?,
    baseId: String,
    checkout: Checkout,
    lineage: RepositoryLineage?,
  ): ProjectStoreLocation {
    val id = freeId(baseId)
    val keyKind = keyKindFor(checkout, lineage)
    val root = storeParent.resolve(id)
    val existedBefore = Files.exists(root)
    var written = false
    try {
      writeRecord(
        root,
        ProjectStoreRecord(
          projectId = id,
          keyKind = keyKind,
          commonDir = checkout.commonDir.toString(),
          rootCommits = lineage?.rootCommits.orEmpty(),
          remoteUrl = lineage?.remoteUrl,
          lastSeen = now(),
        ),
      )
      written = true
    } finally {
      if (!written && !existedBefore) runCatching { Files.deleteIfExists(root) }
    }
    outcome?.let(::identityWarning)
    return location(id)
  }

  private fun noteLegacyState(
    checkout: Checkout,
    location: ProjectStoreLocation,
  ) {
    val checkoutRoot = checkout.gitRoot ?: checkout.commonDir
    val legacyDirectory = checkoutRoot.resolve(LegacyProjectStatePaths.RUNTIME_STATE_DIRECTORY)
    if (!Files.isDirectory(legacyDirectory)) return
    val stored = readStore(location.root)?.takeIf { it.record.legacyStateNoticeAt == null } ?: return
    diagnostics.warning(
      "seam=project_store_legacy_state value_expected=${location.root} value_used=$legacyDirectory " +
        "cause=legacy_state_not_migrated run-evidence, feature-task-tracking and acceptance-repair were not " +
        "migrated and the project store starts fresh; the old directory can be deleted",
    )
    try {
      writeRecord(stored.dir, stored.record.copy(legacyStateNoticeAt = now()))
    } catch (failure: IOException) {
      diagnostics.warning(
        "seam=project_store_legacy_state value_expected=notice_stamped value_used=write_failed " +
          "cause=${failure.causeLabel()}",
      )
    }
  }

  private fun keyKindFor(
    checkout: Checkout,
    lineage: RepositoryLineage?,
  ): ProjectKeyKind =
    when {
      checkout.gitRoot == null -> ProjectKeyKind.NON_GIT_PATH
      lineage == null -> ProjectKeyKind.NO_COMMITS_PATH
      else -> ProjectKeyKind.LINEAGE
    }

  private fun gitUnavailable(
    workingDirectory: Path,
    cause: Throwable,
  ): ProjectStoreLocation {
    identityWarning("$GIT_UNAVAILABLE_OUTCOME cause=${cause.causeLabel()}")
    val directory =
      runCatching { repositories.canonicalPath(workingDirectory) }
        .getOrElse { failure ->
          failure.rethrowIfCancellation()
          workingDirectory.toAbsolutePath().normalize()
        }
    return runCatching { synchronized(lock) { bindNonGit(Checkout(directory, null), null) } }
      .getOrElse { failure ->
        failure.rethrowIfCancellation()
        location(nonGitProjectId(directory))
      }
  }

  private fun Throwable.causeLabel(): String = javaClass.simpleName.ifEmpty { javaClass.name }

  private fun scan(): List<StoredProject> {
    val parent = storeParent
    if (!Files.isDirectory(parent)) return emptyList()
    return Files.list(parent).use { entries -> entries.filter(Files::isDirectory).toList() }
      .sortedBy { it.fileName.toString() }
      .mapNotNull(::readStore)
  }

  private fun readStore(dir: Path): StoredProject? {
    val recordPath = dir.resolve(RECORD_FILE)
    if (!Files.isRegularFile(recordPath)) return null
    return try {
      StoredProject(dir, ProjectStoreRecordCodec.parse(Files.readString(recordPath)))
    } catch (failure: SkillBillRuntimeException) {
      warnInvalidRecord(failure, recordPath)
      null
    } catch (failure: IOException) {
      warnInvalidRecord(failure, recordPath)
      null
    }
  }

  private fun readTarget(
    projectId: String,
    targetDir: Path,
  ): ProjectStoreRecord {
    val recordPath = targetDir.resolve(RECORD_FILE)
    val record =
      if (STORE_ID_PATTERN.matches(projectId)) {
        runCatching { ProjectStoreRecordCodec.parse(Files.readString(recordPath)) }.getOrNull()
      } else {
        null
      }
    return record ?: throw SkillBillRuntimeException(
      ProjectStoreFailureCode.PROJECT_STORE_TARGET_UNKNOWN,
      "Project store '$projectId' is unknown or has an invalid record under '$storeParent'.",
    )
  }

  private fun writeRecord(
    root: Path,
    record: ProjectStoreRecord,
  ) {
    Files.createDirectories(root)
    val temporary = Files.createTempFile(root, "project-", ".json.tmp")
    try {
      Files.writeString(temporary, ProjectStoreRecordCodec.encode(record))
      atomicMoveReplacing(temporary, root.resolve(RECORD_FILE))
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  private fun freeId(baseId: String): String =
    generateSequence(1) { it + 1 }
      .map { if (it == 1) baseId else "$baseId-$it" }
      .first { !Files.exists(storeParent.resolve(it)) }

  private fun location(projectId: String): ProjectStoreLocation {
    val root = storeParent.resolve(projectId)
    return ProjectStoreLocation(
      projectId = projectId,
      root = root,
      specsRoot = root.resolve(ProjectStoreLayout.SPECS_DIRECTORY),
      configPath = root.resolve("config.yaml"),
      runEvidenceRoot = root.resolve("run-evidence"),
      trackingRoot = root.resolve("feature-task-tracking"),
      acceptanceRepairRoot = root.resolve("acceptance-repair"),
    )
  }

  private fun now(): String = clock.instant().toString()

  private fun identityWarning(outcome: String) {
    diagnostics.warning("seam=project_store_identity value_expected=exact_common_dir_match value_used=$outcome")
  }

  private fun warnInvalidRecord(
    failure: Exception,
    recordPath: Path,
  ) {
    val observed = failure.message?.lineSequence()?.firstOrNull() ?: failure::class.simpleName
    val cause = failure.failureCodeLabel() ?: failure::class.simpleName
    diagnostics.warning(
      "seam=project_store_record value_expected=contract_version $PROJECT_STORE_RECORD_CONTRACT_VERSION " +
        "value_used=$observed cause=$cause path=$recordPath",
    )
  }

  private fun Throwable.rethrowIfCancellation(): Throwable {
    if (this is CancellationException || this is InterruptedException) throw this
    return this
  }

  private data class Checkout(
    val commonDir: Path,
    val gitRoot: Path?,
  )

  private companion object {
    const val PROJECTS_DIRECTORY = "projects"
    const val RECORD_FILE = "project.json"
    const val GIT_MARKER = ".git"
    const val NON_GIT_OUTCOME = "non_git_path_hash"
    const val NO_COMMITS_OUTCOME = "no_commits_path_key"
    const val ADOPTED_OUTCOME = "lineage_adopted"
    const val REKEYED_OUTCOME = "rekeyed_to_lineage"
    const val SECOND_CLONE_OUTCOME = "second_clone"
    const val MULTIPLE_CANDIDATES_OUTCOME = "multiple_candidates"
    const val GIT_UNAVAILABLE_OUTCOME = "git_unavailable"
    const val LINK_HINT = "'run skill-bill project link <id> to attach this checkout'"
    val STORE_ID_PATTERN = Regex("""[A-Za-z0-9_-][A-Za-z0-9._-]*""")
  }
}
