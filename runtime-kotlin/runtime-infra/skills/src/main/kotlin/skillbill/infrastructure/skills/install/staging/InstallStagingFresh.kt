package skillbill.infrastructure.skills.install.staging

import skillbill.infrastructure.skills.agentaddon.AgentAddonPointer
import skillbill.infrastructure.skills.install.identity.SkillContentIdentity
import skillbill.infrastructure.skills.scaffold.authoring.AuthoringTarget
import skillbill.install.model.RenderedSkill
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Files
import java.nio.file.Path

internal data class FreshInstallStagingArtifacts(
  val copiedInTemp: List<Path>,
  val skillFileInTemp: Path,
  val pointerFilesInTemp: List<Path>,
  val supportPointerFilesInTemp: List<Path>,
  val agentAddonFilesInTemp: List<Path>,
  val sidecarFilesInTemp: List<Path>,
)

internal fun populateFreshInstallStagingTemp(
  inputs: FreshInstallInputs,
  tempDir: Path,
): FreshInstallStagingArtifacts {
  val copiedInTemp = copyAuthoredIntoStaging(inputs.sourceSkillDir, tempDir, inputs.authored)
  val skillFileInTemp = writeRenderedSkillFile(tempDir, inputs.target)
  val pointerFilesInTemp = writeRenderedPointerFiles(inputs.repoRoot, tempDir, inputs.platformPointers)
  val supportPointerFilesInTemp =
    writeRenderedSupportPointerFiles(
      repoRoot = inputs.repoRoot,
      sourceSkillDir = inputs.sourceSkillDir,
      tempDir = tempDir,
      pointers = inputs.supportPointers,
    )
  val agentAddonFilesInTemp = writeAgentAddonPointerFiles(tempDir, inputs.agentAddonPointers)
  val sidecarFilesInTemp =
    writeInternalSidecarFiles(
      tempDir = tempDir,
      parentSourceDir = inputs.sourceSkillDir,
      children = inputs.internalChildren,
    )
  val packsRoot = inputs.repoRoot.resolve("platform-packs")
  if (Files.isDirectory(packsRoot)) {
    Files.createSymbolicLink(tempDir.resolve("platform-packs"), packsRoot)
  }
  writeInstallStagingMarkers(tempDir, inputs)
  return FreshInstallStagingArtifacts(
    copiedInTemp = copiedInTemp,
    skillFileInTemp = skillFileInTemp,
    pointerFilesInTemp = pointerFilesInTemp,
    supportPointerFilesInTemp = supportPointerFilesInTemp,
    agentAddonFilesInTemp = agentAddonFilesInTemp,
    sidecarFilesInTemp = sidecarFilesInTemp,
  )
}

internal fun finalizeFreshInstallStaging(
  inputs: FreshInstallInputs,
  tempDir: Path,
  staged: FreshInstallStagingArtifacts,
): RenderedSkill {
  val finalSkillFile = inputs.finalStagingDir.resolve(tempDir.relativize(staged.skillFileInTemp))
  val finalPointerFiles =
    (
      staged.pointerFilesInTemp + staged.supportPointerFilesInTemp + staged.agentAddonFilesInTemp
    )
      .map { path -> inputs.finalStagingDir.resolve(tempDir.relativize(path)) }
  val finalCopied = staged.copiedInTemp.map { path -> inputs.finalStagingDir.resolve(tempDir.relativize(path)) }
  val finalSidecars =
    staged.sidecarFilesInTemp.map { path ->
      inputs.finalStagingDir.resolve(tempDir.relativize(path))
    }
  return RenderedSkill(
    skillName = inputs.sourceSkillDir.fileName.toString(),
    sourceSkillDir = inputs.sourceSkillDir.toFileLocation(),
    stagingDir = inputs.finalStagingDir.toFileLocation(),
    renderedSkillFile = finalSkillFile.toFileLocation(),
    renderedPointerFiles = finalPointerFiles.map { entry -> entry.toFileLocation() },
    copiedAuthoredFiles = finalCopied.map { entry -> entry.toFileLocation() },
    contentHash = inputs.contentHash,
    renderedSidecarFiles = finalSidecars.map { entry -> entry.toFileLocation() },
  )
}

internal data class FreshInstallInputs(
  val home: Path,
  val sourceSkillDir: Path,
  val repoRoot: Path,
  val target: AuthoringTarget,
  val platformPointers: List<Pair<PlatformManifest, PointerSpec>>,
  val supportPointers: List<GeneratedSupportPointer>,
  val authored: List<Path>,
  val contentHash: String,
  val contentIdentity: SkillContentIdentity,
  val finalStagingDir: Path,
  val internalChildren: List<InternalSidecarTarget>,
  val agentAddonPointers: List<AgentAddonPointer>,
)
