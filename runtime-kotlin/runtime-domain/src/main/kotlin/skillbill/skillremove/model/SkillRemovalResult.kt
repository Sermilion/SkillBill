package skillbill.skillremove.model

sealed class SkillRemovalResult {
  data class Preview(val preview: SkillRemovalPreview) : SkillRemovalResult()

  data class Success(
    val preview: SkillRemovalPreview,
    val removedPaths: List<String>,
    val editedManifests: List<String>,
    val unlinkedSymlinks: List<String>,
    val readmeWarnings: List<ReadmeCatalogWarning> = emptyList(),
  ) : SkillRemovalResult()

  data class Refused(
    val reason: SkillRemovalRefusalReason,
    val message: String,
  ) : SkillRemovalResult()

  data class Failed(
    val exceptionName: String,
    val exceptionMessage: String,
    val rollbackComplete: Boolean,
  ) : SkillRemovalResult()
}
