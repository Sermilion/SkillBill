package skillbill.infrastructure.skills.skillremove

import skillbill.error.core.failureCodeLabel
import skillbill.infrastructure.skills.install.nativeagent.link.unlinkProviderAgents
import skillbill.infrastructure.skills.scaffold.manifest.removeAddonReferences
import skillbill.infrastructure.skills.scaffold.manifest.removeCodeReviewArea
import skillbill.infrastructure.skills.scaffold.manifest.removeDeclaredFilesBaseline
import skillbill.infrastructure.skills.scaffold.manifest.removeDeclaredQualityCheckFile
import skillbill.infrastructure.skills.scaffold.manifest.removePointersBlockKey
import skillbill.infrastructure.skills.scaffold.manifest.removeSkillClassPointer
import skillbill.infrastructure.skills.scaffold.platformpack.manifest.ReadmeCatalogEdits
import skillbill.infrastructure.skills.scaffold.platformpack.manifest.ReadmeEditOutcome
import skillbill.skillremove.SkillBillRollbackException
import skillbill.skillremove.model.AppliedCascade
import skillbill.skillremove.model.ManifestEdit
import skillbill.skillremove.model.ManifestEditKind
import skillbill.skillremove.model.ReadmeCatalogEdit
import skillbill.skillremove.model.ReadmeCatalogEditKind
import skillbill.skillremove.model.ReadmeCatalogWarning
import skillbill.skillremove.model.SkillRemovalPreview
import skillbill.skillremove.model.SkillRemovalRequest
import skillbill.skillremove.model.SkillRemovalTarget
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun SkillRemoveJvmFileSystemApply.applyCascadeBody(
  request: SkillRemovalRequest,
  preview: SkillRemovalPreview,
  repoRoot: Path,
  rollbackStash: MutableList<SkillRemoveJvmFileSystemApply.RollbackEntry>,
): AppliedCascade {
  val removedPaths = mutableListOf<String>()
  val editedManifests = mutableListOf<String>()
  val unlinkedSymlinks = mutableListOf<String>()
  val readmeWarnings = mutableListOf<ReadmeCatalogWarning>()
  stashCascadeTargets(repoRoot, preview, rollbackStash)
  applyManifestEdits(repoRoot, preview.manifestEdits, editedManifests)
  applyReadmeCatalogEdits(request, repoRoot, preview.readmeCatalogEdits, readmeWarnings)
  removeFilesystemPaths(repoRoot, preview.filesystemPaths, removedPaths)
  unlinkedSymlinks += unlinkProviderAgents(request).map { it.toString().replace('\\', '/') }
  return AppliedCascade(
    removedPaths = removedPaths,
    editedManifests = editedManifests,
    unlinkedSymlinks = unlinkedSymlinks,
    readmeWarnings = readmeWarnings,
  )
}

internal fun SkillRemoveJvmFileSystemApply.stashCascadeTargets(
  repoRoot: Path,
  preview: SkillRemovalPreview,
  rollbackStash: MutableList<SkillRemoveJvmFileSystemApply.RollbackEntry>,
) {
  preview.manifestEdits.forEach { edit ->
    stashFile(repoRoot.resolve(edit.manifestPath), rollbackStash)
  }
  preview.readmeCatalogEdits.forEach { edit ->
    stashFile(repoRoot.resolve(edit.readmePath), rollbackStash)
  }
  preview.filesystemPaths
    .map(repoRoot::resolve)
    .filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
    .forEach { absolute -> stashTree(absolute, rollbackStash) }
}

internal fun applyManifestEdits(
  repoRoot: Path,
  manifestEdits: List<ManifestEdit>,
  editedManifests: MutableList<String>,
) {
  manifestEdits.forEach { edit ->
    val manifest = repoRoot.resolve(edit.manifestPath)
    when (edit.editKind) {
      ManifestEditKind.REMOVE_CODE_REVIEW_AREA,
      ManifestEditKind.REMOVE_DECLARED_FILES_AREA_ENTRY,
      ManifestEditKind.REMOVE_AREA_METADATA_ENTRY,
      -> removeCodeReviewArea(manifest, edit.detail)
      ManifestEditKind.REMOVE_DECLARED_QUALITY_CHECK_FILE -> removeDeclaredQualityCheckFile(manifest)
      ManifestEditKind.REMOVE_DECLARED_FILES_BASELINE -> removeDeclaredFilesBaseline(manifest)
      ManifestEditKind.REMOVE_POINTERS_BLOCK_KEY -> removePointersBlockKey(manifest, edit.detail)
      ManifestEditKind.REMOVE_ADDON_REFERENCES -> removeAddonReferences(manifest, edit.detail)
      ManifestEditKind.REMOVE_SKILL_CLASS_POINTER -> removeSkillClassPointer(manifest, edit.detail)
    }
    editedManifests += edit.manifestPath
  }
}

internal fun applyReadmeCatalogEdits(
  request: SkillRemovalRequest,
  repoRoot: Path,
  readmeCatalogEdits: List<ReadmeCatalogEdit>,
  readmeWarnings: MutableList<ReadmeCatalogWarning>,
) {
  val skillNameForReadme = (request.target as? SkillRemovalTarget.HorizontalSkill)?.skillName
  readmeCatalogEdits.forEach { edit ->
    val readme = repoRoot.resolve(edit.readmePath)
    val outcome: ReadmeEditOutcome? =
      when (edit.kind) {
        ReadmeCatalogEditKind.REMOVE_CATALOG_ROW ->
          if (skillNameForReadme != null) {
            ReadmeCatalogEdits.removeCatalogRow(readme, skillNameForReadme)
          } else {
            null
          }
        ReadmeCatalogEditKind.DECREMENT_SECTION_COUNT ->
          ReadmeCatalogEdits.decrementSectionCount(readme)
      }
    if (outcome is ReadmeEditOutcome.LandmarksMissing) {
      readmeWarnings +=
        ReadmeCatalogWarning(
          readmePath = edit.readmePath,
          kind = edit.kind,
          reason = outcome.reason,
        )
    }
  }
}

internal fun SkillRemoveJvmFileSystemApply.removeFilesystemPaths(
  repoRoot: Path,
  filesystemPaths: List<String>,
  removedPaths: MutableList<String>,
) {
  val absolutePaths =
    filesystemPaths
      .map(repoRoot::resolve)
      .filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
  absolutePaths.forEach { absolute ->
    deletePath(absolute)
    removedPaths += absolute.toString().replace('\\', '/')
  }
}

internal fun SkillRemoveJvmFileSystemApply.handleApplyCascadeFailure(
  error: Throwable,
  rollbackStash: List<SkillRemoveJvmFileSystemApply.RollbackEntry>,
): Nothing {
  logApplyCascadeFailure(error)
  val rollbackOk = attemptRollback(rollbackStash)
  if (!rollbackOk) {
    throw SkillBillRollbackException(
      "Skill removal failed AND rollback could not fully restore the repo: ${error.message.orEmpty()}",
      error,
    )
  }
  throw error
}

internal fun SkillRemoveJvmFileSystemApply.logApplyCascadeFailure(error: Throwable) {
  SkillRemoveJvmFileSystemApply.log.info(
    "skill-bill remove failed: exceptionName=${error.failureCodeLabel() ?: error::class.simpleName.orEmpty()}",
  )
}
