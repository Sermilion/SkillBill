package skillbill.infrastructure.skills.install.nativeagent.link

import skillbill.error.shellcontent.MissingInstalledNativeAgentContext
import skillbill.error.shellcontent.missingInstalledNativeAgent
import skillbill.infrastructure.contracts.sha256Hex
import skillbill.infrastructure.host.jvm.resolveUserHome
import skillbill.infrastructure.skills.install.nativeagent.installNativeAgentCompositionContext
import skillbill.infrastructure.skills.install.nativeagent.inventory.NativeAgentLinkInventoryEntry
import skillbill.infrastructure.skills.install.nativeagent.parseEmbeddedLogicalName
import skillbill.infrastructure.skills.nativeagent.composition.nativeAgentCompositionRepoRoot
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentInstallRenderOverrides
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentInstallRenderRequest
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentOperations
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.infrastructure.skills.nativeagent.validation.validateNativeAgentArtifactsForInstall
import skillbill.install.model.AgentTarget
import skillbill.install.model.SupportedAgent
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.coroutines.cancellation.CancellationException

internal fun linkProviderAgents(
  provider: NativeAgentProvider,
  request: NativeAgentLinkRequest,
  detectTargets: (Path) -> List<AgentTarget>,
): NativeAgentLinkOutcome {
  val validationRoot = nativeAgentCompositionRepoRoot(request.platformPacksRoot, request.skillsRoot)
  val resolvedHome = request.home ?: resolveUserHome(null)
  val effectivePackRoots =
    effectivePackRootsForInstall(
      platformPacksRoot = request.platformPacksRoot,
      userHome = resolvedHome,
      environment = request.environment,
      selectedPlatforms = request.selectedPlatforms,
      catalogLoader = request.catalogLoader,
    )
  val compositionContext = installNativeAgentCompositionContext(effectivePackRoots)
  request.overrides.sourceRoots
    ?.let { roots ->
      validateNativeAgentArtifactsForInstall(roots, validationRoot, compositionContext)
    }
    ?: validateNativeAgentArtifactsForInstall(
      request.platformPacksRoot,
      request.skillsRoot,
      request.selectedPlatforms,
      compositionContext,
    )
  val targets = detectTargets(resolvedHome)
  if (targets.isEmpty()) return NativeAgentLinkOutcome(emptyList(), emptyList())
  val cacheRoot =
    request.overrides.installCacheRoot?.toAbsolutePath()?.normalize()
      ?: NativeAgentOperations.installCacheRoot(resolvedHome, request.platformPacksRoot, request.skillsRoot)
  val journal = ProviderMutationJournal()
  return linkProviderAgentsWithJournal(journal) {
    linkProviderAgentsBody(
      NativeAgentLinkProviderBodyArgs(
        provider = provider,
        request = request,
        targets = targets,
        resolvedHome = resolvedHome,
        cacheRoot = cacheRoot,
        validationRoot = validationRoot,
        compositionContext = compositionContext,
        effectivePackRoots = effectivePackRoots,
        journal = journal,
      ),
    )
  }
}

internal fun publishInstalledReviewCatalog(
  platformPacksRoot: Path,
  selectedPlatforms: List<String>?,
  cacheRoot: Path,
  journal: ProviderMutationJournal,
  effectivePackRoots: List<Path> = emptyList(),
) {
  val catalogParent = cacheRoot.resolve("review-catalog")
  val catalogRoot = catalogParent.resolve("platform-packs")
  val staging = catalogParent.resolve(".platform-packs.staging")
  val superseded = catalogParent.resolve(".platform-packs.superseded")

  journal.beforeMutation(catalogParent)
  Files.createDirectories(catalogParent)
  deleteRecursively(staging)
  deleteRecursively(superseded)
  journal.afterTemporaryCreation(staging)
  Files.createDirectories(staging)

  val failure =
    runCatching {
      stageReviewCatalogPacks(platformPacksRoot, selectedPlatforms, staging, effectivePackRoots)
    }.exceptionOrNull()
  if (failure != null) {
    deleteRecursively(staging)
    throwCatalogStageFailure(failure)
  }
  val publishFailure =
    runCatching {
      journalReviewCatalogSwap(catalogRoot, staging, journal)
      swapReviewCatalogIntoPlace(catalogRoot, staging, superseded)
    }.exceptionOrNull()
  publishFailure?.let { failure ->
    throw retainedCatalogFailure(failure, platformPacksRoot, effectivePackRoots)
  }
}

