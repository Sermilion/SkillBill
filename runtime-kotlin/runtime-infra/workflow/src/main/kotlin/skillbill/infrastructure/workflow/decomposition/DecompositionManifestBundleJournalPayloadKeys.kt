package skillbill.infrastructure.workflow.decomposition

object DecompositionManifestBundleJournalPayloadKeys {
  const val ENTRIES: String = "entries"
  const val STAGING_DIRECTORY: String = "staging_directory"
  const val TARGET: String = "target"
  const val STAGED: String = "staged"
  const val SHA256: String = "sha256"
  const val BEFORE_EXISTS: String = "before_exists"
  const val BEFORE_SHA256: String = "before_sha256"
  const val PROTECTED_FILES: String = "protected_files"
  const val PATH: String = "path"
}
