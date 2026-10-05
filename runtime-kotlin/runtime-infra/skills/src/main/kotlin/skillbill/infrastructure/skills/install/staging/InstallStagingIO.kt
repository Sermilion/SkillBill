package skillbill.infrastructure.skills.install.staging

import skillbill.infrastructure.host.jvm.atomicMoveReplacing
import skillbill.infrastructure.skills.agentaddon.AgentAddonPointer
import skillbill.infrastructure.skills.install.identity.SKILL_CONTENT_IDENTITY_FILENAME
import skillbill.infrastructure.skills.scaffold.authoring.AuthoringTarget
import skillbill.infrastructure.skills.scaffold.authoring.normalizeMarkdownLineEndings
import skillbill.infrastructure.skills.scaffold.authoring.renderWrapper
import skillbill.infrastructure.skills.scaffold.pointer.renderPointer
import skillbill.install.model.RenderedSkill
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger

internal const val INSTALL_STAGING_SKILL_FILENAME = "SKILL.md"
internal const val INSTALL_STAGING_CONTENT_HASH_FILENAME = ".content-hash"
internal const val AUTHORED_SKILL_CONTENT_FILENAME = "content.md"

private val log: Logger = Logger.getLogger("skillbill.install.InstallStagingIO")

internal fun isReusableInstallStaging(
  finalStagingDir: Path,
  contentHash: String,
  expectedStagedNames: Set<String> = emptySet(),
): Boolean {
  if (!Files.isDirectory(finalStagingDir)) {
    return false
  }
  val marker = finalStagingDir.resolve(INSTALL_STAGING_CONTENT_HASH_FILENAME)
  val markerIsFile = Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
  val recorded =
    if (markerIsFile) {
      String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trimEnd('\n', '\r')
    } else {
      null
    }
  val skillFile = finalStagingDir.resolve(INSTALL_STAGING_SKILL_FILENAME)
  val skillIsFile = Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS)
  val stagedFilesIntact =
    expectedStagedNames.all { name ->
      Files.isRegularFile(finalStagingDir.resolve(name), LinkOption.NOFOLLOW_LINKS)
    }
  return markerIsFile && recorded == contentHash && skillIsFile && stagedFilesIntact
}

internal fun reuseInstallStaging(input: ReuseInstallStagingInput): RenderedSkill {
  val skillFile = input.finalStagingDir.resolve(INSTALL_STAGING_SKILL_FILENAME)
  val skillFileNormalized = skillFile.toAbsolutePath().normalize()
  val staged =
    Files.walk(input.finalStagingDir).use { stream ->
      stream
        .sorted()
        .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
        .filter { path ->
          path.fileName.toString() != INSTALL_STAGING_CONTENT_HASH_FILENAME &&
            path.fileName.toString() != SKILL_CONTENT_IDENTITY_FILENAME
        }
        .filter { path -> path.toAbsolutePath().normalize() != skillFileNormalized }
        .toList()
    }
  val finalRoot = input.finalStagingDir.toAbsolutePath().normalize()
  val pointerRelativePaths =
    (
      input.applicablePointers.map { (_, spec) -> spec.name } +
        input.generatedSupportPointers.map { pointer -> pointer.name } +
        input.agentAddonPointerNames
    ).map { name -> Path.of(name) }.toSet()
  val sidecarRelativePaths = input.internalSidecarNames.map { name -> Path.of(name) }.toSet()
  val authoredCopied =
    staged.filter { path ->
      val rel = finalRoot.relativize(path.toAbsolutePath().normalize())
      rel !in pointerRelativePaths && rel !in sidecarRelativePaths &&
        Files.isRegularFile(input.sourceSkillDir.resolve(rel), LinkOption.NOFOLLOW_LINKS)
    }
  val sidecarFiles =
    staged.filter { path ->
      val rel = finalRoot.relativize(path.toAbsolutePath().normalize())
      rel in sidecarRelativePaths
    }
  val pointerFiles = staged.filter { path -> path !in authoredCopied && path !in sidecarFiles }
  return RenderedSkill(
    skillName = input.sourceSkillDir.fileName.toString(),
    sourceSkillDir = input.sourceSkillDir.toFileLocation(),
    stagingDir = input.finalStagingDir.toFileLocation(),
    renderedSkillFile = skillFile.toFileLocation(),
    renderedPointerFiles = pointerFiles.map { entry -> entry.toFileLocation() },
    copiedAuthoredFiles = authoredCopied.map { entry -> entry.toFileLocation() },
    contentHash = input.contentHash,
    renderedSidecarFiles = sidecarFiles.map { entry -> entry.toFileLocation() },
  )
}