private fun throwCatalogStageFailure(error: Throwable): Nothing {
  if (error is CancellationException) throw error
  throw retainedCatalogFailure(error)
}

internal fun deleteRecursively(root: Path) {
  if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
  Files.walk(root).use { paths ->
    paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
  }
}

internal fun verifyInstalledNativeAgent(entry: NativeAgentLinkInventoryEntry) {
  val installed = entry.installedPath
  val repair = "skill-bill install apply"

  fun fail(
    reason: String,
    cause: Throwable? = null,
  ): Nothing =
    throw missingInstalledNativeAgent(
      MissingInstalledNativeAgentContext(
        logicalName = entry.logicalName,
        provider = entry.provider,
        expectedPath = installed.toString(),
        reason = reason,
        repairCommand = repair,
      ),
      cause = cause,
    )
  if (!Files.isSymbolicLink(installed)) fail("managed link is missing")
  val resolved =
    runCatching { installed.toRealPath() }
      .getOrElse { fail("managed link is dangling or unreadable", it) }
  if (resolved != entry.cacheTargetPath.toRealPath()) fail("managed link resolves outside the current cache target")
  if (!Files.isReadable(resolved)) fail("rendered artifact is unreadable")
  if (parseEmbeddedLogicalName(resolved, SupportedAgent.fromWire(entry.provider)) != entry.logicalName) {
    fail("rendered artifact logical name does not match the launch worker")
  }
  val digest = sha256Hex(Files.readAllBytes(resolved))
  if (digest != entry.contentDigest) fail("rendered artifact content digest is stale")
}

internal class ProviderMutationJournal {
  private val entries = linkedMapOf<Path, FileSnapshot?>()

