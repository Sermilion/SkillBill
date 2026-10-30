package skillbill.infrastructure.skills.nativeagent

import skillbill.infrastructure.contracts.sha256Bytes
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentSourceFile
import skillbill.infrastructure.skills.nativeagent.discovery.discoverNativeAgentSourceEntries
import skillbill.infrastructure.skills.nativeagent.discovery.discoverNativeAgentSourceEntriesInRoots
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentInstallRenderRequest
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentInstallRenderResult
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentRegenerationRequest
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentRegenerationResult
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentRenderPromotionRequest
import skillbill.infrastructure.skills.nativeagent.rendering.RenderedAgent
import skillbill.infrastructure.skills.nativeagent.rendering.composeNativeAgentSource
import skillbill.infrastructure.skills.nativeagent.rendering.deleteNativeAgentRenderStaging
import skillbill.infrastructure.skills.nativeagent.rendering.discoverRepoNativeAgentSourceFiles
import skillbill.infrastructure.skills.nativeagent.rendering.listOrphanRenderCandidates
import skillbill.infrastructure.skills.nativeagent.rendering.nativeAgentCompositionRepoRoot
import skillbill.infrastructure.skills.nativeagent.rendering.stageAndPromoteNativeAgentRenders
import skillbill.infrastructure.skills.nativeagent.validation.validateNativeAgentArtifactsForInstall
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

private const val NATIVE_AGENT_CACHE_KEY_BYTES = 8
private const val NATIVE_AGENT_SLUG_MAX_CHARS = 32

object NativeAgentOperations {
  internal fun regenerate(request: NativeAgentRegenerationRequest): NativeAgentRegenerationResult {
    val root = request.repoRoot.toAbsolutePath().normalize()
    val selectedSkillNames = request.skillNames.toSet()
    val sourceFiles =
      discoverRepoNativeAgentSourceFiles(root)
        .filter { sourcePath ->
          selectedSkillNames.isEmpty() || sourcePath.parent?.parent?.name in selectedSkillNames
        }
    val sources = sourceFiles.flatMap(::parseNativeAgentSourceFile)
    if (sources.isEmpty()) {
      return NativeAgentRegenerationResult(emptyList())
    }
    val cacheRoot = installCacheRoot(request.home, root.resolve("platform-packs"), root.resolve("skills"))
    val written = mutableListOf<Path>()
    val composedSources =
      sources.map { source ->
        composeNativeAgentSource(
          root,
          source,
          request.compositionContext,
        )
      }
    val byProvider =
      NativeAgentProvider.entries.associateWith { provider ->
        composedSources.map { composed ->
          RegenerationEntry(
            target = cacheRoot.resolve(provider.directoryName).resolve("${composed.name}.${provider.extension}"),
            contents = provider.render(composed).toByteArray(Charsets.UTF_8),
          )
        }
      }
    byProvider.forEach { (provider, entries) ->
      val providerRoot = cacheRoot.resolve(provider.directoryName)
      Files.createDirectories(providerRoot)
      entries.forEach { entry ->
        val existed = Files.exists(entry.target)
        val current = if (existed) Files.readAllBytes(entry.target) else null
        if (current != null && current.contentEquals(entry.contents)) {
          return@forEach
        }
        if (existed && request.originalBytes != null && entry.target !in request.originalBytes) {
          request.originalBytes[entry.target] = current ?: ByteArray(0)
        }
        Files.write(entry.target, entry.contents)
        if (!existed) {
          request.createdPaths?.add(entry.target)
        }
        written.add(entry.target)
      }
    }
    return NativeAgentRegenerationResult(written.sortedBy { it.toString() })
  }

  private data class RegenerationEntry(val target: Path, val contents: ByteArray) {
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
  }

