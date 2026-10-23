package skillbill.contracts.projectstore

object ProjectStoreLayout {
  const val SPECS_DIRECTORY: String = "specs"
  const val SPECS_PREFIX: String = "$SPECS_DIRECTORY/"

  val SPEC_BUNDLE_ROOT_DIRECTORY_NAMES: Set<String> =
    setOf(SPECS_DIRECTORY, LegacyProjectStatePaths.FEATURE_SPECS_DIRECTORY)
}
