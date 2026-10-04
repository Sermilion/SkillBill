package skillbill.infrastructure.skills.scaffold.pointer

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.infrastructure.host.jvm.readGitTrackedFiles
import skillbill.infrastructure.skills.nativeagent.validation.discoverNativeAgentGeneratedArtifactFiles
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.supportingFileTargets
import skillbill.infrastructure.skills.scaffold.runtime.service.support.requiredSupportingFilesForSkill
import skillbill.model.toPath
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.relativeTo

internal data class GeneratedArtifactGuardReport(
  val issues: List<String>,
) {
  val passed: Boolean = issues.isEmpty()
}

data class GeneratedArtifactFile(
  val path: Path,
  val reason: String,
)

internal typealias TrackedRepoFilesProvider = (Path) -> Set<String>?

internal fun discoverGeneratedArtifactFiles(repoRoot: Path): List<GeneratedArtifactFile> {
  val root = repoRoot.toAbsolutePath().normalize()
  return (
    discoverGovernedSkillOutputs(root).map { artifact ->
      GeneratedArtifactFile(
        path = artifact,
        reason = "Generated runtime wrapper. Edit content.md instead.",
      )
    } +
      discoverDeclaredPointerFiles(root).map { artifact ->
        GeneratedArtifactFile(
          path = artifact,
          reason = "Generated platform.yaml-declared support pointer. Runtime/install output is read-only.",
        )
      } +
      discoverGeneratedSupportingPointerFiles(root).map { artifact ->
        GeneratedArtifactFile(
          path = artifact,
          reason = "Generated support pointer. Runtime/install output is read-only.",
        )
      } +
      discoverNativeAgentGeneratedArtifactFiles(root).map { artifact ->
        GeneratedArtifactFile(
          path = artifact,
          reason = "Generated provider-specific native-agent output.",
        )
      } +
      discoverAgentAddonGeneratedArtifacts(root).map { artifact ->
        GeneratedArtifactFile(
          path = artifact,
          reason = "Generated agent add-on output. Author only agent-addon.yaml and content.md.",
        )
      } +
      discoverGeneratedAgentAddonPointersUnderSkills(root).map { artifact ->
        GeneratedArtifactFile(path = artifact, reason = "Generated agent add-on install pointer.")
      }
  )
    .distinctBy { artifact -> artifact.path.toAbsolutePath().normalize() }
    .sortedBy { artifact -> artifact.path.toString() }
}

internal fun validateGeneratedArtifactGuard(repoRoot: Path): GeneratedArtifactGuardReport =
  validateGeneratedArtifactGuard(repoRoot, ::trackedRepoFiles)

internal fun validateGeneratedArtifactGuard(
  repoRoot: Path,
  trackedFilesProvider: TrackedRepoFilesProvider,
): GeneratedArtifactGuardReport {
  val root = repoRoot.toAbsolutePath().normalize()
  val trackedFiles = trackedFilesProvider(root)
  val issues = mutableListOf<String>()
  discoverGovernedSkillOutputs(root).forEach { skillFile ->
    val relative = displayGuardPath(root, skillFile)
    if (!shouldValidateCommittedArtifact(relative, trackedFiles)) {
      return@forEach
    }
    issues += "$relative: committed governed SKILL.md output is not allowed; " +
      "author governed skill content in content.md and render wrappers only for install/output targets"
  }
  discoverDeclaredPointerFiles(root).forEach { pointerFile ->
    val relative = displayGuardPath(root, pointerFile)
    if (!shouldValidateCommittedArtifact(relative, trackedFiles)) {
      return@forEach
    }
    issues += "$relative: committed platform.yaml pointer file is not allowed; " +
      "render pointer files only for install/output targets"
  }
  discoverGeneratedSupportingPointerFiles(root).forEach { pointerFile ->
    val relative = displayGuardPath(root, pointerFile)
    if (!shouldValidateCommittedArtifact(relative, trackedFiles)) {
      return@forEach
    }
    issues += "$relative: committed generated supporting pointer file is not allowed; " +
      "render supporting pointers only for install/output targets"
  }
  discoverAgentAddonGeneratedArtifacts(root).forEach { artifact ->
    val relative = displayGuardPath(root, artifact)
    if (shouldValidateCommittedArtifact(relative, trackedFiles)) {
      issues += "$relative: committed generated agent add-on output is not allowed; " +
        "author only agent-addon.yaml and content.md"
    }
  }
  discoverGeneratedAgentAddonPointersUnderSkills(root).forEach { artifact ->
    val relative = displayGuardPath(root, artifact)
    if (shouldValidateCommittedArtifact(relative, trackedFiles)) {
      issues += "$relative: committed generated agent add-on pointer is not allowed; render it only for install targets"
    }
  }
  return GeneratedArtifactGuardReport(issues.sorted())
}