  fun beforeMutation(path: Path) {
    val normalized = path.toAbsolutePath().normalize()
    generateSequence(normalized.parent) { it.parent }
      .takeWhile { !Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
      .toList().asReversed().forEach(::record)
    record(normalized)
  }

  fun afterTemporaryCreation(path: Path) {
    entries.putIfAbsent(path.toAbsolutePath().normalize(), null)
  }

  private fun record(normalized: Path) {
    if (normalized !in entries) {
      entries[normalized] =
        normalized.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
          ?.let(FileSnapshot::capture)
    }
  }

  fun restore(): List<Throwable> {
    val failures = mutableListOf<Throwable>()
    entries.entries.toList().asReversed().forEach { (path, snapshot) ->
      runCatching {
        if (snapshot == null) {
          when {
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && isEmptyDirectory(path) -> Files.deleteIfExists(path)
            !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> Files.deleteIfExists(path)
          }
        } else {
          snapshot.restore(path)
        }
      }.exceptionOrNull()?.let(failures::add)
    }
    return failures
  }
}

private data class FileSnapshot(
  val kind: FileKind,
  val bytes: ByteArray?,
  val rawTarget: Path?,
  val permissions: Set<PosixFilePermission>?,
  val dosAttributes: DosAttributes?,
) {
  fun restore(path: Path) {
    when (kind) {
      FileKind.Directory -> Files.createDirectories(path)
      FileKind.Regular -> {
        Files.deleteIfExists(path)
        Files.createDirectories(path.parent)
        Files.write(path, requireNotNull(bytes))
      }
      FileKind.SymbolicLink -> {
        Files.deleteIfExists(path)
        Files.createDirectories(path.parent)
        Files.createSymbolicLink(path, requireNotNull(rawTarget))
      }
    }
    permissions?.let { captured ->
      Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        ?.setPermissions(captured)
    }
    dosAttributes?.restore(path)
  }

  companion object {
    fun capture(path: Path): FileSnapshot =
      FileSnapshot(
        kind =
          when {
            Files.isSymbolicLink(path) -> FileKind.SymbolicLink
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> FileKind.Directory
            else -> FileKind.Regular
          },
        bytes = path.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }?.let(Files::readAllBytes),
        rawTarget = path.takeIf(Files::isSymbolicLink)?.let(Files::readSymbolicLink),
        permissions =
          captureOptionalAttributes {
            Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
              ?.readAttributes()?.permissions()
          },
        dosAttributes =
          captureOptionalAttributes {
            Files.getFileAttributeView(path, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
              ?.readAttributes()?.let { DosAttributes(it.isReadOnly, it.isHidden, it.isArchive, it.isSystem) }
          },
      )

    private fun <T> captureOptionalAttributes(read: () -> T?): T? =
      try {
        read()
      } catch (_: UnsupportedOperationException) {
        null
      } catch (error: FileSystemException) {
        if (error.reason.orEmpty().contains("not supported", ignoreCase = true) ||
          error.reason.orEmpty().contains("too many levels of symbolic links", ignoreCase = true)
        ) {
          null
        } else {
          throw error
        }
      }
  }
}

private data class DosAttributes(
  val readOnly: Boolean,
  val hidden: Boolean,
  val archive: Boolean,
  val system: Boolean,
) {
  fun restore(path: Path) {
    Files.getFileAttributeView(path, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)?.apply {
      setReadOnly(readOnly)
      setHidden(hidden)
      setArchive(archive)
      setSystem(system)
    }
  }
}

private enum class FileKind { Directory, Regular, SymbolicLink }

internal fun isEmptyDirectory(path: Path): Boolean = Files.list(path).use { !it.findAny().isPresent }

internal fun unlinkProviderAgents(
  provider: NativeAgentProvider,
  request: NativeAgentLinkRequest,
): List<Path> {
  val resolvedHome = request.home ?: resolveUserHome(null)
  val compositionContext = installNativeAgentCompositionContext()
  val generated =
    NativeAgentOperations.renderInstallArtifacts(
      NativeAgentInstallRenderRequest(
        platformPacksRoot = request.platformPacksRoot,
        skillsRoot = request.skillsRoot,
        selectedPlatforms = request.selectedPlatforms,
        provider = provider,
        home = resolvedHome,
        compositionContext = compositionContext,
        overrides =
          NativeAgentInstallRenderOverrides(
            cacheRoot = request.overrides.installCacheRoot,
            sourceRoots = request.overrides.sourceRoots,
          ),
      ),
    )
  val legacyGenerated =
    request.overrides.legacyManagedRoot
      ?.takeIf { legacyRoot -> legacyRoot != generated.cacheRoot }
      ?.let { legacyRoot ->
        NativeAgentOperations.renderInstallArtifacts(
          NativeAgentInstallRenderRequest(
            platformPacksRoot = request.platformPacksRoot,
            skillsRoot = request.skillsRoot,
            selectedPlatforms = request.selectedPlatforms,
            provider = provider,
            home = resolvedHome,
            compositionContext = compositionContext,
            overrides =
              NativeAgentInstallRenderOverrides(
                cacheRoot = legacyRoot,
                sourceRoots = request.overrides.sourceRoots,
              ),
          ),
        )
      }
  return uninstallNativeAgentFiles(
    (generated.generatedFiles + legacyGenerated?.generatedFiles.orEmpty()).distinct(),
    provider.homeAgentDirs(resolvedHome),
  )
}
