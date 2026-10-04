package skillbill.infrastructure.skills.install.staging

import skillbill.error.core.InvalidInstallStagingError
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.failureCodeLabel
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.infrastructure.skills.install.identity.SKILL_CONTENT_IDENTITY_FILENAME
import skillbill.infrastructure.skills.install.identity.suppliedSkillContentIdentity
import skillbill.infrastructure.skills.install.staging.content.installedSkillSlug
import skillbill.infrastructure.skills.install.staging.content.isContentManagedSkill
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.infrastructure.skills.scaffold.platformpack.loader.discoverPlatformPackManifests
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.RenderedSkill
import skillbill.model.toPath
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException

private val log: Logger = Logger.getLogger("skillbill.install.InstallStaging")

internal data class StagedSymlinkTargetInput(
  val resolvedSkill: Path,
  val repoRoot: Path?,
  val home: Path,
  val manifests: List<PlatformManifest>? = null,
  val selectedPackSkills: List<InstallPlanSkill> = emptyList(),
  val selectedPlatformSlugs: Set<String> = emptySet(),
  val environment: Map<String, String> = emptyMap(),
  val catalogLoader: PlatformPackCatalogLoader? = null,
)

internal fun installedSkillsCacheRoot(home: Path): Path =
  home.toAbsolutePath().normalize().resolve(".skill-bill/installed-skills")

internal fun installedSkillStagingDir(
  home: Path,
  sourceSkillDir: Path,
  contentHash: String,
): Path {
  val cacheRoot = installedSkillsCacheRoot(home)
  val slug = installedSkillSlug(sourceSkillDir)
  val leaf = if (slug.isEmpty()) contentHash else "$slug-$contentHash"
  val staging = cacheRoot.resolve(leaf).normalize()

  require(staging.startsWith(cacheRoot)) {
    "Resolved staging dir '$staging' escapes installed-skills cache root '$cacheRoot'."
  }
  return staging
}

internal fun applicablePointers(
  repoRoot: Path,
  installPath: Path,
  manifests: List<PlatformManifest>? = null,
): List<Pair<PlatformManifest, PointerSpec>> {
  val resolvedInstall = installPath.toAbsolutePath().normalize()
  val packsRoot = repoRoot.toAbsolutePath().normalize().resolve("platform-packs")

  val discovered =
    manifests ?: run {
      if (!Files.isDirectory(packsRoot)) {
        return emptyList()
      }
      discoverPlatformPackManifests(packsRoot)
    }
  val collected = mutableListOf<Pair<PlatformManifest, PointerSpec>>()
  discovered.forEach { manifest ->
    val packRoot = manifest.packRoot.toPath().toAbsolutePath().normalize()
    if (!resolvedInstall.startsWith(packRoot)) {
      return@forEach
    }
    val skillRelativeDir = packRoot.relativize(resolvedInstall).toString().replace(File.separatorChar, '/')
    manifest.pointers
      .filter { spec -> spec.skillRelativeDir == skillRelativeDir }
      .forEach { spec -> collected.add(manifest to spec) }
  }
  return collected
}

internal fun authoredFilesFor(
  sourceSkillDir: Path,
  applicablePointers: List<Pair<PlatformManifest, PointerSpec>>,
  generatedSupportPointers: List<GeneratedSupportPointer> = emptyList(),
  excludedSidecarNames: Set<String> = emptySet(),
): List<Path> {
  val excluded = mutableSetOf<Path>()
  excluded.add(sourceSkillDir.resolve(INSTALL_STAGING_SKILL_FILENAME).toAbsolutePath().normalize())
  excluded.add(sourceSkillDir.resolve(SKILL_CONTENT_IDENTITY_FILENAME).toAbsolutePath().normalize())
  applicablePointers.forEach { (manifest, spec) ->
    val packRoot = manifest.packRoot.toPath().toAbsolutePath().normalize()
    val pointerPath = packRoot.resolve(spec.skillRelativeDir).resolve(spec.name).toAbsolutePath().normalize()
    excluded.add(pointerPath)
  }
  generatedSupportPointers.forEach { pointer ->
    excluded.add(sourceSkillDir.resolve(pointer.name).toAbsolutePath().normalize())
  }

  excludedSidecarNames.forEach { sidecarName ->
    excluded.add(sourceSkillDir.resolve(sidecarName).toAbsolutePath().normalize())
  }
  val resolvedSource = sourceSkillDir.toAbsolutePath().normalize()
  return Files.walk(sourceSkillDir).use { stream ->
    stream
      .sorted()
      .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
      .filter { path -> path.toAbsolutePath().normalize() !in excluded }
      .peek { path -> requireWithinSource(path, resolvedSource) }
      .toList()
  }
}

private fun requireWithinSource(
  path: Path,
  resolvedSourceSkillDir: Path,
) {
  val realPath =
    try {
      path.toRealPath()
    } catch (_: IOException) {
      throw InvalidInstallStagingError(
        sourceLabel = resolvedSourceSkillDir.toString(),
        reason = "Authored path '$path' could not be resolved to a real path.",
      )
    }
  val realRoot = resolvedSourceSkillDir.toRealPath()
  if (!realPath.startsWith(realRoot)) {
    throw InvalidInstallStagingError(
      sourceLabel = resolvedSourceSkillDir.toString(),
      reason = "Authored path '$path' resolves to '$realPath' which escapes source skill dir '$realRoot'.",
    )
  }
}