  internal fun renderInstallArtifacts(request: NativeAgentInstallRenderRequest): NativeAgentInstallRenderResult {
    val repoRoot = nativeAgentCompositionRepoRoot(request.platformPacksRoot, request.skillsRoot)
    validateNativeAgentInstallSources(request, repoRoot)
    val cacheRoot =
      request.overrides.cacheRoot?.toAbsolutePath()?.normalize()
        ?: installCacheRoot(request.home, request.platformPacksRoot, request.skillsRoot)
    val providerRoot = cacheRoot.resolve(request.provider.directoryName)
    val rendered = composeRenderedAgents(request, repoRoot)
    request.overrides.beforeMutation(cacheRoot)
    request.overrides.beforeMutation(providerRoot)
    Files.createDirectories(providerRoot)
    val orphanCandidates = listOrphanRenderCandidates(providerRoot, rendered)
    val staging = Files.createTempDirectory(providerRoot, ".skill-bill-native-agent-render-")
    return Closeable { deleteNativeAgentRenderStaging(staging) }.use {
      request.overrides.afterTemporaryCreation(staging)
      stageAndPromoteNativeAgentRenders(
        NativeAgentRenderPromotionRequest(
          providerRoot = providerRoot,
          staging = staging,
          rendered = rendered,
          orphanCandidates = orphanCandidates,
          beforeMutation = request.overrides.beforeMutation,
          provider = request.provider,
          cacheRoot = cacheRoot,
        ),
      )
    }
  }

  private fun validateNativeAgentInstallSources(
    request: NativeAgentInstallRenderRequest,
    repoRoot: Path,
  ) {
    if (request.overrides.sourceRoots == null) {
      validateNativeAgentArtifactsForInstall(
        request.platformPacksRoot,
        request.skillsRoot,
        request.selectedPlatforms,
        request.compositionContext,
      )
    } else {
      validateNativeAgentArtifactsForInstall(
        request.overrides.sourceRoots,
        repoRoot,
        request.compositionContext,
      )
    }
  }

  private fun composeRenderedAgents(
    request: NativeAgentInstallRenderRequest,
    repoRoot: Path,
  ): List<RenderedAgent> {
    val sources =
      request.overrides.sourceRoots
        ?.let(::discoverNativeAgentSourceEntriesInRoots)
        ?: discoverNativeAgentSourceEntries(request.platformPacksRoot, request.skillsRoot, request.selectedPlatforms)
    return sources.map { source ->
      val composed =
        composeNativeAgentSource(
          repoRoot,
          source,
          request.compositionContext,
        )
      RenderedAgent(
        targetName = "${composed.name}.${request.provider.extension}",
        contents = request.provider.render(composed).toByteArray(Charsets.UTF_8),
      )
    }
  }

  fun installCacheRoot(
    home: Path,
    platformPacksRoot: Path,
    skillsRoot: Path?,
  ): Path {
    val hash = stableRepoKey(platformPacksRoot, skillsRoot)
    val slug = repoSlug(platformPacksRoot)
    val leaf = if (slug.isEmpty()) hash else "$slug-$hash"
    return home.toAbsolutePath().normalize().resolve(".skill-bill/native-agents/$leaf")
  }

  private fun repoSlug(platformPacksRoot: Path): String {
    val raw = platformPacksRoot.toAbsolutePath().normalize().parent?.fileName?.toString().orEmpty()
    if (raw.isEmpty()) {
      return ""
    }
    val collapsed =
      raw.lowercase()
        .replace(Regex("[^a-z0-9-]+"), "-")
        .trim('-')
    return collapsed.take(NATIVE_AGENT_SLUG_MAX_CHARS)
  }

  private fun stableRepoKey(
    platformPacksRoot: Path,
    skillsRoot: Path?,
  ): String {
    val input =
      listOfNotNull(
        platformPacksRoot.toAbsolutePath().normalize().toString(),
        skillsRoot?.toAbsolutePath()?.normalize()?.toString(),
      ).joinToString("|")
    val digest = sha256Bytes(input.toByteArray(Charsets.UTF_8))
    return digest.take(NATIVE_AGENT_CACHE_KEY_BYTES).joinToString("") { byte -> "%02x".format(byte) }
  }
}
