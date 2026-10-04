package skillbill.infrastructure.skills.skillremove

import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.host.jvm.rollbackDeletePathEntry
import skillbill.infrastructure.host.jvm.rollbackRestoreBytes
import skillbill.infrastructure.skills.install.nativeagent.link.InstallNativeAgentOperations
import skillbill.infrastructure.skills.install.nativeagent.link.NativeAgentLinkRequest
import skillbill.install.model.SupportedAgent
import skillbill.skillremove.SkillRemoveFailureCode
import skillbill.skillremove.model.AppliedCascade
import skillbill.skillremove.model.SkillRemovalPreview
import skillbill.skillremove.model.SkillRemovalRequest
import skillbill.skillremove.model.SkillRemovalTarget
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitResult.CONTINUE
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException

internal class SkillRemoveJvmFileSystemApply(
  private val home: Path?,
) {
  fun applyCascade(
    request: SkillRemovalRequest,
    preview: SkillRemovalPreview,
  ): AppliedCascade {
    val repoRoot = skillRemoveRepoRoot(request)
    val rollbackStash = mutableListOf<RollbackEntry>()
    log.info(
      "skill-bill remove begin: target=${describeTargetForLog(request.target)} " +
        "cascadedSkills=${preview.cascadedSkillNames.size} " +
        "filesystemPaths=${preview.filesystemPaths.size}",
    )
    return runCatching {
      applyCascadeBody(request, preview, repoRoot, rollbackStash)
    }.fold(
      onSuccess = { applied ->
        log.info(
          "skill-bill remove success: removedPaths=${applied.removedPaths.size} " +
            "editedManifests=${applied.editedManifests.size} " +
            "unlinkedSymlinks=${applied.unlinkedSymlinks.size}",
        )
        applied
      },
      onFailure = { error -> handleApplyCascadeFailure(error, rollbackStash) },
    )
  }

  fun providerUnlink(provider: SupportedAgent): (NativeAgentLinkRequest) -> List<Path> =
    when (provider) {
      SupportedAgent.CLAUDE -> InstallNativeAgentOperations::unlinkClaudeAgents
      SupportedAgent.CODEX -> InstallNativeAgentOperations::unlinkCodexAgents
      SupportedAgent.JUNIE -> InstallNativeAgentOperations::unlinkJunieAgents
      SupportedAgent.CURSOR -> InstallNativeAgentOperations::unlinkCursorAgents
    }

  fun unlinkProviderAgents(request: SkillRemovalRequest): List<Path> {
    val repoRoot = skillRemoveRepoRoot(request)
    val target = request.target
    val resolvedHome = skillRemoveUserHome(request, home)
    val platformPacksRoot = repoRoot.resolve("platform-packs")
    val skillsRoot = repoRoot.resolve("skills")
    val selectedPlatforms: List<String>? =
      when (target) {
        is SkillRemovalTarget.PlatformPack -> listOf(target.platform)
        is SkillRemovalTarget.HorizontalSkill,
        is SkillRemovalTarget.AddOn,
        is SkillRemovalTarget.ExternalAddOn,
        -> null
      }
    val baseRequest =
      NativeAgentLinkRequest(
        platformPacksRoot = platformPacksRoot,
        skillsRoot = skillsRoot,
        home = resolvedHome,
        selectedPlatforms = selectedPlatforms,
      )
    val unlinked = mutableListOf<Path>()
    val failures = mutableListOf<UnlinkFailure>()
    SupportedAgent.entries.forEach { provider ->
      try {
        unlinked += providerUnlink(provider)(baseRequest)
      } catch (cancellation: CancellationException) {
        throw cancellation
      } catch (error: IOException) {
        log.warning(
          "skill-bill remove unlink failed for ${provider.name}: ${error::class.simpleName.orEmpty()}",
        )
        failures += UnlinkFailure(provider = provider, message = error.message.orEmpty())
      }
    }
    if (failures.isNotEmpty()) {
      val summary = failures.joinToString(separator = "; ") { "${it.provider.name}: ${it.message}" }
      throw SkillBillRuntimeException(
        SkillRemoveFailureCode.ROLLBACK_INCOMPLETE,
        "Agent symlink unlink failed for: $summary",
      )
    }
    return unlinked
  }

  private data class UnlinkFailure(
    val provider: SupportedAgent,
    val message: String,
  )

  internal data class RollbackEntry(val path: Path, val bytes: ByteArray?, val wasDirectory: Boolean)

  internal fun stashFile(
    path: Path,
    stash: MutableList<RollbackEntry>,
  ) {
    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      stash += RollbackEntry(path = path, bytes = Files.readAllBytes(path), wasDirectory = false)
    }
  }

  internal fun stashTree(
    root: Path,
    stash: MutableList<RollbackEntry>,
  ) {
    if (Files.isSymbolicLink(root)) {
      return
    }
    if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
          override fun visitFile(
            file: Path,
            attrs: BasicFileAttributes,
          ): FileVisitResult {
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
              stash += RollbackEntry(path = file, bytes = Files.readAllBytes(file), wasDirectory = false)
            }
            return CONTINUE
          }

          override fun preVisitDirectory(
            dir: Path,
            attrs: BasicFileAttributes,
          ): FileVisitResult {
            stash += RollbackEntry(path = dir, bytes = null, wasDirectory = true)
            return CONTINUE
          }
        },
      )
    } else if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)) {
      stash += RollbackEntry(path = root, bytes = Files.readAllBytes(root), wasDirectory = false)
    }
  }

  internal fun deletePath(target: Path) {
    rollbackDeletePathEntry(target)
  }

  internal fun attemptRollback(stash: List<RollbackEntry>): Boolean =
    try {
      stash.filter { it.wasDirectory }
        .sortedBy { it.path.nameCount }
        .forEach { entry ->
          if (!Files.isDirectory(entry.path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(entry.path)
          }
        }
      stash.filterNot { it.wasDirectory }.forEach { entry ->
        val bytes = entry.bytes ?: return@forEach
        Files.createDirectories(entry.path.parent ?: return@forEach)
        rollbackRestoreBytes(entry.path, bytes)
      }
      true
    } catch (cancellation: CancellationException) {
      throw cancellation
    } catch (_: Exception) {
      false
    }

  internal companion object {
    internal val log: Logger = Logger.getLogger("skillbill.skillremove.SkillRemoveJvmFileSystem")
  }
}