internal fun stageInstalledSkill(input: StageInstalledSkillInput): RenderedSkill {
  val prepared =
    try {
      prepareStageInstalledSkill(input)
    } catch (error: CancellationException) {
      throw error
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isShellContentContractFailure())
      throw error
    } catch (error: IOException) {
      invalidStageInstalledSkill(input, error)
    } catch (error: IllegalArgumentException) {
      invalidStageInstalledSkill(input, error)
    }
  tryReusePreparedStageInstalledSkill(prepared, input.suppliedCompactIdentity)?.let { reused ->
    log.fine(
      "stageInstalledSkill reuse=true skill=${prepared.skillName} " +
        "hash=${prepared.contentHash} dir=${prepared.finalStagingDir}",
    )
    return reused
  }
  log.fine(
    "stageInstalledSkill reuse=false skill=${prepared.skillName} " +
      "hash=${prepared.contentHash} dir=${prepared.finalStagingDir}",
  )
  return buildFreshInstallStaging(
    FreshInstallInputs(
      home = input.home,
      sourceSkillDir = prepared.resolvedSource,
      repoRoot = prepared.resolvedRepoRoot,
      target = prepared.target,
      platformPointers = prepared.pointers,
      supportPointers = prepared.internal.supportPointers,
      authored = prepared.authored,
      contentHash = prepared.contentHash,
      contentIdentity = prepared.contentIdentity,
      finalStagingDir = prepared.finalStagingDir,
      internalChildren = prepared.internal.children,
      agentAddonPointers = prepared.agentAddonPointers,
    ),
  )
}

private fun buildFreshInstallStaging(inputs: FreshInstallInputs): RenderedSkill {
  Files.createDirectories(installedSkillsCacheRoot(inputs.home))
  val tempDir = Files.createTempDirectory(installedSkillsCacheRoot(inputs.home), ".staging-tmp-")
  var promoted = false
  var failure: Throwable? = null
  var stagedResult: RenderedSkill? = null
  try {
    val staged = populateFreshInstallStagingTemp(inputs, tempDir)
    promoteInstallStagingDir(tempDir, inputs.finalStagingDir)
    promoted = true
    stagedResult = finalizeFreshInstallStaging(inputs, tempDir, staged)
  } catch (error: CancellationException) {
    logInstallStagingFailure(inputs, tempDir, promoted, error)
    failure = error
  } catch (error: IOException) {
    logInstallStagingFailure(inputs, tempDir, promoted, error)
    failure = error
  } catch (error: SkillBillRuntimeException) {
    logInstallStagingFailure(inputs, tempDir, promoted, error)
    failure = error
  }
  failure?.let { throw it }
  return stagedResult!!
}

private fun invalidStageInstalledSkill(
  input: StageInstalledSkillInput,
  error: Throwable,
): Nothing =
  throw InvalidInstallStagingError(
    sourceLabel = input.sourceSkillDir.toString(),
    reason = error.message ?: error::class.simpleName.orEmpty(),
    cause = error,
  )

private fun logInstallStagingFailure(
  inputs: FreshInstallInputs,
  tempDir: Path,
  promoted: Boolean,
  error: Throwable,
) {
  log.log(
    Level.SEVERE,
    "stageInstalledSkill failure skill=${inputs.sourceSkillDir.fileName} hash=${inputs.contentHash} " +
      "source=${inputs.sourceSkillDir} tempDir=$tempDir finalDir=${inputs.finalStagingDir} " +
      "promoted=$promoted error=${error.failureCodeLabel() ?: error::class.simpleName}",
    error,
  )
  cleanupInstallStagingOnFailure(tempDir, inputs.finalStagingDir, promoted)
}

internal fun writeInstallStagingMarkers(
  tempDir: Path,
  inputs: FreshInstallInputs,
) {
  Files.write(
    tempDir.resolve(INSTALL_STAGING_CONTENT_HASH_FILENAME),
    inputs.contentHash.toByteArray(StandardCharsets.UTF_8),
  )
  Files.writeString(
    tempDir.resolve(SKILL_CONTENT_IDENTITY_FILENAME),
    inputs.contentIdentity.compact(),
    StandardCharsets.UTF_8,
  )
}

internal fun resolveStagedSymlinkTarget(input: StagedSymlinkTargetInput): Path {
  if (input.repoRoot == null || !isContentManagedSkill(input.resolvedSkill)) {
    return input.resolvedSkill
  }
  return stageInstalledSkill(
    StageInstalledSkillInput(
      repoRoot = input.repoRoot,
      sourceSkillDir = input.resolvedSkill,
      home = input.home,
      manifests = input.manifests,
      selectedPackSkills = input.selectedPackSkills,
      selectedPlatformSlugs = input.selectedPlatformSlugs,
      environment = input.environment,
      catalogLoader = input.catalogLoader,
      suppliedCompactIdentity = suppliedSkillContentIdentity(input.resolvedSkill).compact(),
    ),
  ).stagingDir.toPath().toAbsolutePath().normalize()
}
