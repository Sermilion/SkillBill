package skillbill.contracts.projectstore

object LegacyProjectStatePaths {
  const val RUNTIME_STATE_DIRECTORY: String = ".skill-bill"
  const val FEATURE_SPECS_DIRECTORY: String = ".feature-specs"
  const val RUNTIME_STATE_PREFIX: String = "$RUNTIME_STATE_DIRECTORY/"
  const val FEATURE_SPECS_PREFIX: String = "$FEATURE_SPECS_DIRECTORY/"
  const val LEGACY_RUN_EVIDENCE_PREFIX: String = "${RUNTIME_STATE_PREFIX}run-evidence/"
}