internal fun writeAgentAddonPointerFiles(
  tempDir: Path,
  pointers: List<AgentAddonPointer>,
): List<Path> =
  pointers.sortedBy { it.slug }.map { pointer ->
    val destination = tempDir.resolve(pointer.name).normalize()
    require(destination.parent == tempDir.toAbsolutePath().normalize()) {
      "Agent add-on pointer '${pointer.name}' escapes staging dir '$tempDir'."
    }
    Files.write(destination, pointer.renderedBytes)
    destination
  }

internal fun copyAuthoredIntoStaging(
  sourceSkillDir: Path,
  tempDir: Path,
  authored: List<Path>,
): List<Path> {
  val copied = mutableListOf<Path>()
  authored.forEach { file ->
    val rel = sourceSkillDir.relativize(file).toString().replace(File.separatorChar, '/')
    if (rel == AUTHORED_SKILL_CONTENT_FILENAME) {
      return@forEach
    }
    val dest = tempDir.resolve(rel).normalize()
    require(dest.startsWith(tempDir)) {
      "Authored file '$rel' resolves to '$dest' which escapes staging dir '$tempDir'."
    }
    Files.createDirectories(dest.parent)
    Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS)
    copied.add(dest)
  }
  return copied
}

internal fun writeRenderedSkillFile(
  tempDir: Path,
  target: AuthoringTarget,
): Path {
  val skillFile = tempDir.resolve(INSTALL_STAGING_SKILL_FILENAME).normalize()
  require(skillFile.startsWith(tempDir)) {
    "SKILL.md staging path '$skillFile' escapes staging dir '$tempDir'."
  }
  Files.write(skillFile, renderWrapper(target).toByteArray(StandardCharsets.UTF_8))
  return skillFile
}

internal fun writeRenderedPointerFiles(
  repoRoot: Path,
  tempDir: Path,
  pointers: List<Pair<PlatformManifest, PointerSpec>>,
): List<Path> =
  pointers.map { (manifest, spec) ->
    val pointerFile = tempDir.resolve(spec.name).normalize()
    require(pointerFile.startsWith(tempDir)) {
      "Pointer '${spec.name}' staging path '$pointerFile' escapes staging dir '$tempDir'."
    }
    renderPointer(repoRoot = repoRoot, packRoot = manifest.packRoot.toPath(), spec = spec)
    val targetFile = repoRoot.toAbsolutePath().normalize().resolve(spec.target).normalize()
    val rendered = normalizeMarkdownLineEndings(Files.readString(targetFile)).trimEnd() + "\n"
    Files.write(pointerFile, rendered.toByteArray(StandardCharsets.UTF_8))
    pointerFile
  }

internal fun writeInternalSidecarFiles(
  tempDir: Path,
  parentSourceDir: Path,
  children: List<InternalSidecarTarget>,
): List<Path> {
  validateInternalSidecarFileNames(parentSourceDir, children)
  return children.sortedBy { child -> child.skillName }.flatMap { child ->
    val wrapper =
      writeInternalStagingFile(
        tempDir,
        "${child.skillName}.md",
        child.renderedWrapper.toByteArray(StandardCharsets.UTF_8),
      )
    val companions =
      child.authoredCompanions.sortedBy { companion -> companion.name }.map { companion ->
        writeInternalStagingFile(tempDir, companion.name, companion.bytes)
      }
    listOf(wrapper) + companions
  }
}

internal fun promoteInstallStagingDir(
  tempDir: Path,
  finalStagingDir: Path,
) {
  if (Files.exists(finalStagingDir, LinkOption.NOFOLLOW_LINKS)) {
    promoteByBackupAndMove(tempDir, finalStagingDir)
    return
  }
  atomicMoveReplacing(tempDir, finalStagingDir)
}

internal fun cleanupInstallStagingOnFailure(
  tempDir: Path,
  finalStagingDir: Path,
  promoted: Boolean,
  primaryError: Throwable,
) {
  log.log(
    Level.WARNING,
    "cleanupInstallStagingOnFailure tempDir=$tempDir finalDir=$finalStagingDir promoted=$promoted",
  )
  suppressedDelete(tempDir, primaryError)
  if (promoted) {
    suppressedDelete(finalStagingDir, primaryError)
  }
}

internal fun deleteInstallStagingDirectory(root: Path) {
  if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
  Files.walk(root).use { stream ->
    stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
  }
}
