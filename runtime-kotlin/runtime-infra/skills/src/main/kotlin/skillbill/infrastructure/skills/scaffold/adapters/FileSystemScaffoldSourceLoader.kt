package skillbill.infrastructure.skills.scaffold.adapters

import me.tatarka.inject.annotations.Inject
import skillbill.error.shellcontent.invalidScaffoldPayloadError
import skillbill.infrastructure.skills.scaffold.platformpack.manifest.declaredSkillRelativeDirs
import skillbill.model.toPath
import skillbill.ports.scaffold.source.model.ScaffoldPlatformPackLoadRequest
import skillbill.ports.scaffold.source.model.ScaffoldPlatformPackLoadResult
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.policy.requireStringList
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformPack as fsLoadPlatformPack

@Inject
class FileSystemScaffoldSourceLoader {
  fun loadPlatformPack(request: ScaffoldPlatformPackLoadRequest): ScaffoldPlatformPackLoadResult =
    ScaffoldPlatformPackLoadResult(
      packRoot = request.packRoot,
      manifest = fsLoadPlatformPack(request.packRoot),
    )

  internal fun resolveAddonConsumerSkillDirs(
    payload: Map<String, Any?>,
    packRoot: Path,
    pack: PlatformManifest,
  ): List<String> {
    val raw = payload["consumer_skill_dirs"]
    val requested =
      if (raw == null) {
        defaultAddonConsumerSkillDirs(packRoot, pack)
      } else {
        requireStringList(raw, "consumer_skill_dirs").map(String::trim)
      }
    val seen = mutableSetOf<String>()
    return requested.map { dir ->
      validateAddonConsumerSkillDir(pack, dir)
    }.filter { dir -> seen.add(dir) }
  }

  internal fun validateAddonConsumerSkillDir(
    pack: PlatformManifest,
    skillRelativeDir: String,
  ): String {
    val relative = parseRelativePath(skillRelativeDir)
    if (relative.isAbsolute || skillRelativeDir.startsWith("/") || skillRelativeDir.startsWith("\\")) {
      failConsumerSkillDirsNotRelative()
    }
    relative.iterator().forEachRemaining { segment ->
      if (segment.toString() == "..") {
        failConsumerSkillDirsParentSegment()
      }
    }
    val normalized = relative.normalize()
    val normalizedDir = normalized.toString().replace('\\', '/')
    if (!Files.isDirectory(pack.packRoot.resolve(normalized.toString()).toPath())) {
      failConsumerSkillDirsMissing(skillRelativeDir)
    }
    if (normalizedDir !in pack.declaredSkillRelativeDirs()) {
      failConsumerSkillDirsNotDeclared(pack, skillRelativeDir)
    }
    return normalizedDir
  }

  private fun parseRelativePath(skillRelativeDir: String): Path =
    try {
      Path.of(skillRelativeDir)
    } catch (error: InvalidPathException) {
      throw invalidScaffoldPayloadError(
        "Scaffold payload field 'consumer_skill_dirs' contains invalid path '$skillRelativeDir': ${error.message}",
        error,
      )
    }

  private fun defaultAddonConsumerSkillDirs(
    packRoot: Path,
    pack: PlatformManifest,
  ): List<String> {
    pack.declaredFiles.baseline?.let { contentFile ->
      return listOf(packRoot.relativize(contentFile.toPath().parent).toString().replace('\\', '/'))
    }
    val declaredSkillDirs = pack.declaredSkillRelativeDirs().sorted()
    if (declaredSkillDirs.size == 1) {
      return declaredSkillDirs
    }
    throw invalidScaffoldPayloadError(
      "Scaffold payload for add-on platform '${pack.slug}' omitted 'consumer_skill_dirs', but the pack has " +
        "no unambiguous default consumer. Provide scripted 'consumer_skill_dirs'. Declared skill directories: " +
        "$declaredSkillDirs.",
    )
  }
}

private fun failConsumerSkillDirsNotRelative(): Nothing =
  throw invalidScaffoldPayloadError(
    "Scaffold payload field 'consumer_skill_dirs' entries must be relative skill directories.",
  )

private fun failConsumerSkillDirsParentSegment(): Nothing =
  throw invalidScaffoldPayloadError(
    "Scaffold payload field 'consumer_skill_dirs' entries must not contain '..' segments.",
  )

private fun failConsumerSkillDirsMissing(skillRelativeDir: String): Nothing =
  throw invalidScaffoldPayloadError(
    "Scaffold payload field 'consumer_skill_dirs' references missing skill directory '$skillRelativeDir'.",
  )

private fun failConsumerSkillDirsNotDeclared(
  pack: PlatformManifest,
  skillRelativeDir: String,
): Nothing =
  throw invalidScaffoldPayloadError(
    "Scaffold payload field 'consumer_skill_dirs' references '$skillRelativeDir', but that directory is not " +
      "declared as a skill in platform pack '${pack.slug}'. Declared skill directories: " +
      "${pack.declaredSkillRelativeDirs().sorted()}.",
  )