private fun shouldValidateCommittedArtifact(
  relativePath: String,
  trackedFiles: Set<String>?,
): Boolean = trackedFiles == null || relativePath in trackedFiles

private fun trackedRepoFiles(root: Path): Set<String>? {
  return readGitTrackedFiles(root)
}

private fun discoverGovernedSkillOutputs(root: Path): List<Path> {
  val scanRoots = listOf(root.resolve("skills"), root.resolve("platform-packs")).filter { it.isDirectory() }
  return scanRoots.flatMap { scanRoot ->
    Files.walk(scanRoot).use { stream ->
      stream
        .filter { path -> path.name == "content.md" }
        .map { contentFile -> contentFile.resolveSibling("SKILL.md") }
        .filter { skillFile -> Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS) }
        .toList()
    }
  }.sorted()
}

private fun discoverDeclaredPointerFiles(root: Path): List<Path> {
  val packsRoot = root.resolve("platform-packs")
  if (!packsRoot.isDirectory()) {
    return emptyList()
  }
  return Files.list(packsRoot).use { stream ->
    stream
      .filter { packRoot -> packRoot.isDirectory() && !packRoot.name.startsWith(".") }
      .flatMap { packRoot ->
        val pack =
          try {
            loadPlatformManifest(packRoot)
          } catch (error: SkillBillRuntimeException) {
            error.rethrowUnless(error.isShellContentContractFailure())
            return@flatMap emptyList<Path>().stream()
          }
        pack.pointers
          .map { spec -> pack.packRoot.resolve(spec.skillRelativeDir).resolve(spec.name).toPath().normalize() }
          .filter { pointerFile ->
            Files.isSymbolicLink(pointerFile) ||
              Files.isRegularFile(pointerFile, LinkOption.NOFOLLOW_LINKS)
          }
          .stream()
      }
      .toList()
      .sorted()
  }
}

private fun discoverGeneratedSupportingPointerFiles(root: Path): List<Path> {
  val skillsRoot = root.resolve("skills")
  if (!skillsRoot.isDirectory()) {
    return emptyList()
  }
  val targets = supportingFileTargets(root)
  return Files.list(skillsRoot).use { stream ->
    stream
      .filter { skillDir -> skillDir.isDirectory() && !skillDir.name.startsWith(".") }
      .flatMap { skillDir ->
        val skillName = skillDir.name
        requiredSupportingFilesForSkill(skillName, root)
          .asSequence()
          .filter { fileName ->
            val target = targets[fileName]?.normalize()?.toAbsolutePath()
            val sidecar = skillDir.resolve(fileName).normalize().toAbsolutePath()
            target != null && target != sidecar
          }
          .map { fileName -> skillDir.resolve(fileName) }
          .filter { pointerFile ->
            Files.isSymbolicLink(pointerFile) ||
              Files.isRegularFile(pointerFile, LinkOption.NOFOLLOW_LINKS)
          }
          .toList()
          .stream()
      }
      .toList()
      .sorted()
  }
}

private fun displayGuardPath(
  root: Path,
  path: Path,
): String {
  val resolvedRoot = root.toAbsolutePath().normalize()
  val resolvedPath = path.toAbsolutePath().normalize()
  return runCatching { resolvedPath.relativeTo(resolvedRoot).toString().replace('\\', '/') }
    .getOrDefault(resolvedPath.toString())
}
