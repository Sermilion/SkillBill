package skillbill.infrastructure.host.projectstore

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.contracts.projectstore.PROJECT_STORE_RECORD_CONTRACT_VERSION
import skillbill.contracts.projectstore.ProjectStoreLayout
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.catchingNonCancellation
import skillbill.error.core.failureCodeLabel
import skillbill.error.projectstore.ProjectStoreFailureCode
import skillbill.infrastructure.host.jvm.atomicMoveReplacing
import skillbill.infrastructure.host.resolveTelemetryStateDir
import skillbill.infrastructure.host.withProcessDefaults
import skillbill.model.EnvironmentContext
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.ProjectCatalogPort
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.RepositoryLineagePort
import skillbill.ports.projectstore.model.ProjectCatalogEntry
import skillbill.ports.projectstore.model.ProjectStoreLocation
import skillbill.ports.projectstore.model.RepositoryLineage
import skillbill.ports.repository.RepositoryEnclosingRootPort
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap

@Inject
class ProjectStoreResolver(
  private val repositories: RepositoryEnclosingRootPort,
  private val lineages: RepositoryLineagePort,
  private val diagnostics: RuntimeDiagnostics,
  context: EnvironmentContext,
  private val clock: Clock,
) : ProjectStorePort, ProjectCatalogPort {
  private val resolvedContext = context.withProcessDefaults()
  private val stateDir: Path = resolveTelemetryStateDir(resolvedContext.environment, resolvedContext.userHome)
  private val storeParent: Path = stateDir.resolve(PROJECTS_DIRECTORY)
  private val cache = ConcurrentHashMap<Path, ProjectStoreLocation>()
  private val lock = Any()

  override fun resolve(workingDirectory: Path): ProjectStoreLocation {
    val checkout =
      catchingNonCancellation { locate(workingDirectory) }.getOrElse { failure ->
        return gitUnavailable(workingDirectory, failure)
      }
    cache[checkout.commonDir]?.let { return it }
    return synchronized(lock) {
      cache[checkout.commonDir] ?: catchingNonCancellation {
        bind(checkout).also {
          noteLegacyState(checkout, it)
          cache[checkout.commonDir] = it
        }
      }.getOrElse { failure -> gitUnavailable(workingDirectory, failure) }
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
      val commonDir = checkout.commonDir.toString()
      scan().filter { it.id != projectId && it.record.commonDir == commonDir && it.record.linkedTo != projectId }
        .forEach { writeRecord(it.dir, it.record.copy(linkedTo = projectId)) }
      writeRecord(targetDir, target.withCheckout(commonDir).copy(lastSeen = now()))
      cache.clear()
      location(projectId)
    }

  override fun list(): List<ProjectCatalogEntry> = scan().map(::catalogEntry).sortedWith(CATALOG_ORDER)

  private fun catalogEntry(store: StoredProject): ProjectCatalogEntry =
    ProjectCatalogEntry(
      projectId = store.id,
      storeRoot = store.dir,
      specsRoot = store.dir.resolve(ProjectStoreLayout.SPECS_DIRECTORY),
      checkoutRoots = store.liveCheckouts().map(::workingTreeOf).distinct(),
      lastSeen = parseInstantOrNull(store.record.lastSeen),
      lastCheckoutRoot = workingTreeOf(store.record.commonDir),
      hasSpecs = hasBundles(store.dir.resolve(ProjectStoreLayout.SPECS_DIRECTORY)),
    )

  private fun hasBundles(specsRoot: Path): Boolean =
    Files.isDirectory(specsRoot) &&
      try {
        Files.list(specsRoot).use { entries -> entries.anyMatch(Files::isDirectory) }
      } catch (_: IOException) {
        false
      }

  private fun workingTreeOf(commonDir: String): Path {
    val path = Path.of(commonDir)
    return if (path.fileName?.toString() == GIT_MARKER) path.parent ?: path else path
  }

  private fun parseInstantOrNull(raw: String): Instant? =
    try {
      Instant.parse(raw)
    } catch (_: DateTimeParseException) {
      null
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
    val gitRoot = checkout.gitRoot ?: return bindNonGitOutsideState(checkout)
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
    stores.firstOrNull {
      checkout.commonDir.toString() in it.record.checkoutCommonDirs && it.record.linkedTo == null
    }

  private fun bindNonGitOutsideState(checkout: Checkout): ProjectStoreLocation =
    if (checkout.commonDir.startsWith(canonicalStateDir())) {
      location(nonGitProjectId(checkout.commonDir))
    } else {
      bindNonGit(checkout, NON_GIT_OUTCOME)
    }

  private fun canonicalStateDir(): Path = canonicalOrNormalized(stateDir)

  private fun canonicalOrNormalized(path: Path): Path =
    catchingNonCancellation { repositories.canonicalPath(path) }.getOrElse { failure ->
      diagnostics.warning(
        "seam=project_store_identity value_expected=canonical_path value_used=normalized_path " +
          "cause=${failure.causeLabel()} path=$path",
      )
      path.toAbsolutePath().normalize()
    }

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
      only != null && only.liveCheckouts().isEmpty() -> adopt(only, checkout, lineage)
      only != null && isSameRepository(only, lineage) -> share(only, checkout)
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

  private fun isSameRepository(
    candidate: StoredProject,
    lineage: RepositoryLineage,
  ): Boolean {
    val remote = lineage.remoteUrl ?: return false
    if (sameRemote(candidate.record.remoteUrl, remote)) return true
    val local = localRemoteCommonDir(remote) ?: return false
    return local in candidate.record.checkoutCommonDirs
  }

  private fun localRemoteCommonDir(remote: String): String? =
    catchingNonCancellation {
      val path = if (remote.startsWith(FILE_SCHEME)) Path.of(URI(remote)) else Path.of(remote).takeIf { it.isAbsolute }
      path?.takeIf(Files::isDirectory)?.let(::locate)?.takeIf { it.gitRoot != null }?.commonDir?.toString()
    }.getOrElse { failure ->
      identityWarning("local_remote_unresolved cause=${failure.causeLabel()}")
      null
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
        sharedCommonDirs = emptyList(),
        rootCommits = lineage.rootCommits,
        remoteUrl = lineage.remoteUrl,
        lastSeen = now(),
      ),
    )
    identityWarning(ADOPTED_OUTCOME)
    return location(candidate.id)
  }

  private fun share(
    candidate: StoredProject,
    checkout: Checkout,
  ): ProjectStoreLocation {
    val live = candidate.liveCheckouts()
    val record = candidate.record
    val primary = record.commonDir.takeIf(live::contains) ?: live.first()
    writeRecord(
      candidate.dir,
      record.copy(commonDir = primary, sharedCommonDirs = live - primary, lastSeen = now())
        .withCheckout(checkout.commonDir.toString()),
    )
    identityWarning("$SHARED_CLONE_OUTCOME matched=${candidate.id}")
    return location(candidate.id)
  }

  private fun StoredProject.liveCheckouts(): List<String> =
    record.checkoutCommonDirs.filter { Files.exists(Path.of(it)) }

  private fun ProjectStoreRecord.withCheckout(commonDir: String): ProjectStoreRecord =
    if (commonDir in checkoutCommonDirs) this else copy(sharedCommonDirs = sharedCommonDirs + commonDir)

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
      if (!written && !existedBefore) {
        try {
          Files.deleteIfExists(root)
        } catch (cleanup: IOException) {
          diagnostics.warning(
            "seam=project_store_create value_expected=partial_store_removed value_used=$root " +
              "cause=${cleanup.causeLabel()}",
          )
        }
      }
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
    val directory = canonicalOrNormalized(workingDirectory)
    return catchingNonCancellation { synchronized(lock) { bindNonGit(Checkout(directory, null), null) } }
      .getOrElse { failure ->
        identityWarning("non_git_store_unrecorded cause=${failure.causeLabel()}")
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
        try {
          ProjectStoreRecordCodec.parse(Files.readString(recordPath))
        } catch (_: SkillBillRuntimeException) {
          null
        } catch (_: IOException) {
          null
        }
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

  private data class Checkout(
    val commonDir: Path,
    val gitRoot: Path?,
  )

  private companion object {
    const val PROJECTS_DIRECTORY = "projects"
    const val RECORD_FILE = "project.json"
    const val GIT_MARKER = ".git"
    const val FILE_SCHEME = "file:"
    const val NON_GIT_OUTCOME = "non_git_path_hash"
    const val NO_COMMITS_OUTCOME = "no_commits_path_key"
    const val ADOPTED_OUTCOME = "lineage_adopted"
    const val REKEYED_OUTCOME = "rekeyed_to_lineage"
    const val SECOND_CLONE_OUTCOME = "second_clone"
    const val SHARED_CLONE_OUTCOME = "lineage_shared"
    const val MULTIPLE_CANDIDATES_OUTCOME = "multiple_candidates"
    const val GIT_UNAVAILABLE_OUTCOME = "git_unavailable"
    const val LINK_HINT = "'run skill-bill project link <id> to attach this checkout'"
    val STORE_ID_PATTERN = Regex("""[A-Za-z0-9_-][A-Za-z0-9._-]*""")
    val CATALOG_ORDER: Comparator<ProjectCatalogEntry> =
      compareByDescending<ProjectCatalogEntry, Instant?>(nullsFirst()) { it.lastSeen }.thenBy { it.projectId }
  }
}
